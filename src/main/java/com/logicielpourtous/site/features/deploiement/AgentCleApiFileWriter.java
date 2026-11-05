package com.logicielpourtous.site.features.deploiement;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.logicielpourtous.site.features.licence.EtatLicenceLocale;
import com.logicielpourtous.site.features.licence.EtatLicenceLocaleRepository;

/**
 * Ecrit la cle API licence de CETTE installation dans un fichier LOCAL (jamais une route HTTP —
 * voir la revue de securite qui a supprime {@code AgentLocalKeyController}, fix round, Task 3) :
 * l'Agent de deploiement (Task 5, un processus distinct tournant sur CETTE MEME machine) le lit
 * une seule fois au demarrage pour s'authentifier auprès du maitre ({@code GET
 * /api/licence/ordre-deploiement}, voir {@code DeploiementController}).
 *
 * <p><b>Pourquoi pas une route HTTP (meme loopback-only)</b> : Task 8 de ce meme plan configure
 * Nginx avec un upstream {@code server 127.0.0.1:<port>;} — TOUT le trafic (y compris public) que
 * Nginx relaie a l'app arrive donc, du point de vue de l'app, depuis {@code 127.0.0.1}. Une garde
 * {@code request.getRemoteAddr() == 127.0.0.1} est donc completement contournable une fois Task 8
 * en place ; la seule defense fiable est de ne JAMAIS exposer la cle sur le reseau, meme en
 * loopback — d'ou ce fichier, jamais une reponse HTTP.
 *
 * <p><b>Declenchement</b> : {@link ApplicationReadyEvent} (au demarrage, si une cle existe deja)
 * PUIS un {@code @Scheduled} court (meme patron que {@code LicenceBootstrap#lancer}) — PAS un appel
 * direct depuis {@code LicenceBootstrap} au moment ou la cle est obtenue : ce module ({@code
 * deploiement}) depend deja de {@code licence} (voir {@code DeploiementController}), et un appel en
 * sens inverse ({@code licence} -> {@code deploiement}) creerait un cycle de modules Spring Modulith
 * (verifie par {@code ModularityTests}, qui liste {@code licence} parmi les modules dont les
 * dependances declarees sont controlees). Le sondage court (meme intervalle par defaut que {@code
 * LicenceBootstrap}, 5s) a le MEME effet pratique ("pas besoin de redemarrer apres un enrolement
 * frais") sans ce cycle, au prix d'un delai de quelques secondes au lieu d'etre instantane — juge
 * largement suffisant ici (l'Agent, Task 5, sonde lui-meme periodiquement).
 *
 * <p>Ecrit SEULEMENT si une cle existe ({@code EtatLicenceLocaleRepository}, meme lecture que
 * {@code LicenceClient#requireCleApi}) : tant que ce serveur n'est pas enrole, le fichier reste
 * ABSENT — l'Agent (Task 5) doit interpreter "fichier absent" comme "pas encore pret, reessayer
 * plus tard", jamais comme une erreur fatale.
 *
 * <p><b>{@code app.agent.cle-api-file-path} OBLIGATOIRE, AUCUNE valeur par defaut</b> (fix round 2,
 * finding 2) : un chemin relatif devine par defaut resoudrait a l'interieur de l'arbre source en
 * dev (repertoire de travail de l'app), hors de tout {@code .gitignore} dedie — dangereux. Si la
 * propriete est absente/vide, {@link #ecrireSiPresente()} logue une {@code ERROR} UNE SEULE fois
 * (pas a chaque sondage) et n'ecrit jamais rien — voir {@code application.properties.example}.
 *
 * <p><b>Ecriture atomique, permissions 0600 (fix round 2, finding 1)</b> : sur un systeme de
 * fichiers POSIX, le fichier CIBLE n'est JAMAIS cree puis chmod-e apres coup (fenetre ou un fichier
 * trop permissif contiendrait la cle en clair) — un fichier TEMPORAIRE, dans le MEME dossier (donc
 * sur le MEME systeme de fichiers, requis pour {@link StandardCopyOption#ATOMIC_MOVE}), est cree
 * AVEC les permissions {@code 0600} DEJA appliquees a sa creation ({@code
 * Files#createTempFile(Path, String, String, FileAttribute...)}), puis deplace atomiquement vers la
 * cible : {@code rename(2)} preserve les permissions du fichier source, donc la cible obtenue a
 * TOUJOURS ete {@code 0600}, jamais un instant plus permissive. Si la creation du temporaire (donc
 * l'application des permissions) echoue, c'est un ECHEC FERME : rien n'est ecrit du tout, log
 * {@code ERROR} (pas {@code WARN}) — contrairement a l'ancien patron ecrire-puis-chmod, qui pouvait
 * laisser un fichier faiblement protege sur disque si le chmod echouait. Sur un systeme de fichiers
 * SANS attributs POSIX (ex. ce worktree de developpement sous Windows), la restriction 0600 est
 * simplement impossible : degrade au mieux (ecriture directe, log {@code WARN}, pas d'exception) —
 * limitation acceptee en dev uniquement, jamais en production (les 2 hotes cibles de ce plan sont
 * Linux).
 */
@Component
public class AgentCleApiFileWriter {

    private static final Logger log = LoggerFactory.getLogger(AgentCleApiFileWriter.class);

    private static final Set<PosixFilePermission> PERMISSIONS_FICHIER_PROPRIETAIRE_SEUL =
            PosixFilePermissions.fromString("rw-------");
    private static final Set<PosixFilePermission> PERMISSIONS_DOSSIER_PROPRIETAIRE_SEUL =
            PosixFilePermissions.fromString("rwx------");

    private final EtatLicenceLocaleRepository etatLicenceLocaleRepository;
    private final Path cheminFichier;

    private boolean erreurConfigurationDejaLoguee;

    public AgentCleApiFileWriter(EtatLicenceLocaleRepository etatLicenceLocaleRepository,
                                 @Value("${app.agent.cle-api-file-path:}") String cheminFichier) {
        this.etatLicenceLocaleRepository = etatLicenceLocaleRepository;
        this.cheminFichier = isBlank(cheminFichier) ? null : Paths.get(cheminFichier);
    }

    @EventListener(ApplicationReadyEvent.class)
    @Scheduled(fixedDelayString = "${app.agent.cle-api-file-poll-interval-ms:5000}",
               initialDelayString = "${app.agent.cle-api-file-poll-interval-ms:5000}")
    public synchronized void ecrireSiPresente() {
        if (cheminFichier == null) {
            if (!erreurConfigurationDejaLoguee) {
                log.error("Agent : app.agent.cle-api-file-path n'est pas configure (ou vide) — "
                        + "impossible d'ecrire le fichier de cle API locale pour l'Agent de "
                        + "deploiement (Task 5). Configurez un chemin ABSOLU hors du depot, voir "
                        + "application.properties.example.");
                erreurConfigurationDejaLoguee = true;
            }
            return;
        }

        String cleApiLicence = etatLicenceLocaleRepository.findAll().stream()
                .findFirst()
                .map(EtatLicenceLocale::getCleApiLicence)
                .orElse(null);
        if (cleApiLicence == null || cleApiLicence.isBlank()) {
            // Pas encore enrole : le fichier reste ABSENT (jamais cree vide) — voir la javadoc de
            // la classe sur ce que l'Agent doit en deduire.
            return;
        }

        ecrireAtomiquement(cleApiLicence.trim());
    }

    private void ecrireAtomiquement(String contenu) {
        boolean posix = FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
        Path parent = cheminFichier.toAbsolutePath().getParent();
        try {
            if (parent != null) {
                if (posix) {
                    Files.createDirectories(parent,
                            PosixFilePermissions.asFileAttribute(PERMISSIONS_DOSSIER_PROPRIETAIRE_SEUL));
                } else {
                    Files.createDirectories(parent);
                }
            }
        } catch (IOException e) {
            log.error("Agent : impossible de creer le dossier {} pour le fichier de cle API locale : {}",
                    parent, e.toString());
            return;
        }

        if (!posix) {
            // Pas d'attributs POSIX disponibles : aucune fenetre a fermer (il n'y a pas de permissions
            // a restreindre), ecriture directe, degrade au mieux — voir la javadoc de la classe.
            try {
                Files.writeString(cheminFichier, contenu, StandardCharsets.UTF_8);
                log.warn("Agent : systeme de fichiers sans attributs POSIX, impossible de restreindre "
                        + "{} a 0600 (acceptable en dev Windows, PAS en production).", cheminFichier);
            } catch (IOException e) {
                log.error("Agent : ecriture du fichier de cle API locale {} impossible : {}", cheminFichier, e.toString());
            }
            return;
        }

        ecrireAtomiquementSurPosix(contenu, parent);
    }

    /**
     * Fichier temporaire cree AVEC {@code 0600} deja applique, dans le MEME dossier que la cible,
     * puis deplace atomiquement — voir la javadoc de la classe pour le rationale complet (pas de
     * fenetre "fichier moins permissif", echec ferme si la creation 0600 echoue).
     */
    private void ecrireAtomiquementSurPosix(String contenu, Path parent) {
        FileAttribute<Set<PosixFilePermission>> attributFichier =
                PosixFilePermissions.asFileAttribute(PERMISSIONS_FICHIER_PROPRIETAIRE_SEUL);
        Path tmp;
        try {
            tmp = Files.createTempFile(parent, cheminFichier.getFileName().toString(), ".tmp", attributFichier);
        } catch (IOException e) {
            // Echec FERME : la creation 0600 elle-meme a echoue, donc RIEN n'est ecrit — jamais de
            // fichier moins permissif contenant la cle, contrairement a l'ancien patron chmod-apres.
            log.error("Agent : impossible de creer le fichier temporaire 0600 pour la cle API locale "
                    + "(rien n'est ecrit sur disque) : {}", e.toString());
            return;
        }
        try {
            Files.writeString(tmp, contenu, StandardCharsets.UTF_8);
            Files.move(tmp, cheminFichier, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            log.error("Agent : ecriture/deplacement atomique du fichier de cle API locale {} impossible : {}",
                    cheminFichier, e.toString());
            supprimerTemporaireSiPresent(tmp);
        }
    }

    private static void supprimerTemporaireSiPresent(Path tmp) {
        try {
            Files.deleteIfExists(tmp);
        } catch (IOException ignored) {
            // Nettoyage best-effort du temporaire : aucun risque de securite si ca echoue (le
            // temporaire etait deja 0600 a sa creation), juste un fichier orphelin a nettoyer
            // manuellement plus tard.
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}

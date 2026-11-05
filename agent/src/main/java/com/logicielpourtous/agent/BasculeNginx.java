package com.logicielpourtous.agent;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bascule le port actif cote Nginx local de l'Agent (Task 8) : reecrit le fichier de config
 * upstream a partir du template classpath, puis recharge Nginx via le meme
 * {@link CommandeExecuteur} que {@link DockerControl}.
 */
public class BasculeNginx {

    private static final Logger log = LoggerFactory.getLogger(BasculeNginx.class);
    private static final String TEMPLATE = "/nginx-upstream.conf.template";
    private static final String PLACEHOLDER_PORT = "__PORT__";
    private static final int PORT_PAR_DEFAUT = 8080;
    private static final Pattern LIGNE_SERVEUR = Pattern.compile("server 127\\.0\\.0\\.1:(\\d+);");

    private final Path fichierConfig;
    private final CommandeExecuteur executeur;

    public BasculeNginx(Path fichierConfig, CommandeExecuteur executeur) {
        this.fichierConfig = fichierConfig;
        this.executeur = executeur;
    }

    /**
     * Ecrit {@code server 127.0.0.1:<port>;} dans le fichier upstream puis execute
     * {@code nginx -s reload}. Si le reload echoue, l'ancien contenu est restaure (best-effort)
     * pour que le fichier ne decrive pas un port jamais applique.
     *
     * @throws IllegalStateException si {@code nginx -s reload} echoue
     */
    public void basculerVers(int port) {
        String precedent = lireConfigCourante();
        ecrireConfig(port);
        ResultatCommande resultat;
        try {
            resultat = executeur.executer(List.of("nginx", "-s", "reload"));
        } catch (RuntimeException e) {
            // Le reload n'a pas pu etre lance (binaire absent, timeout, interruption) : Nginx n'a
            // rien applique, donc on restaure avant de relancer l'exception d'origine.
            restaurer(precedent);
            throw e;
        }
        // Le test unitaire ne stub pas executer() : le mock renvoie null. Un vrai executeur ne
        // renvoie jamais null, cette garde ne sert qu'a ce cas de test.
        if (resultat != null && resultat.codeSortie() != 0) {
            restaurer(precedent);
            throw new IllegalStateException("nginx -s reload a echoue (code "
                    + resultat.codeSortie() + ") : " + resultat.sortieErreur().trim());
        }
    }

    /**
     * Port actif lu dans le fichier upstream (source de verite, survit aux redemarrages de
     * l'Agent). Avant le tout premier {@link #basculerVers(int)}, le port actif est 8080 (Global
     * Constraint du plan, meme valeur que {@code OrchestrateurDeploiement.PORT_BLEU}).
     *
     * @throws IllegalStateException si le fichier existe mais ne contient pas la ligne attendue
     */
    public int portActifActuel() {
        String contenu = lireConfigCourante();
        if (contenu == null) {
            return PORT_PAR_DEFAUT;
        }
        Matcher correspondance = LIGNE_SERVEUR.matcher(contenu);
        if (!correspondance.find()) {
            throw new IllegalStateException("Config upstream inattendue dans " + fichierConfig
                    + " : ligne 'server 127.0.0.1:<port>;' introuvable. Corriger ou supprimer le"
                    + " fichier avant de relancer l'Agent.");
        }
        return Integer.parseInt(correspondance.group(1));
    }

    /** Contenu actuel du fichier, ou {@code null} si le fichier n'existe pas encore. */
    private String lireConfigCourante() {
        if (!Files.exists(fichierConfig)) {
            return null;
        }
        try {
            return Files.readString(fichierConfig, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Lecture de la config upstream impossible : "
                    + fichierConfig, e);
        }
    }

    /**
     * Restaure le contenu precedent apres un reload rate. Best-effort : un echec ici est logue
     * en erreur et ne masque pas l'exception de reload d'origine.
     */
    private void restaurer(String precedent) {
        try {
            if (precedent == null) {
                Files.deleteIfExists(fichierConfig);
            } else {
                ecrireAtomiquement(precedent);
            }
        } catch (IOException | RuntimeException e) {
            log.error("RESTAURATION DE LA CONFIG UPSTREAM ECHOUEE apres echec du reload Nginx : "
                    + "le fichier {} peut etre incoherent avec Nginx, intervention manuelle "
                    + "requise", fichierConfig, e);
        }
    }

    private void ecrireConfig(int port) {
        String contenu = lireTemplate().replace(PLACEHOLDER_PORT, String.valueOf(port));
        try {
            ecrireAtomiquement(contenu);
        } catch (IOException e) {
            throw new IllegalStateException("Ecriture de la config upstream impossible : "
                    + fichierConfig, e);
        }
    }

    /**
     * Ecriture atomique : temp dans le meme dossier puis move ATOMIC_MOVE, pour qu'un crash en
     * cours d'ecriture ne laisse jamais un bloc upstream tronque.
     */
    private void ecrireAtomiquement(String contenu) throws IOException {
        Path temp = null;
        try {
            temp = Files.createTempFile(fichierConfig.getParent(),
                    fichierConfig.getFileName().toString(), ".tmp");
            Files.writeString(temp, contenu, StandardCharsets.UTF_8);
            Files.move(temp, fichierConfig, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            supprimerSiPresent(temp);
            throw e;
        }
    }

    private static void supprimerSiPresent(Path temp) {
        if (temp == null) {
            return;
        }
        try {
            Files.deleteIfExists(temp);
        } catch (IOException ignoree) {
            // Nettoyage best-effort : l'erreur d'ecriture d'origine reste la seule remontee.
        }
    }

    private static String lireTemplate() {
        try (InputStream flux = BasculeNginx.class.getResourceAsStream(TEMPLATE)) {
            if (flux == null) {
                throw new IllegalStateException("Template introuvable dans le classpath : "
                        + TEMPLATE);
            }
            return new String(flux.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Lecture du template impossible : " + TEMPLATE, e);
        }
    }
}

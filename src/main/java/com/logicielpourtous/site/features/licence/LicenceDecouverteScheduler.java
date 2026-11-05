package com.logicielpourtous.site.features.licence;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.logicielpourtous.site.features.commun.ServerRole;
import com.logicielpourtous.site.features.commun.ServerRoleProperties;
import com.logicielpourtous.site.features.sync.SecondaryServer;
import com.logicielpourtous.site.features.sync.SecondaryServerRepository;

/**
 * Cote PARTNER uniquement (Task 6) : sonde periodiquement le MAITRE ({@link
 * LicenceClient#listerNouveauxServeurs}) pour decouvrir ses {@link LicenceServeur} {@code CLIENT}
 * pas encore notifies, materialise localement le {@code SecondaryServer} correspondant
 * (toujours initie par le secondaire — meme principe que {@code
 * features.sync.SyncOutboxScheduler}, deja livre, pour la synchronisation de donnees), et pousse
 * en retour un {@code enrollmentCode} LOCAL (module {@code features.sync}, EXISTANT, {@code
 * POST /api/sync/enroll}) pour que le client concerne puisse terminer SA PROPRE inscription
 * auprès de ce {@code PARTNER}. La revocation d'un {@link LicenceServeur} cote maitre est
 * propagee de la meme facon : le {@code SecondaryServer} local correspondant passe {@code
 * REVOQUE} au cycle suivant.
 *
 * <p>Bean cree seulement si {@code app.licence.master-url} est configure (meme condition que
 * {@link LicenceClient}/{@link LicenceBootstrap}) ET garde explicite {@code app.role=PARTNER} —
 * contrairement a {@code SyncOutboxScheduler} (generique CLIENT/PARTNER), cette decouverte n'a de
 * sens QUE pour un {@code PARTNER} (seul role qui a ses propres clients a materialiser) : un
 * {@code CLIENT} (ou, en theorie, un {@code MASTER} mal configure) pour lequel {@code
 * app.licence.master-url} serait pourtant presente ne doit RIEN faire, voir {@link #decouvrir}.
 *
 * <p><b>Correspondance locale (fix round 1)</b> : {@code customerId} seul NE SUFFIT PAS a identifier
 * une installation de facon stable — un partenaire peut generer un 2e jeton d'installation (Task 4/5)
 * pour un {@code customerId} DEJA servi (machine remplacee), ce qui cree un {@link LicenceServeur}
 * {@code CLIENT} {@code ACTIF} GENUINEMENT DISTINCT sous le MEME {@code customerId} — rien, ni cote
 * maitre ni cote partenaire, n'empeche structurellement 2 {@code LicenceServeur} {@code CLIENT} actifs
 * (ou un actif + un revoque) pour le meme {@code customerId}. Matcher par {@code customerId} seul
 * retrouverait alors l'ANCIEN {@code SecondaryServer} (potentiellement deja {@code REVOQUE}) et le
 * reactiverait a tort, re-autorisant son {@code apiKeyHash} — valide pour la machine MORTE, pas pour la
 * nouvelle. {@link SecondaryServer#getIdentifiantInstallationStable()} (correlation metadata ajoutee
 * a cet effet, jamais lue/ecrite par le protocole de sync existant) est donc la cle de correspondance
 * PRIMAIRE ; {@code customerId} seul n'est plus utilise QUE pour un repli retro-compatible sur une
 * ligne materialisee par une version anterieure a ce fix (champ encore {@code null}), et seulement
 * quand ce repli est sans ambiguite ET que la candidate n'est pas {@code REVOQUE} (fix round 2 : une
 * ligne {@code REVOQUE} n'est JAMAIS reactivee par ce repli, meme seule candidate) — voir {@link
 * #trouverServeurLocal}.
 *
 * <p><b>Concurrence</b> : {@link #decouvrir} est {@code synchronized}, meme patron defensif que
 * {@code SyncOutboxScheduler#synchroniser} — un {@code fixedDelay} Spring n'autorise de toute
 * facon pas une 2e execution avant la fin de la precedente, mais ce verrou protege aussi un appel
 * manuel concurrent (tests, declenchement administratif futur). A l'interieur d'un seul cycle,
 * chaque entree decouverte (identifiee par son {@code identifiantInstallationStable}, voir plus haut)
 * n'est traitee qu'une fois (boucle sequentielle) : aucun risque de doublon de {@link SecondaryServer}
 * NI de double-notification pour CE cycle. Un {@code
 * LicenceServeur} donne n'est de toute facon renvoye par {@code /nouveaux-serveurs} qu'une fois
 * par etat (voir {@code notifiePartenaire}), meme si un cycle precedent a echoue en cours de route
 * (le {@code POST /code-partenaire} de l'entree en cause n'a alors pas ete envoye) — un
 * {@code SecondaryServer} deja materialise serait alors simplement retrouve (pas recree) et son
 * code renvoye a nouveau, idempotent.
 *
 * <p><b>Robustesse</b> : aucune exception ne sort d'un cycle (meme patron non-fatal que {@link
 * LicenceBootstrap#lancer}).
 *
 * <p>Propriete : {@code app.licence.poll-interval-ms} (delai entre deux cycles, defaut 5000) et
 * {@code app.licence.initial-delay-ms} (defaut 5000) — partagees avec le sondage de retry de
 * {@link LicenceBootstrap} cote CLIENT, meme semantique ("frequence des echanges periodiques de ce
 * module avec le maitre").
 */
@Component
@ConditionalOnProperty(name = "app.licence.master-url")
public class LicenceDecouverteScheduler {

    private static final Logger log = LoggerFactory.getLogger(LicenceDecouverteScheduler.class);

    /** Duree de validite du code d'enrolement LOCAL (sync) genere ici : aussi longue que celle des
     * codes generes manuellement par un admin sur {@code GestionServeursController} (transmis hors-
     * perimetre, pas un echange immediat du meme flux automatique) — meme rationale. */
    private static final java.time.Duration DUREE_CODE_LOCAL = java.time.Duration.ofDays(7);

    private final LicenceClient licenceClient;
    private final SecondaryServerRepository secondaryServerRepository;
    private final ServerRoleProperties serverRoleProperties;

    public LicenceDecouverteScheduler(LicenceClient licenceClient,
                                      SecondaryServerRepository secondaryServerRepository,
                                      ServerRoleProperties serverRoleProperties) {
        this.licenceClient = licenceClient;
        this.secondaryServerRepository = secondaryServerRepository;
        this.serverRoleProperties = serverRoleProperties;
    }

    @Scheduled(fixedDelayString = "${app.licence.poll-interval-ms:5000}",
               initialDelayString = "${app.licence.initial-delay-ms:5000}")
    public synchronized void decouvrir() {
        if (serverRoleProperties.getRole() != ServerRole.PARTNER) {
            // Defense en profondeur : seul un PARTNER a des clients a decouvrir (voir javadoc de
            // classe) ; ce bean ne devrait meme pas exister pour un CLIENT/MASTER correctement
            // configure (aucune raison d'avoir app.licence.master-url dans ce cas).
            return;
        }
        try {
            for (LicenceEnrolementController.NouveauServeurDto nouveau : licenceClient.listerNouveauxServeurs()) {
                // Revue finale, C2 : isolation PAR ENTREE — un echec sur une entree (quelle qu'en soit
                // la cause) ne doit jamais empecher le traitement des suivantes de CE cycle. L'entree en
                // echec reste notifiePartenaire=false cote maitre, donc retentee au cycle suivant.
                try {
                    traiter(nouveau);
                } catch (RuntimeException e) {
                    log.error("Licence : decouverte en echec pour l'installation {} (client {}, statut {}), "
                            + "nouvel essai au prochain cycle : {}", nouveau.identifiantInstallationStable(),
                            nouveau.customerId(), nouveau.statut(), e.toString());
                }
            }
        } catch (RuntimeException e) {
            // Echec de listerNouveauxServeurs() lui-meme : rien a iterer, cycle avorte.
            log.error("Licence : cycle de decouverte interrompu, nouvel essai au prochain cycle : {}", e.toString());
        }
    }

    private void traiter(LicenceEnrolementController.NouveauServeurDto nouveau) {
        SecondaryServer.SecondaryServerStatut statutCible = nouveau.statut() == LicenceServeur.LicenceServeurStatut.REVOQUE
                ? SecondaryServer.SecondaryServerStatut.REVOQUE
                : SecondaryServer.SecondaryServerStatut.ACTIF;

        SecondaryServer serveur = trouverServeurLocal(nouveau.identifiantInstallationStable(), nouveau.customerId())
                .orElse(null);
        if (serveur == null) {
            // Nouvelle installation (y compris le cas limite ou elle serait decouverte deja
            // REVOQUE, ex. un admin revoque entre la creation du LicenceServeur et le cycle de
            // decouverte qui l'aurait sinon materialisee ACTIF en premier) : un enrollmentCode est
            // toujours genere, meme inutile pour un SecondaryServer deja REVOQUE, pour que CET appel
            // notifie le maitre de la meme facon dans tous les cas (pas de 2e branche sans code).
            serveur = materialiser(nouveau, statutCible);
        } else {
            serveur.setStatut(statutCible);
            // Backfill (fix round 1) : une ligne retrouvee via le repli customerId retro-compatible
            // (champ encore null) recoit maintenant son identifiant, pour matcher EXACTEMENT au
            // prochain cycle au lieu de retomber sur ce repli.
            serveur.setIdentifiantInstallationStable(nouveau.identifiantInstallationStable());
            secondaryServerRepository.save(serveur);
            log.info("Licence : SecondaryServer {} (client {}, installation {}) mis a jour -> statut {}",
                    serveur.getId(), nouveau.customerId(), nouveau.identifiantInstallationStable(), statutCible);
        }

        // Toujours notifie, meme sur une mise a jour (revocation) : c'est CET appel qui marque
        // notifiePartenaire=true cote maitre et arrete le re-sondage de cette entree.
        // Revue finale, C2 : pour une revocation, le code est sans objet — toujours null explicitement
        // (enrollmentCode est de toute facon deja null pour un client inscrit sync, EnrollmentController
        // l'efface) ; le maitre accepte un code vide UNIQUEMENT pour un LicenceServeur REVOQUE.
        String codeAPousser = statutCible == SecondaryServer.SecondaryServerStatut.REVOQUE
                ? null
                : serveur.getEnrollmentCode();
        licenceClient.pousserCodePartenaire(nouveau.identifiantInstallationStable(), codeAPousser);
    }

    private SecondaryServer materialiser(LicenceEnrolementController.NouveauServeurDto nouveau,
                                         SecondaryServer.SecondaryServerStatut statutCible) {
        SecondaryServer serveur = new SecondaryServer();
        serveur.setNom("Client " + nouveau.customerId());
        serveur.setRole(ServerRole.CLIENT);
        serveur.setCustomerId(nouveau.customerId());
        serveur.setIdentifiantInstallationStable(nouveau.identifiantInstallationStable());
        serveur.setStatut(statutCible);
        serveur.setEnrollmentCode(UUID.randomUUID().toString());
        serveur.setEnrollmentCodeExpiresAt(Instant.now().plus(DUREE_CODE_LOCAL));
        serveur = secondaryServerRepository.save(serveur);
        log.info("Licence : SecondaryServer {} materialise pour le client {} (installation {}) decouvert (statut {})",
                serveur.getId(), nouveau.customerId(), nouveau.identifiantInstallationStable(), statutCible);
        return serveur;
    }

    /**
     * {@link SecondaryServerRepository} n'offre aucune requete dediee (hors perimetre des fichiers de
     * cette tache, voir la javadoc de classe) : {@code findAll} + filtre en memoire, acceptable pour un
     * job de fond peu frequent sur un volume de {@code SecondaryServer} necessairement petit.
     *
     * <p><b>Fix round 1</b> : matche D'ABORD par {@code identifiantInstallationStable} EXACT (cle
     * stable, voir la javadoc de classe) — JAMAIS par {@code customerId} seul quand une ligne porte
     * deja CET identifiant pour un AUTRE {@code customerId}... en pratique cela n'arrive pas (un
     * {@code identifiantInstallationStable} est globalement unique, genere par l'installation
     * elle-meme), donc un match exact est toujours le bon. Seulement si AUCUNE ligne ne porte cet
     * identifiant, repli retro-compatible sur {@code customerId} parmi les lignes dont le champ est
     * encore {@code null} (materialisees avant ce fix, OU par {@code GestionServeursController}, voir
     * fix round 2) — UNIQUEMENT si ce repli est SANS AMBIGUITE (exactement une candidate) ; sinon
     * ({@code null} ou plusieurs) on ne matche PAS par customerId et {@link #traiter} materialise une
     * NOUVELLE ligne plutot que de risquer de reactiver la mauvaise.
     *
     * <p><b>Fix round 2 — exclusion categorique des lignes {@code REVOQUE}</b> : une ligne {@code
     * identifiantInstallationStable == null} ET {@code REVOQUE} n'est PAS seulement un residu d'avant
     * ce fix destine a etre "revendique" par la prochaine decouverte de la MEME installation — elle
     * peut tout aussi bien venir de {@code GestionServeursController} (ecran admin, Task 5 : cree ET
     * revoque des {@code SecondaryServer} entierement HORS du flux de decouverte licence, ne renseigne
     * jamais ce champ) OU etre une revocation anterieure a ce fix DEJA PROPAGEE ({@code
     * notifiePartenaire=true} cote maitre : elle ne reapparaitra JAMAIS dans {@code
     * /nouveaux-serveurs}, donc son {@code identifiantInstallationStable} ne sera JAMAIS retro-rempli).
     * Dans ces 2 cas, s'il n'existe par ailleurs AUCUNE AUTRE ligne non-identifiee pour ce {@code
     * customerId}, le repli serait a tort jugee "sans ambiguite" et reactiverait cette ligne MORTE —
     * EXACTEMENT le bug du round 1, seulement moins facile a declencher. Les candidates {@code
     * REVOQUE} sont donc exclues du repli AVANT meme de compter l'ambiguite (jamais reactivees par ce
     * repli, QUEL QUE SOIT le nombre de candidates) : s'il n'en reste aucune ({@code ACTIF}) apres ce
     * filtre, {@link #traiter} materialise une NOUVELLE ligne, sans jamais toucher a l'ancienne.
     */
    private java.util.Optional<SecondaryServer> trouverServeurLocal(String identifiantInstallationStable, Long customerId) {
        java.util.List<SecondaryServer> clients = secondaryServerRepository.findAll().stream()
                .filter(s -> s.getRole() == ServerRole.CLIENT)
                .toList();

        java.util.Optional<SecondaryServer> parIdentifiant = clients.stream()
                .filter(s -> identifiantInstallationStable.equals(s.getIdentifiantInstallationStable()))
                .findFirst();
        if (parIdentifiant.isPresent()) {
            return parIdentifiant;
        }

        java.util.List<SecondaryServer> repliNonIdentifies = clients.stream()
                .filter(s -> s.getIdentifiantInstallationStable() == null
                        && Objects.equals(s.getCustomerId(), customerId)
                        // Fix round 2 : jamais une ligne REVOQUE, meme seule candidate — voir javadoc.
                        && s.getStatut() != SecondaryServer.SecondaryServerStatut.REVOQUE)
                .toList();
        return repliNonIdentifies.size() == 1 ? java.util.Optional.of(repliNonIdentifies.get(0)) : java.util.Optional.empty();
    }
}

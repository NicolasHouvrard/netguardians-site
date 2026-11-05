package com.logicielpourtous.site.features.licence;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.logicielpourtous.site.features.commun.ServerRole;
import com.logicielpourtous.site.features.commun.ServerRoleProperties;

/**
 * Cote SECONDAIRE uniquement (bean cree seulement si {@code app.licence.master-url} est
 * configure — meme patron que {@code features.sync.RestSyncClient}/{@code SyncOutboxScheduler} :
 * absent de config = rien ne se passe, cohérent avec "un {@code MASTER} n'a besoin d'aucune
 * config {@code app.licence.*}").
 *
 * <p>Au premier demarrage ({@link ApplicationReadyEvent}) : cree {@link EtatLicenceLocale} si
 * absent (UUID neuf), puis, si l'enrolement n'est pas deja termine, appelle
 * {@code POST /api/licence/enregistrer} (login/mot de passe pour un {@code PARTNER}, ou jeton pour
 * un {@code CLIENT} — exactement un des deux configure) suivi de {@code POST /api/licence/cle},
 * et stocke la cle API recue. Pendant pour le module {@code licence} de l'enrolement
 * {@code features.sync.EnrollmentController} deja livre, mais entierement AUTOMATIQUE : aucune
 * copie manuelle de cle n'est necessaire.
 *
 * <p><b>Pas de redemarrage programmatique</b> (contrainte globale du plan) : une fois la cle
 * obtenue, elle n'est appliquee qu'au PROCHAIN demarrage du processus (log {@code INFO} explicite).
 *
 * <p><b>Robustesse</b> : aucune exception ne sort de {@link #lancer()} (meme patron que
 * {@code SyncOutboxScheduler#synchroniser()}) — un maitre inaccessible, un code expire ou une
 * configuration invalide sont loggues en {@code ERROR} mais ne font jamais planter le demarrage de
 * ce serveur ; l'enrolement est simplement retente au prochain demarrage (ou apres correction de la
 * configuration).
 *
 * <p><b>Sondage periodique pour un CLIENT (Task 6)</b> : pour un {@code PARTNER} (ou tout autre
 * role), l'enrolement est termine dès que {@code /enregistrer}+{@code /cle} ont reussi UNE fois —
 * {@code enregistrementTermine=true}, comme avant cette tache. Pour un {@code CLIENT}, ce n'est PAS
 * suffisant : il lui faut EN PLUS l'adresse du {@code PARTNER} de son compte et un code local
 * d'enrolement {@code features.sync}, poussees par ce {@code PARTNER} (via {@code
 * LicenceDecouverteScheduler}, potentiellement bien plus tard que l'installation du CLIENT) — tant
 * qu'elles manquent, {@link #lancer()} (desormais aussi {@code @Scheduled}, pas seulement au
 * demarrage) retente le MEME appel {@code /enregistrer} (idempotent, voir {@link LicenceServeur})
 * au cycle suivant plutot que d'echouer ou de rester bloque indefiniment apres le seul essai du
 * demarrage. {@code /cle}, lui, n'est appele qu'UNE fois ({@code etat.getCleApiLicence() == null}),
 * et pour un {@code CLIENT} seulement une fois "pret" (revue finale, C1 : l'appeler plus tot poserait
 * {@code cleApiHash} cote MP et ferait refuser 409 tous les {@code /enregistrer} suivants).
 *
 * <p><b>Fix round 1 — inscription sync automatique</b> : une fois {@code adressePartenaireResolue}+
 * {@code codeLocal} obtenus, un {@code CLIENT} appelle AUSSI, automatiquement, {@code POST
 * /api/sync/enroll} (module {@code features.sync}, DEJA EXISTANT, voir {@code EnrollmentController}
 * — lu directement, jamais devine) sur l'adresse du partenaire, avec {@code codeLocal} comme {@code
 * enrollmentCode}, et stocke la cle API sync obtenue dans {@link EtatLicenceLocale#getCleApiSync()}.
 * Comme pour la cle licence elle-meme, elle n'est jamais re-demandee une fois obtenue ({@code
 * etat.getCleApiSync() == null}) : le code d'enrolement sync est a USAGE UNIQUE cote maitre ({@code
 * EnrollmentController} l'efface apres succes), le rejouer echouerait. Un {@code RestClient} DISTINCT
 * de {@link #restClient} (qui pointe sur {@code app.licence.master-url}, pas sur l'adresse du
 * partenaire, connue seulement a l'execution) est construit via {@link RestClient.Builder#clone()} du
 * builder injecte — jamais en mutant {@code restClient}/le builder partage, pour rester sans risque
 * en cas d'appels concurrents. Ce module n'ecrit JAMAIS {@code app.sync.*} ni ne touche {@code
 * RestSyncClient}/{@code SyncOutboxScheduler} : la cle obtenue est seulement STOCKEE, a copier
 * manuellement dans la configuration avant de redemarrer (meme restriction "pas de redemarrage
 * programmatique" que pour la cle licence elle-meme) — log {@code INFO} explicite donnant l'adresse et
 * rappelant les 2 proprietes a renseigner.
 */
@Component
@ConditionalOnProperty(name = "app.licence.master-url")
public class LicenceBootstrap {

    private static final Logger log = LoggerFactory.getLogger(LicenceBootstrap.class);

    record EnregistrerRequest(String login, String motDePasse, String jeton, ServerRole roleVoulu,
                               String identifiantInstallationStable, String adresseDeclaree) {
    }

    // codeEnrolementExpireA n'est jamais interprete cote client (le maitre fait autorite sur
    // l'expiration, via un 410 Gone a /api/licence/cle) : type String, pas de parsing de date.
    //
    // adressePartenaireResolue/codeLocal (Task 6) : absents (donc null, Jackson ne leve rien sur un
    // champ manquant) de la reponse PARTNER (jamais remplis pour ce role cote maitre) ; pour un
    // CLIENT, null tant que le PARTNER de son compte n'a pas encore pousse son code local via
    // POST /api/licence/code-partenaire — voir estPretPourUnClient.
    record EnregistrerResponse(String codeEnrolement, String codeEnrolementExpireA,
                                String adressePartenaireResolue, String codeLocal) {
    }

    record CleRequest(String codeEnrolement) {
    }

    record CleResponse(String cleApi) {
    }

    /** {@code enrollmentCode} : nom de champ EXACT attendu par {@code
     * features.sync.EnrollmentController.EnrollmentRequest} — lu directement, pas devine. */
    record SyncEnrollRequest(String enrollmentCode) {
    }

    /** {@code apiKey} : nom de champ EXACT de la reponse de {@code EnrollmentController#enroll}. */
    record SyncEnrollResponse(String apiKey) {
    }

    private final EtatLicenceLocaleRepository etatRepository;
    private final ServerRoleProperties serverRoleProperties;
    private final RestClient.Builder restClientBuilder;
    private final RestClient restClient;
    private final String login;
    private final String motDePasse;
    private final String jeton;
    private final String adressePublique;

    public LicenceBootstrap(RestClient.Builder restClientBuilder,
                             EtatLicenceLocaleRepository etatRepository,
                             ServerRoleProperties serverRoleProperties,
                             @Value("${app.licence.master-url}") String masterUrl,
                             @Value("${app.licence.login:}") String login,
                             @Value("${app.licence.mot-de-passe:}") String motDePasse,
                             @Value("${app.licence.jeton:}") String jeton,
                             @Value("${app.licence.adresse-publique:}") String adressePublique) {
        // Conserve AUSSI le builder lui-meme (pas seulement le RestClient construit ci-dessous, fige
        // sur masterUrl) : l'inscription sync (fix round 1) a besoin d'un RestClient pointant sur
        // l'adresse du PARTENAIRE, connue seulement a l'execution — voir enrolerSyncSiNecessaire.
        this.restClientBuilder = restClientBuilder;
        this.restClient = restClientBuilder.baseUrl(masterUrl).build();
        this.etatRepository = etatRepository;
        this.serverRoleProperties = serverRoleProperties;
        this.login = login;
        this.motDePasse = motDePasse;
        this.jeton = jeton;
        this.adressePublique = adressePublique;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Scheduled(fixedDelayString = "${app.licence.poll-interval-ms:5000}",
               initialDelayString = "${app.licence.initial-delay-ms:5000}")
    // Revue finale, I3 : synchronized — a la fois listener ApplicationReadyEvent (thread principal) ET
    // @Scheduled (thread du scheduler) ; sans verrou, deux executions concurrentes pourraient chacune
    // obtenir une cle API licence DIFFERENTE et sauver la "mauvaise" en dernier. Meme patron que
    // LicenceDecouverteScheduler#decouvrir.
    public synchronized void lancer() {
        if (serverRoleProperties.getRole() == ServerRole.MASTER) {
            // Defense en profondeur : un MASTER ne devrait avoir aucune config app.licence.* (donc
            // ce bean n'existerait meme pas, voir @ConditionalOnProperty), mais si c'est pourtant
            // le cas on ne fait rien plutot que de tenter un auto-enrolement absurde.
            return;
        }
        try {
            enrolerSiNecessaire();
        } catch (RuntimeException e) {
            log.error("Licence : auto-enrolement en echec, nouvel essai au prochain sondage : {}", e.toString());
        }
    }

    private void enrolerSiNecessaire() {
        EtatLicenceLocale etat = etatRepository.findAll().stream().findFirst().orElseGet(this::creerEtatInitial);
        if (etat.isEnregistrementTermine()) {
            return;
        }

        EnregistrerRequest requete = construireRequeteEnregistrer(etat.getIdentifiantInstallationStable());
        EnregistrerResponse enregistrement = restClient.post()
                .uri("/api/licence/enregistrer")
                .body(requete)
                .retrieve()
                .body(EnregistrerResponse.class);
        if (enregistrement == null || enregistrement.codeEnrolement() == null || enregistrement.codeEnrolement().isBlank()) {
            throw new IllegalStateException("Reponse invalide du maitre a /api/licence/enregistrer");
        }

        // Revue finale, C1 : pour un CLIENT, la garde "pas encore pret" passe AVANT l'echange /cle.
        // Appeler /cle des le 1er sondage poserait cleApiHash sur le LicenceServeur cote MP ; chaque
        // /enregistrer suivant (seul moyen d'obtenir adresse + code local) serait alors refuse 409 par
        // LicenceEnrolementController#estReutilisationLegitimePourCeJeton (qui exige cleApiHash == null),
        // bloquant ce CLIENT pour toujours. Un PARTNER n'a pas de notion de "pret" : inchange.
        boolean estClient = serverRoleProperties.getRole() == ServerRole.CLIENT;
        if (estClient && !estPretPourUnClient(enregistrement)) {
            etatRepository.save(etat);
            log.info("Licence : CLIENT enrole mais en attente que son PARTENAIRE le decouvre et pousse "
                    + "son code local, nouvel essai au prochain sondage.");
            return;
        }

        if (etat.getCleApiLicence() == null) {
            CleResponse cle = restClient.post()
                    .uri("/api/licence/cle")
                    .body(new CleRequest(enregistrement.codeEnrolement()))
                    .retrieve()
                    .body(CleResponse.class);
            if (cle == null || cle.cleApi() == null || cle.cleApi().isBlank()) {
                throw new IllegalStateException("Reponse invalide du maitre a /api/licence/cle");
            }
            etat.setCleApiLicence(cle.cleApi());
        }

        if (estClient) {
            etat.setAdressePartenaireResolue(enregistrement.adressePartenaireResolue());
            etat.setCodeLocalPartenaire(enregistrement.codeLocal());
            enrolerSyncSiNecessaire(etat);
        }
        etat.setEnregistrementTermine(true);
        etatRepository.save(etat);
        if (estClient) {
            log.info("Licence obtenue (+ inscription sync), redemarrez ce serveur pour l'appliquer : "
                    + "configurez app.sync.master-url={} et app.sync.api-key=<cle sync obtenue>.",
                    etat.getAdressePartenaireResolue());
        } else {
            log.info("Licence obtenue, redemarrez ce serveur pour l'appliquer.");
        }
    }

    /**
     * Fix round 1 : termine l'inscription de ce {@code CLIENT} auprès du moteur de synchronisation
     * {@code features.sync}, DEJA EXISTANT ({@code POST /api/sync/enroll}, {@code EnrollmentController}
     * — lu directement), en consommant {@code codeLocal} sur l'adresse du PARTENAIRE. A appeler
     * UNIQUEMENT quand {@code estPretPourUnClient} est vrai (donc {@code codeLocal} non vide).
     *
     * <p>Idempotent via {@code etat.getCleApiSync() == null} : {@code codeLocal} est un code sync A
     * USAGE UNIQUE (efface cote partenaire apres succes par {@code EnrollmentController}) — le rejouer
     * une 2e fois echouerait (401), donc on ne le fait JAMAIS une fois la cle obtenue.
     */
    private void enrolerSyncSiNecessaire(EtatLicenceLocale etat) {
        if (etat.getCleApiSync() != null) {
            return;
        }
        RestClient clientVersPartenaire = restClientBuilder.clone()
                .baseUrl(etat.getAdressePartenaireResolue())
                .build();
        SyncEnrollResponse reponse = clientVersPartenaire.post()
                .uri("/api/sync/enroll")
                .body(new SyncEnrollRequest(etat.getCodeLocalPartenaire()))
                .retrieve()
                .body(SyncEnrollResponse.class);
        if (reponse == null || reponse.apiKey() == null || reponse.apiKey().isBlank()) {
            throw new IllegalStateException("Reponse invalide du partenaire a /api/sync/enroll");
        }
        etat.setCleApiSync(reponse.apiKey());
    }

    /** Task 6 : un CLIENT n'est "pret" que lorsque le PARTENAIRE de son compte a ete decouvert
     * (adresse connue) ET a pousse un code local d'enrolement {@code features.sync}. */
    private static boolean estPretPourUnClient(EnregistrerResponse enregistrement) {
        return !isBlank(enregistrement.adressePartenaireResolue()) && !isBlank(enregistrement.codeLocal());
    }

    private EtatLicenceLocale creerEtatInitial() {
        EtatLicenceLocale nouveau = new EtatLicenceLocale();
        nouveau.setIdentifiantInstallationStable(UUID.randomUUID().toString());
        return etatRepository.save(nouveau);
    }

    /**
     * Exactement une des deux methodes d'authentification doit etre configuree : {@code
     * app.licence.login} + {@code app.licence.mot-de-passe} (serveur {@code PARTNER}), ou {@code
     * app.licence.jeton} (serveur {@code CLIENT}, cote maitre pas encore implemente — tache
     * ulterieure du plan ; le champ est neanmoins deja transmis ici). Ni les deux, ni aucune des
     * deux : erreur de configuration documentee, loggue en {@code ERROR} par {@link #lancer()}.
     *
     * <p>Visibilite package-private (plutot que {@code private}) uniquement pour que {@code
     * LicenceBootstrapTest} puisse verifier directement le message de l'{@link
     * IllegalStateException} sur chacun des 2 cas invalides, sans dupliquer la capture des logs.
     */
    EnregistrerRequest construireRequeteEnregistrer(String identifiantInstallationStable) {
        boolean loginPresent = !isBlank(login);
        boolean motDePassePresent = !isBlank(motDePasse);
        if (loginPresent != motDePassePresent) {
            throw new IllegalStateException("Configuration licence invalide : app.licence.login et "
                    + "app.licence.mot-de-passe doivent etre configures ENSEMBLE (ou aucun des deux).");
        }
        boolean loginEtMotDePassePresents = loginPresent && motDePassePresent;
        boolean jetonPresent = !isBlank(jeton);
        if (loginEtMotDePassePresents == jetonPresent) {
            throw new IllegalStateException("Configuration licence invalide : configurez EXACTEMENT une des "
                    + "deux methodes d'enrolement : app.licence.login + app.licence.mot-de-passe (PARTNER), "
                    + "ou app.licence.jeton (CLIENT) — jamais les deux, ni aucune des deux.");
        }

        return new EnregistrerRequest(
                loginEtMotDePassePresents ? login : null,
                loginEtMotDePassePresents ? motDePasse : null,
                jetonPresent ? jeton : null,
                serverRoleProperties.getRole(),
                identifiantInstallationStable,
                adresseDeclaree());
    }

    /**
     * Revue finale, I1 : {@code app.licence.adresse-publique}, si renseignee, est prioritaire sur
     * l'auto-detection — l'IP nue de {@link InetAddress#getLocalHost()} (sans schema ni port, souvent
     * la mauvaise interface ou le loopback) est relayee aux clients comme {@code
     * adressePartenaireResolue} puis utilisee comme URL de base {@code RestClient}, ce qui exige une
     * URL complete ({@code http(s)://hote:port}).
     */
    private String adresseDeclaree() {
        if (!isBlank(adressePublique)) {
            return adressePublique;
        }
        try {
            return InetAddress.getLocalHost().getHostAddress();
        } catch (UnknownHostException e) {
            log.warn("Licence : adresse locale non resolue automatiquement, adresseDeclaree laissee vide : {}",
                    e.toString());
            return null;
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}

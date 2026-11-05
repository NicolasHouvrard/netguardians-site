package com.logicielpourtous.site.features.licence;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Validation de bout en bout du module {@code licence} sur TROIS processus Spring Boot distincts
 * (MP, PARTENAIRE, CLIENT — Task 7), sans aucun mock : HTTP reel entre les trois instances,
 * verification par SQL direct sur leurs TROIS bases. Meme patron que {@code
 * features.sync.EndToEndTwoInstancesTest} (plan precedent, Task 5), etendu a une 3e instance et aux
 * endpoints specifiques au module licence.
 *
 * <p><b>Pourquoi {@code @Disabled} par defaut</b> (memes raisons que {@code
 * EndToEndTwoInstancesTest}, plus une 3e instance et plus d'etapes manuelles) :
 * <ol>
 *   <li>Il PILOTE trois instances deja lancees a la main (trois JVM, trois ports, trois bases
 *       PostgreSQL NEUVES — {@code site_db_licence_mp/partenaire/client}, jamais {@code
 *       site_db}/{@code site_db_secondaire} deja charges par la validation sync) : un seul contexte
 *       Spring de test ne peut pas jouer a la fois MASTER (sans {@code app.licence.master-url}),
 *       PARTNER (scheduler de decouverte actif) et CLIENT (scheduler d'enrolement actif), ni avoir
 *       trois datasources distinctes pour la meme application.</li>
 *   <li>Il depend d'un utilisateur {@code ROLE_ADMIN} connu dans CHACUNE des trois bases (aucun
 *       signup en libre-service dans ce depot — {@code DataInitializer} ne seme que les {@code
 *       Role}s, pas d'utilisateur) : a creer une fois par SQL direct avant tout, voir le rapport de
 *       cette tache pour la procedure exacte (tenant + user + mapping de role, hash bcrypt).</li>
 *   <li>Le flux CLIENT exige un jeton d'installation GENERE DYNAMIQUEMENT par le PARTENAIRE
 *       (ecran {@code /intra/licence/serveurs}) : le processus CLIENT ne peut etre demarre pour la
 *       PREMIERE fois qu'APRES ce jeton obtenu (lu via {@code app.licence.jeton}, une {@code
 *       @Value} figee a la construction du bean — jamais relue dynamiquement). Ce test ne peut donc
 *       pas, lui non plus, demarrer ce processus a la bonne etape : exactement la meme limite que
 *       {@code EndToEndTwoInstancesTest} pour son scenario 4 (coupure/retablissement de
 *       connectivite), ici appliquee a l'enrolement CLIENT lui-meme plutot qu'a un conflit.</li>
 *   <li>Idem pour la bascule {@code app.sync.master-url}/{@code app.sync.api-key} du CLIENT, une
 *       fois sa cle API sync obtenue automatiquement et stockee en base ({@code
 *       EtatLicenceLocale.cleApiSync}) : "pas de redemarrage programmatique" est une contrainte
 *       GLOBALE du plan, que ce module respecte lui-meme (voir {@code LicenceBootstrap}) — ce test
 *       ne la contourne donc pas non plus pour piloter le processus CLIENT a sa place.</li>
 * </ol>
 *
 * <p><b>Pour l'executer</b> (procedure complete et preuve d'execution :
 * {@code .superpowers/sdd/2026-10-28-interface-admin-licence-serveurs/task-7-report.md}) :
 * <ol>
 *   <li>Creer les 3 bases neuves ({@code createdb site_db_licence_mp site_db_licence_partenaire
 *       site_db_licence_client}, ou equivalent {@code CREATE DATABASE}) ;</li>
 *   <li>Demarrer le MP :
 *       {@code java -jar target/site-*.jar --spring.profiles.active=local,licence-mp} (port 8091) ;
 *       bootstrap-er un utilisateur {@code ROLE_ADMIN} dans {@code site_db_licence_mp} par SQL direct
 *       (voir rapport — {@code DataInitializer} seme les {@code Role}s au 1er demarrage, donc {@code
 *       ROLE_ADMIN} existe deja a cet instant) ;</li>
 *   <li>Choisir un login/mot de passe pour le futur CompteLicence du partenaire, les exporter comme
 *       {@code LICENCE_LOGIN}/{@code LICENCE_MOT_DE_PASSE}, puis demarrer le PARTENAIRE :
 *       {@code LICENCE_LOGIN=... LICENCE_MOT_DE_PASSE=... java -jar target/site-*.jar
 *       --spring.profiles.active=local,licence-partenaire} (port 8092) ; bootstrap-er de meme un
 *       utilisateur {@code ROLE_ADMIN} dans {@code site_db_licence_partenaire} ;</li>
 *   <li>Retirer {@code @Disabled} localement et lancer {@code
 *       partenaireSAutoEnroleEtGenereUnJetonPourUnClient} avec {@code -De2e.licence.login=... -De2e.licence.password=...}
 *       (MEMES valeurs que {@code LICENCE_LOGIN}/{@code LICENCE_MOT_DE_PASSE}) et les identifiants
 *       admin : cree le CompteLicence sur le MP, verifie l'auto-enrolement du PARTENAIRE, cree un
 *       {@code Customer} sur le PARTENAIRE et lui genere un jeton — la sortie console donne la
 *       commande exacte pour l'etape suivante ;</li>
 *   <li>Demarrer le CLIENT avec le jeton obtenu : {@code LICENCE_JETON=<jeton> java -jar
 *       target/site-*.jar --spring.profiles.active=local,licence-client} (port 8093) ; bootstrap-er
 *       de meme un utilisateur {@code ROLE_ADMIN} dans {@code site_db_licence_client} ; lancer
 *       {@code clientSAutoEnroleEtTermineSonInscriptionSync} ;</li>
 *   <li>Redemarrer le CLIENT (2e lancement) avec les {@code app.sync.master-url}/{@code
 *       app.sync.api-key} donnes par la sortie console du test precedent, puis lancer {@code
 *       uneRequestCreeeCoteClientApparaitCotePartenaireAvecLeBonCustomerId} ;</li>
 *   <li>Lancer {@code revocationEstPropageeAuPartenaire}.</li>
 * </ol>
 */
@Disabled("Necessite 3 instances Spring Boot reelles lancees a la main sur 3 bases PostgreSQL NEUVES "
        + "(profils local,licence-mp / local,licence-partenaire / local,licence-client), un "
        + "utilisateur ROLE_ADMIN bootstrape par SQL direct dans chacune, et au moins 2 redemarrages "
        + "manuels du processus CLIENT (jeton puis cle API sync) qu'aucun test ne peut piloter a sa "
        + "place (app.licence.jeton/app.sync.* sont lues une fois a la construction du bean, et 'pas "
        + "de redemarrage programmatique' est une contrainte globale du plan) : voir la Javadoc de "
        + "classe et task-7-report.md.")
class EndToEndLicenceTest {

    private static final String MP_URL = System.getProperty("e2e.mp-url", "http://localhost:8091");
    private static final String PARTENAIRE_URL = System.getProperty("e2e.partenaire-url", "http://localhost:8092");
    private static final String CLIENT_URL = System.getProperty("e2e.client-url", "http://localhost:8093");

    private static final String MP_JDBC = System.getProperty("e2e.mp-jdbc",
            "jdbc:postgresql://localhost:5432/site_db_licence_mp");
    private static final String PARTENAIRE_JDBC = System.getProperty("e2e.partenaire-jdbc",
            "jdbc:postgresql://localhost:5432/site_db_licence_partenaire");
    private static final String CLIENT_JDBC = System.getProperty("e2e.client-jdbc",
            "jdbc:postgresql://localhost:5432/site_db_licence_client");
    private static final String DB_USER = System.getProperty("e2e.db.user", "nicolas");
    private static final String DB_PASSWORD = System.getProperty("e2e.db.password", "");

    /** Identifiants de l'utilisateur ROLE_ADMIN bootstrape par SQL direct dans CHACUNE des 3 bases
     * (meme email/mot de passe reutilise sur les 3, par simple commodite de ce scenario local). */
    private static final String APP_USER = System.getProperty("e2e.user", "licence-e2e@test.local");
    private static final String APP_PASSWORD = System.getProperty("e2e.password", "");

    /** Identifiants EXACTS du CompteLicence a creer sur le MP — DOIVENT correspondre a {@code
     * LICENCE_LOGIN}/{@code LICENCE_MOT_DE_PASSE} deja exportes avant le demarrage du PARTENAIRE
     * (voir la Javadoc de classe) : ce test ne choisit jamais ces valeurs lui-meme, il ne ferait que
     * creer un compte que le PARTENAIRE deja lance ne pourrait jamais utiliser. */
    private static final String LICENCE_LOGIN = System.getProperty("e2e.licence.login", "licence-e2e-partenaire");
    private static final String LICENCE_PASSWORD = System.getProperty("e2e.licence.password", "");

    /** Plusieurs cycles des schedulers concernes ({@code app.licence.poll-interval-ms=2000} dans les
     * 2 profils secondaires). */
    private static final Duration DELAI_MAX = Duration.ofSeconds(30);

    private final ObjectMapper json = new ObjectMapper();

    // ── Scenario (Steps 1 a 2 du brief) ─────────────────────────────────────────

    @Test
    void partenaireSAutoEnroleEtGenereUnJetonPourUnClient() throws Exception {
        HttpClient mpAdmin = sessionClient(MP_URL);

        // ── Step 1/2 : creation manuelle d'un CompteLicence sur le MP ──
        String successCreate = postForm(mpAdmin, MP_URL, "/intra/licence/comptes", Map.of(
                "login", LICENCE_LOGIN,
                "motDePasse", LICENCE_PASSWORD,
                "typeAbonnement", "ILLIMITE",
                "frequence", "MENSUEL",
                "nombreServeursAutorises", "10"));
        assertThat(successCreate).as("creation du CompteLicence").isNotEqualTo("ECHEC");

        Long compteId = Long.parseLong(scalar(MP_JDBC,
                "select id from comptes_licence where login = ?", LICENCE_LOGIN));

        // ── Step 2 : le PARTENAIRE (deja lance, LICENCE_LOGIN/LICENCE_MOT_DE_PASSE configures en
        // environnement) s'auto-enrole au cycle suivant de son LicenceBootstrap ──
        String statutPartenaire = attendre(() -> scalarOrNull(MP_JDBC,
                "select statut from licence_serveurs where compte_id = ? and role = 'PARTNER'", compteId));
        assertThat(statutPartenaire).as("LicenceServeur PARTNER cote MP").isEqualTo("ACTIF");

        String cleLicencePartenaire = attendre(() -> scalarOrNull(PARTENAIRE_JDBC,
                "select cle_api_licence from etat_licence_locale limit 1", null));
        assertThat(cleLicencePartenaire).as("EtatLicenceLocale.cleApiLicence cote PARTENAIRE").isNotBlank();
        assertThat(scalar(PARTENAIRE_JDBC, "select enregistrement_termine from etat_licence_locale limit 1", null))
                .isEqualTo("true");

        // ── Step 3 : creation d'un Customer sur le PARTENAIRE, puis generation d'un jeton pour lui ──
        HttpClient partenaireAdmin = sessionClient(PARTENAIRE_URL);
        String nomClient = "Client E2E " + UUID.randomUUID();
        postForm(partenaireAdmin, PARTENAIRE_URL, "/intra/customers", Map.of(
                "type", "client",
                "companyName", nomClient));
        Long customerId = Long.parseLong(scalar(PARTENAIRE_JDBC,
                "select id from customers where company_name = ?", nomClient));

        postForm(partenaireAdmin, PARTENAIRE_URL, "/intra/licence/serveurs/jetons", Map.of(
                "customerId", String.valueOf(customerId)));

        String jeton = scalar(MP_JDBC,
                "select code from jetons_client where customer_id = ? order by id desc limit 1", customerId);
        assertThat(jeton).isNotBlank();
        // Pas de parsing strict d'Instant ici (le format texte exact renvoye par le driver JDBC pour
        // une colonne timestamp n'est pas garanti ISO-8601) : seule l'absence d'expiration immediate
        // compte pour ce scenario, une lecture non-vide suffit a le montrer.
        assertThat(scalar(MP_JDBC, "select expire_a from jetons_client where code = ?", jeton)).isNotBlank();

        System.out.println("Licence E2E : jeton pour le Customer " + customerId + " (" + nomClient + ") = " + jeton);
        System.out.println("Licence E2E : demarrer le CLIENT avec : LICENCE_JETON=" + jeton
                + " java -jar target/site-*.jar --spring.profiles.active=local,licence-client");
        System.out.println("Licence E2E : puis lancer clientSAutoEnroleEtTermineSonInscriptionSync avec "
                + "-De2e.customer-id=" + customerId);
    }

    // ── Scenario (Step 3 du brief, suite — a lancer APRES avoir demarre le CLIENT avec le jeton
    // obtenu ci-dessus) ────────────────────────────────────────────────────────

    @Test
    void clientSAutoEnroleEtTermineSonInscriptionSync() throws InterruptedException {
        // ── Le PARTENAIRE (LicenceDecouverteScheduler) materialise localement le SecondaryServer du
        // client decouvert, et pousse en retour son code local d'enrolement sync ──
        Long customerId = Long.parseLong(System.getProperty("e2e.customer-id"));
        String statutSecondaryServer = attendre(() -> scalarOrNull(PARTENAIRE_JDBC,
                "select statut from secondary_servers where customer_id = ? and role = 'CLIENT'", customerId));
        assertThat(statutSecondaryServer).as("SecondaryServer CLIENT cote PARTENAIRE").isEqualTo("ACTIF");

        // ── Le CLIENT recoit l'adresse du PARTENAIRE + un code local, termine son enrolement licence,
        // ET son inscription automatique au moteur de synchronisation (POST /api/sync/enroll) ──
        // Aucune correction manuelle d'adresse sur l'ecran MP n'est necessaire : le profil
        // licence-partenaire fixe app.licence.adresse-publique=http://localhost:8092 (revue finale, I1),
        // declaree telle quelle au MP a la place de l'IP nue auto-detectee.
        String adresseResolue = attendre(() -> scalarOrNull(CLIENT_JDBC,
                "select adresse_partenaire_resolue from etat_licence_locale limit 1", null));
        assertThat(adresseResolue).as("adresse du PARTENAIRE resolue cote CLIENT").isEqualTo(PARTENAIRE_URL);

        String cleApiSync = attendre(() -> scalarOrNull(CLIENT_JDBC,
                "select cle_api_sync from etat_licence_locale limit 1", null));
        assertThat(cleApiSync).as("EtatLicenceLocale.cleApiSync cote CLIENT (inscription sync automatique)")
                .isNotBlank();

        System.out.println("Licence E2E : redemarrer le CLIENT avec : java -jar target/site-*.jar "
                + "--spring.profiles.active=local,licence-client --app.sync.master-url=" + PARTENAIRE_URL
                + " --app.sync.api-key=" + cleApiSync);
        System.out.println("Licence E2E : puis lancer uneRequestCreeeCoteClientApparaitCotePartenaireAvecLeBonCustomerId");
    }

    // ── Scenario (Step 3 du brief, fin — a lancer APRES le redemarrage manuel ci-dessus, reutilise
    // EXACTEMENT le scenario deja prouve par EndToEndTwoInstancesTest, desormais sans AUCUNE etape
    // manuelle de code : seul le redemarrage reste manuel) ──────────────────────

    @Test
    void uneRequestCreeeCoteClientApparaitCotePartenaireAvecLeBonCustomerId() throws Exception {
        Long customerId = Long.parseLong(System.getProperty("e2e.customer-id"));
        String jetonAuth = login(CLIENT_URL);
        String titre = "Licence E2E auto " + UUID.randomUUID();
        JsonNode creee = callJson(CLIENT_URL, "POST", "/api/intra/tickets", jetonAuth,
                Map.of("title", titre, "description", "EndToEndLicenceTest", "priority", "HIGH", "type", "SUPPORT"));
        long idClient = creee.get("id").asLong();
        UUID syncId = UUID.fromString(scalar(CLIENT_JDBC, "select sync_id from requests where id = ?", idClient));

        attendre(() -> scalarOrNull(PARTENAIRE_JDBC, "select id from requests where sync_id = ?", syncId));
        assertThat(scalar(PARTENAIRE_JDBC, "select title from requests where sync_id = ?", syncId)).isEqualTo(titre);
        assertThat(scalar(PARTENAIRE_JDBC, "select customer_id from requests where sync_id = ?", syncId))
                .as("rattachee automatiquement au bon Customer, via le SecondaryServer materialise par "
                        + "la decouverte licence — plus AUCUNE etape manuelle de code dans tout ce flux")
                .isEqualTo(String.valueOf(customerId));
    }

    // ── Scenario (Step 4 du brief) ──────────────────────────────────────────────

    @Test
    void revocationEstPropageeAuPartenaire() throws Exception {
        Long customerId = Long.parseLong(System.getProperty("e2e.customer-id"));
        Long compteId = Long.parseLong(scalar(MP_JDBC,
                "select id from comptes_licence where login = ?", LICENCE_LOGIN));
        // 2 conditions fixes (compteId ET customerId) : scalar()/scalarOrNull() n'acceptent qu'UN
        // seul parametre lie — customerId (Long, jamais une saisie utilisateur, lu depuis la
        // System property e2e.customer-id) est donc inlined directement dans le SQL plutot que
        // d'etendre ces outils pour un unique appel de ce genre.
        Long serveurId = Long.parseLong(scalar(MP_JDBC,
                "select id from licence_serveurs where compte_id = ? and role = 'CLIENT' and customer_id = "
                        + customerId,
                compteId));

        HttpClient mpAdmin = sessionClient(MP_URL);
        postForm(mpAdmin, MP_URL, "/intra/licence/comptes/" + compteId + "/serveurs/" + serveurId + "/revoquer",
                Map.of());

        assertThat(scalar(MP_JDBC, "select statut from licence_serveurs where id = ?", serveurId))
                .isEqualTo("REVOQUE");

        String statutPropage = attendre(() -> {
            String s = scalarOrNull(PARTENAIRE_JDBC,
                    "select statut from secondary_servers where customer_id = ? and role = 'CLIENT'", customerId);
            return "REVOQUE".equals(s) ? s : null;
        });
        assertThat(statutPropage).as("SecondaryServer local revoque au cycle suivant du PARTENAIRE")
                .isEqualTo("REVOQUE");
    }

    // ── Outils HTTP ──────────────────────────────────────────────────────────

    /** Authentification JWT stateless (API Angular), utilisee UNIQUEMENT pour {@code
     * /api/intra/tickets} (comme {@code EndToEndTwoInstancesTest}) — PAS pour les ecrans admin
     * {@code /intra/licence/**}, servis par la chaine de securite "session web" (formLogin), voir
     * {@link #sessionClient}. */
    private String login(String baseUrl) throws Exception {
        return callJson(baseUrl, "POST", "/api/auth/login", null,
                Map.of("email", APP_USER, "password", APP_PASSWORD)).get("token").asText();
    }

    private JsonNode callJson(String baseUrl, String method, String path, String jeton, Object corps) throws Exception {
        HttpClient http = HttpClient.newHttpClient();
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(json.writeValueAsString(corps)));
        if (jeton != null) {
            req.header("Authorization", "Bearer " + jeton);
        }
        HttpResponse<String> rep = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
        return rep.body() == null || rep.body().isBlank() ? json.createObjectNode() : json.readTree(rep.body());
    }

    /**
     * Session Spring Security classique (formLogin, cookie {@code JSESSIONID}) requise par les
     * ecrans {@code /intra/licence/**} : CSRF est desactive sur CETTE chaine de securite aussi (voir
     * {@code SecurityConfig#filterChain}), donc un simple POST {@code username}/{@code password} sur
     * {@code /login} suffit, sans jeton CSRF a extraire au prealable.
     *
     * <p><b>Fix round 1 (task review)</b> : {@code CaptchaFilter} siege sur CETTE MEME chaine, AVANT
     * {@code UsernamePasswordAuthenticationFilter}, et {@code application.properties} active le
     * captcha Turnstile PAR DEFAUT ({@code captcha.enabled=${CAPTCHA_ENABLED:true}}) — un {@link
     * HttpClient} nu (pas de navigateur pour resoudre un challenge Turnstile reel) ne peut JAMAIS
     * passer ce POST tant que {@code captcha.enabled=false} n'est pas configure. C'est pourquoi les 3
     * profils de ce scenario ({@code application-licence-mp/partenaire/client.properties})
     * desactivent explicitement {@code captcha.enabled} — UNIQUEMENT pour cette validation locale
     * scriptee, JAMAIS a reproduire dans un profil de deploiement reel.
     */
    private HttpClient sessionClient(String baseUrl) throws Exception {
        HttpClient http = HttpClient.newBuilder().cookieHandler(new CookieManager())
                .followRedirects(HttpClient.Redirect.NORMAL).build();
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/login"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "username=" + encode(APP_USER) + "&password=" + encode(APP_PASSWORD)))
                .build();
        HttpResponse<String> rep = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (rep.uri().getPath().equals("/login")) {
            throw new IllegalStateException("Connexion admin echouee sur " + baseUrl
                    + " : verifier (1) que captcha.enabled=false est bien configure sur CETTE instance "
                    + "(sinon CaptchaFilter rejette systematiquement ce POST, un HttpClient nu ne "
                    + "pouvant jamais resoudre un challenge Turnstile reel), (2) qu'un utilisateur "
                    + "ROLE_ADMIN " + APP_USER + " existe bien dans cette base (voir le rapport de "
                    + "cette tache pour le bootstrap SQL).");
        }
        return http;
    }

    /** POST d'un formulaire HTML classique (pas JSON — les controleurs {@code /intra/licence/**}
     * consomment des {@code @RequestParam}), sur une session deja authentifiee. Retourne {@code
     * "ECHEC"} sur un statut HTTP d'erreur serveur, sinon {@code "OK"} (succes ou redirection avec
     * message flash d'erreur metier — verifie par SQL par l'appelant, pas par le statut HTTP ici). */
    private String postForm(HttpClient http, String baseUrl, String path, Map<String, String> champs) throws Exception {
        StringBuilder corps = new StringBuilder();
        champs.forEach((k, v) -> {
            if (corps.length() > 0) corps.append('&');
            corps.append(encode(k)).append('=').append(encode(v));
        });
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(corps.toString()))
                .build();
        HttpResponse<String> rep = http.send(req, HttpResponse.BodyHandlers.ofString());
        return rep.statusCode() >= 500 ? "ECHEC" : "OK";
    }

    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    // ── Outils SQL ───────────────────────────────────────────────────────────

    private static String attendre(Supplier<String> sonde) throws InterruptedException {
        Instant limite = Instant.now().plus(DELAI_MAX);
        while (Instant.now().isBefore(limite)) {
            String valeur = sonde.get();
            if (valeur != null) {
                return valeur;
            }
            Thread.sleep(500);
        }
        throw new AssertionError("Synchronisation/decouverte non observee apres " + DELAI_MAX);
    }

    private static String scalar(String jdbc, String sql, Object param) {
        String valeur = scalarOrNull(jdbc, sql, param);
        assertThat(valeur).as(sql).isNotNull();
        return valeur;
    }

    private static String scalarOrNull(String jdbc, String sql, Object param) {
        try (Connection c = DriverManager.getConnection(jdbc, DB_USER, DB_PASSWORD);
             PreparedStatement ps = c.prepareStatement(sql)) {
            if (param != null) {
                ps.setObject(1, param);
            }
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}

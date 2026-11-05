package com.logicielpourtous.site.features.licence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.logicielpourtous.site.features.commun.ServerRoleProperties;

/**
 * {@link LicenceBootstrap} : auto-enrolement cote SECONDAIRE au demarrage. Test unitaire pur (pas
 * de contexte Spring) : le client HTTP vers le maitre est mocke ({@link MockRestServiceServer},
 * lie au {@link RestClient.Builder} passe au constructeur — une requete HTTP non attendue fait
 * immediatement echouer le test), et {@link EtatLicenceLocaleRepository} est un mock Mockito
 * dont les 2 methodes utilisees ({@code findAll}/{@code save}) sont redirigees vers un etat en
 * memoire, pour simuler "la base survit entre deux demarrages" sans base de donnees reelle — ce
 * test porte sur la logique d'orchestration de {@link LicenceBootstrap}, pas sur le mapping JPA.
 */
class LicenceBootstrapTest {

    private static final String MASTER_URL = "http://maitre-test";

    @Test
    void bootstrap_withLoginAndPassword_registersOnceAndStoresApiKey() {
        EtatLicenceLocaleRepository repo = repositoryEnMemoire();
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

        server.expect(requestTo(MASTER_URL + "/api/licence/enregistrer"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(
                        "{\"codeEnrolement\":\"code-123\",\"codeEnrolementExpireA\":\"2099-01-01T00:00:00Z\"}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo(MASTER_URL + "/api/licence/cle"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("{\"codeEnrolement\":\"code-123\"}"))
                .andRespond(withSuccess("{\"cleApi\":\"cle-api-secrete\"}", MediaType.APPLICATION_JSON));

        LicenceBootstrap bootstrap = new LicenceBootstrap(builder, repo, role("PARTNER"),
                MASTER_URL, "login-test", "mdp-test", "", "");

        bootstrap.lancer();

        server.verify();
        EtatLicenceLocale etat = repo.findAll().get(0);
        assertThat(etat.getCleApiLicence()).isEqualTo("cle-api-secrete");
        assertThat(etat.isEnregistrementTermine()).isTrue();

        // 2e appel : enregistrementTermine=true -> AUCUN nouvel appel HTTP (le mock n'attend plus
        // rien ; une requete inattendue ferait echouer immediatement, avant meme ce verify()).
        bootstrap.lancer();
        server.verify();
    }

    @Test
    void bootstrap_generatesIdentifiantInstallationStableOnceAndKeepsItAcrossRestarts() {
        EtatLicenceLocaleRepository repoPartage = repositoryEnMemoire();

        // Instance 1 = 1er demarrage du processus : cree l'identifiant et termine l'enrolement.
        RestClient.Builder builder1 = RestClient.builder();
        MockRestServiceServer server1 = MockRestServiceServer.bindTo(builder1).build();
        server1.expect(requestTo(MASTER_URL + "/api/licence/enregistrer"))
                .andRespond(withSuccess(
                        "{\"codeEnrolement\":\"code-1\",\"codeEnrolementExpireA\":\"2099-01-01T00:00:00Z\"}",
                        MediaType.APPLICATION_JSON));
        server1.expect(requestTo(MASTER_URL + "/api/licence/cle"))
                .andRespond(withSuccess("{\"cleApi\":\"cle-1\"}", MediaType.APPLICATION_JSON));
        new LicenceBootstrap(builder1, repoPartage, role("PARTNER"), MASTER_URL, "login-test", "mdp-test", "", "")
                .lancer();
        server1.verify();
        String identifiantApresPremierDemarrage = repoPartage.findAll().get(0).getIdentifiantInstallationStable();
        assertThat(identifiantApresPremierDemarrage).isNotBlank();

        // Instance 2 = 2e demarrage (meme "base" partagee, nouveau processus) : enrolement deja
        // termine -> AUCUN appel HTTP, et l'identifiant retrouve est INCHANGE.
        RestClient.Builder builder2 = RestClient.builder();
        MockRestServiceServer server2 = MockRestServiceServer.bindTo(builder2).build();
        new LicenceBootstrap(builder2, repoPartage, role("PARTNER"), MASTER_URL, "login-test", "mdp-test", "", "")
                .lancer();
        server2.verify();

        assertThat(repoPartage.findAll()).hasSize(1);
        assertThat(repoPartage.findAll().get(0).getIdentifiantInstallationStable())
                .isEqualTo(identifiantApresPremierDemarrage);
    }

    @Test
    void bootstrap_bothLoginAndJetonConfigured_logsClearErrorWithoutCallingHttpOrCrashing() {
        EtatLicenceLocaleRepository repo = repositoryEnMemoire();
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        // Aucune expectation enregistree : la validation doit echouer AVANT tout appel HTTP.

        LicenceBootstrap bootstrap = new LicenceBootstrap(builder, repo, role("PARTNER"),
                MASTER_URL, "login-test", "mdp-test", "jeton-test", "");

        // lancer() ne doit JAMAIS laisser une IllegalStateException remonter (meme patron non-fatal
        // que les autres echecs, voir LicenceBootstrap#lancer) : elle est capturee et loggue en ERROR.
        assertThatCode(bootstrap::lancer).doesNotThrowAnyException();
        server.verify();
        EtatLicenceLocale etat = repo.findAll().get(0);
        assertThat(etat.isEnregistrementTermine()).isFalse();
        assertThat(etat.getCleApiLicence()).isNull();

        // Le message precis qui finit dans le log ERROR de lancer() (via e.toString()) : verifie
        // directement a la source plutot que de capturer les logs.
        assertThatThrownBy(() -> bootstrap.construireRequeteEnregistrer("id-test"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("EXACTEMENT une des deux methodes d'enrolement");
    }

    @Test
    void bootstrap_neitherLoginNorJetonConfigured_logsClearErrorWithoutCallingHttpOrCrashing() {
        EtatLicenceLocaleRepository repo = repositoryEnMemoire();
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        // Aucune expectation enregistree : la validation doit echouer AVANT tout appel HTTP.

        LicenceBootstrap bootstrap = new LicenceBootstrap(builder, repo, role("PARTNER"),
                MASTER_URL, "", "", "", "");

        assertThatCode(bootstrap::lancer).doesNotThrowAnyException();
        server.verify();
        EtatLicenceLocale etat = repo.findAll().get(0);
        assertThat(etat.isEnregistrementTermine()).isFalse();
        assertThat(etat.getCleApiLicence()).isNull();

        assertThatThrownBy(() -> bootstrap.construireRequeteEnregistrer("id-test"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("EXACTEMENT une des deux methodes d'enrolement");
    }

    // Task 6 : un CLIENT dont le PARTENAIRE de son compte n'a pas encore ete decouvert (donc pas
    // encore pousse d'adresse/code local via POST /api/licence/code-partenaire) ne doit ni echouer,
    // ni se marquer "termine" prematurement — il retente le MEME sondage /enregistrer au cycle
    // suivant, jusqu'a ce que ces 2 champs soient enfin presents dans la reponse.
    @Test
    void bootstrap_clientWithoutPartnerAddressYet_retriesInsteadOfFinishingOrFailing() {
        EtatLicenceLocaleRepository repo = repositoryEnMemoire();
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

        // MockRestServiceServer exige TOUTES les expectations enregistrees AVANT la 1ere requete
        // reelle ("Cannot add more expectations after actual requests are made") : les 2 cycles sont
        // donc enregistres ici d'un coup, consommes dans l'ordre par les 2 appels a lancer() ci-dessous.
        //
        // 1er sondage : le maitre a bien cree le LicenceServeur CLIENT (code d'enrolement valide),
        // mais son PARTENAIRE n'a pas encore ete decouvert -> ni adresse ni code local dans la reponse.
        // Revue finale, C1 : AUCUN /api/licence/cle a ce stade — l'echange poserait cleApiHash cote
        // MP, et tout /enregistrer suivant serait alors refuse 409 (estReutilisationLegitimePourCeJeton
        // exige cleApiHash == null), bloquant ce CLIENT pour toujours.
        server.expect(requestTo(MASTER_URL + "/api/licence/enregistrer"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(
                        "{\"codeEnrolement\":\"code-1\",\"codeEnrolementExpireA\":\"2099-01-01T00:00:00Z\"}",
                        MediaType.APPLICATION_JSON));
        // 2e sondage (cycle suivant) : le PARTENAIRE a maintenant ete decouvert et a pousse son code
        // local -> la reponse porte les 2 champs. SEULEMENT MAINTENANT /api/licence/cle (avec le code
        // de CE sondage), puis (fix round 1) POST .../api/sync/enroll sur l'ADRESSE DU PARTENAIRE (pas
        // MASTER_URL) avec codeLocal comme enrollmentCode.
        server.expect(requestTo(MASTER_URL + "/api/licence/enregistrer"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(
                        "{\"codeEnrolement\":\"code-2\",\"codeEnrolementExpireA\":\"2099-01-01T00:00:00Z\","
                                + "\"adressePartenaireResolue\":\"http://partenaire-test\",\"codeLocal\":\"code-local-abc\"}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo(MASTER_URL + "/api/licence/cle"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("{\"codeEnrolement\":\"code-2\"}"))
                .andRespond(withSuccess("{\"cleApi\":\"cle-client-1\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://partenaire-test/api/sync/enroll"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("{\"enrollmentCode\":\"code-local-abc\"}"))
                .andRespond(withSuccess("{\"apiKey\":\"cle-sync-abc\"}", MediaType.APPLICATION_JSON));

        LicenceBootstrap bootstrap = new LicenceBootstrap(builder, repo, role("CLIENT"),
                MASTER_URL, "", "", "jeton-test", "");

        bootstrap.lancer();

        EtatLicenceLocale etat = repo.findAll().get(0);
        assertThat(etat.isEnregistrementTermine()).isFalse();
        assertThat(etat.getCleApiLicence()).isNull(); // C1 : /cle differe tant que pas pret
        assertThat(etat.getAdressePartenaireResolue()).isNull();
        assertThat(etat.getCodeLocalPartenaire()).isNull();
        assertThat(etat.getCleApiSync()).isNull();

        bootstrap.lancer();

        server.verify(); // les 4 requetes attendues (enregistrer, enregistrer, cle, sync/enroll) ont eu lieu, rien de plus.
        EtatLicenceLocale etatFinal = repo.findAll().get(0);
        assertThat(etatFinal.isEnregistrementTermine()).isTrue();
        assertThat(etatFinal.getCleApiLicence()).isEqualTo("cle-client-1");
        assertThat(etatFinal.getAdressePartenaireResolue()).isEqualTo("http://partenaire-test");
        assertThat(etatFinal.getCodeLocalPartenaire()).isEqualTo("code-local-abc");
        assertThat(etatFinal.getCleApiSync()).isEqualTo("cle-sync-abc");

        // 3e appel : enregistrementTermine=true -> plus aucun appel HTTP (ni /enregistrer, ni surtout
        // un 2e /api/sync/enroll qui rejouerait un codeLocal a usage unique deja consomme).
        bootstrap.lancer();
        server.verify();
    }

    // Revue finale, C1 : sequence REALISTE complete — plusieurs sondages "pas encore pret" d'affilee
    // (le PARTENAIRE peut decouvrir ce CLIENT bien plus tard), AUCUN /api/licence/cle pendant toute
    // l'attente (sinon cleApiHash pose cote MP -> chaque /enregistrer suivant refuse 409 a vie), puis
    // /cle avec le code du sondage enfin "pret", et toute la chaine (inscription sync incluse) aboutit.
    // MockRestServiceServer echoue immediatement sur toute requete non attendue A CE RANG (ordre
    // strict par defaut) : un /cle premature au 1er ou 2e sondage ferait echouer ce test.
    @Test
    void bootstrap_clientNotReadyForSeveralPolls_defersCleUntilReadyThenCompletesWholeChain() {
        EtatLicenceLocaleRepository repo = repositoryEnMemoire();
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

        // (a) 1er sondage : pas pret (ni adresse ni code local).
        server.expect(requestTo(MASTER_URL + "/api/licence/enregistrer"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(
                        "{\"codeEnrolement\":\"code-1\",\"codeEnrolementExpireA\":\"2099-01-01T00:00:00Z\"}",
                        MediaType.APPLICATION_JSON));
        // (b) 2e sondage : toujours pas pret (adresse du partenaire connue, mais pas encore de code local).
        server.expect(requestTo(MASTER_URL + "/api/licence/enregistrer"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(
                        "{\"codeEnrolement\":\"code-2\",\"codeEnrolementExpireA\":\"2099-01-01T00:00:00Z\","
                                + "\"adressePartenaireResolue\":\"http://partenaire-test\",\"codeLocal\":null}",
                        MediaType.APPLICATION_JSON));
        // (c) 3e sondage : pret -> /cle avec le code de CE sondage, puis /api/sync/enroll.
        server.expect(requestTo(MASTER_URL + "/api/licence/enregistrer"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(
                        "{\"codeEnrolement\":\"code-3\",\"codeEnrolementExpireA\":\"2099-01-01T00:00:00Z\","
                                + "\"adressePartenaireResolue\":\"http://partenaire-test\",\"codeLocal\":\"code-local-xyz\"}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo(MASTER_URL + "/api/licence/cle"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("{\"codeEnrolement\":\"code-3\"}"))
                .andRespond(withSuccess("{\"cleApi\":\"cle-client-finale\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://partenaire-test/api/sync/enroll"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("{\"enrollmentCode\":\"code-local-xyz\"}"))
                .andRespond(withSuccess("{\"apiKey\":\"cle-sync-xyz\"}", MediaType.APPLICATION_JSON));

        LicenceBootstrap bootstrap = new LicenceBootstrap(builder, repo, role("CLIENT"),
                MASTER_URL, "", "", "jeton-test", "");

        bootstrap.lancer(); // (a)
        EtatLicenceLocale apres1 = repo.findAll().get(0);
        assertThat(apres1.getCleApiLicence()).as("aucun /cle au 1er sondage non pret").isNull();
        assertThat(apres1.isEnregistrementTermine()).isFalse();

        bootstrap.lancer(); // (b)
        EtatLicenceLocale apres2 = repo.findAll().get(0);
        assertThat(apres2.getCleApiLicence()).as("aucun /cle au 2e sondage non pret").isNull();
        assertThat(apres2.isEnregistrementTermine()).isFalse();
        assertThat(apres2.getCleApiSync()).isNull();

        bootstrap.lancer(); // (c)
        server.verify(); // exactement : enregistrer x3, cle x1 (au 3e), sync/enroll x1 — dans cet ordre.
        EtatLicenceLocale etatFinal = repo.findAll().get(0);
        assertThat(etatFinal.getCleApiLicence()).isEqualTo("cle-client-finale");
        assertThat(etatFinal.getAdressePartenaireResolue()).isEqualTo("http://partenaire-test");
        assertThat(etatFinal.getCodeLocalPartenaire()).isEqualTo("code-local-xyz");
        assertThat(etatFinal.getCleApiSync()).isEqualTo("cle-sync-xyz");
        assertThat(etatFinal.isEnregistrementTermine()).isTrue();

        bootstrap.lancer(); // termine -> plus aucun appel HTTP.
        server.verify();
    }

    // Fix round 1 : si la cle sync a deja ete obtenue (etat.getCleApiSync() != null) mais que
    // l'enrolement n'a pas encore ete marque termine (ex. redemarrage/crash entre les 2 ecritures),
    // un nouveau cycle ne doit JAMAIS rappeler /api/sync/enroll (codeLocal est a usage unique, un 2e
    // appel echouerait 401 cote partenaire) — seulement finaliser enregistrementTermine.
    @Test
    void bootstrap_clientSyncApiKeyAlreadyObtained_doesNotRecallSyncEnrollAndFinishes() {
        EtatLicenceLocaleRepository repo = repositoryEnMemoire();
        EtatLicenceLocale etatPrealable = new EtatLicenceLocale();
        etatPrealable.setIdentifiantInstallationStable(UUID.randomUUID().toString());
        etatPrealable.setCleApiLicence("cle-client-deja-connue");
        etatPrealable.setAdressePartenaireResolue("http://partenaire-test");
        etatPrealable.setCodeLocalPartenaire("code-local-deja-consomme");
        etatPrealable.setCleApiSync("cle-sync-deja-obtenue");
        etatPrealable.setEnregistrementTermine(false); // pas encore finalise (crash simule)
        repo.save(etatPrealable);

        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        // AUCUNE expectation sur /api/sync/enroll : le moindre appel ferait echouer ce test.
        server.expect(requestTo(MASTER_URL + "/api/licence/enregistrer"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(
                        "{\"codeEnrolement\":\"code-3\",\"codeEnrolementExpireA\":\"2099-01-01T00:00:00Z\","
                                + "\"adressePartenaireResolue\":\"http://partenaire-test\",\"codeLocal\":\"code-local-deja-consomme\"}",
                        MediaType.APPLICATION_JSON));
        // etat.getCleApiLicence() deja non-null -> AUCUN appel a /api/licence/cle non plus.

        LicenceBootstrap bootstrap = new LicenceBootstrap(builder, repo, role("CLIENT"),
                MASTER_URL, "", "", "jeton-test", "");

        bootstrap.lancer();

        server.verify();
        EtatLicenceLocale etatFinal = repo.findAll().get(0);
        assertThat(etatFinal.isEnregistrementTermine()).isTrue();
        assertThat(etatFinal.getCleApiSync()).isEqualTo("cle-sync-deja-obtenue"); // inchangee
    }

    // Revue finale, I1 : l'adresse auto-detectee (InetAddress.getLocalHost().getHostAddress()) est une
    // IP nue, sans schema ni port — inutilisable comme URL de base RestClient par les futurs clients
    // (/api/sync/enroll). app.licence.adresse-publique, si renseignee, la remplace telle quelle.
    @Test
    void bootstrap_adressePubliqueConfiguree_estDeclareeTelleQuelleAuLieuDeLAutoDetection() {
        EtatLicenceLocaleRepository repo = repositoryEnMemoire();
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

        server.expect(requestTo(MASTER_URL + "/api/licence/enregistrer"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("{\"adresseDeclaree\":\"http://partenaire.exemple:8092\"}"))
                .andRespond(withSuccess(
                        "{\"codeEnrolement\":\"code-a\",\"codeEnrolementExpireA\":\"2099-01-01T00:00:00Z\"}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo(MASTER_URL + "/api/licence/cle"))
                .andRespond(withSuccess("{\"cleApi\":\"cle-a\"}", MediaType.APPLICATION_JSON));

        LicenceBootstrap bootstrap = new LicenceBootstrap(builder, repo, role("PARTNER"),
                MASTER_URL, "login-test", "mdp-test", "", "http://partenaire.exemple:8092");

        bootstrap.lancer();

        server.verify();
        assertThat(repo.findAll().get(0).isEnregistrementTermine()).isTrue();
    }

    // Revue finale, I3 : lancer() est a la fois listener ApplicationReadyEvent ET @Scheduled (2 threads)
    // -> doit etre synchronized (meme patron que LicenceDecouverteScheduler#decouvrir).
    @Test
    void lancer_estSynchronized() throws NoSuchMethodException {
        assertThat(java.lang.reflect.Modifier.isSynchronized(
                LicenceBootstrap.class.getMethod("lancer").getModifiers())).isTrue();
    }

    @Test
    void bootstrap_masterRole_doesNothing() {
        EtatLicenceLocaleRepository repo = mock(EtatLicenceLocaleRepository.class);
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        // Aucune expectation enregistree : le moindre appel HTTP ferait echouer ce test.

        LicenceBootstrap bootstrap = new LicenceBootstrap(builder, repo, role("MASTER"),
                MASTER_URL, "login-test", "mdp-test", "", "");

        bootstrap.lancer();

        server.verify();
        verifyNoInteractions(repo);
    }

    private static ServerRoleProperties role(String role) {
        ServerRoleProperties properties = new ServerRoleProperties();
        properties.setRole(role);
        return properties;
    }

    /** Mock dont {@code findAll}/{@code save} sont rediriges vers un etat en memoire partage par
     * tous les appelants de l'instance retournee (simule la persistance reelle, sans base). */
    private static EtatLicenceLocaleRepository repositoryEnMemoire() {
        AtomicReference<EtatLicenceLocale> ligne = new AtomicReference<>();
        AtomicLong prochainId = new AtomicLong(1);
        EtatLicenceLocaleRepository repo = mock(EtatLicenceLocaleRepository.class);
        when(repo.findAll()).thenAnswer(inv -> {
            EtatLicenceLocale e = ligne.get();
            return e == null ? List.<EtatLicenceLocale>of() : List.of(e);
        });
        when(repo.save(any())).thenAnswer(inv -> {
            EtatLicenceLocale e = inv.getArgument(0);
            if (e.getId() == null) {
                e.setId(prochainId.getAndIncrement());
            }
            ligne.set(e);
            return e;
        });
        return repo;
    }
}

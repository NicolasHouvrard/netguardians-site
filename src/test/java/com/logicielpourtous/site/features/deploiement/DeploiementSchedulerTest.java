package com.logicielpourtous.site.features.deploiement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.logicielpourtous.site.features.commun.ServerRoleProperties;
import com.logicielpourtous.site.features.licence.LicenceServeur;
import com.logicielpourtous.site.features.licence.LicenceServeurRepository;

/**
 * {@link DeploiementScheduler} : detection d'une nouvelle version GitHub (cote MAITRE uniquement)
 * et reveil de l'Agent de chaque {@link LicenceServeur} {@code ACTIF}.
 *
 * <p>Test unitaire pur (pas de contexte Spring) : {@link LicenceServeurRepository}/{@link
 * OrdreDeploiementRepository}/{@link GitHubReleaseClient} sont des mocks Mockito, et le client HTTP
 * de reveil est mocke via {@link MockRestServiceServer} lie a un {@link RestClient.Builder} —
 * {@code .build()} est appele ICI (dans le fixture {@link #nouveauScheduler}), et le {@link
 * RestClient} DEJA CONSTRUIT est passe au constructeur package-prive de {@link
 * DeploiementScheduler} (PAS le constructeur public, qui imposerait sa propre {@code
 * ClientHttpRequestFactory} a delais courts et casserait l'interception du mock — voir la javadoc
 * de ce constructeur).
 *
 * <p>Toutes les adresses de test sont des LITTERAUX IP (jamais un nom d'hote symbolique comme
 * {@code "injoignable"}) : {@link DeploiementScheduler} resout maintenant {@code adresseDeclaree}
 * via {@code InetAddress.getByName} AVANT tout appel HTTP (protection SSRF, voir la javadoc de
 * {@code adresseAutorisee}) — un nom d'hote inventé y declencherait une VRAIE resolution DNS
 * (lente, non deterministe selon l'environnement), alors qu'un litteral IP est analyse directement,
 * sans aucune E/S reseau.
 */
class DeploiementSchedulerTest {

    private static final int AGENT_PORT = 9090;

    @Test
    void reveillerTousLesAgents_oneWakeFails_otherServersStillWokenUp() {
        LicenceServeur serveur1 = mock(LicenceServeur.class);
        when(serveur1.getAdresseDeclaree()).thenReturn("10.0.0.1");
        LicenceServeur serveur2 = mock(LicenceServeur.class);
        when(serveur2.getAdresseDeclaree()).thenReturn("10.0.0.2");

        LicenceServeurRepository licenceServeurRepository = mock(LicenceServeurRepository.class);
        when(licenceServeurRepository.findByStatut(LicenceServeur.LicenceServeurStatut.ACTIF))
                .thenReturn(List.of(serveur1, serveur2));
        OrdreDeploiementRepository ordreDeploiementRepository = mock(OrdreDeploiementRepository.class);

        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        // serveur1 : le reveil echoue (500) — ne doit JAMAIS empecher serveur2 d'etre contacte.
        server.expect(requestTo("http://10.0.0.1:" + AGENT_PORT + "/reveil"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withServerError());
        server.expect(requestTo("http://10.0.0.2:" + AGENT_PORT + "/reveil"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess());

        DeploiementScheduler scheduler = nouveauScheduler(
                licenceServeurRepository, ordreDeploiementRepository, mock(GitHubReleaseClient.class), builder);

        assertThatCode(() -> scheduler.reveillerTousLesAgents("v1.2.3")).doesNotThrowAnyException();

        server.verify(); // les 2 appels (echec puis succes) ont bien eu lieu, dans cet ordre.
    }

    // Revue de Task 4, finding Important + MEDIUM combines : une adresse MALICIEUSE (SSRF) pour UN
    // serveur du cycle ne doit, elle non plus, jamais empecher le reveil d'un AUTRE serveur valide —
    // meme garantie d'isolation que ci-dessus, mais exercant maintenant le chemin "rejetee avant tout
    // appel HTTP" (pas seulement "l'appel HTTP a echoue").
    @Test
    void reveillerTousLesAgents_oneServerHasSsrfAddress_otherServersStillWokenUpAndNoRequestSentForIt() {
        LicenceServeur serveurEnPanne = mock(LicenceServeur.class);
        when(serveurEnPanne.getAdresseDeclaree()).thenReturn("10.0.0.1");
        LicenceServeur serveurMalicieux = mock(LicenceServeur.class);
        when(serveurMalicieux.getAdresseDeclaree()).thenReturn("169.254.169.254"); // metadonnees cloud
        LicenceServeur serveurValide = mock(LicenceServeur.class);
        when(serveurValide.getAdresseDeclaree()).thenReturn("10.0.0.2");

        LicenceServeurRepository licenceServeurRepository = mock(LicenceServeurRepository.class);
        when(licenceServeurRepository.findByStatut(LicenceServeur.LicenceServeurStatut.ACTIF))
                .thenReturn(List.of(serveurEnPanne, serveurMalicieux, serveurValide));
        OrdreDeploiementRepository ordreDeploiementRepository = mock(OrdreDeploiementRepository.class);

        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://10.0.0.1:" + AGENT_PORT + "/reveil")).andRespond(withServerError());
        // Rien enregistre pour 169.254.169.254 : le moindre appel vers cette adresse ferait echouer
        // ce test (requete inattendue).
        server.expect(requestTo("http://10.0.0.2:" + AGENT_PORT + "/reveil")).andRespond(withSuccess());

        DeploiementScheduler scheduler = nouveauScheduler(
                licenceServeurRepository, ordreDeploiementRepository, mock(GitHubReleaseClient.class), builder);

        assertThatCode(() -> scheduler.reveillerTousLesAgents("v1.2.3")).doesNotThrowAnyException();

        server.verify(); // les 2 appels attendus ont eu lieu ; aucun 3e appel (vers l'adresse malicieuse).
    }

    @Test
    void reveiller_linkLocalAddress_isRejectedWithoutAnyHttpCall() {
        verifierAdresseRejetee("169.254.169.254"); // metadonnees cloud (link-local)
    }

    @Test
    void reveiller_loopbackAddress_isRejectedWithoutAnyHttpCall() {
        verifierAdresseRejetee("127.0.0.1");
    }

    @Test
    void reveiller_anyLocalAddress_isRejectedWithoutAnyHttpCall() {
        verifierAdresseRejetee("0.0.0.0");
    }

    @Test
    void reveiller_addressWithForbiddenCharacters_isRejectedWithoutAnyHttpCall() {
        verifierAdresseRejetee("evil.example@attacker.example");
    }

    @Test
    void reveiller_addressWithSlash_isRejectedWithoutAnyHttpCall() {
        verifierAdresseRejetee("10.0.0.1/../admin");
    }

    @Test
    void reveiller_addressWithEmbeddedPort_isRejectedWithoutAnyHttpCall() {
        // Un hote NU est attendu (voir la javadoc d'adresseAutorisee) : un ':' deja present indique
        // soit un hote:port deja compose, soit une IPv6 non supportee par cette construction.
        verifierAdresseRejetee("10.0.0.1:8090");
    }

    // Revue de Task 4, round 2 (finding Important) : SEUL un litteral IPv4 est accepte — un nom
    // d'hote syntaxiquement plausible (meme sans aucun des caracteres interdits) doit etre rejete
    // AVANT tout appel a InetAddress.getByName, qui declencherait sinon une vraie resolution DNS a
    // delai NON BORNE (pas couvert par la factory a delais courts de reveiller, voir la javadoc
    // d'adresseAutorisee). Si ce nom d'hote etait accepte a tort, ce test tenterait une vraie
    // resolution DNS — potentiellement lente — au lieu d'echouer instantanement sur la regex.
    @Test
    void reveiller_hostname_isRejectedWithoutAnyHttpCallOrDnsLookup() {
        verifierAdresseRejetee("serveur-partenaire.example.com");
    }

    // Deliberement PAS rejetee (revue de Task 4, finding Important) : ce plan relie le Maitre a
    // chaque pair via un mesh VPN prive (Headscale), dont les adresses attribuees (RFC1918/CGNAT)
    // sont le cas d'usage NORMAL de adresseDeclaree — un rejet generique des plages privees
    // casserait ce cas legitime. Test de non-regression : la requete DOIT partir.
    @Test
    void reveiller_privateRangeAddress_isAllowedAndRequestIsSent() {
        LicenceServeur serveur = mock(LicenceServeur.class);
        when(serveur.getAdresseDeclaree()).thenReturn("10.1.2.3");
        LicenceServeurRepository licenceServeurRepository = mock(LicenceServeurRepository.class);
        when(licenceServeurRepository.findByStatut(LicenceServeur.LicenceServeurStatut.ACTIF))
                .thenReturn(List.of(serveur));
        OrdreDeploiementRepository ordreDeploiementRepository = mock(OrdreDeploiementRepository.class);

        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://10.1.2.3:" + AGENT_PORT + "/reveil"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess());

        nouveauScheduler(licenceServeurRepository, ordreDeploiementRepository, mock(GitHubReleaseClient.class), builder)
                .reveillerTousLesAgents("v1.0.0");

        server.verify(); // la requete a bien ete envoyee : une plage privee n'est pas une plage refusee.
    }

    // Meme garantie pour une adresse CGNAT (100.64.0.0/10, plage Tailscale/Headscale typique).
    @Test
    void reveiller_cgnatRangeAddress_isAllowedAndRequestIsSent() {
        LicenceServeur serveur = mock(LicenceServeur.class);
        when(serveur.getAdresseDeclaree()).thenReturn("100.64.0.5");
        LicenceServeurRepository licenceServeurRepository = mock(LicenceServeurRepository.class);
        when(licenceServeurRepository.findByStatut(LicenceServeur.LicenceServeurStatut.ACTIF))
                .thenReturn(List.of(serveur));
        OrdreDeploiementRepository ordreDeploiementRepository = mock(OrdreDeploiementRepository.class);

        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://100.64.0.5:" + AGENT_PORT + "/reveil")).andRespond(withSuccess());

        nouveauScheduler(licenceServeurRepository, ordreDeploiementRepository, mock(GitHubReleaseClient.class), builder)
                .reveillerTousLesAgents("v1.0.0");

        server.verify();
    }

    // Revue finale whole-branch, finding 2 : app.licence.adresse-publique (chantier anterieur, sans
    // rapport avec celui-ci) documente accepter une URL COMPLETE (http(s)://hote:port) comme valeur
    // de adresseDeclaree, pour le protocole DISTINCT /api/sync/enroll. Le round precedent de cette
    // revue rejetait a tort TOUTE une telle valeur. L'hote doit etre extrait de l'URL, et l'URL de
    // reveil construite avec CET hote + app.agent.port (9090 ici) — PAS le port 8090 de l'URL
    // d'origine, qui appartient a un tout autre protocole et doit etre ignore.
    @Test
    void reveiller_adresseDeclareeAsFullUrl_extractsHostAndWakesUsingAgentPort() {
        LicenceServeur serveur = mock(LicenceServeur.class);
        when(serveur.getAdresseDeclaree()).thenReturn("http://100.64.0.5:8090");
        LicenceServeurRepository licenceServeurRepository = mock(LicenceServeurRepository.class);
        when(licenceServeurRepository.findByStatut(LicenceServeur.LicenceServeurStatut.ACTIF))
                .thenReturn(List.of(serveur));
        OrdreDeploiementRepository ordreDeploiementRepository = mock(OrdreDeploiementRepository.class);

        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        // AGENT_PORT (9090), pas le port 8090 de l'URL d'origine.
        server.expect(requestTo("http://100.64.0.5:" + AGENT_PORT + "/reveil"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess());

        nouveauScheduler(licenceServeurRepository, ordreDeploiementRepository, mock(GitHubReleaseClient.class), builder)
                .reveillerTousLesAgents("v1.0.0");

        server.verify();
    }

    // Une URL complete dont l'hote extrait est lui-meme refuse (ici loopback) doit rester refusee —
    // l'extraction ne doit pas devenir une echappatoire a la validation.
    @Test
    void reveiller_adresseDeclareeAsFullUrlWithLoopbackHost_isRejectedWithoutAnyHttpCall() {
        verifierAdresseRejetee("http://127.0.0.1:8090");
    }

    // Une URL complete malformee (URI.create leve) doit etre refusee, pas lever d'exception.
    @Test
    void reveiller_adresseDeclareeAsMalformedFullUrl_isRejectedWithoutAnyHttpCall() {
        verifierAdresseRejetee("http://");
    }

    @Test
    void reveillerTousLesAgents_persistsOneNewOrdreDeploiementForThisVersion() {
        LicenceServeurRepository licenceServeurRepository = mock(LicenceServeurRepository.class);
        when(licenceServeurRepository.findByStatut(LicenceServeur.LicenceServeurStatut.ACTIF))
                .thenReturn(List.of());
        OrdreDeploiementRepository ordreDeploiementRepository = mock(OrdreDeploiementRepository.class);
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer.bindTo(builder).build(); // aucune expectation : aucun serveur ACTIF.

        DeploiementScheduler scheduler = nouveauScheduler(
                licenceServeurRepository, ordreDeploiementRepository, mock(GitHubReleaseClient.class), builder);

        scheduler.reveillerTousLesAgents("v2.0.0");

        ArgumentCaptor<OrdreDeploiement> captor = ArgumentCaptor.forClass(OrdreDeploiement.class);
        verify(ordreDeploiementRepository).save(captor.capture());
        assertThat(captor.getValue().getVersion()).isEqualTo("v2.0.0");
    }

    @Test
    void reveillerTousLesAgents_serverWithBlankAdresseDeclaree_isSkippedWithoutHttpCallOrException() {
        LicenceServeur serveurSansAdresse = mock(LicenceServeur.class);
        when(serveurSansAdresse.getAdresseDeclaree()).thenReturn(null);
        LicenceServeurRepository licenceServeurRepository = mock(LicenceServeurRepository.class);
        when(licenceServeurRepository.findByStatut(LicenceServeur.LicenceServeurStatut.ACTIF))
                .thenReturn(List.of(serveurSansAdresse));
        OrdreDeploiementRepository ordreDeploiementRepository = mock(OrdreDeploiementRepository.class);
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        // Aucune expectation : le moindre appel HTTP ferait echouer ce test.

        DeploiementScheduler scheduler = nouveauScheduler(
                licenceServeurRepository, ordreDeploiementRepository, mock(GitHubReleaseClient.class), builder);

        assertThatCode(() -> scheduler.reveillerTousLesAgents("v1.0.0")).doesNotThrowAnyException();

        server.verify();
    }

    @Test
    void verifierNouvelleVersion_onlyRunsOnMasterRole() {
        GitHubReleaseClient gitHubReleaseClientMock = mock(GitHubReleaseClient.class);
        LicenceServeurRepository licenceServeurRepository = mock(LicenceServeurRepository.class);
        OrdreDeploiementRepository ordreDeploiementRepository = mock(OrdreDeploiementRepository.class);
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer.bindTo(builder).build(); // aucune expectation
        RestClient restClient = builder.build();

        new DeploiementScheduler(licenceServeurRepository, ordreDeploiementRepository, gitHubReleaseClientMock,
                role("PARTNER"), restClient, AGENT_PORT).verifierNouvelleVersion();
        new DeploiementScheduler(licenceServeurRepository, ordreDeploiementRepository, gitHubReleaseClientMock,
                role("CLIENT"), restClient, AGENT_PORT).verifierNouvelleVersion();

        verifyNoInteractions(gitHubReleaseClientMock);
        verifyNoInteractions(licenceServeurRepository);
    }

    @Test
    void verifierNouvelleVersion_sameVersionAsLastOrder_doesNothing() {
        GitHubReleaseClient gitHubReleaseClientMock = mock(GitHubReleaseClient.class);
        when(gitHubReleaseClientMock.dernierTag()).thenReturn("v1.0.0");
        OrdreDeploiementRepository ordreDeploiementRepository = mock(OrdreDeploiementRepository.class);
        OrdreDeploiement dernierOrdre = new OrdreDeploiement();
        dernierOrdre.setId(1L);
        dernierOrdre.setVersion("v1.0.0");
        when(ordreDeploiementRepository.findTopByOrderByCreeADescIdDesc()).thenReturn(Optional.of(dernierOrdre));
        LicenceServeurRepository licenceServeurRepository = mock(LicenceServeurRepository.class);
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer.bindTo(builder).build(); // aucune expectation : aucun reveil attendu.

        nouveauScheduler(licenceServeurRepository, ordreDeploiementRepository, gitHubReleaseClientMock, builder)
                .verifierNouvelleVersion();

        verify(ordreDeploiementRepository, never()).save(any());
        verifyNoInteractions(licenceServeurRepository);
    }

    @Test
    void verifierNouvelleVersion_noTagAvailable_doesNothingWithoutThrowing() {
        GitHubReleaseClient gitHubReleaseClientMock = mock(GitHubReleaseClient.class);
        when(gitHubReleaseClientMock.dernierTag()).thenReturn(null);
        OrdreDeploiementRepository ordreDeploiementRepository = mock(OrdreDeploiementRepository.class);
        LicenceServeurRepository licenceServeurRepository = mock(LicenceServeurRepository.class);
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer.bindTo(builder).build();

        DeploiementScheduler scheduler = nouveauScheduler(
                licenceServeurRepository, ordreDeploiementRepository, gitHubReleaseClientMock, builder);

        assertThatCode(scheduler::verifierNouvelleVersion).doesNotThrowAnyException();

        verify(ordreDeploiementRepository, never()).save(any());
        verifyNoInteractions(licenceServeurRepository);
    }

    @Test
    void verifierNouvelleVersion_newVersionDetected_wakesAgentsAndPersistsNewOrder() {
        LicenceServeur serveur = mock(LicenceServeur.class);
        when(serveur.getAdresseDeclaree()).thenReturn("10.0.0.9");
        LicenceServeurRepository licenceServeurRepository = mock(LicenceServeurRepository.class);
        when(licenceServeurRepository.findByStatut(LicenceServeur.LicenceServeurStatut.ACTIF))
                .thenReturn(List.of(serveur));

        GitHubReleaseClient gitHubReleaseClientMock = mock(GitHubReleaseClient.class);
        when(gitHubReleaseClientMock.dernierTag()).thenReturn("v9.9.9");
        OrdreDeploiementRepository ordreDeploiementRepository = mock(OrdreDeploiementRepository.class);
        OrdreDeploiement ordrePrecedent = new OrdreDeploiement();
        ordrePrecedent.setId(1L);
        ordrePrecedent.setVersion("v9.9.8");
        when(ordreDeploiementRepository.findTopByOrderByCreeADescIdDesc()).thenReturn(Optional.of(ordrePrecedent));

        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://10.0.0.9:" + AGENT_PORT + "/reveil"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess());

        nouveauScheduler(licenceServeurRepository, ordreDeploiementRepository, gitHubReleaseClientMock, builder)
                .verifierNouvelleVersion();

        server.verify();
        ArgumentCaptor<OrdreDeploiement> captor = ArgumentCaptor.forClass(OrdreDeploiement.class);
        verify(ordreDeploiementRepository).save(captor.capture());
        assertThat(captor.getValue().getVersion()).isEqualTo("v9.9.9");
    }

    @Test
    void verifierNouvelleVersion_noPreviousOrderAtAll_isTreatedAsNewVersion() {
        LicenceServeurRepository licenceServeurRepository = mock(LicenceServeurRepository.class);
        when(licenceServeurRepository.findByStatut(LicenceServeur.LicenceServeurStatut.ACTIF)).thenReturn(List.of());

        GitHubReleaseClient gitHubReleaseClientMock = mock(GitHubReleaseClient.class);
        when(gitHubReleaseClientMock.dernierTag()).thenReturn("v1.0.0");
        OrdreDeploiementRepository ordreDeploiementRepository = mock(OrdreDeploiementRepository.class);
        when(ordreDeploiementRepository.findTopByOrderByCreeADescIdDesc()).thenReturn(Optional.empty());

        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer.bindTo(builder).build();

        nouveauScheduler(licenceServeurRepository, ordreDeploiementRepository, gitHubReleaseClientMock, builder)
                .verifierNouvelleVersion();

        ArgumentCaptor<OrdreDeploiement> captor = ArgumentCaptor.forClass(OrdreDeploiement.class);
        verify(ordreDeploiementRepository).save(captor.capture());
        assertThat(captor.getValue().getVersion()).isEqualTo("v1.0.0");
    }

    @Test
    void verifierNouvelleVersion_estSynchronized() throws NoSuchMethodException {
        assertThat(java.lang.reflect.Modifier.isSynchronized(
                DeploiementScheduler.class.getMethod("verifierNouvelleVersion").getModifiers())).isTrue();
    }

    // Revue de Task 4, finding MEDIUM : pas de test de bout en bout (lent/fragile — necessiterait un
    // vrai socket qui ne repond jamais) ; verification unitaire directe de la configuration de la
    // factory dediee (delais courts), via reflexion sur ses champs prives (aucun getter public
    // n'existe sur JdkClientHttpRequestFactory).
    @Test
    void construireRequestFactoryAvecTimeout_configuresShortConnectAndReadTimeouts() {
        JdkClientHttpRequestFactory factory = DeploiementScheduler.construireRequestFactoryAvecTimeout();

        HttpClient httpClient = (HttpClient) ReflectionTestUtils.getField(factory, "httpClient");
        Duration readTimeout = (Duration) ReflectionTestUtils.getField(factory, "readTimeout");

        assertThat(httpClient).isNotNull();
        assertThat(httpClient.connectTimeout()).contains(Duration.ofSeconds(3));
        assertThat(readTimeout).isEqualTo(Duration.ofSeconds(3));
    }

    // ── Fixtures ─────────────────────────────────────────────────────────────

    /** Construit un {@link DeploiementScheduler} role MASTER dont l'adresse {@code adresse} est
     * refusee par {@code adresseAutorisee} : aucune expectation n'est enregistree sur le {@link
     * MockRestServiceServer}, donc le moindre appel HTTP ferait immediatement echouer le test. */
    private static void verifierAdresseRejetee(String adresse) {
        LicenceServeur serveur = mock(LicenceServeur.class);
        when(serveur.getAdresseDeclaree()).thenReturn(adresse);
        LicenceServeurRepository licenceServeurRepository = mock(LicenceServeurRepository.class);
        when(licenceServeurRepository.findByStatut(LicenceServeur.LicenceServeurStatut.ACTIF))
                .thenReturn(List.of(serveur));
        OrdreDeploiementRepository ordreDeploiementRepository = mock(OrdreDeploiementRepository.class);

        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

        DeploiementScheduler scheduler = nouveauScheduler(
                licenceServeurRepository, ordreDeploiementRepository, mock(GitHubReleaseClient.class), builder);

        assertThatCode(() -> scheduler.reveillerTousLesAgents("v1.0.0")).doesNotThrowAnyException();

        server.verify();
    }

    private static DeploiementScheduler nouveauScheduler(LicenceServeurRepository licenceServeurRepository,
                                                          OrdreDeploiementRepository ordreDeploiementRepository,
                                                          GitHubReleaseClient gitHubReleaseClient,
                                                          RestClient.Builder builder) {
        // .build() ICI (apres que le test ait lie MockRestServiceServer au builder), puis le
        // RestClient DEJA CONSTRUIT est passe au constructeur package-prive de DeploiementScheduler —
        // voir la javadoc de classe et celle de ce constructeur.
        return new DeploiementScheduler(licenceServeurRepository, ordreDeploiementRepository, gitHubReleaseClient,
                role("MASTER"), builder.build(), AGENT_PORT);
    }

    private static ServerRoleProperties role(String role) {
        ServerRoleProperties properties = new ServerRoleProperties();
        properties.setRole(role);
        return properties;
    }
}

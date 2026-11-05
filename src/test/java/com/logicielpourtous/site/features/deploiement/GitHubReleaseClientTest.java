package com.logicielpourtous.site.features.deploiement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * {@link GitHubReleaseClient} : interrogation de l'API publique GitHub (derniere release PUBLIEE
 * du depot configure), utilisee par {@link DeploiementScheduler} pour detecter une nouvelle
 * version a deployer.
 *
 * <p>Test unitaire pur (pas de contexte Spring, pas d'appel reseau reel) : {@link
 * MockRestServiceServer}, lie au {@link RestClient.Builder} passe au constructeur — meme patron que
 * {@code LicenceBootstrapTest}/{@code LicenceClientIntegrationTest}.
 *
 * <p>{@code GET /releases/latest} (pas {@code GET /tags}, voir la revue finale whole-branch,
 * finding 1, et la javadoc de classe de {@link GitHubReleaseClient}) : une release en JSON porte
 * son tag dans le champ {@code tag_name}, pas {@code name}.
 */
class GitHubReleaseClientTest {

    private static final String RELEASES_LATEST_URL = "https://api.github.com/repos/mon-org/site/releases/latest";

    @Test
    void dernierTag_returnsTagNameOfLatestRelease() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(RELEASES_LATEST_URL))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"tag_name\":\"v1.2.3\",\"name\":\"Release 1.2.3\"}",
                        MediaType.APPLICATION_JSON));

        GitHubReleaseClient client = new GitHubReleaseClient(builder, "mon-org/site");

        assertThat(client.dernierTag()).isEqualTo("v1.2.3");
        server.verify();
    }

    // Revue finale whole-branch, finding 1 : un depot sans AUCUNE release publiee encore (ex. tout
    // premier deploiement de ce chantier) renvoie 404 sur cet endpoint — etat normal, pas une
    // erreur, meme traitement silencieux qu'une liste de tags vide avant ce correctif.
    @Test
    void dernierTag_noReleasePublishedYet_returnsNullWithoutThrowing() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(RELEASES_LATEST_URL)).andRespond(withStatus(HttpStatus.NOT_FOUND));

        GitHubReleaseClient client = new GitHubReleaseClient(builder, "mon-org/site");

        assertThatCode(() -> assertThat(client.dernierTag()).isNull()).doesNotThrowAnyException();
        server.verify();
    }

    @Test
    void dernierTag_githubUnreachableOrErrorResponse_returnsNullWithoutThrowing() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(RELEASES_LATEST_URL)).andRespond(withServerError());

        GitHubReleaseClient client = new GitHubReleaseClient(builder, "mon-org/site");

        assertThatCode(() -> assertThat(client.dernierTag()).isNull()).doesNotThrowAnyException();
        server.verify();
    }

    // app.deploiement.github-repo OBLIGATOIRE (pas de valeur par defaut devinee, meme discipline
    // que app.agent.cle-api-file-path pour AgentCleApiFileWriter) : si absent/vide, aucun appel HTTP
    // n'est tente, et dernierTag() renvoie null au lieu de lever.
    @Test
    void dernierTag_repoNotConfigured_returnsNullWithoutAnyHttpCall() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        // Aucune expectation enregistree : le moindre appel HTTP ferait echouer ce test.

        GitHubReleaseClient client = new GitHubReleaseClient(builder, "");

        assertThat(client.dernierTag()).isNull();
        server.verify();
    }
}

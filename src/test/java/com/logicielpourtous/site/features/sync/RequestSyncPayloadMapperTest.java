package com.logicielpourtous.site.features.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.logicielpourtous.site.features.request.Comment;
import com.logicielpourtous.site.features.request.Request;
import com.logicielpourtous.site.features.request.RequestStatus;

/**
 * Review Focus de la Task 4 : un commentaire prive ({@code isPublic=false}) ne doit JAMAIS sortir
 * du serveur par la synchronisation. Test unitaire pur (aucun contexte Spring) : seul le filtrage
 * du mapper est en jeu.
 */
class RequestSyncPayloadMapperTest {

    private final RequestSyncPayloadMapper mapper = new RequestSyncPayloadMapper();

    @Test
    void mapperNeverIncludesPrivateComments() {
        Request request = request();
        comment(request, "Visible", true);
        comment(request, "Interne, jamais envoye", false);

        RequestSyncPayload payload = mapper.toPayload(request);

        assertThat(payload.publicComments())
                .extracting("content")
                .containsExactly("Visible")
                .doesNotContain("Interne, jamais envoye");
        // Cle de synchronisation du commentaire transmise (idempotence a la reception).
        assertThat(payload.publicComments()).singleElement()
                .satisfies(c -> assertThat(c.syncId()).isEqualTo(request.getComments().get(0).getSyncId()));
    }

    @Test
    void mapperCopiesScalarFieldsAndLocalVersion() {
        Request request = request();
        request.setTitle("Titre");
        request.setDescription("Description");
        request.setStatus(RequestStatus.IN_PROGRESS);

        RequestSyncPayload payload = mapper.toPayload(request);

        assertThat(payload.title()).isEqualTo("Titre");
        assertThat(payload.description()).isEqualTo("Description");
        assertThat(payload.status()).isEqualTo(RequestStatus.IN_PROGRESS);
        assertThat(payload.version()).isEqualTo(4L);
    }

    /** Push d'un secondaire : la version envoyee est la version MAITRE connue, pas la version locale. */
    @Test
    void mapperCanCarryAnExplicitBaseVersionInsteadOfLocalOne() {
        Request request = request();
        comment(request, "Interne", false);

        RequestSyncPayload payload = mapper.toPayload(request, 1L);

        assertThat(payload.version()).isEqualTo(1L);
        assertThat(payload.publicComments()).isEmpty();
    }

    @Test
    void mapperRefusesRequestWithoutVersion() {
        assertThatThrownBy(() -> mapper.toPayload(new Request()))
                .isInstanceOf(NullPointerException.class);
    }

    private static Request request() {
        Request request = new Request();
        // Version simulee d'une Request deja persistee : setVersion n'est appele ICI que pour
        // fabriquer une fixture en memoire (aucune ecriture JPA), jamais par le code de synchro.
        request.setVersion(4L);
        return request;
    }

    private static void comment(Request request, String content, boolean isPublic) {
        Comment c = new Comment();
        c.adopterSyncIdRecu(java.util.UUID.randomUUID()); // fixture en memoire (aucun @PrePersist ici)
        c.setContent(content);
        c.setPublic(isPublic);
        c.setRequest(request);
        request.getComments().add(c);
    }
}

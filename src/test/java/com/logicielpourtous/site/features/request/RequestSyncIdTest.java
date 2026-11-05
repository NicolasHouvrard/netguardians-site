package com.logicielpourtous.site.features.request;

import org.springframework.context.annotation.Import;
import com.logicielpourtous.site.config.MultiTenantJpaConfiguration;
import com.logicielpourtous.site.features.commun.MultiTenantConnectionProviderImpl;
import com.logicielpourtous.site.features.commun.SchemaTenantResolver;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// Meme EntityManagerFactory que l'application reelle (voir la Javadoc de MultiTenantJpaConfiguration) : sans cela,
// la slice JPA utiliserait l'EMF auto-configure de Boot, avec une strategie de nommage
// differente, et ecrirait dans d'autres colonnes que l'application.
@DataJpaTest
@Import({ MultiTenantJpaConfiguration.class, MultiTenantConnectionProviderImpl.class, SchemaTenantResolver.class })
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class RequestSyncIdTest {

    @Autowired
    private RequestRepository requestRepository;

    @Autowired
    private TestEntityManager entityManager;

    @Test
    void generatesAndKeepsSyncIdStableAcrossRealDatabaseUpdate() {
        Request request = new Request();
        Request saved = requestRepository.save(request);
        entityManager.flush();

        assertThat(saved.getSyncId()).isNotNull();
        Long id = saved.getId();

        // Detache tout du contexte de persistance : la relecture qui suit doit
        // declencher un vrai SELECT, pas renvoyer la meme instance en memoire.
        entityManager.clear();

        Request reloaded = requestRepository.findById(id).orElseThrow();
        UUID firstSyncId = reloaded.getSyncId();
        assertThat(firstSyncId).isNotNull();

        // Choisit une valeur differente de celle en base pour garantir un vrai
        // UPDATE SQL (Hibernate ne flush rien si le champ n'est pas modifie).
        RequestStatus nouveauStatut = reloaded.getStatus() == RequestStatus.IN_PROGRESS
                ? RequestStatus.COMPLETED
                : RequestStatus.IN_PROGRESS;
        reloaded.setStatus(nouveauStatut);
        requestRepository.save(reloaded);
        entityManager.flush(); // force l'emission immediate de l'UPDATE (visible dans les logs Hibernate)

        entityManager.clear();

        Request updated = requestRepository.findById(id).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(nouveauStatut);
        assertThat(updated.getSyncId()).isEqualTo(firstSyncId);
    }
}

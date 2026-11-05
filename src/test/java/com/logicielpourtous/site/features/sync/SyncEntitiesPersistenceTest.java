package com.logicielpourtous.site.features.sync;

import org.springframework.context.annotation.Import;
import com.logicielpourtous.site.config.MultiTenantJpaConfiguration;
import com.logicielpourtous.site.features.commun.MultiTenantConnectionProviderImpl;
import com.logicielpourtous.site.features.commun.SchemaTenantResolver;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;

import com.logicielpourtous.site.features.commun.ServerRole;

import static org.assertj.core.api.Assertions.assertThat;

// Meme EntityManagerFactory que l'application reelle (voir la Javadoc de MultiTenantJpaConfiguration) : sans cela,
// la slice JPA utiliserait l'EMF auto-configure de Boot, avec une strategie de nommage
// differente, et ecrirait dans d'autres colonnes que l'application.
@DataJpaTest
@Import({ MultiTenantJpaConfiguration.class, MultiTenantConnectionProviderImpl.class, SchemaTenantResolver.class })
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class SyncEntitiesPersistenceTest {

    @Autowired
    private SecondaryServerRepository secondaryServerRepository;

    @Autowired
    private SyncOutboxEntryRepository syncOutboxEntryRepository;

    @Autowired
    private SyncConflictRepository syncConflictRepository;

    @Test
    void persistsSecondaryServerWithDefaults() {
        SecondaryServer s = new SecondaryServer();
        s.setNom("Client test");
        s.setRole(ServerRole.CLIENT);
        SecondaryServer saved = secondaryServerRepository.save(s);
        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getStatut()).isEqualTo(SecondaryServer.SecondaryServerStatut.ACTIF);
    }

    @Test
    void persistsOutboxEntryAndConflict() {
        SyncOutboxEntry entry = new SyncOutboxEntry();
        entry.setEntityType("Request");
        entry.setEntitySyncId(UUID.randomUUID());
        entry.setPayload("{}");
        SyncOutboxEntry savedEntry = syncOutboxEntryRepository.save(entry);
        assertThat(savedEntry.getId()).isNotNull();
        assertThat(savedEntry.getStatut()).isEqualTo(SyncOutboxEntry.SyncOutboxEntryStatut.EN_ATTENTE);

        SyncConflict conflict = new SyncConflict();
        conflict.setEntitySyncId(UUID.randomUUID());
        conflict.setVersionAttendue(3L);
        conflict.setVersionRecue(2L);
        conflict.setPayloadRecu("{}");
        SyncConflict savedConflict = syncConflictRepository.save(conflict);
        assertThat(savedConflict.getId()).isNotNull();
        assertThat(savedConflict.isResolu()).isFalse();
        assertThat(savedConflict.getCreeA()).isInstanceOf(Instant.class);
    }
}

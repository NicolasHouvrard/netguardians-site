package com.logicielpourtous.site.features.sync;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.logicielpourtous.site.features.sync.SyncOutboxEntry.SyncOutboxEntryStatut;

public interface SyncOutboxEntryRepository extends JpaRepository<SyncOutboxEntry, Long> {

    /** Entrees d'un statut donne destinees a UN secondaire, dans l'ordre de creation. */
    List<SyncOutboxEntry> findByDestinationServerIdAndStatutOrderByCreeAAscIdAsc(
            Long destinationServerId, SyncOutboxEntryStatut statut);

    /** Idem, limitees aux entrees creees strictement apres {@code since}. */
    List<SyncOutboxEntry> findByDestinationServerIdAndStatutAndCreeAAfterOrderByCreeAAscIdAsc(
            Long destinationServerId, SyncOutboxEntryStatut statut, Instant since);

    /**
     * Cote secondaire : entrees a pousser vers le maitre (sans destinataire secondaire) dans les
     * statuts donnes, dans l'ordre de creation.
     */
    List<SyncOutboxEntry> findByDestinationServerIdIsNullAndStatutInOrderByCreeAAscIdAsc(
            Collection<SyncOutboxEntryStatut> statuts);

    /** Cote secondaire : cette entite a-t-elle des editions locales encore a pousser ? */
    boolean existsByEntitySyncIdAndDestinationServerIdIsNullAndStatutIn(UUID entitySyncId,
                                                                         Collection<SyncOutboxEntryStatut> statuts);
}

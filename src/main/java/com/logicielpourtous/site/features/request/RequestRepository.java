package com.logicielpourtous.site.features.request;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RequestRepository extends JpaRepository<Request, Long>, JpaSpecificationExecutor<Request> {

    Optional<Request> findById(Integer id);

    List<Request> findByCustomerIdOrderByCreatedAtDesc(Long customerId);

    @Query("SELECT COUNT(r) FROM Request r WHERE r.customer.id = :customerId AND r.status IN :statuses")
    long countByCustomerIdAndStatusIn(@Param("customerId") Long customerId,
                                      @Param("statuses") List<RequestStatus> statuses);

    List<Request> findAllByOrderByCreatedAtDesc();

    long countByStatusIn(List<RequestStatus> statuses);

    long countByStatusInAndPriorityIn(List<RequestStatus> statuses, List<RequestPriority> priorities);

    List<Request> findByStatusInOrderByCreatedAtDesc(List<RequestStatus> statuses);

    /**
     * {@code syncId} tel que PERSISTE en base (requete scalaire : ne renvoie pas l'eventuelle
     * instance geree du contexte de persistance, dont le {@code syncId} en memoire peut avoir ete
     * ecrase a {@code null} par le {@code merge} d'une instance liee depuis un formulaire).
     */
    @Query("SELECT r.syncId FROM Request r WHERE r.id = :id")
    Optional<UUID> findSyncIdById(@Param("id") Long id);

    /**
     * Id du client tel que PERSISTE (requete scalaire, meme raison que {@link #findSyncIdById}) ; vide
     * si la demande n'a pas de client. Utilise par le module {@code sync} pour le routage.
     */
    @Query("SELECT r.customer.id FROM Request r WHERE r.id = :id")
    Optional<Long> findCustomerIdById(@Param("id") Long id);

    /** Resolution d'une demande par sa cle de synchronisation (module {@code sync}). */
    Optional<Request> findBySyncId(UUID syncId);
}

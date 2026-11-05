package com.logicielpourtous.site.features.crm.quote;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;

@Repository
public interface QuoteRepository extends JpaRepository<Quote, Long> {

    List<Quote> findByCustomerIdOrderByCreatedAtDesc(Long customerId);

    List<Quote> findByStatusOrderByCreatedAtDesc(QuoteStatus status);

    List<Quote> findAllByOrderByCreatedAtDesc();

    long countByStatus(QuoteStatus status);

    @Query("SELECT COUNT(q) FROM Quote q WHERE q.status = 'ACCEPTE'")
    long countAccepted();

    @Query("SELECT COALESCE(SUM(q.totalHT), 0) FROM Quote q WHERE q.status = 'ENVOYE'")
    BigDecimal sumTotalHTByStatusEnvoye();

    List<Quote> findByOpportunityIdOrderByCreatedAtDesc(Long opportunityId);

    @Query("SELECT COALESCE(SUM(q.totalHT),0) FROM Quote q WHERE q.opportunity.id = :id AND q.status IN ('ENVOYE','ACCEPTE')")
    BigDecimal sumActiveQuotesByOpportunity(@Param("id") Long id);

    @Query("SELECT q.opportunity.id, SUM(q.totalHT) FROM Quote q WHERE q.opportunity.id IN :ids AND q.status IN :statuses GROUP BY q.opportunity.id")
    List<Object[]> sumTotalHTByOpportunityIds(@Param("ids") List<Long> ids,
                                              @Param("statuses") List<QuoteStatus> statuses);

    @Modifying
    @Query("UPDATE Quote q SET q.totalHT = :ht, q.totalTVA = :tva, q.totalTTC = :ttc WHERE q.id = :id")
    void updateTotals(@Param("id") Long id,
                      @Param("ht")  BigDecimal totalHT,
                      @Param("tva") BigDecimal totalTVA,
                      @Param("ttc") BigDecimal totalTTC);
}

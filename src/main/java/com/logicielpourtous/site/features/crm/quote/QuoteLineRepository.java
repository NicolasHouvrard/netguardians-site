package com.logicielpourtous.site.features.crm.quote;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;

@Repository
public interface QuoteLineRepository extends JpaRepository<QuoteLine, Long> {
    List<QuoteLine> findByQuoteIdOrderByPositionAsc(Long quoteId);

    @Query("SELECT COALESCE(SUM(l.totalHT), 0) FROM QuoteLine l WHERE l.quote.status = :status")
    BigDecimal sumTotalHTByQuoteStatus(@Param("status") QuoteStatus status);

    @Query("SELECT COALESCE(SUM(l.totalHT), 0) FROM QuoteLine l WHERE l.quote.status IN :statuses")
    BigDecimal sumTotalHTByQuoteStatuses(@Param("statuses") java.util.Collection<QuoteStatus> statuses);
}

package com.logicielpourtous.site.features.invoice;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

public interface InvoiceRepository extends JpaRepository<Invoice, Long> {
    Optional<Invoice> findByInvoiceNumber(String invoiceNumber);
    List<Invoice> findByCustomerIdOrderByInvoiceDateDesc(Long customerId);
    List<Invoice> findByTypeOrderByInvoiceDateDesc(Invoice.InvoiceType type);
    List<Invoice> findByStatusOrderByInvoiceDateDesc(Invoice.InvoiceStatus status);
    long countByStatus(Invoice.InvoiceStatus status);
    List<Invoice> findByTicketIdOrderByCreatedAtDesc(Long ticketId);
    List<Invoice> findTop10ByOrderByCreatedAtDesc();

    @Query("SELECT COALESCE(SUM(i.totalTTC), 0) FROM Invoice i WHERE i.type = 'SALE' AND i.status = 'PAID'")
    BigDecimal sumPaidSales();

    @Query("SELECT COALESCE(SUM(i.totalTTC), 0) FROM Invoice i WHERE i.status = 'OVERDUE'")
    BigDecimal sumOverdue();
}

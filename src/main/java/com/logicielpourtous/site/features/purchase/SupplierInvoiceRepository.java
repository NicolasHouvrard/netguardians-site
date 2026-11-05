package com.logicielpourtous.site.features.purchase;

import com.logicielpourtous.site.features.commun.Tenant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface SupplierInvoiceRepository extends JpaRepository<SupplierInvoice, Long> {
    List<SupplierInvoice> findByTenantOrderByCreatedAtDesc(Tenant tenant);
    List<SupplierInvoice> findByTenantAndStatusOrderByDueDateAsc(Tenant tenant, SupplierInvoice.Status status);
    Optional<SupplierInvoice> findByIdAndTenant(Long id, Tenant tenant);
    long countByTenantAndStatus(Tenant tenant, SupplierInvoice.Status status);
    long countByTenantAndStatusAndDueDateBefore(Tenant tenant, SupplierInvoice.Status status, LocalDate date);
    List<SupplierInvoice> findTop10ByTenantOrderByCreatedAtDesc(Tenant tenant);

    @Query("SELECT COALESCE(SUM(si.totalTTC), 0) FROM SupplierInvoice si WHERE si.tenant = :tenant AND si.status = :status")
    BigDecimal sumTotalTTCByTenantAndStatus(@Param("tenant") Tenant tenant, @Param("status") SupplierInvoice.Status status);

    Optional<SupplierInvoice> findByTenantAndIngramInvoiceNumber(Tenant tenant, String ingramInvoiceNumber);
    List<SupplierInvoice> findByTenantAndOrderIdOrderByCreatedAtDesc(Tenant tenant, Long orderId);
    List<SupplierInvoice> findByOrderIdOrderByCreatedAtDesc(Long orderId);
}

package com.logicielpourtous.site.features.vatrate;

import com.logicielpourtous.site.features.customers.Customer;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface VatRateRepository extends JpaRepository<VatRate, Long> {
    List<VatRate> findByActiveTrueOrderByZoneAscRateDesc();
    List<VatRate> findByZoneAndActiveTrueOrderByRateDesc(Customer.FiscalZone zone);
    Optional<VatRate> findFirstByZoneAndIsDefaultTrueAndActiveTrue(Customer.FiscalZone zone);
    boolean existsByName(String name);
}

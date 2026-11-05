package com.logicielpourtous.site.features.connector;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface PlatformConnectorRepository extends JpaRepository<PlatformConnector, Long> {
    List<PlatformConnector> findByActiveTrue();
    List<PlatformConnector> findByCustomerIdOrderByNameAsc(Long customerId);
    List<PlatformConnector> findBySchedulingEnabledTrueAndActiveTrue();
    java.util.Optional<PlatformConnector> findFirstByCustomerId(Long customerId);
}

package com.logicielpourtous.site.features.integration;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface ClientIntegrationRepository extends JpaRepository<ClientIntegration, Long> {
    List<ClientIntegration> findByCustomerIdOrderByServiceTypeAscLabelAsc(Long customerId);
    List<ClientIntegration> findAllByOrderByCustomerIdAscServiceTypeAsc();
    long countByCustomerId(Long customerId);
}

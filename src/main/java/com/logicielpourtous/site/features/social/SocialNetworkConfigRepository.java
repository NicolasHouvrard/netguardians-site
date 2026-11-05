package com.logicielpourtous.site.features.social;

import com.logicielpourtous.site.features.commun.Tenant;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface SocialNetworkConfigRepository extends JpaRepository<SocialNetworkConfig, Long> {
    List<SocialNetworkConfig> findByTenantOrderByNetworkTypeAscNameAsc(Tenant tenant);
    List<SocialNetworkConfig> findByTenantAndActiveTrueOrderByNetworkTypeAsc(Tenant tenant);
    Optional<SocialNetworkConfig> findByIdAndTenant(Long id, Tenant tenant);
}

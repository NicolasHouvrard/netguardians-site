package com.logicielpourtous.site.features.commun;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface TenantRepository extends JpaRepository<Tenant, Long> {
    Optional<Tenant> findBySchemaName(String schemaName);

    /** Vrai si au moins un tenant client dependant de ce tenant partenaire existe. */
    boolean existsByTenantPartenaireId(Long tenantPartenaireId);

    /** Le tenant cree en premier (plus petit id) : celui du Maitre. */
    Optional<Tenant> findFirstByOrderByIdAsc();
 
}
package com.logicielpourtous.site.features.esn;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface PartnerEsnRepository extends JpaRepository<PartnerEsn, Long> {
    List<PartnerEsn> findByActiveTrueOrderByCompanyNameAsc();
    Optional<PartnerEsn> findBySiret(String siret);
    Optional<PartnerEsn> findByCompanyName(String companyName);
}

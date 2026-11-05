package com.logicielpourtous.site.features.licence;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Acces a {@link EtatLicenceLocale} — singleton logique : {@link #findAll()} ne contient jamais
 * plus d'une ligne en pratique (voir {@link LicenceBootstrap}, seul createur de cette ligne).
 */
public interface EtatLicenceLocaleRepository extends JpaRepository<EtatLicenceLocale, Long> {
}

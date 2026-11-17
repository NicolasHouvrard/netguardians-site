package com.logicielpourtous.site.features.licence;

import java.util.Optional;

import org.springframework.stereotype.Component;

import com.logicielpourtous.site.features.commun.CleMaitre;

/**
 * {@link CleMaitre} lue dans {@link EtatLicenceLocale#getCleApiLicence()}, en base à chaque appel (même source et même
 * discipline que {@link LicenceClient} : la clé n'existe qu'une fois l'auto-enrôlement terminé). L'interface vit dans
 * {@code commun} pour que {@code plateforme} l'utilise sans dépendre de {@code licence}, et que {@code licence} ne
 * dépende jamais de {@code plateforme} (aucun cycle). Ne journalise jamais la clé.
 */
@Component
public class CleMaitreLicence implements CleMaitre {

	private final EtatLicenceLocaleRepository etatLicenceLocaleRepository;

	public CleMaitreLicence(EtatLicenceLocaleRepository etatLicenceLocaleRepository) {
		this.etatLicenceLocaleRepository = etatLicenceLocaleRepository;
	}

	@Override
	public Optional<String> cle() {
		return etatLicenceLocaleRepository.findAll()
			.stream()
			.findFirst()
			.map(EtatLicenceLocale::getCleApiLicence)
			.filter(cle -> !cle.isBlank())
			.map(String::trim);
	}

}

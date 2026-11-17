package com.logicielpourtous.site.features.licence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;

/** Clé du Maître pour la plateforme : lue en base, vide tant que l'installation n'est pas enrôlée. */
class CleMaitreLicenceTest {

	private final EtatLicenceLocaleRepository depot = mock(EtatLicenceLocaleRepository.class);

	private final CleMaitreLicence cle = new CleMaitreLicence(depot);

	@Test
	void vide_sans_etat_ou_sans_cle() {
		when(depot.findAll()).thenReturn(List.of());
		assertThat(cle.cle()).isEmpty();

		EtatLicenceLocale etat = mock(EtatLicenceLocale.class);
		when(etat.getCleApiLicence()).thenReturn(null);
		when(depot.findAll()).thenReturn(List.of(etat));
		assertThat(cle.cle()).isEmpty();

		when(etat.getCleApiLicence()).thenReturn("  ");
		assertThat(cle.cle()).isEmpty();
	}

	@Test
	void lue_a_chaque_appel() {
		EtatLicenceLocale etat = mock(EtatLicenceLocale.class);
		when(depot.findAll()).thenReturn(List.of(etat));
		when(etat.getCleApiLicence()).thenReturn("cle-1");
		assertThat(cle.cle()).contains("cle-1");
		when(etat.getCleApiLicence()).thenReturn("cle-2");
		assertThat(cle.cle()).contains("cle-2");
	}

}

package com.logicielpourtous.site.sdk;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class GestionPartenairesContratTest {

	@Test
	void resultatPartenaireConserveSesValeurs() {
		var resultat = new GestionPartenairesContrat.ResultatPartenaire("CREE", 42L);

		assertThat(resultat.issue()).isEqualTo("CREE");
		assertThat(resultat.societeId()).isEqualTo(42L);
	}

	@Test
	void creationPartenairePermetDesValeursNulles() {
		var demande = new GestionPartenairesContrat.CreationPartenaire(null, null, null, null, null);

		assertThat(demande.siren()).isNull();
		assertThat(demande.email()).isNull();
	}

	@Test
	void partenaireResumeConserveSesValeurs() {
		var resume = new GestionPartenairesContrat.PartenaireResume(7L, "123456789", "Acme", "ACTIF");

		assertThat(resume.societeId()).isEqualTo(7L);
		assertThat(resume.siren()).isEqualTo("123456789");
		assertThat(resume.raisonSociale()).isEqualTo("Acme");
		assertThat(resume.etat()).isEqualTo("ACTIF");
	}

}

package com.logicielpourtous.site.features.plateforme;

import static org.assertj.core.api.Assertions.assertThat;

import com.logicielpourtous.site.sdk.ManifestePlugin;
import com.logicielpourtous.site.sdk.ModeMiseAJour;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class VerificateurMiseAJourTest {

	private static final Map<String, String> DESTRUCTIVE = Map.of("V2__x.sql", "DROP TABLE factures;");

	private static ManifestePlugin manifeste(ModeMiseAJour mode, String min, String max) {
		return new ManifestePlugin("p", "1.0.0", min, max, mode, "pk", "pk.C", List.of(), List.of());
	}

	@Test
	void chaud_avec_migration_destructive_refuse() {
		var v = VerificateurMiseAJour.verifier(manifeste(ModeMiseAJour.CHAUD, "1.0.0", null), DESTRUCTIVE, Set.of(),
				"1.5.0");
		assertThat(v.accepte()).isFalse();
		assertThat(v.raisons()).isNotEmpty();
	}

	@Test
	void chaud_sans_migration_sensible_accepte() {
		var v = VerificateurMiseAJour.verifier(manifeste(ModeMiseAJour.CHAUD, "1.0.0", null),
				Map.of("V1__x.sql", "CREATE TABLE a (id int);"), Set.of(), "1.5.0");
		assertThat(v.accepte()).isTrue();
		assertThat(v.raisons()).isEmpty();
	}

	@Test
	void coeur_avec_migration_destructive_accepte() {
		var v = VerificateurMiseAJour.verifier(manifeste(ModeMiseAJour.COEUR, "1.0.0", null), DESTRUCTIVE, Set.of(),
				"1.5.0");
		assertThat(v.accepte()).isTrue();
	}

	@Test
	void version_coeur_trop_ancienne_refusee() {
		var v = VerificateurMiseAJour.verifier(manifeste(ModeMiseAJour.COEUR, "1.6.0", null), Map.of(), Set.of(),
				"1.5.9");
		assertThat(v.accepte()).isFalse();
		assertThat(v.raisons()).isNotEmpty();
	}

	@Test
	void comparaison_numerique_par_segment() {
		var v = VerificateurMiseAJour.verifier(manifeste(ModeMiseAJour.COEUR, "1.9.0", null), Map.of(), Set.of(),
				"1.10.0");
		assertThat(v.accepte()).isTrue();
	}

	@Test
	void coeur_max_depasse_refuse() {
		var v = VerificateurMiseAJour.verifier(manifeste(ModeMiseAJour.COEUR, "1.0.0", "1.5.0"), Map.of(), Set.of(),
				"1.5.1");
		assertThat(v.accepte()).isFalse();
	}

	@Test
	void comparaison_gere_les_suffixes_pre_release() {
		assertThat(VerificateurMiseAJour.comparer("1.5.0-rc1", "1.5.0")).isNegative();
		assertThat(VerificateurMiseAJour.comparer("1.5.0", "1.5.0-rc1")).isPositive();
		assertThat(VerificateurMiseAJour.comparer("1.5.0-rc1", "1.5.0-rc2")).isNegative();
		assertThat(VerificateurMiseAJour.comparer("1.5.0-rc.2", "1.5.0-rc.10")).isNegative();
		assertThat(VerificateurMiseAJour.comparer("1.5.0-alpha", "1.5.0-alpha.1")).isNegative();
		assertThat(VerificateurMiseAJour.comparer("1.5.0-1", "1.5.0-alpha")).isNegative();
		assertThat(VerificateurMiseAJour.comparer("1.5.0-rc1", "1.4.9")).isPositive();
		assertThat(VerificateurMiseAJour.comparer("1.5.0+build7", "1.5.0")).isZero();
		assertThat(VerificateurMiseAJour.comparer("1.5", "1.5.0")).isZero();
	}

	@Test
	void coeur_pre_release_inferieur_au_minimum_refuse() {
		var v = VerificateurMiseAJour.verifier(manifeste(ModeMiseAJour.COEUR, "1.5.0", null), Map.of(), Set.of(),
				"1.5.0-rc1");
		assertThat(v.accepte()).isFalse();
	}

	private static ManifestePlugin chaud(String version) {
		return new ManifestePlugin("p", version, "0.0.0", null, ModeMiseAJour.CHAUD, "pk", "pk.C", List.of(),
				List.of());
	}

	@Test
	void changement_majeur_impose_la_mise_a_jour_du_coeur_meme_declare_chaud() {
		var v = VerificateurMiseAJour.verifier(chaud("2.0.0"), Map.of(), Set.of(), "1.0.0", "1.4.2");
		assertThat(v.accepte()).isFalse();
		assertThat(v.raisons()).anyMatch(r -> r.contains("majeure") && r.contains("mise à jour du cœur"));
	}

	@Test
	void changement_mineur_a_chaud_accepte() {
		var v = VerificateurMiseAJour.verifier(chaud("1.5.0"), Map.of(), Set.of(), "1.0.0", "1.4.2");
		assertThat(v.accepte()).isTrue();
	}

	@Test
	void premiere_installation_sans_version_installee_ignore_le_majeur() {
		var v = VerificateurMiseAJour.verifier(chaud("2.0.0"), Map.of(), Set.of(), "1.0.0", null);
		assertThat(v.accepte()).isTrue();
	}

	@Test
	void mise_a_jour_declaree_coeur_refusee_a_chaud() {
		var v = VerificateurMiseAJour.verifier(manifeste(ModeMiseAJour.COEUR, "1.0.0", null), Map.of(), Set.of(),
				"1.5.0", "1.0.0");
		assertThat(v.accepte()).isFalse();
		assertThat(v.raisons()).anyMatch(r -> r.contains("mise à jour du cœur"));
	}

	@Test
	void dependances_satisfaites() {
		assertThat(VerificateurMiseAJour.verifierDependances(List.of("contacts>=1.2.0", "societes"),
				Map.of("contacts", "1.10.0", "societes", "0.1.0"))).isEmpty();
	}

	@Test
	void dependance_absente_refusee() {
		assertThat(VerificateurMiseAJour.verifierDependances(List.of("contacts>=1.2.0"), Map.of()))
			.singleElement()
			.asString()
			.contains("contacts")
			.contains("absent");
	}

	@Test
	void dependance_trop_ancienne_refusee() {
		assertThat(VerificateurMiseAJour.verifierDependances(List.of("contacts >= 1.2.0"),
				Map.of("contacts", "1.2.0-rc1")))
			.singleElement()
			.asString()
			.contains("contacts")
			.contains("1.2.0-rc1");
	}

	@Test
	void dependance_mal_formee_refusee() {
		assertThat(VerificateurMiseAJour.verifierDependances(List.of("Contacts=>1"), Map.of("contacts", "1.0.0")))
			.singleElement()
			.asString()
			.contains("Contacts=>1");
	}

	@Test
	void coeur_max_egal_accepte() {
		var v = VerificateurMiseAJour.verifier(manifeste(ModeMiseAJour.COEUR, "1.0.0", "1.5.0"), Map.of(), Set.of(),
				"1.5.0");
		assertThat(v.accepte()).isTrue();
	}
}

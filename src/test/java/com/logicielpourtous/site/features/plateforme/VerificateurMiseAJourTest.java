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
	void coeur_max_egal_accepte() {
		var v = VerificateurMiseAJour.verifier(manifeste(ModeMiseAJour.COEUR, "1.0.0", "1.5.0"), Map.of(), Set.of(),
				"1.5.0");
		assertThat(v.accepte()).isTrue();
	}
}

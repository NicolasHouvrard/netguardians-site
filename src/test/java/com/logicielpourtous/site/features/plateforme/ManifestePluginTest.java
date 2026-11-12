package com.logicielpourtous.site.features.plateforme;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.logicielpourtous.site.sdk.ManifestePlugin;
import com.logicielpourtous.site.sdk.ModeMiseAJour;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;

class ManifestePluginTest {

	private static Properties completes() {
		Properties p = new Properties();
		p.setProperty("nom", "facturation");
		p.setProperty("version", "1.2.0");
		p.setProperty("coeur-min", "0.0.1");
		p.setProperty("coeur-max", "0.9.0");
		p.setProperty("mise-a-jour", "chaud");
		p.setProperty("paquet", "com.exemple.facturation");
		p.setProperty("configuration", "com.exemple.facturation.FacturationConfiguration");
		p.setProperty("dependances", "contacts, societes");
		p.setProperty("tables-referencees", "users,societes");
		return p;
	}

	@Test
	void depuis_lit_toutes_les_cles() {
		ManifestePlugin m = ManifestePlugin.depuis(completes());

		assertThat(m.nom()).isEqualTo("facturation");
		assertThat(m.version()).isEqualTo("1.2.0");
		assertThat(m.coeurMin()).isEqualTo("0.0.1");
		assertThat(m.coeurMax()).isEqualTo("0.9.0");
		assertThat(m.miseAJour()).isEqualTo(ModeMiseAJour.CHAUD);
		assertThat(m.paquet()).isEqualTo("com.exemple.facturation");
		assertThat(m.configuration()).isEqualTo("com.exemple.facturation.FacturationConfiguration");
		assertThat(m.dependances()).isEqualTo(List.of("contacts", "societes"));
		assertThat(m.tablesReferencees()).isEqualTo(List.of("users", "societes"));
	}

	@Test
	void depuis_refuse_une_cle_obligatoire_absente() {
		Properties p = completes();
		p.remove("version");

		assertThatThrownBy(() -> ManifestePlugin.depuis(p))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("version");
	}

	@Test
	void depuis_refuse_un_nom_qui_n_est_pas_un_identifiant_sur() {
		for (String nom : List.of("Facturation", "factu-ration", "1facturation", "a;drop schema public", "a.b", "_a",
				"a".repeat(41), "factur ation")) {
			Properties p = completes();
			p.setProperty("nom", nom);

			assertThatThrownBy(() -> ManifestePlugin.depuis(p)).as(nom)
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("nom");
		}
	}

	@Test
	void constructeur_refuse_aussi_un_nom_invalide() {
		assertThatThrownBy(() -> new ManifestePlugin("Mauvais-Nom", "1.0.0", "0.0.0", null, ModeMiseAJour.CHAUD, "p",
				"p.C", List.of(), List.of()))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("Mauvais-Nom");
		assertThatThrownBy(() -> new ManifestePlugin(null, "1.0.0", "0.0.0", null, ModeMiseAJour.CHAUD, "p", "p.C",
				List.of(), List.of()))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void depuis_accepte_un_nom_valide() {
		for (String nom : List.of("a", "facturation_v2", "a".repeat(40), "x9")) {
			Properties p = completes();
			p.setProperty("nom", nom);

			assertThat(ManifestePlugin.depuis(p).nom()).isEqualTo(nom);
		}
	}

	@Test
	void depuis_refuse_mise_a_jour_inconnue() {
		Properties p = completes();
		p.setProperty("mise-a-jour", "x");

		assertThatThrownBy(() -> ManifestePlugin.depuis(p)).isInstanceOf(IllegalArgumentException.class);
	}
}

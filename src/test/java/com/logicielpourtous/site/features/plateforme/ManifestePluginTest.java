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
	void depuis_refuse_mise_a_jour_inconnue() {
		Properties p = completes();
		p.setProperty("mise-a-jour", "x");

		assertThatThrownBy(() -> ManifestePlugin.depuis(p)).isInstanceOf(IllegalArgumentException.class);
	}
}

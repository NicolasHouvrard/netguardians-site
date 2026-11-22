package com.logicielpourtous.site.features.plateforme;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.logicielpourtous.site.sdk.BlocFiche;
import com.logicielpourtous.site.sdk.EntreeNavigation;
import com.logicielpourtous.site.sdk.ManifestePlugin;
import com.logicielpourtous.site.sdk.ModeMiseAJour;
import com.logicielpourtous.site.sdk.ZoneNavigation;
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
				"p.C", List.of(), List.of(), List.of(), List.of()))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("Mauvais-Nom");
		assertThatThrownBy(() -> new ManifestePlugin(null, "1.0.0", "0.0.0", null, ModeMiseAJour.CHAUD, "p", "p.C",
				List.of(), List.of(), List.of(), List.of()))
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
	void depuis_lit_les_entrees_et_les_blocs_dans_l_ordre() {
		Properties p = completes();
		p.setProperty("entree.1", "CLIENT|Mes factures|receipt|/client/factures|factures.voir");
		p.setProperty("entree.2", "ADMIN|Réglages|gear|/admin/reglages|reglages.gerer");
		p.setProperty("bloc.1", "factures|Factures|/partenaire/clients/factures|factures.voir");

		ManifestePlugin m = ManifestePlugin.depuis(p);

		assertThat(m.entrees()).containsExactly(
				new EntreeNavigation(ZoneNavigation.CLIENT, "Mes factures", "receipt", "/client/factures",
						"factures.voir"),
				new EntreeNavigation(ZoneNavigation.ADMIN, "Réglages", "gear", "/admin/reglages",
						"reglages.gerer"));
		assertThat(m.blocs()).containsExactly(
				new BlocFiche("factures", "Factures", "/partenaire/clients/factures", "factures.voir"));
	}

	@Test
	void depuis_sans_entree_ni_bloc_donne_des_listes_vides() {
		ManifestePlugin m = ManifestePlugin.depuis(completes());

		assertThat(m.entrees()).isEmpty();
		assertThat(m.blocs()).isEmpty();
	}

	@Test
	void depuis_refuse_une_zone_inconnue() {
		Properties p = completes();
		p.setProperty("entree.1", "LUNE|Libellé|icone|/x|droit.x");

		assertThatThrownBy(() -> ManifestePlugin.depuis(p))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("LUNE");
	}

	@Test
	void depuis_refuse_un_chemin_qui_ne_commence_pas_par_un_slash() {
		Properties p = completes();
		p.setProperty("entree.1", "CLIENT|Libellé|icone|client/x|droit.x");
		assertThatThrownBy(() -> ManifestePlugin.depuis(p)).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("chemin");

		Properties q = completes();
		q.setProperty("bloc.1", "factures|Factures|partenaire/x|droit.x");
		assertThatThrownBy(() -> ManifestePlugin.depuis(q)).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("chemin");
	}

	@Test
	void depuis_refuse_un_droit_invalide() {
		for (String droit : List.of("Droit", "1droit", "a-b", "a b", "a".repeat(61), "")) {
			Properties p = completes();
			p.setProperty("entree.1", "CLIENT|Libellé|icone|/x|" + droit);
			assertThatThrownBy(() -> ManifestePlugin.depuis(p)).as(droit)
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("droit");
		}
	}

	@Test
	void depuis_refuse_un_id_de_bloc_invalide() {
		for (String id : List.of("Factures", "1a", "a-b", "a".repeat(41))) {
			Properties p = completes();
			p.setProperty("bloc.1", id + "|Factures|/x|droit.x");
			assertThatThrownBy(() -> ManifestePlugin.depuis(p)).as(id)
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("id de bloc");
		}
	}

	@Test
	void depuis_refuse_une_numerotation_non_contigue() {
		Properties p = completes();
		p.setProperty("entree.1", "CLIENT|A|i|/a|droit.a");
		p.setProperty("entree.3", "CLIENT|B|i|/b|droit.b");
		assertThatThrownBy(() -> ManifestePlugin.depuis(p)).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("entree.3");

		Properties q = completes();
		q.setProperty("bloc.2", "factures|Factures|/x|droit.x");
		assertThatThrownBy(() -> ManifestePlugin.depuis(q)).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("bloc.2");

		for (String cle : List.of("entree.x", "entree.0", "entree.01")) {
			Properties r = completes();
			r.setProperty("entree.1", "CLIENT|A|i|/a|droit.a");
			r.setProperty(cle, "CLIENT|B|i|/b|droit.b");
			assertThatThrownBy(() -> ManifestePlugin.depuis(r)).as(cle)
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining(cle);
		}
	}

	@Test
	void depuis_refuse_une_entree_mal_formee() {
		Properties p = completes();
		p.setProperty("entree.1", "CLIENT|Libellé|/x");

		assertThatThrownBy(() -> ManifestePlugin.depuis(p)).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("entree.1");
	}

	@Test
	void depuis_refuse_mise_a_jour_inconnue() {
		Properties p = completes();
		p.setProperty("mise-a-jour", "x");

		assertThatThrownBy(() -> ManifestePlugin.depuis(p)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void entree_a_5_champs_garde_la_couleur_primaire_et_6_champs_lit_la_couleur() {
		Properties p = completes();
		p.setProperty("entree.1", "CLIENT|Mes factures|receipt|/client/factures|factures.voir");
		p.setProperty("entree.2", "CLIENT|Clients|people|/clients|clients.voir|orange");

		ManifestePlugin m = ManifestePlugin.depuis(p);

		assertThat(m.entrees().get(0).couleur()).isEqualTo("primaire");
		assertThat(m.entrees().get(1).couleur()).isEqualTo("orange");
		assertThat(ManifestePlugin.COULEURS_TUILE).containsExactlyInAnyOrder("primaire", "violet", "sarcelle",
				"orange", "ambre", "rose", "ardoise", "vert");
		assertThat(new EntreeNavigation(ZoneNavigation.CLIENT, "L", "i", "/x", "a.b").couleur())
			.isEqualTo("primaire");
	}

	@org.junit.jupiter.params.ParameterizedTest
	@org.junit.jupiter.params.provider.ValueSource(strings = { "CLIENT|L|people|/x|a.b|red",
			"CLIENT|L|people|/x|a.b|#fff", "CLIENT|L|people|/x|a.b|bg-orange-500", "CLIENT|L|<script>|/x|a.b",
			"CLIENT|L|People|/x|a.b", "CLIENT|L|a b|/x|a.b",
			"CLIENT|L|aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa|/x|a.b", "CLIENT|L|people|/x|a.b|orange|extra",
			"CLIENT|L||/x|a.b" })
	void entree_refuse_couleur_icone_ou_nombre_de_champs_invalides(String valeur) {
		Properties p = completes();
		p.setProperty("entree.1", valeur);

		assertThatThrownBy(() -> ManifestePlugin.depuis(p)).isInstanceOf(IllegalArgumentException.class)
			.satisfies(e -> assertThat(e.getMessage().length()).isLessThan(400));
	}

	@Test
	void un_message_d_erreur_ne_reprend_pas_une_valeur_enorme() {
		Properties p = completes();
		p.setProperty("entree.1", "CLIENT|L|" + "a".repeat(5000) + "|/x|a.b");

		assertThatThrownBy(() -> ManifestePlugin.depuis(p)).isInstanceOf(IllegalArgumentException.class)
			.satisfies(e -> assertThat(e.getMessage().length()).isLessThan(400));
	}
}

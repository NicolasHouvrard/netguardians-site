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

	// ---- ext.N : emplacements d'extension ----

	@Test
	void ext_valide_est_lue_et_un_manifeste_sans_ext_reste_valide() {
		assertThat(ManifestePlugin.depuis(completes()).extensions()).isEmpty();
		Properties p = completes();
		p.setProperty("ext.1", "clients.fiche.onglets|Historique|/historique?client={id}|historique.lire|20");
		p.setProperty("ext.2", "demo.fiche.boutons| Export |/export|export.lancer|0");

		ManifestePlugin m = ManifestePlugin.depuis(p);

		assertThat(m.extensions()).containsExactly(
				new com.logicielpourtous.site.sdk.ExtensionUi("clients.fiche.onglets", "Historique",
						"/historique?client={id}", "historique.lire", 20),
				new com.logicielpourtous.site.sdk.ExtensionUi("demo.fiche.boutons", "Export", "/export",
						"export.lancer", 0));
	}

	@Test
	void constructeur_sans_extensions_donne_une_liste_vide() {
		ManifestePlugin m = new ManifestePlugin("p", "1.0.0", "0.0.0", null, ModeMiseAJour.CHAUD, "p", "p.C", List.of(),
				List.of(), List.of(), List.of());
		assertThat(m.extensions()).isEmpty();
	}

	@org.junit.jupiter.params.ParameterizedTest
	@org.junit.jupiter.params.provider.ValueSource(strings = {
			"clients|L|/x|a.b|1", // emplacement sans point
			"Clients.fiche|L|/x|a.b|1", "clients..fiche|L|/x|a.b|1", "clients.fiche.|L|/x|a.b|1",
			"clients.fiche|L|/x|Droit|1", // droit invalide
			"clients.fiche|L|/x||1", "clients.fiche|L|/x|a.b|-1", "clients.fiche|L|/x|a.b|abc",
			"clients.fiche|L|/x|a.b|10000", "clients.fiche|L|/x|a.b|+5", "clients.fiche|L|/x|a.b|", // ordre
			"clients.fiche|L|/x|a.b| 5 x",
			"clients.fiche|L|x|a.b|1", // chemin sans /
			"clients.fiche|L|/x/{id}/{id}|a.b|1", // deux marqueurs
			"clients.fiche|L|/x<y|a.b|1", "clients.fiche|L|/x y|a.b|1", "clients.fiche|L|/x;y|a.b|1",
			"clients.fiche|L|/x%2e|a.b|1", "clients.fiche|L|/x#a|a.b|1", "clients.fiche|L|/../x|a.b|1",
			"clients.fiche|L|/x//y|a.b|1", "clients.fiche|L|//x|a.b|1", "clients.fiche|L|/x?a=1?b=2|a.b|1",
			"clients.fiche|L|/x?a/b|a.b|1", "clients.fiche|L|/x?a.b=1|a.b|1",
			"clients.fiche||/x|a.b|1", "clients.fiche|   |/x|a.b|1", // libellé vide
			"clients.fiche|L|/x|a.b", // 4 champs
			"clients.fiche|L|/x|a.b|1|extra" })
	void ext_refuse_les_valeurs_invalides(String valeur) {
		Properties p = completes();
		p.setProperty("ext.1", valeur);

		assertThatThrownBy(() -> ManifestePlugin.depuis(p)).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("ext.1")
			.satisfies(e -> assertThat(e.getMessage().length()).isLessThan(400));
	}

	@Test
	void ext_refuse_libelle_trop_long_ou_avec_caractere_de_controle_et_valeurs_trop_longues() {
		for (String valeur : List.of("clients.fiche|" + "a".repeat(61) + "|/x|a.b|1",
				"clients.fiche|a\u0007b|/x|a.b|1", "clients.fiche|a\tb|/x|a.b|1",
				"clients.fiche|L|/" + "a".repeat(200) + "|a.b|1",
				"a." + "b".repeat(80) + "|L|/x|a.b|1")) {
			Properties p = completes();
			p.setProperty("ext.1", valeur);
			assertThatThrownBy(() -> ManifestePlugin.depuis(p)).as(valeur.length() + "").isInstanceOf(
					IllegalArgumentException.class).satisfies(e -> assertThat(e.getMessage().length()).isLessThan(400));
		}
	}

	@Test
	void ext_chemin_a_la_limite_et_avec_un_seul_marqueur_est_accepte() {
		Properties p = completes();
		p.setProperty("ext.1", "a.b|L|/" + "a".repeat(199) + "|a.b|9999");
		p.setProperty("ext.2", "a.b|" + "l".repeat(60) + "|/x/{id}/y?c={id}|a.b|0".replace("?c={id}", "?c=2"));
		assertThat(ManifestePlugin.depuis(p).extensions()).hasSize(2);
	}

	@Test
	void ext_refuse_une_numerotation_non_contigue() {
		Properties p = completes();
		p.setProperty("ext.1", "a.b|L|/x|a.b|1");
		p.setProperty("ext.3", "a.b|L|/y|a.b|1");
		assertThatThrownBy(() -> ManifestePlugin.depuis(p)).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("ext.3");
		for (String cle : List.of("ext.x", "ext.0", "ext.01")) {
			Properties r = completes();
			r.setProperty("ext.1", "a.b|L|/x|a.b|1");
			r.setProperty(cle, "a.b|L|/y|a.b|1");
			assertThatThrownBy(() -> ManifestePlugin.depuis(r)).as(cle).isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining(cle);
		}
	}

	@Test
	void les_messages_chemin_droit_et_id_de_bloc_ne_reprennent_pas_une_valeur_enorme() {
		String enorme = "z".repeat(5000);
		Properties a = completes();
		a.setProperty("entree.1", "CLIENT|L|icone|" + enorme + "|a.b");
		Properties b = completes();
		b.setProperty("entree.1", "CLIENT|L|icone|/x|" + enorme);
		Properties c = completes();
		c.setProperty("bloc.1", enorme + "|L|/x|a.b");
		for (Properties p : List.of(a, b, c)) {
			assertThatThrownBy(() -> ManifestePlugin.depuis(p)).isInstanceOf(IllegalArgumentException.class)
				.satisfies(e -> assertThat(e.getMessage().length()).isLessThan(400));
		}
	}

	@Test
	void un_message_d_erreur_ne_reprend_pas_une_valeur_enorme() {
		Properties p = completes();
		p.setProperty("entree.1", "CLIENT|L|" + "a".repeat(5000) + "|/x|a.b");

		assertThatThrownBy(() -> ManifestePlugin.depuis(p)).isInstanceOf(IllegalArgumentException.class)
			.satisfies(e -> assertThat(e.getMessage().length()).isLessThan(400));
	}
}

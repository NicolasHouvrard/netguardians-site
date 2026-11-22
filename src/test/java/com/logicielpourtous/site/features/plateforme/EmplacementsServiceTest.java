package com.logicielpourtous.site.features.plateforme;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.logicielpourtous.site.features.plateforme.EmplacementsService.ElementAffiche;
import com.logicielpourtous.site.features.plateforme.EtatPlugin.Etat;
import com.logicielpourtous.site.sdk.ExtensionUi;
import com.logicielpourtous.site.sdk.IdentiteContrat;
import com.logicielpourtous.site.sdk.IdentiteContrat.IdentiteCourante;
import com.logicielpourtous.site.sdk.ManifestePlugin;
import com.logicielpourtous.site.sdk.ModeMiseAJour;

/** Éléments d'écran déclarés par les plugins ACTIFS : droits, ordre, identifiant d'entité, échec fermé. */
class EmplacementsServiceTest {

	private static final long USER = 7L;

	private static final long SOCIETE = 42L;

	private static final String SLOT = "clients.fiche.onglets";

	private PluginManager manager;

	private ControleDroits droits;

	private IdentiteContrat identite;

	private EmplacementsService service;

	private final Map<String, PluginGeneration> actifs = new HashMap<>();

	private final Map<String, Etat> etats = new HashMap<>();

	private final Set<String> accordes = new HashSet<>();

	@BeforeEach
	void preparer() {
		manager = mock(PluginManager.class);
		droits = mock(ControleDroits.class);
		identite = mock(IdentiteContrat.class);
		when(droits.autorise(anyLong(), anyLong(), anyString())).thenAnswer(inv -> inv.<Long>getArgument(0) == USER
				&& inv.<Long>getArgument(1) == SOCIETE && accordes.contains(inv.<String>getArgument(2)));
		when(manager.actifs()).thenAnswer(inv -> Map.copyOf(actifs));
		when(manager.etats()).thenAnswer(inv -> etats.entrySet().stream()
				.map(e -> new EtatPlugin(e.getKey(), "1.0.0", e.getValue())).toList());
		when(identite.courante()).thenReturn(new IdentiteCourante(USER, Set.of(), SOCIETE, 1L, "PARTNER"));
		service = new EmplacementsService(manager, droits, identite);
	}

	private void plugin(String nom, ExtensionUi... extensions) {
		PluginGeneration gen = new PluginGeneration(nom, "1.0.0", Path.of(nom + ".jar"));
		gen.manifeste = new ManifestePlugin(nom, "1.0.0", "1.0.0", null, ModeMiseAJour.CHAUD, "p", "c", List.of(),
				List.of(), List.of(), List.of(), List.of(extensions));
		actifs.put(nom, gen);
		etats.put(nom, Etat.ACTIF);
	}

	private static ExtensionUi ext(String libelle, String chemin, String droit, int ordre) {
		return new ExtensionUi(SLOT, libelle, chemin, droit, ordre);
	}

	@Test
	void deux_plugins_sont_tries_par_ordre_puis_par_plugin_puis_par_declaration() {
		plugin("beta", ext("B10", "/b", "d", 10), ext("B5b", "/b5b", "d", 5));
		plugin("alpha", ext("A10", "/a", "d", 10), ext("A5", "/a5", "d", 5));
		accordes.add("d");

		assertThat(service.elements(SLOT, null, null)).extracting(ElementAffiche::libelle)
			.containsExactly("A5", "B5b", "A10", "B10");
		assertThat(service.elements(SLOT, null, null).get(0)).isEqualTo(new ElementAffiche("A5", "/p/alpha/a5",
				"alpha", 5));
	}

	@Test
	void seul_l_emplacement_demande_est_renvoye() {
		plugin("alpha", ext("A", "/a", "d", 1), new ExtensionUi("autre.slot", "Z", "/z", "d", 1));
		accordes.add("d");

		assertThat(service.elements(SLOT, null, null)).extracting(ElementAffiche::libelle).containsExactly("A");
		assertThat(service.elements("inconnu.slot", null, null)).isEmpty();
		assertThat(service.elements(null, null, null)).isEmpty();
	}

	@Test
	void un_utilisateur_sans_le_droit_ne_voit_pas_l_element() {
		plugin("alpha", ext("A", "/a", "alpha.voir", 1));
		plugin("beta", ext("B", "/b", "beta.voir", 2));
		accordes.add("beta.voir");

		assertThat(service.elements(SLOT, null, null)).extracting(ElementAffiche::libelle).containsExactly("B");
	}

	@Test
	void la_societe_demandee_est_verifiee_par_les_droits_et_sans_identite_rien() {
		plugin("alpha", ext("A", "/a", "d", 1));
		accordes.add("d");

		assertThat(service.elements(SLOT, SOCIETE, null)).hasSize(1);
		assertThat(service.elements(SLOT, 999L, null)).isEmpty();
		when(identite.courante()).thenReturn(new IdentiteCourante(USER, Set.of(), null, 1L, "ESN"));
		assertThat(service.elements(SLOT, null, null)).isEmpty();
		assertThat(service.elements(SLOT, SOCIETE, null)).hasSize(1);
		when(identite.courante()).thenReturn(null);
		assertThat(service.elements(SLOT, SOCIETE, null)).isEmpty();
		when(identite.courante()).thenThrow(new IllegalStateException("aucune"));
		assertThat(service.elements(SLOT, SOCIETE, null)).isEmpty();
	}

	@Test
	void un_plugin_non_actif_ou_desinstalle_ne_contribue_plus() {
		plugin("alpha", ext("A", "/a", "d", 1));
		plugin("beta", ext("B", "/b", "d", 2));
		accordes.add("d");
		assertThat(service.elements(SLOT, null, null)).hasSize(2);

		etats.put("beta", Etat.MAINTENANCE);
		assertThat(service.elements(SLOT, null, null)).extracting(ElementAffiche::plugin).containsExactly("alpha");

		actifs.remove("alpha");
		assertThat(service.elements(SLOT, null, null)).isEmpty();
	}

	@Test
	void mise_a_jour_a_chaud_les_nouveaux_elements_apparaissent_sans_cache() {
		plugin("alpha", ext("A", "/a", "d", 1));
		accordes.add("d");
		assertThat(service.elements(SLOT, null, null)).hasSize(1);

		plugin("alpha", ext("A", "/a", "d", 1), ext("A2", "/a2", "d", 2));
		assertThat(service.elements(SLOT, null, null)).extracting(ElementAffiche::libelle)
			.containsExactly("A", "A2");
	}

	@Test
	void l_identifiant_numerique_remplace_le_marqueur_et_sinon_l_element_est_omis() {
		plugin("alpha", ext("H", "/historique?client={id}", "d", 1), ext("S", "/statique", "d", 2));
		accordes.add("d");

		assertThat(service.elements(SLOT, null, "12")).extracting(ElementAffiche::url)
			.containsExactly("/p/alpha/historique?client=12", "/p/alpha/statique");
		assertThat(service.elements(SLOT, null, "123456789012345")).hasSize(2);
		for (String mauvais : new String[] { "1;2", "../x", "", "1234567890123456", " 12", "12 ", "-1", "1.5", "１２",
				"12\n", "%31", null }) {
			// l'élément sans marqueur ignore idEntite ; celui qui en demande un est omis
			assertThat(service.elements(SLOT, null, mauvais)).as("[" + mauvais + "]")
				.extracting(ElementAffiche::url)
				.containsExactly("/p/alpha/statique");
		}
	}

	@Test
	void une_exception_du_controle_de_droits_omet_l_element_sans_erreur() {
		plugin("alpha", ext("A", "/a", "boom", 1), ext("B", "/b", "d", 2));
		accordes.add("d");
		when(droits.autorise(anyLong(), anyLong(), org.mockito.ArgumentMatchers.eq("boom")))
			.thenThrow(new IllegalStateException("panne"));

		assertThat(service.elements(SLOT, null, null)).extracting(ElementAffiche::libelle).containsExactly("B");
	}

	@Test
	void un_manifeste_illisible_ou_incoherent_est_ignore_sans_erreur() {
		plugin("alpha", ext("A", "/a", "d", 1));
		PluginGeneration pourri = mock(PluginGeneration.class);
		when(pourri.manifeste()).thenThrow(new IllegalStateException("illisible"));
		actifs.put("zeta", pourri);
		etats.put("zeta", Etat.ACTIF);
		plugin("beta", ext("B", "/b", "d", 2));
		actifs.get("beta").manifeste = new ManifestePlugin("autrenom", "1.0.0", "1.0.0", null, ModeMiseAJour.CHAUD,
				"p", "c", List.of(), List.of(), List.of(), List.of(), List.of(ext("X", "/x", "d", 1)));
		accordes.add("d");

		assertThat(service.elements(SLOT, null, null)).extracting(ElementAffiche::libelle).containsExactly("A");
	}

	@Test
	void une_url_non_sure_n_est_jamais_renvoyee() {
		// ces chemins ne passent pas le manifeste mais le constructeur canonique ne les contrôle pas : seconde barrière
		plugin("alpha", ext("1", "//evil.example/x", "d", 1), ext("2", "/x/../y", "d", 2), ext("3", "/x\\y", "d", 3),
				ext("4", "/x y", "d", 4), ext("5", "/x%2e", "d", 5), ext("6", "/x#a", "d", 6),
				ext("7", "x", "d", 7), ext("8", "/x\u0007", "d", 8), ext("9", "/ok", "d", 9), ext("10", null, "d", 10));
		accordes.add("d");

		assertThat(service.elements(SLOT, null, null)).extracting(ElementAffiche::url).containsExactly("/p/alpha/ok");
	}

	@Test
	void le_libelle_vide_ou_absent_est_omis() {
		plugin("alpha", ext(null, "/a", "d", 1), ext("", "/b", "d", 2), ext("ok", "/c", "d", 3));
		accordes.add("d");

		assertThat(service.elements(SLOT, null, null)).extracting(ElementAffiche::libelle).containsExactly("ok");
	}
}

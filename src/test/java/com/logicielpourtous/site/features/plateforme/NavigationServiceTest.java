package com.logicielpourtous.site.features.plateforme;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.logicielpourtous.site.features.plateforme.EtatPlugin.Etat;
import com.logicielpourtous.site.features.plateforme.NavigationService.BlocAutorise;
import com.logicielpourtous.site.features.plateforme.NavigationService.Tuile;
import com.logicielpourtous.site.sdk.BlocFiche;
import com.logicielpourtous.site.sdk.EntreeNavigation;
import com.logicielpourtous.site.sdk.IdentiteContrat;
import com.logicielpourtous.site.sdk.IdentiteContrat.IdentiteCourante;
import com.logicielpourtous.site.sdk.ManifestePlugin;
import com.logicielpourtous.site.sdk.ModeMiseAJour;
import com.logicielpourtous.site.sdk.ZoneNavigation;

/** Navigation filtrée par les droits : plugins actifs, zone, droit de l'utilisateur sur la société. */
class NavigationServiceTest {

	private static final long USER = 7L;

	private static final long SOCIETE = 42L;

	private PluginManager manager;

	private ControleDroits droits;

	private IdentiteContrat identite;

	private NavigationService service;

	private final Map<String, PluginGeneration> actifs = new HashMap<>();

	private final Map<String, Etat> etats = new HashMap<>();

	private final Set<String> accordes = new java.util.HashSet<>();

	@BeforeEach
	void preparer() {
		manager = mock(PluginManager.class);
		droits = mock(ControleDroits.class);
		identite = mock(IdentiteContrat.class);
		// Seuls les droits de l'ensemble "accordes" sont accordés, à USER sur SOCIETE ; chaque test l'ajuste.
		when(droits.autorise(anyLong(), anyLong(), anyString())).thenAnswer(inv -> inv.<Long>getArgument(0) == USER
				&& inv.<Long>getArgument(1) == SOCIETE && accordes.contains(inv.<String>getArgument(2)));
		when(manager.actifs()).thenAnswer(inv -> Map.copyOf(actifs));
		when(manager.etats()).thenAnswer(inv -> etats.entrySet().stream()
				.map(e -> new EtatPlugin(e.getKey(), "1.0.0", e.getValue())).toList());
		when(identite.courante()).thenReturn(new IdentiteCourante(USER, Set.of(), SOCIETE, 1L, "PARTNER"));
		service = new NavigationService(manager, droits, identite);
	}

	private void plugin(String nom, List<EntreeNavigation> entrees, List<BlocFiche> blocs) {
		PluginGeneration gen = new PluginGeneration(nom, "1.0.0", Path.of(nom + ".jar"));
		gen.manifeste = new ManifestePlugin(nom, "1.0.0", "1.0.0", null, ModeMiseAJour.CHAUD, "p", "c", List.of(),
				List.of(), entrees, blocs);
		actifs.put(nom, gen);
		etats.put(nom, Etat.ACTIF);
	}

	private static EntreeNavigation entree(ZoneNavigation zone, String libelle, String droit) {
		return new EntreeNavigation(zone, libelle, "icone", "/" + libelle, droit);
	}

	@Test
	void deux_plugins_actifs_droit_du_premier_seulement_une_seule_tuile() {
		plugin("alpha", List.of(entree(ZoneNavigation.PARTENAIRE, "a", "alpha.voir")), List.of());
		plugin("beta", List.of(entree(ZoneNavigation.PARTENAIRE, "b", "beta.voir")), List.of());
		accordes.add("alpha.voir");

		List<Tuile> tuiles = service.tuiles(ZoneNavigation.PARTENAIRE, SOCIETE);

		assertThat(tuiles).containsExactly(new Tuile("alpha", "a", "icone", "/a", ZoneNavigation.PARTENAIRE, "1.0.0"));
	}

	@Test
	void filtre_par_zone() {
		plugin("alpha", List.of(entree(ZoneNavigation.PARTENAIRE, "p", "d"), entree(ZoneNavigation.CLIENT, "c", "d")),
				List.of());
		accordes.add("d");

		assertThat(service.tuiles(ZoneNavigation.CLIENT, SOCIETE)).extracting(Tuile::libelle).containsExactly("c");
		assertThat(service.tuiles(ZoneNavigation.ADMIN, SOCIETE)).isEmpty();
	}

	@Test
	void plugin_absent_de_actifs_pas_de_tuile() {
		plugin("alpha", List.of(entree(ZoneNavigation.PARTENAIRE, "a", "d")), List.of());
		accordes.add("d");
		actifs.clear();

		assertThat(service.tuiles(ZoneNavigation.PARTENAIRE, SOCIETE)).isEmpty();
	}

	@Test
	void plugin_en_maintenance_pas_de_tuile() {
		plugin("alpha", List.of(entree(ZoneNavigation.PARTENAIRE, "a", "d")), List.of());
		accordes.add("d");
		etats.put("alpha", Etat.MAINTENANCE);

		assertThat(service.tuiles(ZoneNavigation.PARTENAIRE, SOCIETE)).isEmpty();
	}

	@Test
	void plugin_sans_etat_connu_pas_de_tuile() {
		plugin("alpha", List.of(entree(ZoneNavigation.PARTENAIRE, "a", "d")), List.of());
		accordes.add("d");
		etats.clear();

		assertThat(service.tuiles(ZoneNavigation.PARTENAIRE, SOCIETE)).isEmpty();
	}

	@Test
	void societe_d_un_autre_utilisateur_liste_vide() {
		plugin("alpha", List.of(entree(ZoneNavigation.PARTENAIRE, "a", "d")), List.of());
		accordes.add("d");

		assertThat(service.tuiles(ZoneNavigation.PARTENAIRE, 999L)).isEmpty();
		assertThat(service.blocs(999L)).isEmpty();
	}

	@Test
	void societe_absente_utilise_celle_de_l_identite_et_null_donne_liste_vide() {
		plugin("alpha", List.of(entree(ZoneNavigation.PARTENAIRE, "a", "d")), List.of());
		accordes.add("d");

		assertThat(service.tuiles(ZoneNavigation.PARTENAIRE, null)).hasSize(1);

		when(identite.courante()).thenReturn(new IdentiteCourante(USER, Set.of(), null, 1L, "PARTNER"));
		assertThat(service.tuiles(ZoneNavigation.PARTENAIRE, null)).isEmpty();
		assertThat(service.blocs(null)).isEmpty();
	}

	@Test
	void identite_absente_echec_ferme_liste_vide() {
		plugin("alpha", List.of(entree(ZoneNavigation.PARTENAIRE, "a", "d")), List.of());
		accordes.add("d");
		when(identite.courante()).thenThrow(new IllegalStateException("pas authentifie"));

		assertThat(service.tuiles(ZoneNavigation.PARTENAIRE, SOCIETE)).isEmpty();
		assertThat(service.blocs(SOCIETE)).isEmpty();
		verify(droits, never()).autorise(any(), any(), any());
	}

	@Test
	void zone_publique_sans_controle_de_droit_les_autres_zones_controlees() {
		plugin("alpha", List.of(entree(ZoneNavigation.PUBLIQUE, "pub", "d"), entree(ZoneNavigation.CLIENT, "cli", "d")),
				List.of());

		assertThat(service.tuiles(ZoneNavigation.PUBLIQUE, SOCIETE)).extracting(Tuile::libelle).containsExactly("pub");
		assertThat(service.tuiles(ZoneNavigation.CLIENT, SOCIETE)).isEmpty();
	}

	@Test
	void tri_par_plugin_puis_ordre_de_declaration() {
		plugin("zeta", List.of(entree(ZoneNavigation.PARTENAIRE, "z1", "d")), List.of());
		plugin("alpha", List.of(entree(ZoneNavigation.PARTENAIRE, "a2", "d"), entree(ZoneNavigation.PARTENAIRE, "a1", "d")),
				List.of());
		accordes.add("d");

		assertThat(service.tuiles(ZoneNavigation.PARTENAIRE, SOCIETE)).extracting(Tuile::libelle)
				.containsExactly("a2", "a1", "z1");
	}

	@Test
	void manifeste_illisible_ou_incoherent_ignore_sans_casser_la_liste() {
		plugin("alpha", List.of(entree(ZoneNavigation.PARTENAIRE, "a", "d")), List.of());
		actifs.put("sansmanifeste", new PluginGeneration("sansmanifeste", "1.0.0", Path.of("x.jar")));
		etats.put("sansmanifeste", Etat.ACTIF);
		plugin("beta", List.of(entree(ZoneNavigation.PARTENAIRE, "b", "d")), List.of());
		actifs.get("beta").manifeste = new ManifestePlugin("autrenom", "1.0.0", "1.0.0", null, ModeMiseAJour.CHAUD,
				"p", "c", List.of(), List.of(), List.of(entree(ZoneNavigation.PARTENAIRE, "b", "d")), List.of());
		accordes.add("d");

		assertThat(service.tuiles(ZoneNavigation.PARTENAIRE, SOCIETE)).extracting(Tuile::plugin)
				.containsExactly("alpha");
	}

	@Test
	void relit_les_plugins_actifs_a_chaque_appel() {
		accordes.add("d");
		assertThat(service.tuiles(ZoneNavigation.PARTENAIRE, SOCIETE)).isEmpty();
		plugin("alpha", List.of(entree(ZoneNavigation.PARTENAIRE, "a", "d")), List.of());
		assertThat(service.tuiles(ZoneNavigation.PARTENAIRE, SOCIETE)).hasSize(1);
		actifs.clear();
		assertThat(service.tuiles(ZoneNavigation.PARTENAIRE, SOCIETE)).isEmpty();
	}

	@Test
	void blocs_autorises_filtres_par_droit_avec_version() {
		plugin("alpha", List.of(), List.of(new BlocFiche("notes", "Notes", "/notes", "alpha.notes"),
				new BlocFiche("secret", "Secret", "/secret", "alpha.secret")));
		plugin("beta", List.of(), List.of(new BlocFiche("autre", "Autre", "/autre", "beta.voir")));
		accordes.add("alpha.notes");
		etats.put("beta", Etat.MAINTENANCE);
		accordes.add("beta.voir");

		assertThat(service.blocs(SOCIETE))
				.containsExactly(new BlocAutorise("alpha", "notes", "Notes", "/notes", "1.0.0"));
	}

	@Test
	void zone_nulle_refusee() {
		assertThatThrownBy(() -> service.tuiles(null, SOCIETE)).isInstanceOf(IllegalArgumentException.class);
	}

}

package com.logicielpourtous.site.features.plateforme;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.logicielpourtous.site.features.auth.CustomUserDetails;
import com.logicielpourtous.site.features.commun.Tenant;
import com.logicielpourtous.site.features.commun.TenantRepository;
import com.logicielpourtous.site.features.plateforme.NavigationService.Tuile;
import com.logicielpourtous.site.features.role.Role;
import com.logicielpourtous.site.features.user.User;
import com.logicielpourtous.site.sdk.EntreeNavigation;
import com.logicielpourtous.site.sdk.IdentiteContrat;
import com.logicielpourtous.site.sdk.ManifestePlugin;
import com.logicielpourtous.site.sdk.ModeMiseAJour;
import com.logicielpourtous.site.sdk.ZoneNavigation;

/**
 * Accueil à tuiles sur l'application complète (vraie chaîne de sécurité, plateforme active) ; le service de navigation
 * est simulé (son filtrage par droits est couvert par NavigationServiceTest).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AccueilControllerTest {

	@DynamicPropertySource
	static void proprietes(DynamicPropertyRegistry registre) {
		registre.add("app.plateforme.actif", () -> "true");
		registre.add("app.plateforme.signature-obligatoire", () -> "false");
	}

	@Autowired
	private MockMvc mvc;

	@Autowired
	private TenantRepository tenantRepository;

	@MockitoBean
	private NavigationService navigation;

	private static Tuile tuile(String libelle, String icone, String couleur) {
		return new Tuile("clients", libelle, icone, "/clients", ZoneNavigation.PARTENAIRE, "1.0.0", couleur);
	}

	private String page(String url, boolean htmx) throws Exception {
		var req = get(url).with(user(compte("USER")));
		if (htmx) {
			req = req.header("HX-Request", "true");
		}
		return mvc.perform(req).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
	}

	@Test
	void a_anonyme_redirige_vers_login_et_pas_200() throws Exception {
		mvc.perform(get("/accueil")).andExpect(status().isFound())
				.andExpect(header().string("Location", org.hamcrest.Matchers.endsWith("/login")));
		mvc.perform(get("/accueil").header("HX-Request", "true")).andExpect(status().isOk())
				.andExpect(header().string("HX-Redirect", "/login"));
	}

	@Test
	void b_la_grille_contient_le_lien_de_la_tuile() throws Exception {
		when(navigation.tuilesAutorisees(null)).thenReturn(List.of(tuile("Clients", "people", "orange")));
		String html = page("/accueil", false);
		assertThat(html).contains("id=\"grille-apps\"").contains("href=\"/p/clients/clients\"")
				.contains("hx-boost=\"true\"").contains("autofocus").contains("hx-get=\"/accueil\"")
				.contains("hx-push-url=\"false\"");
	}

	@Test
	void c_la_tuile_admin_n_est_donnee_qu_a_l_admin_du_maitre() {
		PluginManager manager = mock(PluginManager.class);
		PluginGeneration gen = new PluginGeneration("partenaires", "1.0.0", java.nio.file.Path.of("p.jar"));
		gen.manifeste = new ManifestePlugin("partenaires", "1.0.0", "1.0.0", null, ModeMiseAJour.CHAUD, "p", "c",
				List.of(), List.of(), List.of(new EntreeNavigation(ZoneNavigation.ADMIN, "Partenaires", "apartment",
						"/partenaires", "partenaires.gerer", "violet")),
				List.of());
		when(manager.actifs()).thenReturn(Map.of("partenaires", gen));
		when(manager.etats()).thenReturn(List.of(new EtatPlugin("partenaires", "1.0.0", EtatPlugin.Etat.ACTIF)));
		IdentiteContrat admin = mock(IdentiteContrat.class);
		when(admin.courante())
				.thenReturn(new IdentiteContrat.IdentiteCourante(1L, Set.of("ROLE_ADMIN"), null, 1L, "MASTER"));
		IdentiteContrat ordinaire = mock(IdentiteContrat.class);
		when(ordinaire.courante())
				.thenReturn(new IdentiteContrat.IdentiteCourante(2L, Set.of("ROLE_USER"), null, 1L, "MASTER"));
		var pourAdmin = new NavigationService(manager, mock(ControleDroits.class), admin,
				mock(ControleCapacites.class));
		var pourUser = new NavigationService(manager, mock(ControleDroits.class), ordinaire,
				mock(ControleCapacites.class));

		assertThat(pourAdmin.tuilesAutorisees(null)).extracting(Tuile::libelle, Tuile::couleur, Tuile::url)
				.containsExactly(org.assertj.core.groups.Tuple.tuple("Partenaires", "violet",
						"/p/partenaires/partenaires"));
		assertThat(pourUser.tuilesAutorisees(null)).isEmpty();
	}

	@Test
	void d_aucune_tuile_affiche_le_message() throws Exception {
		when(navigation.tuilesAutorisees(null)).thenReturn(List.of());
		// Plus aucun compte connecte n'a une grille vide (tuile « Mon espace ») : le message vient d'une recherche sans resultat.
		assertThat(page("/accueil?q=zzzintrouvable", false)).contains("Aucune application disponible");
	}

	@Test
	void e_service_en_echec_affiche_le_message_en_200() throws Exception {
		when(navigation.tuilesAutorisees(null)).thenThrow(new IllegalStateException("boum secret"));
		String html = page("/accueil", false);
		assertThat(html).contains("Espace ESN").doesNotContain("boum secret");
	}

	@Test
	void f_requete_htmx_renvoie_la_grille_seule() throws Exception {
		when(navigation.tuilesAutorisees(null)).thenReturn(List.of(tuile("Clients", "people", "orange")));
		String html = page("/accueil", true);
		assertThat(html).contains("id=\"grille-apps\"").contains("Clients").doesNotContain("<html")
				.doesNotContain("<head");
	}

	@Test
	void f2_navigation_boostee_ou_restauration_d_historique_recoit_la_page_complete() throws Exception {
		when(navigation.tuilesAutorisees(null)).thenReturn(List.of(tuile("Clients", "people", "orange")));
		for (String entete : new String[] { "HX-Boosted", "HX-History-Restore-Request" }) {
			String html = mvc.perform(get("/accueil").with(user(compte("USER"))).header("HX-Request", "true")
					.header(entete, "true")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
			assertThat(html).as(entete).contains("<html").contains("class=\"socle-topbar\"").contains("id=\"grille-apps\"");
		}
		// La recherche en direct (HX-Request seul) ne renvoie toujours que la grille.
		assertThat(page("/accueil", true)).doesNotContain("<html").contains("id=\"grille-apps\"");
	}

	@Test
	void h_la_tuile_porte_couleur_et_icone_sans_style_et_libelle_echappe() throws Exception {
		when(navigation.tuilesAutorisees(null)).thenReturn(List.of(tuile("<b>x</b>", "people", "orange")));
		String grille = page("/accueil", true);
		assertThat(grille).contains("app-couleur-orange").contains("bi-people").contains("&lt;b&gt;x&lt;/b&gt;")
				.doesNotContain("<b>x</b>").doesNotContain("style=");
	}

	@Test
	void i_la_recherche_ignore_casse_et_accents_et_echappe() throws Exception {
		when(navigation.tuilesAutorisees(null)).thenReturn(
				List.of(tuile("Facturation", "receipt", "vert"), tuile("Clients", "people", "orange")));
		String fac = page("/accueil?q=FAC", true);
		assertThat(fac).contains("Facturation").doesNotContain("Clients");
		assertThat(page("/accueil?q=facturatión", true)).contains("Facturation").doesNotContain("Clients");
		String script = page("/accueil?q=<script>", true);
		assertThat(script).contains("Aucune application disponible").doesNotContain("<script>")
				.doesNotContain("Facturation");
		assertThat(page("/accueil?q=<script>", false)).doesNotContain("<script>");
	}

	@Test
	void j_une_page_du_socle_sans_modele_affiche_le_menu_applications() throws Exception {
		when(navigation.tuilesAutorisees(null)).thenReturn(List.of(tuile("Facturation", "receipt", "vert")));
		String html = mvc.perform(get("/admin/socle").with(user(compte("ADMIN")))).andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		assertThat(html).contains("socle-apps-menu").contains("href=\"/p/clients/clients\"")
				.contains("Facturation");
	}

	private CustomUserDetails compte(String role) {
		Tenant tenant = new Tenant();
		tenant.setName("Test Tenant");
		tenant.setSchemaName("test_tenant_" + UUID.randomUUID());
		tenant = tenantRepository.save(tenant);

		User user = new User();
		user.setEmail("accueil-test-" + UUID.randomUUID() + "@logicielpourtous.com");
		user.setPassword("test");
		user.setFullName("Test " + role);
		user.setActive(true);
		user.setTenant(tenant);
		user.setRoles(Set.of(new Role(role, role, "ESN")));
		return new CustomUserDetails(user);
	}

}

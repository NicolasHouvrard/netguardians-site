package com.logicielpourtous.site.features.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.thymeleaf.context.WebContext;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.spring6.view.ThymeleafViewResolver;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import org.thymeleaf.web.IWebExchange;
import org.thymeleaf.web.servlet.JakartaServletWebApplication;

import com.logicielpourtous.site.features.plateforme.NavigationService.Tuile;

import nz.net.ultraq.thymeleaf.layoutdialect.LayoutDialect;

/** Rendu autonome (sans contexte Spring, sans base) des gabarits reutilisables du socle. */
class SocleFragmentsTest {

	private SpringTemplateEngine moteur;

	@BeforeEach
	void init() {
		moteur = nouveauMoteur();
	}

	static SpringTemplateEngine nouveauMoteur() {
		ClassLoaderTemplateResolver r = new ClassLoaderTemplateResolver();
		r.setPrefix("templates/");
		r.setSuffix(".html");
		r.setTemplateMode(TemplateMode.HTML);
		r.setCharacterEncoding("UTF-8");
		SpringTemplateEngine m = new SpringTemplateEngine();
		m.setTemplateResolver(r);
		m.addDialect(new LayoutDialect());
		return m;
	}

	private String rendre(String cas, Map<String, Object> variables) {
		MockServletContext sc = new MockServletContext();
		JakartaServletWebApplication app = JakartaServletWebApplication.buildApplication(sc);
		IWebExchange echange = app.buildExchange(new MockHttpServletRequest(sc), new MockHttpServletResponse());
		WebContext ctx = new WebContext(echange, Locale.FRENCH);
		Map<String, Object> toutes = new HashMap<>(variables);
		toutes.put("cas", cas);
		ctx.setVariables(toutes);
		return moteur.process("testsocle/compo", ctx);
	}

	private static Map<String, Object> m(Object... kv) {
		Map<String, Object> r = new HashMap<>();
		for (int i = 0; i < kv.length; i += 2) {
			r.put((String) kv[i], kv[i + 1]);
		}
		return r;
	}

	@Test
	void barre_de_statut_marque_exactement_l_etat_courant() {
		List<Map<String, Object>> etats = List.of(m("code", "brouillon", "libelle", "Brouillon"),
				m("code", "valide", "libelle", "Validé"), m("code", "paye", "libelle", "Payé"));
		String html = rendre("statut", m("etats", etats, "courant", "valide"));
		assertThat(html.split("aria-current=\"step\"", -1)).hasSize(2);
		assertThat(html).containsPattern("aria-current=\"step\"[^>]*>Validé<");
		assertThat(html).doesNotContainPattern("aria-current=\"step\"[^>]*>Brouillon<")
				.doesNotContainPattern("aria-current=\"step\"[^>]*>Payé<");
	}

	@Test
	void vue_liste_echappe_toute_donnee() {
		String piege = "<img src=x onerror=alert(1)>";
		List<Map<String, Object>> colonnes = List.of(m("cle", "nom", "libelle", "Nom", "tri", true));
		List<Map<String, Object>> lignes = List.of(m("id", 7, "nom", piege));
		String html = rendre("liste", m("colonnes", colonnes, "lignes", lignes, "urlLigne", "/admin/socle"));
		assertThat(html).doesNotContain("<img").contains("&lt;img src=x onerror=alert(1)&gt;");
		assertThat(html).contains("href=\"/admin/socle/7\"");
	}

	@Test
	void vue_liste_vide_affiche_un_message() {
		List<Map<String, Object>> colonnes = List.of(m("cle", "nom", "libelle", "Nom", "tri", false));
		String html = rendre("liste", m("colonnes", colonnes, "lignes", List.of(), "urlLigne", null));
		assertThat(html).contains("Aucun résultat");
	}

	@Test
	void champ_de_recherche_porte_les_attributs_hx() {
		String html = rendre("panneau", m("titre", "Clients", "fil", List.of(m("libelle", "Accueil", "url", "/")),
				"actions", List.of(), "recherche", m("url", "/admin/socle/liste", "cible", "#liste")));
		assertThat(html).contains("hx-get=\"/admin/socle/liste\"").contains("hx-trigger=\"keyup changed delay:300ms\"")
				.contains("hx-target=\"#liste\"").contains("hx-push-url=\"false\"");
		String sans = rendre("panneau",
				m("titre", "Clients", "fil", List.of(), "actions", List.of(), "recherche", null));
		assertThat(sans).doesNotContain("hx-get").contains("Clients");
	}

	@Test
	void grille_de_tuiles_un_lien_par_tuile_ou_message_si_vide() {
		List<Tuile> tuiles = List.of(new Tuile("p1", "Clients", "bi-people", "/clients", null, "1"),
				new Tuile("p2", "<b>x</b>", "bi-box", "/autre", null, "1"));
		String html = rendre("tuiles", m("tuilesDemo", tuiles));
		Matcher lien = Pattern.compile("<a [^>]*class=\"socle-tuile-grande\"").matcher(html);
		int n = 0;
		while (lien.find()) {
			n++;
		}
		assertThat(n).isEqualTo(2);
		assertThat(html).contains("href=\"/clients\"").contains("href=\"/autre\"").contains("&lt;b&gt;x&lt;/b&gt;")
				.doesNotContain("Aucune application disponible");
		String vide = rendre("tuiles", m("tuilesDemo", List.of()));
		assertThat(vide).contains("Aucune application disponible").doesNotContain("socle-tuile-grande\"");
	}

	@Test
	void vue_fiche_affiche_un_bouton_seulement_si_l_action_existe() {
		String avec = rendre("fiche", m("titre", "Fiche", "statut", null, "actions",
				List.of(m("libelle", "Enregistrer", "url", "/admin/socle"))));
		assertThat(avec).contains("class=\"btn btn-primary btn-sm\"").contains("Enregistrer")
				.contains("Contenu de la fiche");
		String sans = rendre("fiche", m("titre", "Fiche", "statut", null, "actions", null));
		assertThat(sans).doesNotContain("btn btn-primary").doesNotContain("Enregistrer");
		String vide = rendre("fiche", m("titre", "Fiche", "statut", null, "actions", List.of()));
		assertThat(vide).doesNotContain("btn btn-primary");
		String statut = rendre("fiche", m("titre", "Fiche", "actions", null, "statut",
				m("etats", List.of(m("code", "a", "libelle", "A")), "courant", "a")));
		assertThat(statut).contains("aria-current=\"step\"");
	}

	// ---- emplacements d'extension (boutons et onglets) ----

	/** Substitut de l'assistant paresseux : meme methode que celle appelee par les gabarits. */
	public static final class FauxSlots {

		private final List<com.logicielpourtous.site.features.plateforme.EmplacementsService.ElementAffiche> els;

		int appels;

		FauxSlots(com.logicielpourtous.site.features.plateforme.EmplacementsService.ElementAffiche... els) {
			this.els = List.of(els);
		}

		public List<com.logicielpourtous.site.features.plateforme.EmplacementsService.ElementAffiche> elements(
				String emplacement, String idEntite) {
			appels++;
			return els;
		}
	}

	private static com.logicielpourtous.site.features.plateforme.EmplacementsService.ElementAffiche el(String libelle,
			String url) {
		return new com.logicielpourtous.site.features.plateforme.EmplacementsService.ElementAffiche(libelle, url,
				"p", 1);
	}

	@Test
	void boutons_chargent_chaque_element_au_chargement_et_affichent_le_libelle_en_texte() {
		FauxSlots slots = new FauxSlots(el("<b>x</b>", "/p/alpha/export?client=42"), el("Deux", "/p/beta/b"));
		String html = rendre("boutons", m("uiSlots", slots, "emplacement", "demo.fiche.boutons", "idEntite", "42"));
		assertThat(html).contains("hx-get=\"/p/alpha/export?client=42\"").contains("hx-get=\"/p/beta/b\"")
				.contains("hx-trigger=\"load\"").contains("hx-swap=\"outerHTML\"")
				.contains("&lt;b&gt;x&lt;/b&gt;").doesNotContain("<b>x").contains("Deux");
		assertThat(html.split("hx-trigger=\"load\"", -1)).hasSize(3);
	}

	@Test
	void onglets_premier_actif_et_charge_d_emblee_les_autres_au_premier_clic() {
		FauxSlots slots = new FauxSlots(el("<b>x</b>", "/p/alpha/h?client=42"), el("Deux", "/p/beta/b"));
		String html = rendre("onglets", m("uiSlots", slots, "emplacement", "demo.fiche.onglets", "idEntite", "42"));
		assertThat(html).contains("nav-tabs").contains("data-bs-toggle=\"tab\"")
				.contains("data-bs-target=\"#panneau-demo-fiche-onglets-0\"")
				.contains("data-bs-target=\"#panneau-demo-fiche-onglets-1\"")
				.contains("hx-target=\"#panneau-demo-fiche-onglets-0\"").contains("hx-get=\"/p/alpha/h?client=42\"")
				.contains("id=\"panneau-demo-fiche-onglets-1\"").contains("Chargement…")
				.contains("&lt;b&gt;x&lt;/b&gt;").doesNotContain("<b>x").doesNotContain("id=\"panneau-0\"");
		// Premier onglet : actif, charge tout de suite (et au clic) ; second : seulement au premier clic.
		assertThat(html).contains("hx-trigger=\"load, click once\"").contains("hx-trigger=\"click once\"");
		assertThat(html.split("hx-trigger=\"load, click once\"", -1)).hasSize(2);
		assertThat(html.split("hx-trigger=\"click once\"", -1)).hasSize(2);
		assertThat(html.split("nav-link active", -1)).hasSize(2);
		assertThat(html).contains("tab-pane fade show active");
		assertThat(html.split("show active", -1)).hasSize(2);
	}

	@Test
	void deux_fragments_onglets_sur_une_page_ont_des_identifiants_distincts() {
		FauxSlots slots = new FauxSlots(el("Un", "/p/alpha/h"));
		String a = rendre("onglets", m("uiSlots", slots, "emplacement", "demo.fiche.onglets", "idEntite", "1"));
		String b = rendre("onglets", m("uiSlots", slots, "emplacement", "autre.zone.onglets", "idEntite", "1"));
		assertThat(a).contains("id=\"panneau-demo-fiche-onglets-0\"").doesNotContain("autre-zone");
		assertThat(b).contains("id=\"panneau-autre-zone-onglets-0\"").doesNotContain("demo-fiche");
	}

	@Test
	void emplacements_sans_element_ou_sans_assistant_ne_rendent_rien() {
		for (String cas : List.of("boutons", "onglets")) {
			FauxSlots vide = new FauxSlots();
			String html = rendre(cas, m("uiSlots", vide, "emplacement", "demo.x.y", "idEntite", "1"));
			assertThat(html).as(cas).doesNotContain("hx-get").doesNotContain("nav-tabs").doesNotContain("Chargement");
			assertThat(vide.appels).as(cas).isEqualTo(1);
			String sans = rendre(cas, m("emplacement", "demo.x.y", "idEntite", "1"));
			assertThat(sans).as(cas).doesNotContain("hx-get").doesNotContain("nav-tabs");
		}
	}

	@Test
	void page_de_demonstration_inclut_les_deux_emplacements_de_la_fiche() throws Exception {
		FauxSlots slots = new FauxSlots(el("Hist", "/p/alpha/h?client=42"));
		String html = mvc().perform(get("/admin/socle").requestAttr("uiSlots", slots)).andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		assertThat(slots.appels).isEqualTo(2);
		assertThat(html).contains("hx-trigger=\"load\"").contains("hx-trigger=\"load, click once\"");
		String sans = mvc().perform(get("/admin/socle")).andExpect(status().isOk()).andReturn().getResponse()
				.getContentAsString();
		assertThat(sans).doesNotContain("click once");
	}

	// ---- controleur de demonstration : MockMvc autonome (ni contexte Spring ni base) ----

	private MockMvc mvc() {
		ThymeleafViewResolver vr = new ThymeleafViewResolver();
		vr.setTemplateEngine(moteur);
		vr.setCharacterEncoding("UTF-8");
		return MockMvcBuilders.standaloneSetup(new SocleStyleguideController()).setViewResolvers(vr).build();
	}

	@Test
	void controleur_avec_hx_request_renvoie_seulement_le_fragment_de_liste() throws Exception {
		String html = mvc().perform(get("/admin/socle/liste").param("q", "alp").header("HX-Request", "true"))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		assertThat(html).doesNotContain("<html").contains("Alpha").doesNotContain("Bravo");
	}

	@Test
	void controleur_sans_hx_request_renvoie_la_page_complete() throws Exception {
		String html = mvc().perform(get("/admin/socle/liste").param("q", "alp")).andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		assertThat(html).contains("<html").contains("Alpha").doesNotContain("Bravo");
		String page = mvc().perform(get("/admin/socle")).andExpect(status().isOk()).andReturn().getResponse()
				.getContentAsString();
		assertThat(page).contains("<html").contains("Alpha").contains("Bravo")
				.contains("hx-get=\"/admin/socle/liste\"").contains("id=\"liste\"");
	}

	@Test
	void controleur_echappe_la_recherche() throws Exception {
		String html = mvc().perform(
				get("/admin/socle/liste").param("q", "<script>x</script>").header("HX-Request", "true"))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		assertThat(html).doesNotContain("<script>");
	}
}

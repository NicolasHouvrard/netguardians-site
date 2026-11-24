package com.logicielpourtous.site.features.plateforme;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.endsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.web.firewall.RequestRejectedException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.logicielpourtous.site.config.JwtTokenProvider;
import com.logicielpourtous.site.features.auth.CustomUserDetails;
import com.logicielpourtous.site.features.auth.CustomUserDetailsService;
import com.logicielpourtous.site.features.clients.DroitsService;
import com.logicielpourtous.site.features.commun.Tenant;
import com.logicielpourtous.site.features.commun.TenantContext;
import com.logicielpourtous.site.features.commun.TenantRepository;
import com.logicielpourtous.site.features.role.Role;
import com.logicielpourtous.site.features.role.RoleRepository;
import com.logicielpourtous.site.features.theme.ThemeReglageRepository;
import com.logicielpourtous.site.features.user.Appartenance;
import com.logicielpourtous.site.features.user.AppartenanceRepository;
import com.logicielpourtous.site.features.user.User;
import com.logicielpourtous.site.features.user.UserRepository;

/**
 * Le socle du front de bout en bout, sur l'application complète ({@code app.plateforme.actif=true}) : VRAIE chaîne de
 * sécurité (session, CSRF ciblé, sélecteur de société, jetons Bearer pour l'API), VRAIE mise en page {@code socle/layout},
 * vrai gestionnaire de plugins et un plugin de test « demo » construit en mémoire (manifeste avec deux entrées et une
 * extension, un {@code @Controller}, un {@code @RestController}, un gabarit et un fichier statique), installé par
 * {@link PluginManager#installer}, mis à jour à chaud puis désinstallé.
 *
 * <p>Les données (sociétés, droits, utilisateurs, réglages de thème et journal) sont créées dans la transaction du test,
 * annulée en fin de cas ; le plugin, lui, est installé hors de cette transaction et désinstallé après chaque cas.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class PluginSocleIT {

	private static final Path RACINE;

	private static final Path REPERTOIRE;

	static {
		try {
			RACINE = Files.createTempDirectory("plugin-socle-it");
			REPERTOIRE = Files.createDirectories(RACINE.resolve("plugins"));
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	@DynamicPropertySource
	static void proprietes(DynamicPropertyRegistry registre) {
		registre.add("app.plateforme.actif", () -> "true");
		registre.add("app.plateforme.repertoire-plugins", REPERTOIRE::toString);
		registre.add("app.plateforme.delai-drainage-ms", () -> "5000");
		registre.add("app.role", () -> "MASTER");
		registre.add("captcha.enabled", () -> "false");
		// Le secret JWT de la configuration locale est trop court pour HS256 : secret aléatoire de 64 caractères.
		registre.add("app.jwt.secret", () -> UUID.randomUUID() + "-" + UUID.randomUUID().toString().substring(0, 27));
	}

	/** Clés de confiance du test (paire éphémère en mémoire) à la place de celles de la ressource du cœur. */
	@TestConfiguration
	static class CleDeTest {

		@Bean
		@Primary
		ClesPubliques clesPubliquesDeTest() {
			return new ClesPubliques(JarDePluginDeTest.clesDeTest());
		}

	}

	// ---- le plugin de test « demo » --------------------------------------------------------------------------------

	private static final String CONFIGURATION = """
			package demo;
			@org.springframework.context.annotation.Configuration
			@org.springframework.context.annotation.ComponentScan("demo")
			public class DemoConfiguration {
			}
			""";

	private static final String CONTROLEUR = """
			package demo;
			import org.springframework.web.bind.annotation.*;
			@org.springframework.stereotype.Controller
			public class DemoController {
			    @GetMapping("/")
			    public String page(org.springframework.ui.Model modele) {
			        modele.addAttribute("texte", "<i>a&b</i>");
			        modele.addAttribute("titre", "Page demo");
			        return "plugins/demo/page";
			    }
			    @PostMapping(value = "/action", produces = "text/html;charset=UTF-8")
			    @ResponseBody
			    public String action() { return "<p>action ok</p>"; }
			    @GetMapping(value = "/onglet", produces = "text/html;charset=UTF-8")
			    @ResponseBody
			    public String onglet(@RequestParam("client") String client) { return "<p>onglet client " + client + "</p>"; }
			    @GetMapping("/{a}/{b}")
			    @ResponseBody
			    public String piege() { return "PLUGIN-PIEGE"; }
			}
			""";

	private static final String API = """
			package demo;
			import org.springframework.web.bind.annotation.*;
			@RestController
			@RequestMapping("/info")
			public class DemoApi {
			    @GetMapping
			    public java.util.Map<String, String> info() { return java.util.Map.of("plugin", "demo"); }
			}
			""";

	private static final String GABARIT = "<html xmlns:layout=\"http://www.ultraq.net.nz/thymeleaf/layout\" "
			+ "xmlns:th=\"http://www.thymeleaf.org\" layout:decorate=\"~{socle/layout}\"><body>"
			+ "<div layout:fragment=\"contenu\"><link rel=\"stylesheet\" th:href=\"@{/p/demo/static/demo.css}\">"
			+ "<p id=\"texte\" th:text=\"${texte}\">x</p><p>GABARIT-@V@</p></div></body></html>";

	/** Jar « demo » : la 1.0.0 (deux entrées, une extension) ou la 1.1.0 (libellé changé, une entrée et une extension de plus). */
	private Path jarDemo(String version) {
		boolean v2 = !"1.0.0".equals(version);
		Map<String, String> manifeste = new HashMap<>(Map.of("coeur-min", "0.0.0", "mise-a-jour", "chaud", "paquet",
				"demo", "configuration", "demo.DemoConfiguration"));
		manifeste.put("entree.1", "PARTENAIRE|" + (v2 ? "Demo V2" : "Demo V1") + "|people|/|demo.lire");
		manifeste.put("entree.2", "ADMIN|Demo admin|gear|/admin|demo.lire");
		manifeste.put("ext.1", "demo.fiche.onglets|Historique demo|/onglet?client={id}|demo.lire|5");
		if (v2) {
			manifeste.put("entree.3", "PARTENAIRE|Demo extra|star|/extra|demo.lire");
			manifeste.put("ext.2", "demo.fiche.onglets|Second onglet|/onglet?client={id}|demo.lire|6");
		}
		Path jar = JarDePluginDeTest.creer(RACINE.resolve("jar-" + UUID.randomUUID()), "demo", version, manifeste,
				Map.of(), Map.of("demo.DemoConfiguration", CONFIGURATION, "demo.DemoController", CONTROLEUR,
						"demo.DemoApi", API),
				Map.of("templates/plugins/demo/page.html", GABARIT.replace("@V@", v2 ? "V2" : "V1"),
						"static/demo.css", v2 ? "body{color:#222}" : "body{color:#111}"));
		return JarDePluginDeTest.signer(jar);
	}

	// ---- environnement ---------------------------------------------------------------------------------------------

	@Autowired
	private MockMvc mvc;

	@Autowired
	private PluginManager manager;

	@Autowired
	private ResolveurGabaritsPlugins resolveur;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private DroitsService droits;

	@Autowired
	private TenantRepository tenants;

	@Autowired
	private RoleRepository roles;

	@Autowired
	private UserRepository users;

	@Autowired
	private AppartenanceRepository appartenances;

	@Autowired
	private CustomUserDetailsService detailsService;

	@Autowired
	private JwtTokenProvider jwt;

	@Autowired
	private ThemeReglageRepository reglagesTheme;

	/** Gestionnaire de la société A (a le droit). */
	private CustomUserDetails gest;

	/** Gestionnaire de la société B (le plafond de B contient le droit, mais aucun rôle ne l'a reçu). */
	private CustomUserDetails sansDroit;

	/** Admin du maître, membre de la société A (a le droit). */
	private CustomUserDetails adminAvecDroit;

	/** Admin du maître, membre de la société B (n'a pas le droit). */
	private CustomUserDetails adminSansDroit;

	/** Membre des sociétés A et B sans société choisie : ne peut rien faire d'autre que choisir. */
	private CustomUserDetails enSelection;

	@BeforeEach
	void preparer() {
		Tenant tenant = new Tenant();
		tenant.setName("Tenant socle IT");
		tenant.setSchemaName("test_tenant_" + UUID.randomUUID());
		tenant = tenants.save(tenant);

		Role gestionnaire = role("ROLE_GESTIONNAIRE");
		Role admin = role("ROLE_ADMIN");
		Long societeA = societe(tenant.getId());
		Long societeB = societe(tenant.getId());
		Set<String> droit = Set.of("demo.lire");
		droits.fixerPlafond(societeA, droit);
		droits.accorderAuRole(societeA, "ROLE_GESTIONNAIRE", droit);
		droits.accorderAuRole(societeA, "ROLE_ADMIN", droit);
		droits.fixerPlafond(societeB, droit); // plafond seulement : aucun rôle n'a reçu le droit

		gest = utilisateur(tenant, societeA, gestionnaire);
		sansDroit = utilisateur(tenant, societeB, gestionnaire);
		adminAvecDroit = utilisateur(tenant, societeA, admin);
		adminSansDroit = utilisateur(tenant, societeB, admin);

		User multiple = nouvelUtilisateur(tenant, gestionnaire);
		Appartenance a1 = appartenance(multiple, tenant, societeA, gestionnaire);
		Appartenance a2 = appartenance(multiple, tenant, societeB, gestionnaire);
		enSelection = new CustomUserDetails(multiple, List.of(a1, a2), null);
		assertThat(enSelection.selectionRequise()).isTrue();
	}

	@AfterEach
	void apres() {
		try {
			attendre(() -> manager.desinstaller("demo"));
		}
		finally {
			TenantContext.clear();
			org.springframework.security.core.context.SecurityContextHolder.clearContext();
		}
	}

	@AfterAll
	static void supprimerDossier() throws IOException {
		try (Stream<Path> arbre = Files.walk(RACINE)) {
			arbre.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
		}
	}

	// ---- (a) accueil : tuiles ----------------------------------------------------------------------------------------

	@Test
	void a_accueil_tuile_pour_qui_a_le_droit_message_vide_sinon_login_pour_l_anonyme_admin_pour_l_admin_du_maitre()
			throws Exception {
		installer("1.0.0");

		String avecDroit = page("/accueil", gest);
		assertThat(avecDroit).contains("id=\"grille-apps\"").contains("href=\"/p/demo/\"").contains("Demo V1")
				.doesNotContain("Aucune application disponible").doesNotContain("Demo admin").doesNotContain("/p/demo/admin");

		String sans = page("/accueil", sansDroit);
		assertThat(sans).contains("Aucune application disponible").doesNotContain("Demo V1").doesNotContain("/p/demo/");

		// Admin du maître : la tuile ADMIN s'ajoute à celle de sa société (il a le droit), et vient seule sans le droit.
		assertThat(page("/accueil", adminAvecDroit)).contains("Demo V1").contains("href=\"/p/demo/admin\"")
				.contains("Demo admin");
		String adminSeul = page("/accueil", adminSansDroit);
		assertThat(adminSeul).contains("Demo admin").contains("href=\"/p/demo/admin\"").doesNotContain("Demo V1")
				.doesNotContain("Aucune application disponible");

		// Anonyme : redirigé vers /login (et HX-Redirect pour un appel HTMX), jamais 200.
		mvc.perform(get("/accueil")).andExpect(status().isFound()).andExpect(header().string("Location", endsWith("/login")));
		mvc.perform(get("/accueil").header("HX-Request", "true")).andExpect(status().isOk())
				.andExpect(header().string("HX-Redirect", "/login"));
	}

	// ---- (b) page de plugin dans la vraie mise en page ---------------------------------------------------------------

	@Test
	void b_la_page_du_plugin_est_decoree_par_le_vrai_layout_du_socle_sans_domaine_tiers() throws Exception {
		installer("1.0.0");
		MockHttpServletResponse reponse = mvc.perform(get("/p/demo/").with(user(gest))).andReturn().getResponse();
		assertThat(reponse.getStatus()).isEqualTo(200);
		assertThat(reponse.getHeader("X-Plugin")).startsWith("demo@1.0.0#");
		String html = reponse.getContentAsString();

		// Marqueurs du VRAI layout : fichiers locaux du socle, jeton CSRF, barre supérieure avec les tuiles.
		assertThat(html).contains("<body class=\"socle\">").contains("href=\"/css/vendor/bootstrap.min.css\"")
				.contains("href=\"/css/socle.css\"").contains("href=\"/css/theme.css\"")
				.contains("src=\"/js/vendor/htmx.min.js\"").contains("src=\"/js/socle.js\"")
				.contains("src=\"/js/vendor/bootstrap.bundle.min.js\"").contains("<meta name=\"_csrf\" content=\"")
				.contains("<meta name=\"_csrf_header\"").contains("class=\"socle-topbar\"").contains("id=\"toasts\"")
				.contains("<title>Page demo</title>");
		// Barre supérieure : les applications de l'utilisateur (dont celle du plugin) y figurent aussi.
		assertThat(html).contains("class=\"socle-tuile\" href=\"/p/demo/\"");
		// Contenu du plugin et son fichier statique, valeur du modèle échappée.
		assertThat(html).contains("GABARIT-V1").contains("href=\"/p/demo/static/demo.css\"")
				.contains("&lt;i&gt;a&amp;b&lt;/i&gt;").doesNotContain("<i>a&b</i>");
		// Aucun domaine tiers : aucune adresse absolue (ni protocole-relative) dans href, src ou action.
		assertThat(Pattern.compile("(?:href|src|action)=\"(?:https?:)?//", Pattern.CASE_INSENSITIVE).matcher(html).find())
				.as("aucune adresse absolue").isFalse();

		// Le plugin décide du rendu : sa page répond aussi à un appel HTMX (il peut servir un fragment, voir /onglet) ;
		// ici seule la page complète existe côté plugin, il n'y a donc pas de rendu « fragment seul » à vérifier.
		mvc.perform(get("/p/demo/").with(user(gest)).header("HX-Request", "true")).andExpect(status().isOk());
		// Sans session : redirigé vers /login.
		mvc.perform(get("/p/demo/")).andExpect(status().isFound()).andExpect(header().string("Location", endsWith("/login")));
	}

	// ---- (c) CSRF, session expirée, sélecteur de société -------------------------------------------------------------

	@Test
	void c_post_sans_jeton_403_avec_jeton_200_htmx_anonyme_redirige_et_la_selection_de_societe_bloque() throws Exception {
		installer("1.0.0");

		mvc.perform(post("/p/demo/action").with(user(gest))).andExpect(status().isForbidden());
		MockHttpServletResponse ok = mvc.perform(post("/p/demo/action").with(user(gest)).with(csrf())).andReturn()
				.getResponse();
		assertThat(ok.getStatus()).isEqualTo(200);
		assertThat(ok.getContentAsString()).isEqualTo("<p>action ok</p>");

		// Anonyme : appel HTMX -> 200 + HX-Redirect ; navigation classique -> redirection vers /login.
		mvc.perform(post("/p/demo/action").header("HX-Request", "true")).andExpect(status().isOk())
				.andExpect(header().string("HX-Redirect", "/login"));
		mvc.perform(post("/p/demo/action").with(csrf())).andExpect(status().isFound())
				.andExpect(header().string("Location", endsWith("/login")));

		// Compte en attente du choix de société : aucune page de plugin (redirigé), aucune écriture (403).
		mvc.perform(get("/p/demo/").with(user(enSelection)).accept("text/html")).andExpect(status().isFound())
				.andExpect(header().string("Location", endsWith("/choisir-societe")));
		mvc.perform(get("/p/demo/").with(user(enSelection)).header("HX-Request", "true")).andExpect(status().isOk())
				.andExpect(header().string("HX-Redirect", "/choisir-societe"));
		mvc.perform(get("/p/demo/onglet").param("client", "42").with(user(enSelection)).accept("text/html"))
				.andExpect(status().isFound()).andExpect(header().string("Location", endsWith("/choisir-societe")));
		mvc.perform(get("/p/demo/static/demo.css").with(user(enSelection)).accept("text/html"))
				.andExpect(status().isFound()).andExpect(header().string("Location", endsWith("/choisir-societe")));
		mvc.perform(post("/p/demo/action").with(user(enSelection)).with(csrf())).andExpect(status().isForbidden());
		mvc.perform(post("/p/demo/action").with(user(enSelection))).andExpect(status().isForbidden());
	}

	// ---- (d) API JSON à jeton --------------------------------------------------------------------------------------

	@Test
	void d_l_api_json_reste_sous_api_plugins_avec_jeton_et_refuse_sans() throws Exception {
		installer("1.0.0");
		mvc.perform(get("/api/plugins/demo/info").header("Authorization", "Bearer " + jwt.jetonDuCompte(gest)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.plugin").value("demo"));
		mvc.perform(get("/api/plugins/demo/info")).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/plugins/demo/info").header("Authorization", "Bearer pas-un-jeton"))
				.andExpect(status().isUnauthorized());
		// Le @RestController n'est pas une page : introuvable sous /p, et la page n'existe pas sous /api/plugins.
		mvc.perform(get("/p/demo/info").with(user(gest))).andExpect(status().isNotFound());
		mvc.perform(get("/api/plugins/demo/").header("Authorization", "Bearer " + jwt.jetonDuCompte(gest)))
				.andExpect(status().isNotFound());
		// Un jeton ne tient pas lieu de session sur les pages.
		mvc.perform(get("/p/demo/").header("Authorization", "Bearer " + jwt.jetonDuCompte(gest)))
				.andExpect(status().isFound()).andExpect(header().string("Location", endsWith("/login")));
	}

	// ---- (e) fichiers statiques --------------------------------------------------------------------------------------

	@Test
	void e_statique_servi_avec_session_les_refus_sont_tous_le_meme_404() throws Exception {
		installer("1.0.0");

		MockHttpServletResponse css = mvc.perform(get("/p/demo/static/demo.css").with(user(gest))).andReturn()
				.getResponse();
		assertThat(css.getStatus()).isEqualTo(200);
		assertThat(css.getContentAsString()).isEqualTo("body{color:#111}");
		assertThat(css.getContentType()).isEqualTo("text/css");
		assertThat(css.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
		assertThat(css.getHeader("X-Plugin")).as("servi par le noyau, jamais par le plugin").isNull();
		// Sans session : /login.
		mvc.perform(get("/p/demo/static/demo.css")).andExpect(status().isFound())
				.andExpect(header().string("Location", endsWith("/login")));

		// Même 404 sans corps : fichier absent, extension refusée, sous-dossier, fichier du noyau, plugin inconnu.
		String[] refus = { "/p/demo/static/absent.css", "/p/demo/static/a.txt", "/p/demo/static/x/y.css",
				"/p/demo/static/", "/p/demo/static/noyau-seul-test.css", "/p/inconnu/static/demo.css" };
		for (String chemin : refus) {
			MockHttpServletResponse r = mvc.perform(get(URI.create(chemin)).with(user(gest))).andReturn().getResponse();
			assertThat(r.getStatus()).as(chemin).isEqualTo(404);
			assertThat(r.getContentAsString()).as(chemin).isEmpty();
			assertThat(r.getHeader("X-Plugin")).as(chemin).isNull();
		}
		// Remontée de dossier : jamais servi (le pare-feu de la chaîne de sécurité la refuse avant le contrôleur).
		for (String chemin : new String[] { "/p/demo/static/../x", "/p/demo/static/%2e%2e/x", "/p/demo/static/..%2Fx",
				"/p/demo/static/%2e%2e%2fdemo.css" }) {
			int statut;
			try {
				MockHttpServletResponse r = mvc.perform(get(URI.create(chemin)).with(user(gest))).andReturn()
						.getResponse();
				statut = r.getStatus();
				assertThat(r.getContentAsString()).as(chemin).doesNotContain("body{").doesNotContain("PLUGIN-");
			}
			catch (jakarta.servlet.ServletException | RequestRejectedException e) {
				statut = 400;
			}
			assertThat(statut).as(chemin).isIn(400, 404);
		}
		// Le mapping large du plugin (/{a}/{b}) répond bien ailleurs que sous /static (sinon ce test ne prouverait rien).
		assertThat(mvc.perform(get("/p/demo/x/y").with(user(gest))).andReturn().getResponse().getContentAsString())
				.isEqualTo("PLUGIN-PIEGE");
	}

	// ---- (f) mise à jour à chaud -------------------------------------------------------------------------------------

	@Test
	void f_mise_a_jour_a_chaud_nouveau_gabarit_tuile_extensions_et_statique_sans_erreur() throws Exception {
		installer("1.0.0");
		// Remplit les caches éventuels avec la version 1.
		assertThat(page("/p/demo/", gest)).contains("GABARIT-V1");
		assertThat(page("/accueil", gest)).contains("Demo V1").doesNotContain("Demo extra");
		assertThat(page("/admin/socle", adminAvecDroit)).contains("Historique demo").doesNotContain("Second onglet");

		installer("1.1.0");

		MockHttpServletResponse reponse = mvc.perform(get("/p/demo/").with(user(gest))).andReturn().getResponse();
		assertThat(reponse.getStatus()).isEqualTo(200);
		assertThat(reponse.getHeader("X-Plugin")).startsWith("demo@1.1.0#");
		assertThat(reponse.getContentAsString()).contains("GABARIT-V2").doesNotContain("GABARIT-V1")
				.contains("<body class=\"socle\">");
		String accueil = page("/accueil", gest);
		assertThat(accueil).contains("Demo V2").contains("Demo extra").doesNotContain("Demo V1");
		String fiche = page("/admin/socle", adminAvecDroit);
		assertThat(fiche).contains("Historique demo").contains("Second onglet");
		assertThat(mvc.perform(get("/p/demo/static/demo.css").with(user(gest))).andReturn().getResponse()
				.getContentAsString()).isEqualTo("body{color:#222}");
		mvc.perform(get("/api/plugins/demo/info").header("Authorization", "Bearer " + jwt.jetonDuCompte(gest)))
				.andExpect(status().isOk());
	}

	// ---- (g) désinstallation -----------------------------------------------------------------------------------------

	@Test
	void g_desinstallation_page_tuile_extension_statique_et_gabarit_disparaissent() throws Exception {
		installer("1.0.0");
		assertThat(page("/p/demo/", gest)).contains("GABARIT-V1");
		assertThat(page("/accueil", gest)).contains("Demo V1");
		assertThat(page("/admin/socle", adminAvecDroit)).contains("Historique demo");
		assertThat(resolveur.resolveTemplate(null, null, "plugins/demo/page", Map.of())).isNotNull();

		assertThat(attendre(() -> manager.desinstaller("demo"))).isTrue();

		MockHttpServletResponse page = mvc.perform(get("/p/demo/").with(user(gest))).andReturn().getResponse();
		assertThat(page.getStatus()).isEqualTo(404);
		assertThat(page.getContentAsString()).doesNotContain("GABARIT-V");
		assertThat(mvc.perform(get("/p/demo/onglet").param("client", "42").with(user(gest))).andReturn().getResponse()
				.getStatus()).isEqualTo(404);
		assertThat(mvc.perform(get("/p/demo/static/demo.css").with(user(gest))).andReturn().getResponse().getStatus())
				.isEqualTo(404);
		assertThat(mvc.perform(post("/p/demo/action").with(user(gest)).with(csrf())).andReturn().getResponse()
				.getStatus()).isEqualTo(404);
		assertThat(page("/accueil", gest)).doesNotContain("Demo V1").contains("Aucune application disponible");
		assertThat(page("/accueil", adminAvecDroit)).doesNotContain("Demo admin");
		assertThat(page("/admin/socle", adminAvecDroit)).doesNotContain("Historique demo")
				.doesNotContain("/p/demo/onglet");
		assertThat(resolveur.resolveTemplate(null, null, "plugins/demo/page", Map.of())).isNull();
		mvc.perform(get("/api/plugins/demo/info").header("Authorization", "Bearer " + jwt.jetonDuCompte(gest)))
				.andExpect(status().isNotFound());
	}

	// ---- (h) thème ---------------------------------------------------------------------------------------------------

	@Test
	void h_l_admin_change_la_couleur_primaire_le_fichier_de_theme_la_porte_et_socle_css_la_consomme() throws Exception {
		installer("1.0.0");
		jdbc.update("DELETE FROM theme_reglages"); // annulé avec la transaction du test
		Long journalAvant = jdbc.queryForObject("SELECT count(*) FROM theme_journal", Long.class);

		String avant = mvc.perform(get("/css/theme.css")).andExpect(status().isOk()).andReturn().getResponse()
				.getContentAsString();
		assertThat(avant).contains("--socle-couleur-primaire:").doesNotContain("#12ab34");

		// Écriture : jeton CSRF obligatoire, admin obligatoire.
		mvc.perform(post("/admin/apparence/couleur-primaire").param("valeur", "#12ab34").with(user(adminAvecDroit)))
				.andExpect(status().isForbidden());
		mvc.perform(post("/admin/apparence/couleur-primaire").param("valeur", "#12ab34").with(user(gest)).with(csrf()))
				.andExpect(status().isForbidden());
		assertThat(reglagesTheme.count()).isZero();
		mvc.perform(post("/admin/apparence/couleur-primaire").param("valeur", "#12ab34").with(user(adminAvecDroit))
				.with(csrf())).andExpect(status().is3xxRedirection());

		MockHttpServletResponse theme = mvc.perform(get("/css/theme.css")).andReturn().getResponse();
		assertThat(theme.getStatus()).isEqualTo(200);
		assertThat(theme.getContentAsString().toLowerCase()).contains("--socle-couleur-primaire:#12ab34");
		assertThat(theme.getContentAsString()).isNotEqualTo(avant);
		// La page d'un plugin charge ce fichier (le même pour tous les écrans) et le socle consomme la variable.
		assertThat(page("/p/demo/", gest)).contains("href=\"/css/theme.css\"");
		String socle = new ClassPathResource("static/css/socle.css").getContentAsString(StandardCharsets.UTF_8);
		assertThat(socle).contains("var(--socle-couleur-primaire");

		// Retour à l'état initial (la transaction du test est de toute façon annulée : réglages et journal inclus).
		mvc.perform(post("/admin/apparence/reinitialiser").with(user(adminAvecDroit)).with(csrf()))
				.andExpect(status().is3xxRedirection());
		assertThat(reglagesTheme.count()).isZero();
		assertThat(mvc.perform(get("/css/theme.css")).andReturn().getResponse().getContentAsString()).isEqualTo(avant);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM theme_journal", Long.class)).isGreaterThan(journalAvant);
	}

	// ---- (i) emplacements d'extension --------------------------------------------------------------------------------

	@Test
	void i_l_onglet_du_plugin_apparait_pour_qui_a_le_droit_se_charge_par_son_adresse_et_disparait_apres_desinstallation()
			throws Exception {
		installer("1.0.0");
		String fiche = page("/admin/socle", adminAvecDroit);
		assertThat(fiche).contains("Historique demo").contains("hx-get=\"/p/demo/onglet?client=42\"")
				.contains("hx-trigger=\"load, click once\"").contains("id=\"panneau-demo-fiche-onglets-0\"")
				.contains("hx-target=\"#panneau-demo-fiche-onglets-0\"");

		// Sans le droit : l'écran est le même (admin), mais l'onglet n'est pas proposé.
		String sans = page("/admin/socle", adminSansDroit);
		assertThat(sans).doesNotContain("Historique demo").doesNotContain("/p/demo/onglet");

		// L'adresse chargée par l'onglet répond (fragment) à la personne qui a une session.
		MockHttpServletResponse fragment = mvc.perform(get("/p/demo/onglet").param("client", "42").with(user(gest)))
				.andReturn().getResponse();
		assertThat(fragment.getStatus()).isEqualTo(200);
		assertThat(fragment.getContentAsString()).isEqualTo("<p>onglet client 42</p>");
		mvc.perform(get("/p/demo/onglet").param("client", "42")).andExpect(status().isFound());

		assertThat(attendre(() -> manager.desinstaller("demo"))).isTrue();
		assertThat(page("/admin/socle", adminAvecDroit)).doesNotContain("Historique demo")
				.doesNotContain("/p/demo/onglet");
		mvc.perform(get("/p/demo/onglet").param("client", "42").with(user(gest))).andExpect(status().isNotFound());
	}

	// ---- (j) gardes du noyau sur les pages de plugin ---------------------------------------------------------------

	@Test
	void j_une_session_au_mot_de_passe_temporaire_est_redirigee_sur_les_pages_et_statiques_des_plugins()
			throws Exception {
		installer("1.0.0");
		org.springframework.mock.web.MockHttpSession temporaire = new org.springframework.mock.web.MockHttpSession();
		temporaire.setAttribute("MUST_CHANGE_PASSWORD", Boolean.TRUE);

		// Même comportement que les écrans du cœur (témoin : /accueil).
		mvc.perform(get("/accueil").with(user(gest)).session(temporaire)).andExpect(status().isFound())
				.andExpect(header().string("Location", endsWith("/change-password")));
		for (String chemin : new String[] { "/p/demo/", "/p/demo/onglet?client=42", "/p/demo/static/demo.css" }) {
			MockHttpServletResponse r = mvc.perform(get(URI.create(chemin)).with(user(gest)).session(temporaire))
					.andReturn().getResponse();
			assertThat(r.getStatus()).as(chemin).isEqualTo(302);
			assertThat(r.getHeader("Location")).as(chemin).endsWith("/change-password");
			assertThat(r.getContentAsString()).as(chemin).doesNotContain("GABARIT-V").doesNotContain("body{");
		}
		// Appel HTMX et écriture : la même redirection que dans le cœur, et l'action du plugin n'est pas exécutée.
		mvc.perform(get("/p/demo/").with(user(gest)).session(temporaire).header("HX-Request", "true"))
				.andExpect(status().isFound()).andExpect(header().string("Location", endsWith("/change-password")));
		MockHttpServletResponse ecriture = mvc.perform(post("/p/demo/action").with(user(gest)).with(csrf())
				.session(temporaire)).andReturn().getResponse();
		assertThat(ecriture.getStatus()).isEqualTo(302);
		assertThat(ecriture.getContentAsString()).doesNotContain("action ok");
		// Le contexte de tenant ne fuit pas hors de la requête.
		assertThat(TenantContext.getCurrentTenant()).isNull();

		// Une session ordinaire n'est pas touchée.
		mvc.perform(get("/p/demo/").with(user(gest)).session(new org.springframework.mock.web.MockHttpSession()))
				.andExpect(status().isOk());
		mvc.perform(get("/p/demo/static/demo.css").with(user(gest))).andExpect(status().isOk());
	}

	// ---- (b2) navigation boostée vers les écrans du cœur -------------------------------------------------------------

	@Test
	void b2_les_ecrans_du_coeur_repondent_page_complete_a_une_navigation_boostee() throws Exception {
		installer("1.0.0");
		for (String entete : new String[] { "HX-Boosted", "HX-History-Restore-Request" }) {
			assertThat(mvc.perform(get("/accueil").with(user(gest)).header("HX-Request", "true").header(entete, "true"))
					.andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).as(entete)
					.contains("<body class=\"socle\">");
			assertThat(mvc.perform(get("/admin/socle").with(user(adminAvecDroit)).header("HX-Request", "true")
					.header(entete, "true")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
					.as(entete).contains("<body class=\"socle\">");
		}
		// Recherche en direct (HX-Request seul) : la grille seule. Le choix page/fragment d'un plugin lui appartient.
		assertThat(mvc.perform(get("/accueil").with(user(gest)).header("HX-Request", "true")).andReturn().getResponse()
				.getContentAsString()).doesNotContain("<body");
	}

	// ---- aides -------------------------------------------------------------------------------------------------------

	/** Page complète d'une URL pour ce compte, en exigeant 200. */
	private String page(String url, CustomUserDetails compte) throws Exception {
		return mvc.perform(get(url).with(user(compte))).andExpect(status().isOk()).andReturn().getResponse()
				.getContentAsString();
	}

	private void installer(String version) {
		Path jar = jarDemo(version);
		ResultatInstallation r = attendre(() -> manager.installer(jar));
		assertThat(r.statut()).as("installation du plugin demo " + version + " : " + r.raisons())
				.isEqualTo(ResultatInstallation.Statut.OK);
		assertThat(manager.actifs()).containsKey("demo");
	}

	/** Exécute hors du fil (et de la transaction) du test : le gestionnaire fait du DDL et ses propres connexions. */
	private static <T> T attendre(java.util.function.Supplier<T> action) {
		return CompletableFuture.supplyAsync(action).join();
	}

	private Role role(String nom) {
		return roles.findByName(nom).orElseGet(() -> roles.save(new Role(nom, nom, "ESN")));
	}

	private Long societe(Long proprietaireTenantId) {
		String suffixe = String.valueOf(Math.abs(UUID.randomUUID().getMostSignificantBits() % 1_000_000_000L));
		Long id = jdbc.queryForObject(
				"INSERT INTO societes_identites (siren, tva, type, proprietaire_tenant_id) VALUES (?, ?, ?, ?) RETURNING id",
				Long.class, "T10" + suffixe, "FRT10" + suffixe, "PARTENAIRE", proprietaireTenantId);
		jdbc.update("INSERT INTO societe_capacites (societe_id, capacite) VALUES (?, 'CLIENT') ON CONFLICT DO NOTHING", id);
		jdbc.update("INSERT INTO societe_capacites (societe_id, capacite) VALUES (?, 'PARTENAIRE') ON CONFLICT DO NOTHING",
				id);
		return id;
	}

	private User nouvelUtilisateur(Tenant tenant, Role role) {
		User u = new User();
		u.setEmail("t10-" + UUID.randomUUID() + "@logicielpourtous.com");
		u.setPassword("test");
		u.setFullName("Utilisateur T10");
		u.setActive(true);
		u.setTenant(tenant);
		u.setRoles(Set.of(role));
		return users.saveAndFlush(u);
	}

	private Appartenance appartenance(User u, Tenant tenant, Long societeId, Role role) {
		Appartenance a = new Appartenance();
		a.setUserId(u.getId());
		a.setTenant(tenant);
		a.setSocieteId(societeId);
		a.setRoles(Set.of(role));
		return appartenances.saveAndFlush(a);
	}

	/** Utilisateur actif, membre de la société donnée avec ce rôle ; renvoie le compte tel que le charge la chaîne. */
	private CustomUserDetails utilisateur(Tenant tenant, Long societeId, Role role) {
		User u = nouvelUtilisateur(tenant, role);
		Appartenance a = appartenance(u, tenant, societeId, role);
		return (CustomUserDetails) detailsService.charger(u.getEmail(), a.getId());
	}

}

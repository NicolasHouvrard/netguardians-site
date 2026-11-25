package com.logicielpourtous.site.features.plateforme;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.endsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import com.logicielpourtous.site.features.auth.CustomUserDetails;
import com.logicielpourtous.site.features.auth.CustomUserDetailsService;
import com.logicielpourtous.site.features.clients.DroitsService;
import com.logicielpourtous.site.features.commun.Tenant;
import com.logicielpourtous.site.features.commun.TenantContext;
import com.logicielpourtous.site.features.commun.TenantRepository;
import com.logicielpourtous.site.features.identite.NumerosSociete;
import com.logicielpourtous.site.features.role.Role;
import com.logicielpourtous.site.features.role.RoleRepository;
import com.logicielpourtous.site.features.user.Appartenance;
import com.logicielpourtous.site.features.user.AppartenanceRepository;
import com.logicielpourtous.site.features.user.User;
import com.logicielpourtous.site.features.user.UserRepository;

/**
 * Le plugin « clients » 1.1.0 de bout en bout : le VRAI jar (construit par {@code mvn -B -o -f plugins-clients/pom.xml
 * package}) installé dans l'application complète, derrière la VRAIE chaîne de sécurité (session, CSRF), avec le VRAI
 * rendu Thymeleaf dans la vraie mise en page {@code socle/layout} et la vraie base.
 *
 * <p>Pas de transaction de test (le noyau crée les clients dans ses propres transactions) : tout ce que le test crée
 * est nettoyé à la main (voir {@link #apres()}).
 */
@SpringBootTest
@AutoConfigureMockMvc
class PluginClientsIT {

	private static final Path RACINE;

	private static final Path REPERTOIRE;

	static {
		try {
			RACINE = Files.createTempDirectory("plugin-clients-it");
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

	private static final String HOSTILE_NOM = "\"><img src=x onerror=1>";

	private static final String HOSTILE_SCRIPT = "<script>alert(1)</script>";

	private static final Pattern LOCATION_FICHE = Pattern.compile("/p/clients/(\\d+)/fiche\\?ok=cree$");

	@Autowired
	private MockMvc mvc;

	@Autowired
	private PluginManager manager;

	@Autowired
	private ResolveurGabaritsPlugins resolveur;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private TransactionTemplate tx;

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

	private Tenant tenantDuTest;

	private Long partenaireA;

	private Long partenaireB;

	private Long clientDeA;

	private Long clientDeB;

	/** Gestionnaire du partenaire A, avec lire/creer/entrer. */
	private CustomUserDetails gestionnaireA;

	/** Membre du partenaire A dont le rôle n'a aucun droit. */
	private CustomUserDetails sansDroit;

	private Role roleSansDroit;

	private final List<String> emails = new ArrayList<>();

	private final Set<Long> societes = new LinkedHashSet<>();

	private long tenantsAvant;

	@BeforeEach
	void preparer() {
		JarClientsReel.localiser(); // échoue avec la commande de build si le jar n'est pas construit
		tenantsAvant = compter("tenants");

		Tenant tenant = new Tenant();
		tenant.setName("Tenant clients IT");
		tenant.setSchemaName("test_tenant_" + UUID.randomUUID());
		tenantDuTest = tenants.save(tenant);

		Role gestionnaire = role("ROLE_GESTIONNAIRE");
		roleSansDroit = roles.save(new Role("ROLE_T3_SANS_DROIT_" + UUID.randomUUID(), "Sans droit", "ESN"));

		partenaireA = societe("PARTENAIRE", tenantDuTest.getId());
		partenaireB = societe("PARTENAIRE", tenantDuTest.getId());
		clientDeA = societe("CLIENT", null);
		clientDeB = societe("CLIENT", null);
		lier(clientDeA, partenaireA);
		lier(clientDeB, partenaireB);
		membre(clientDeA, gestionnaire, "Client Alpha A");
		membre(clientDeB, gestionnaire, "Client Beta B");

		Set<String> tous = Set.of("clients.lire", "clients.creer", "clients.entrer");
		droits.fixerPlafond(partenaireA, tous);
		droits.accorderAuRole(partenaireA, "ROLE_GESTIONNAIRE", tous);
		droits.fixerPlafond(partenaireB, tous);
		droits.accorderAuRole(partenaireB, "ROLE_GESTIONNAIRE", tous);

		gestionnaireA = membre(partenaireA, gestionnaire, "Gestionnaire A");
		sansDroit = membre(partenaireA, roleSansDroit, "Sans droit A");

		installer(JarClientsReel.copieSignee(RACINE.resolve("jar-" + UUID.randomUUID())));
	}

	@AfterEach
	void apres() {
		try {
			attendre(() -> manager.desinstaller("clients"));
		}
		finally {
			TenantContext.clear();
			SecurityContextHolder.clearContext();
			nettoyer();
		}
	}

	@AfterAll
	static void supprimerDossier() throws IOException {
		try (Stream<Path> arbre = Files.walk(RACINE)) {
			arbre.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
		}
	}

	// ---- accueil : la tuile suit le droit clients.lire ---------------------------------------------------------------

	@Test
	void la_tuile_clients_n_apparait_qu_avec_le_droit_lire() throws Exception {
		assertThat(page("/accueil", gestionnaireA)).contains("href=\"/p/clients/liste\"").contains("Clients");
		assertThat(page("/accueil", sansDroit)).doesNotContain("/p/clients");
	}

	// ---- cloisonnement par partenaire --------------------------------------------------------------------------------

	@Test
	void liste_et_fiche_sont_cloisonnees_par_partenaire() throws Exception {
		CustomUserDetails gestionnaireB = membre(partenaireB, role("ROLE_GESTIONNAIRE"), "Gestionnaire B");

		String listeA = page("/p/clients/liste", gestionnaireA);
		assertThat(listeA).contains("Client Alpha A").contains("<body class=\"socle\">")
				.contains("/p/clients/" + clientDeA + "/fiche").doesNotContain("Client Beta B")
				.doesNotContain("/p/clients/" + clientDeB + "/fiche");
		String listeB = page("/p/clients/liste", gestionnaireB);
		assertThat(listeB).contains("Client Beta B").doesNotContain("Client Alpha A");

		assertThat(page("/p/clients/" + clientDeA + "/fiche", gestionnaireA)).contains("Client Alpha A")
				.contains("/p/clients/" + clientDeA + "/entrer");

		// Client d'un autre partenaire et identifiant inconnu (ou mal formé) : exactement la même page 404.
		MockHttpServletResponse autre = mvc.perform(get("/p/clients/" + clientDeB + "/fiche").with(user(gestionnaireA)))
				.andReturn().getResponse();
		MockHttpServletResponse inconnu = mvc.perform(get("/p/clients/999999999/fiche").with(user(gestionnaireA)))
				.andReturn().getResponse();
		MockHttpServletResponse malForme = mvc.perform(get("/p/clients/abc/fiche").with(user(gestionnaireA)))
				.andReturn().getResponse();
		assertThat(autre.getStatus()).isEqualTo(404);
		assertThat(inconnu.getStatus()).isEqualTo(404);
		assertThat(malForme.getStatus()).isEqualTo(404);
		assertThat(autre.getContentAsString()).contains("Page introuvable.").doesNotContain("Client Beta B");
		assertThat(sansJeton(inconnu)).isEqualTo(sansJeton(autre));
		assertThat(sansJeton(malForme)).isEqualTo(sansJeton(autre));

		// Sans le droit : 403 à page fixe, aucune donnée.
		MockHttpServletResponse refus = mvc.perform(get("/p/clients/liste").with(user(sansDroit))).andReturn()
				.getResponse();
		assertThat(refus.getStatus()).isEqualTo(403);
		assertThat(refus.getContentAsString()).doesNotContain("Client Alpha A");
		mvc.perform(get("/p/clients/" + clientDeA + "/fiche").with(user(sansDroit))).andExpect(status().isForbidden());
		mvc.perform(get("/p/clients/nouveau").with(user(sansDroit))).andExpect(status().isForbidden());
	}

	// ---- sans session / sans jeton CSRF ------------------------------------------------------------------------------

	@Test
	void sans_session_les_pages_redirigent_vers_le_login_et_les_post_exigent_le_csrf() throws Exception {
		for (String url : new String[] { "/p/clients/liste", "/p/clients/nouveau", "/p/clients/" + clientDeA + "/fiche" }) {
			mvc.perform(get(url)).andExpect(status().isFound()).andExpect(header().string("Location", endsWith("/login")));
		}
		Compteurs avant = compteurs();
		String emailNeuf = email();
		// Anonyme (même avec un jeton CSRF) : redirigé vers /login, rien n'est écrit.
		mvc.perform(post("/p/clients/").param("particulier", "on").param("email", emailNeuf).param("nom", "Anonyme")
				.with(csrf())).andExpect(status().isFound()).andExpect(header().string("Location", endsWith("/login")));
		mvc.perform(post("/p/clients/" + clientDeA + "/entrer").with(csrf())).andExpect(status().isFound())
				.andExpect(header().string("Location", endsWith("/login")));
		// Authentifié mais sans jeton CSRF : la chaîne de sécurité refuse avant le plugin.
		mvc.perform(post("/p/clients/").param("particulier", "on").param("email", emailNeuf).param("nom", "Sans jeton")
				.with(user(gestionnaireA))).andExpect(status().isForbidden());
		mvc.perform(post("/p/clients/" + clientDeA + "/entrer").with(user(gestionnaireA)))
				.andExpect(status().isForbidden());
		assertThat(compteurs()).isEqualTo(avant);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE email = ?", Long.class, emailNeuf)).isZero();
	}

	// ---- création ----------------------------------------------------------------------------------------------------

	@Test
	void creation_valide_puis_doublon_409_puis_donnees_invalides_400_sans_ecriture() throws Exception {
		String siren = siren();
		String email = email();
		MockHttpServletResponse cree = creer(siren, email, "Client Cree SARL", false);
		assertThat(cree.getStatus()).isEqualTo(302);
		long id = idDepuisLocation(cree);
		assertThat(jdbc.queryForObject("SELECT partenaire_societe_id FROM liens_clients WHERE client_societe_id = ?",
				Long.class, id)).isEqualTo(partenaireA);
		String fiche = page("/p/clients/" + id + "/fiche?ok=cree", gestionnaireA);
		assertThat(fiche).contains("Client créé.").contains("Client Cree SARL").contains(siren);
		assertThat(page("/p/clients/liste", gestionnaireA)).contains("Client Cree SARL");

		// Même e-mail, autre SIREN : 409, rien d'écrit.
		Compteurs avant = compteurs();
		MockHttpServletResponse doublon = creer(siren(), email, "Doublon SARL", false);
		assertThat(doublon.getStatus()).isEqualTo(409);
		assertThat(doublon.getContentAsString()).contains("cette adresse e-mail est déjà utilisée")
				.contains("<body class=\"socle\">");
		assertThat(compteurs()).isEqualTo(avant);

		// Données invalides : 400 avec les messages de champ, rien d'écrit.
		MockHttpServletResponse invalide = creer("123", "pas-un-email", "", false);
		assertThat(invalide.getStatus()).isEqualTo(400);
		assertThat(invalide.getContentAsString()).contains("SIREN invalide : 9 chiffres avec clé de contrôle correcte")
				.contains("Adresse e-mail invalide").contains("Nom obligatoire");
		assertThat(creer("", email(), "Sans SIREN SARL", false).getStatus()).as("SIREN requis hors particulier")
				.isEqualTo(400);
		assertThat(creer(siren(), email(), "N".repeat(256), false).getStatus()).isEqualTo(400);
		assertThat(compteurs()).isEqualTo(avant);
		// Les champs de saisie ne portent pas de case « étrangère » (supprimée).
		assertThat(page("/p/clients/nouveau", gestionnaireA)).contains("<form").contains("name=\"siren\"")
				.doesNotContain("etrangere");
	}

	@Test
	void particulier_sans_siren_ni_tva_est_cree() throws Exception {
		String email = email();
		MockHttpServletResponse cree = creer("", email, "Particulier Test", true);
		assertThat(cree.getStatus()).isEqualTo(302);
		long id = idDepuisLocation(cree);
		assertThat(jdbc.queryForObject("SELECT partenaire_societe_id FROM liens_clients WHERE client_societe_id = ?",
				Long.class, id)).isEqualTo(partenaireA);
		assertThat(page("/p/clients/" + id + "/fiche?ok=cree", gestionnaireA)).contains("Particulier Test");
		assertThat(page("/p/clients/liste", gestionnaireA)).contains("Particulier Test");
	}

	// ---- entrer dans l'espace client ---------------------------------------------------------------------------------

	@Test
	void entrer_accorde_pour_son_client_et_refuse_en_404_identique_pour_un_autre() throws Exception {
		MockHttpSession session = new MockHttpSession();
		String idAvant = session.getId();
		String accueilAvant = mvc.perform(get("/accueil").session(session).with(user(gestionnaireA)))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

		MockHttpServletResponse ok = mvc.perform(post("/p/clients/" + clientDeA + "/entrer").session(session)
				.with(user(gestionnaireA)).with(csrf())).andReturn().getResponse();
		assertThat(ok.getStatus()).isEqualTo(200);
		assertThat(ok.getContentAsString()).contains("Espace client").contains("<body class=\"socle\">")
				.contains(String.valueOf(clientDeA));

		// Client d'un autre partenaire et client inconnu : exactement la même réponse 404.
		MockHttpServletResponse autre = mvc.perform(post("/p/clients/" + clientDeB + "/entrer").session(session)
				.with(user(gestionnaireA)).with(csrf())).andReturn().getResponse();
		MockHttpServletResponse inconnu = mvc.perform(post("/p/clients/999999999/entrer").session(session)
				.with(user(gestionnaireA)).with(csrf())).andReturn().getResponse();
		assertThat(autre.getStatus()).isEqualTo(404);
		assertThat(inconnu.getStatus()).isEqualTo(404);
		assertThat(sansJeton(inconnu)).isEqualTo(sansJeton(autre));
		assertThat(autre.getContentAsString()).contains("Page introuvable.");

		// Sans le droit : 403.
		mvc.perform(post("/p/clients/" + clientDeA + "/entrer").with(user(sansDroit)).with(csrf()))
				.andExpect(status().isForbidden());

		// Aucun changement de session : même identifiant, session valide, accueil identique.
		assertThat(session.getId()).isEqualTo(idAvant);
		assertThat(session.isInvalid()).isFalse();
		String accueilApres = mvc.perform(get("/accueil").session(session).with(user(gestionnaireA)))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		assertThat(sansJeton(accueilApres)).isEqualTo(sansJeton(accueilAvant));
		assertThat(session.getId()).isEqualTo(idAvant);
	}

	// ---- échappement -------------------------------------------------------------------------------------------------

	@Test
	void texte_hostile_dans_un_nom_est_affiche_echappe() throws Exception {
		String email = email();
		MockHttpServletResponse cree = creer(siren(), email, HOSTILE_NOM, false);
		assertThat(cree.getStatus()).isEqualTo(302);
		long id = idDepuisLocation(cree);

		String liste = page("/p/clients/liste", gestionnaireA);
		assertThat(liste).contains("&quot;&gt;&lt;img src=x onerror=1&gt;").doesNotContain("<img src=x");
		String fiche = page("/p/clients/" + id + "/fiche", gestionnaireA);
		assertThat(fiche).contains("&quot;&gt;&lt;img src=x onerror=1&gt;").doesNotContain("<img src=x");

		// Formulaire réaffiché (e-mail en doublon -> 409) : la saisie revient échappée.
		MockHttpServletResponse r = creer(siren(), email, HOSTILE_SCRIPT, false);
		assertThat(r.getStatus()).isEqualTo(409);
		tousLesFormulairesPortentLeJeton("formulaire réaffiché", r.getContentAsString());
		assertThat(r.getContentAsString()).contains("&lt;script&gt;alert(1)&lt;/script&gt;")
				.doesNotContain("<script>alert(1)");
	}

	// ---- mise à jour à chaud puis désinstallation --------------------------------------------------------------------

	@Test
	void mise_a_jour_a_chaud_vers_1_1_1_garde_les_pages_et_la_desinstallation_retire_les_routes() throws Exception {
		assertThat(page("/p/clients/liste", gestionnaireA)).contains("Client Alpha A");

		Path v2 = JarClientsReel.deriver(RACINE.resolve("jar-" + UUID.randomUUID()), "1.1.1", "Clients V2");
		installer(v2);

		MockHttpServletResponse liste = mvc.perform(get("/p/clients/liste").with(user(gestionnaireA))).andReturn()
				.getResponse();
		assertThat(liste.getStatus()).as("X-Plugin=" + liste.getHeader("X-Plugin") + " corps=" + liste.getContentAsString())
				.isEqualTo(200);
		assertThat(liste.getHeader("X-Plugin")).startsWith("clients@1.1.1#");
		assertThat(liste.getContentAsString()).contains("Client Alpha A").contains("<body class=\"socle\">");
		assertThat(page("/accueil", gestionnaireA)).contains("Clients V2").contains("href=\"/p/clients/liste\"");
		assertThat(page("/p/clients/nouveau", gestionnaireA)).contains("<form");
		assertThat(page("/p/clients/" + clientDeA + "/fiche", gestionnaireA)).contains("Client Alpha A");
		mvc.perform(get("/p/clients/" + clientDeB + "/fiche").with(user(gestionnaireA))).andExpect(status().isNotFound());

		assertThat(attendre(() -> manager.desinstaller("clients"))).isTrue();

		assertThat(page("/accueil", gestionnaireA)).doesNotContain("Clients V2").doesNotContain("/p/clients");
		for (String url : new String[] { "/p/clients/liste", "/p/clients/nouveau", "/p/clients/" + clientDeA + "/fiche" }) {
			MockHttpServletResponse r = mvc.perform(get(url).with(user(gestionnaireA))).andReturn().getResponse();
			assertThat(r.getStatus()).as(url).isEqualTo(404);
			assertThat(r.getContentAsString()).as(url).doesNotContain("Client Alpha A");
		}
		assertThat(mvc.perform(post("/p/clients/").with(user(gestionnaireA)).with(csrf())).andReturn().getResponse()
				.getStatus()).isEqualTo(404);
		assertThat(resolveur.resolveTemplate(null, null, "plugins/clients/liste", Map.of())).isNull();
	}

	// ---- aides -------------------------------------------------------------------------------------------------------

	/** Corps de la réponse sans les jetons CSRF (différents à chaque réponse), pour comparer deux pages. */
	private static String sansJeton(MockHttpServletResponse r) throws Exception {
		return sansJeton(r.getContentAsString());
	}

	private static String sansJeton(String html) {
		return html.replaceAll("(content|value)=\"[A-Za-z0-9_-]{30,}\"", "$1=\"JETON\"");
	}

	/** POST du formulaire de création en gestionnaire du partenaire A, avec jeton CSRF. */
	private MockHttpServletResponse creer(String siren, String email, String nom, boolean particulier) throws Exception {
		var req = post("/p/clients/").param("siren", siren).param("tva", "").param("email", email).param("nom", nom);
		if (particulier) {
			req = req.param("particulier", "on");
		}
		return mvc.perform(req.with(user(gestionnaireA)).with(csrf())).andReturn().getResponse();
	}

	private long idDepuisLocation(MockHttpServletResponse r) {
		String location = r.getHeader("Location");
		assertThat(location).as("Location de la création").isNotNull();
		Matcher m = LOCATION_FICHE.matcher(location);
		assertThat(m.find()).as("Location : " + location).isTrue();
		long id = Long.parseLong(m.group(1));
		societes.add(id);
		return id;
	}

	/** Page complète d'une URL pour ce compte, en exigeant 200. */
	private String page(String url, CustomUserDetails compte) throws Exception {
		String html = mvc.perform(get(url).with(user(compte))).andExpect(status().isOk()).andReturn().getResponse()
				.getContentAsString();
		tousLesFormulairesPortentLeJeton(url, html);
		return html;
	}

	/** Tout formulaire POST rendu doit débuter par le champ caché du jeton CSRF (ajouté seulement par th:action). */
	private static void tousLesFormulairesPortentLeJeton(String contexte, String html) {
		Matcher m = Pattern.compile("(?is)<form\\b[^>]*>").matcher(html);
		while (m.find()) {
			if (!m.group().toLowerCase().contains("method=\"post\"")) {
				continue;
			}
			String suite = html.substring(m.end()).stripLeading();
			assertThat(suite).as(contexte + " : formulaire POST sans jeton CSRF : " + m.group())
					.startsWith("<input type=\"hidden\" name=\"_csrf\"");
		}
	}

	private void installer(Path jar) {
		ResultatInstallation r = attendre(() -> manager.installer(jar));
		assertThat(r.statut()).as("installation du plugin clients : " + r.raisons())
				.isEqualTo(ResultatInstallation.Statut.OK);
		assertThat(manager.actifs()).containsKey("clients");
	}

	/** Exécute hors du fil du test : le gestionnaire fait du DDL et ses propres connexions. */
	private static <T> T attendre(java.util.function.Supplier<T> action) {
		return CompletableFuture.supplyAsync(action).join();
	}

	private String siren() {
		for (int n = 700000000 + (int) (Math.random() * 90000000);; n++) {
			String s = String.valueOf(n);
			if (NumerosSociete.sirenValide(s)
					&& jdbc.queryForObject("SELECT count(*) FROM societes_identites WHERE siren = ?", Long.class, s) == 0) {
				return s;
			}
		}
	}

	private String email() {
		String e = "clients-it-" + UUID.randomUUID() + "@example.test";
		emails.add(e);
		return e;
	}

	private Role role(String nom) {
		return roles.findByName(nom).orElseGet(() -> roles.save(new Role(nom, nom, "ESN")));
	}

	private Long societe(String type, Long proprietaireTenantId) {
		String suffixe = String.valueOf(Math.abs(UUID.randomUUID().getMostSignificantBits() % 1_000_000_000L));
		Long id = jdbc.queryForObject(
				"INSERT INTO societes_identites (siren, tva, type, proprietaire_tenant_id) VALUES (?, ?, ?, ?) RETURNING id",
				Long.class, "T30" + suffixe, "FRT30" + suffixe, type, proprietaireTenantId);
		jdbc.update("INSERT INTO societe_capacites (societe_id, capacite) VALUES (?, 'CLIENT') ON CONFLICT DO NOTHING", id);
		if ("PARTENAIRE".equals(type)) {
			jdbc.update("INSERT INTO societe_capacites (societe_id, capacite) VALUES (?, 'PARTENAIRE')"
					+ " ON CONFLICT DO NOTHING", id);
		}
		societes.add(id);
		return id;
	}

	private void lier(Long client, Long partenaire) {
		jdbc.update("INSERT INTO liens_clients (client_societe_id, partenaire_societe_id, origine) VALUES (?, ?, 'PARTENAIRE')",
				client, partenaire);
	}

	/** Utilisateur actif membre de la société avec ce rôle ; renvoie le compte tel que le charge la chaîne. */
	private CustomUserDetails membre(Long societeId, Role role, String nomComplet) {
		User u = new User();
		String e = "clients-it-" + UUID.randomUUID() + "@logicielpourtous.com";
		emails.add(e);
		u.setEmail(e);
		u.setPassword("test");
		u.setFullName(nomComplet);
		u.setActive(true);
		u.setTenant(tenantDuTest);
		u.setRoles(Set.of(role));
		User enregistre = users.saveAndFlush(u);
		Appartenance a = new Appartenance();
		a.setUserId(enregistre.getId());
		a.setTenant(tenantDuTest);
		a.setSocieteId(societeId);
		a.setRoles(Set.of(role));
		Appartenance apparte = appartenances.saveAndFlush(a);
		return tx.execute(s -> (CustomUserDetails) detailsService.charger(enregistre.getEmail(), apparte.getId()));
	}

	private long compter(String table) {
		return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
	}

	private record Compteurs(List<Long> valeurs) {
	}

	private Compteurs compteurs() {
		return new Compteurs(List.of(compter("societes_identites"), compter("users"), compter("tenants"),
				compter("liens_clients"), compter("plafonds_client"), compter("reglages_manager"),
				compter("appartenances")));
	}

	/** Supprime tout ce que le test a créé (jamais d'effet de bord sur la base partagée). */
	private void nettoyer() {
		Set<Long> aSupprimer = new LinkedHashSet<>(societes);
		for (Long partenaire : new ArrayList<>(aSupprimer)) {
			aSupprimer.addAll(jdbc.queryForList("SELECT client_societe_id FROM liens_clients WHERE partenaire_societe_id = ?",
					Long.class, partenaire));
		}
		for (String email : emails) {
			aSupprimer.addAll(jdbc.queryForList("SELECT a.societe_id FROM appartenances a JOIN users u ON u.id = a.user_id "
					+ "WHERE u.email = ?", Long.class, email));
			jdbc.update("DELETE FROM appartenances_roles WHERE appartenance_id IN "
					+ "(SELECT a.id FROM appartenances a JOIN users u ON u.id = a.user_id WHERE u.email = ?)", email);
			jdbc.update("DELETE FROM appartenances WHERE user_id IN (SELECT id FROM users WHERE email = ?)", email);
			jdbc.update("DELETE FROM users_roles WHERE user_id IN (SELECT id FROM users WHERE email = ?)", email);
			jdbc.update("DELETE FROM users WHERE email = ?", email);
		}
		for (Long id : aSupprimer) {
			jdbc.update("DELETE FROM liens_clients WHERE client_societe_id = ? OR partenaire_societe_id = ?", id, id);
			jdbc.update("DELETE FROM reglages_manager WHERE client_societe_id = ?", id);
			jdbc.update("DELETE FROM plafonds_client WHERE client_societe_id = ?", id);
			jdbc.update("DELETE FROM societe_capacites WHERE societe_id = ?", id);
			jdbc.update("DELETE FROM appartenances WHERE societe_id = ?", id);
			jdbc.update("DELETE FROM societes_identites WHERE id = ?", id);
		}
		if (roleSansDroit != null) {
			jdbc.update("DELETE FROM roles WHERE id = ?", roleSansDroit.getId());
		}
		if (tenantDuTest != null) {
			jdbc.update("DELETE FROM tenants WHERE id = ?", tenantDuTest.getId());
		}
		assertThat(compter("tenants")).as("tenants laissés par le test").isEqualTo(tenantsAvant);
	}

}

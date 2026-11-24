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
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
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
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
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
import com.logicielpourtous.site.sdk.AnnuaireClientsContrat;
import com.logicielpourtous.site.sdk.AnnuaireClientsContrat.CreationClient;
import com.logicielpourtous.site.sdk.AnnuaireClientsContrat.ResultatCreation;

/**
 * Le lot B de bout en bout : le VRAI jar du plugin « partenaires » (construit par
 * {@code mvn -f plugins-partenaires/pom.xml package}) installé dans l'application complète, derrière la VRAIE chaîne de
 * sécurité (session, CSRF, garde « admin du serveur maître » du contrat), avec le VRAI rendu Thymeleaf dans la vraie
 * mise en page {@code socle/layout} et la vraie base (création de tenant, de schéma {@code p_*}, de société, de
 * premier manager, de droits).
 *
 * <p>Pas de transaction de test : la création d'un partenaire s'exécute dans sa propre transaction validée
 * (REQUIRES_NEW) ; tout ce que le test crée est donc nettoyé à la main (voir {@link #apres()}), schémas et tenants
 * compris, et le modèle de droits est restauré.
 */
@SpringBootTest
@AutoConfigureMockMvc
class PluginPartenairesIT {

	private static final Path RACINE;

	private static final Path REPERTOIRE;

	static {
		try {
			RACINE = Files.createTempDirectory("plugin-partenaires-it");
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

	private static final String HOSTILE_RAISON = "<script>alert(1)</script>";

	private static final String HOSTILE_NOM = "\"><img src=x onerror=1>";

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
	private AnnuaireClientsContrat annuaire;

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

	private Long societeDuTest;

	private User adminUser;

	private User simpleUser;

	/** Admin du serveur maître (rôle global ROLE_ADMIN). */
	private CustomUserDetails admin;

	/** Gestionnaire d'une société, sans droit d'administration. */
	private CustomUserDetails gestionnaire;

	private final List<String> sirens = new ArrayList<>();

	private final List<String> emails = new ArrayList<>();

	private Set<String> modeleInitial;

	private long schemasAvant;

	private long tenantsAvant;


	@BeforeEach
	void preparer() {
		JarPartenairesReel.localiser(); // échoue avec la commande de build si le jar n'est pas construit
		modeleInitial = new TreeSet<>(jdbc.queryForList("SELECT droit FROM modele_droits_partenaire", String.class));
		schemasAvant = schemas();
		tenantsAvant = compter("tenants");

		Tenant tenant = new Tenant();
		tenant.setName("Tenant partenaires IT");
		tenant.setSchemaName("test_tenant_" + UUID.randomUUID());
		tenantDuTest = tenants.save(tenant);
		tenantsAvant = compter("tenants") - 1; // le tenant de base du test ne compte pas : il est supprimé à la fin

		String suffixe = String.valueOf(Math.abs(UUID.randomUUID().getMostSignificantBits() % 1_000_000_000L));
		societeDuTest = jdbc.queryForObject(
				"INSERT INTO societes_identites (siren, tva, type, proprietaire_tenant_id) VALUES (?, ?, ?, ?) RETURNING id",
				Long.class, "T20" + suffixe, "FRT20" + suffixe, "CLIENT", tenantDuTest.getId());
		jdbc.update("INSERT INTO societe_capacites (societe_id, capacite) VALUES (?, 'CLIENT') ON CONFLICT DO NOTHING",
				societeDuTest);

		Role roleAdmin = role("ROLE_ADMIN");
		Role roleGestionnaire = role("ROLE_GESTIONNAIRE");
		adminUser = utilisateur(roleAdmin);
		simpleUser = utilisateur(roleGestionnaire);
		admin = tx.execute(s -> (CustomUserDetails) detailsService.charger(adminUser.getEmail(),
				appartenance(adminUser, roleAdmin).getId()));
		gestionnaire = tx.execute(s -> (CustomUserDetails) detailsService.charger(simpleUser.getEmail(),
				appartenance(simpleUser, roleGestionnaire).getId()));

		Path jar = JarPartenairesReel.copieSignee(RACINE.resolve("jar-" + UUID.randomUUID()));
		installer(jar);
	}

	@AfterEach
	void apres() {
		try {
			attendre(() -> manager.desinstaller("partenaires"));
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

	// ---- (a) accueil : tuile réservée à l'admin du maître --------------------------------------------------------------

	@Test
	void a_la_tuile_partenaires_n_apparait_que_pour_l_admin_du_maitre_et_l_anonyme_va_au_login() throws Exception {
		assertThat(page("/accueil", admin)).contains("href=\"/p/partenaires/liste\"").contains("Partenaires");
		assertThat(page("/accueil", gestionnaire)).doesNotContain("/p/partenaires");
		mvc.perform(get("/accueil")).andExpect(status().isFound()).andExpect(header().string("Location", endsWith("/login")));
	}

	// ---- (b) accès : seul l'admin du maître, jamais d'écriture sans lui, ni sans jeton CSRF ---------------------------

	@Test
	void b_refus_pour_le_non_admin_l_anonyme_et_sans_jeton_csrf_sans_aucune_ecriture() throws Exception {
		String sirenExistant = creerPartenaire("Partenaire visible SARL", "Alice Visible", null).get("siren");
		Compteurs avant = compteurs();

		MockHttpServletResponse liste = mvc.perform(get("/p/partenaires/liste").with(user(gestionnaire))).andReturn()
				.getResponse();
		assertThat(liste.getStatus()).isEqualTo(403);
		assertThat(liste.getContentAsString()).contains("Accès réservé à l'administrateur du serveur maître")
				.doesNotContain("Partenaire visible SARL").doesNotContain(sirenExistant);
		mvc.perform(get("/p/partenaires/modele").with(user(gestionnaire))).andExpect(status().isForbidden());

		String sirenNeuf = siren();
		String emailNeuf = email();
		MockHttpServletRequestBuilder creation = post("/p/partenaires/").param("siren", sirenNeuf)
				.param("raisonSociale", "Refuse SARL").param("email", emailNeuf).param("nom", "Zoe Refus");
		mvc.perform(creation.with(user(gestionnaire)).with(csrf())).andExpect(status().isForbidden());
		// Le non-admin ne peut pas non plus modifier le modèle ni créer un client pour un partenaire.
		mvc.perform(post("/p/partenaires/modele/ajouter").param("droit", "clients.exporter").with(user(gestionnaire))
				.with(csrf())).andExpect(status().isForbidden());
		mvc.perform(post("/p/partenaires/" + societeIdPartenaire(sirenExistant) + "/clients").param("siren", siren())
				.param("email", email()).param("nom", "Refuse").with(user(gestionnaire)).with(csrf()))
				.andExpect(status().isForbidden());

		// Anonyme : redirigé vers /login (GET comme POST), rien n'est écrit.
		mvc.perform(get("/p/partenaires/liste")).andExpect(status().isFound())
				.andExpect(header().string("Location", endsWith("/login")));
		mvc.perform(post("/p/partenaires/").param("siren", sirenNeuf).param("raisonSociale", "Anon SARL")
				.param("email", emailNeuf).param("nom", "Anon").with(csrf())).andExpect(status().isFound())
				.andExpect(header().string("Location", endsWith("/login")));

		// L'admin lui-même : sans jeton CSRF, l'écriture est refusée par la chaîne de sécurité.
		mvc.perform(post("/p/partenaires/").param("siren", sirenNeuf).param("raisonSociale", "Sans jeton SARL")
				.param("email", emailNeuf).param("nom", "Sans Jeton").with(user(admin))).andExpect(status().isForbidden());
		mvc.perform(post("/p/partenaires/modele/ajouter").param("droit", "clients.exporter").with(user(admin)))
				.andExpect(status().isForbidden());

		assertThat(compteurs()).isEqualTo(avant);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM societes_identites WHERE siren = ?", Long.class, sirenNeuf))
				.isZero();
		assertThat(modeleActuel()).isEqualTo(modeleInitial);

		// Pages de formulaire (comportement actuel documenté) : le non-admin obtient un formulaire VIDE, 200, sans donnée
		// de partenaire ; l'anonyme est renvoyé au login.
		long id = societeIdPartenaire(sirenExistant);
		for (String url : new String[] { "/p/partenaires/nouveau", "/p/partenaires/" + id + "/clients/nouveau" }) {
			MockHttpServletResponse form = mvc.perform(get(url).with(user(gestionnaire))).andReturn().getResponse();
			assertThat(form.getStatus()).as(url).isEqualTo(200);
			assertThat(form.getContentAsString()).as(url).contains("<form").contains("name=\"email\"")
					.doesNotContain("Partenaire visible SARL").doesNotContain(sirenExistant)
					.doesNotContain("Alice Visible");
			mvc.perform(get(url)).andExpect(status().isFound()).andExpect(header().string("Location", endsWith("/login")));
		}
		assertThat(compteurs()).isEqualTo(avant);
	}

	// ---- (c) création d'un partenaire : tenant, schéma, société, premier manager, droits du modèle ------------------

	@Test
	void c_l_admin_cree_un_partenaire_complet_et_la_liste_l_affiche() throws Exception {
		Map<String, String> p = creerPartenaire("Maison Test Partenaire SARL", "Alice Martin", null);
		long societeId = societeIdPartenaire(p.get("siren"));

		String liste = page("/p/partenaires/liste", admin);
		assertThat(liste).contains("Maison Test Partenaire SARL").contains(p.get("siren"))
				.contains("ACTIF").contains("<body class=\"socle\">");

		assertThat(jdbc.queryForObject("SELECT type FROM societes_identites WHERE id = ?", String.class, societeId))
				.isEqualTo("PARTENAIRE");
		assertThat(jdbc.queryForObject("SELECT count(*) FROM societe_capacites WHERE societe_id = ? AND capacite = 'PARTENAIRE'",
				Long.class, societeId)).isEqualTo(1L);
		Long tenantId = jdbc.queryForObject("SELECT proprietaire_tenant_id FROM societes_identites WHERE id = ?",
				Long.class, societeId);
		assertThat(tenantId).isNotNull().isNotEqualTo(tenantDuTest.getId());
		String schema = jdbc.queryForObject("SELECT schema_name FROM tenants WHERE id = ?", String.class, tenantId);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM information_schema.schemata WHERE schema_name = ?",
				Long.class, schema)).as("schéma " + schema).isEqualTo(1L);
		assertThat(schema).startsWith("p_");

		// Premier utilisateur : manager, dans le tenant du partenaire.
		Map<String, Object> premier = jdbc.queryForMap("SELECT id, tenant_id FROM users WHERE email = ?", p.get("email"));
		assertThat(premier.get("tenant_id")).isEqualTo(tenantId);
		assertThat(rolesDe(p.get("email"))).contains("ROLE_GESTIONNAIRE");
		assertThat(jdbc.queryForObject("SELECT count(*) FROM appartenances WHERE user_id = ? AND societe_id = ? AND tenant_id = ?",
				Long.class, premier.get("id"), societeId, tenantId)).isEqualTo(1L);

		// Plafond et réglage du manager égaux au modèle ; état ACTIF.
		assertThat(new TreeSet<>(jdbc.queryForList("SELECT droit FROM plafonds_client WHERE client_societe_id = ?",
				String.class, societeId))).isEqualTo(modeleInitial);
		assertThat(new TreeSet<>(jdbc.queryForList(
				"SELECT droit FROM reglages_manager WHERE client_societe_id = ? AND role_nom = 'ROLE_GESTIONNAIRE'",
				String.class, societeId))).isEqualTo(modeleInitial);
		assertThat(jdbc.queryForObject("SELECT etat FROM partenaires_etat WHERE societe_id = ?", String.class, societeId))
				.isEqualTo("ACTIF");
		assertThat(jdbc.queryForList("SELECT action FROM journal_actions_maitre WHERE admin_user_id = ?", String.class,
				adminUser.getId())).containsExactly("PARTENAIRE_CREE");
	}

	// ---- (d) doublons et saisies invalides : rien n'est écrit --------------------------------------------------------

	@Test
	void d_doublons_et_saisies_invalides_donnent_409_ou_400_sans_rien_ecrire() throws Exception {
		Map<String, String> p = creerPartenaire("Premier SARL", "Premier Manager", null);
		Compteurs avant = compteurs();

		// Même SIREN, autre e-mail : 409 avec message fixe.
		MockHttpServletResponse r = envoyer(p.get("siren"), "Doublon SARL", email(), "Deux Deux");
		assertThat(r.getStatus()).isEqualTo(409);
		assertThat(r.getContentAsString()).contains("Ce SIREN est déjà enregistré.");
		// Même e-mail, autre SIREN : 409.
		r = envoyer(siren(), "Doublon Mail SARL", p.get("email"), "Trois Trois");
		assertThat(r.getStatus()).isEqualTo(409);
		assertThat(r.getContentAsString()).contains("Cette adresse e-mail est déjà utilisée.");
		// SIREN invalide, raison sociale trop longue : 400.
		r = envoyer("123", "Invalide SARL", email(), "Quatre Quatre");
		assertThat(r.getStatus()).isEqualTo(400);
		assertThat(r.getContentAsString()).contains("SIREN invalide : 9 chiffres avec clé de contrôle correcte");
		r = envoyer(siren(), "R".repeat(256), email(), "Cinq Cinq");
		assertThat(r.getStatus()).isEqualTo(400);
		assertThat(r.getContentAsString()).contains("Raison sociale obligatoire");

		assertThat(compteurs()).isEqualTo(avant);
	}

	// ---- (e) texte hostile : toujours échappé ----------------------------------------------------------------------

	@Test
	void e_le_texte_hostile_est_affiche_comme_du_texte_jamais_comme_une_balise() throws Exception {
		Map<String, String> p = creerPartenaire(HOSTILE_RAISON, HOSTILE_NOM, null);

		String liste = page("/p/partenaires/liste", admin);
		assertThat(liste).contains("&lt;script&gt;alert(1)&lt;/script&gt;").doesNotContain("<script>alert(1)");

		// Formulaire réaffiché (SIREN en doublon -> 409) : la saisie revient échappée.
		MockHttpServletResponse r = envoyer(p.get("siren"), HOSTILE_RAISON, email(), HOSTILE_NOM);
		assertThat(r.getStatus()).isEqualTo(409);
		String form = r.getContentAsString();
		tousLesFormulairesPortentLeJeton("formulaire réaffiché", form);
		assertThat(form).contains("&lt;script&gt;alert(1)&lt;/script&gt;").contains("&quot;&gt;&lt;img src=x onerror=1&gt;")
				.doesNotContain("<script>alert(1)").doesNotContain("<img src=x onerror=1>");
	}

	// ---- (f2) liste des clients d'un partenaire -----------------------------------------------------------------

	@Test
	void f2_liste_des_clients_du_partenaire_echappee_reservee_a_l_admin_et_cloisonnee() throws Exception {
		Map<String, String> p = creerPartenaire("Liste Partenaire SARL", "Liste Manager", null);
		Map<String, String> autre = creerPartenaire("Autre Partenaire SARL", "Autre Manager", null);
		long partenaireId = societeIdPartenaire(p.get("siren"));
		long autreId = societeIdPartenaire(autre.get("siren"));
		String sirenClient = siren();
		mvc.perform(post("/p/partenaires/" + partenaireId + "/clients").param("siren", sirenClient)
				.param("email", email()).param("nom", HOSTILE_NOM).with(user(admin)).with(csrf()))
				.andExpect(status().isFound());
		String sirenAutre = siren();
		mvc.perform(post("/p/partenaires/" + autreId + "/clients").param("siren", sirenAutre)
				.param("email", email()).param("nom", "Client Autre Partenaire").with(user(admin)).with(csrf()))
				.andExpect(status().isFound());

		String html = page("/p/partenaires/" + partenaireId + "/clients", admin);
		assertThat(html).contains(sirenClient).contains("PARTENAIRE")
				.contains("&quot;&gt;&lt;img src=x onerror=1&gt;").doesNotContain("<img src=x")
				.doesNotContain(sirenAutre).doesNotContain("Client Autre Partenaire");

		mvc.perform(get("/p/partenaires/" + partenaireId + "/clients").with(user(gestionnaire)))
				.andExpect(status().isForbidden());
		mvc.perform(get("/p/partenaires/abc/clients").with(user(admin))).andExpect(status().isNotFound());
		mvc.perform(get("/p/partenaires/999999999/clients").with(user(admin))).andExpect(status().isNotFound());
	}

	// ---- (f) client pour un partenaire : tenant du partenaire, lien d'origine, journal ------------------------------

	@Test
	void f_client_pour_un_partenaire_dans_le_tenant_du_partenaire_avec_lien_et_journal() throws Exception {
		Map<String, String> p = creerPartenaire("Cible Partenaire SARL", "Cible Manager", null);
		long partenaireId = societeIdPartenaire(p.get("siren"));
		Long tenantPartenaire = jdbc.queryForObject("SELECT proprietaire_tenant_id FROM societes_identites WHERE id = ?",
				Long.class, partenaireId);

		// Société cliente (SIREN neuf), puis particulier.
		String sirenClient = siren();
		String emailSociete = email();
		mvc.perform(post("/p/partenaires/" + partenaireId + "/clients").param("siren", sirenClient)
				.param("email", emailSociete).param("nom", "Client Societe").with(user(admin)).with(csrf()))
				.andExpect(status().isFound())
				.andExpect(header().string("Location", endsWith("/p/partenaires/liste?ok=client")));
		String emailParticulier = email();
		mvc.perform(post("/p/partenaires/" + partenaireId + "/clients").param("particulier", "on").param("siren", "").param("tva", "")
				.param("email", emailParticulier).param("nom", "Client Particulier").with(user(admin)).with(csrf()))
				.andExpect(status().isFound())
				.andExpect(header().string("Location", endsWith("/p/partenaires/liste?ok=client")));

		assertThat(jdbc.queryForList("SELECT origine FROM liens_clients WHERE partenaire_societe_id = ?", String.class,
				partenaireId)).containsExactly("PARTENAIRE", "PARTENAIRE");
		long clientSociete = jdbc.queryForObject("SELECT id FROM societes_identites WHERE siren = ?", Long.class,
				sirenClient);
		assertThat(jdbc.queryForObject("SELECT partenaire_societe_id FROM liens_clients WHERE client_societe_id = ?",
				Long.class, clientSociete)).isEqualTo(partenaireId);
		for (String e : List.of(emailSociete, emailParticulier)) {
			assertThat(jdbc.queryForObject("SELECT tenant_id FROM users WHERE email = ?", Long.class, e))
					.as(e).isEqualTo(tenantPartenaire).isNotEqualTo(tenantDuTest.getId());
		}
		assertThat(jdbc.queryForList("SELECT action FROM journal_actions_maitre WHERE admin_user_id = ? ORDER BY id",
				String.class, adminUser.getId())).containsExactly("PARTENAIRE_CREE", "CLIENT_CREE_POUR_PARTENAIRE",
						"CLIENT_CREE_POUR_PARTENAIRE");

		// Identifiant non numérique : 404 ; société sans capacité partenaire : 400 ; rien d'écrit.
		Compteurs avant = compteurs();
		mvc.perform(post("/p/partenaires/abc/clients").param("siren", siren()).param("email", email()).param("nom", "X")
				.with(user(admin)).with(csrf())).andExpect(status().isNotFound());
		MockHttpServletResponse sansCapacite = mvc.perform(post("/p/partenaires/" + societeDuTest + "/clients")
				.param("siren", siren()).param("email", email()).param("nom", "X").with(user(admin)).with(csrf()))
				.andReturn().getResponse();
		assertThat(sansCapacite.getStatus()).isEqualTo(400);
		assertThat(compteurs()).isEqualTo(avant);
		// Le formulaire de client se rend pour un partenaire existant, et le 404 pour un identifiant invalide.
		assertThat(page("/p/partenaires/" + partenaireId + "/clients/nouveau", admin)).contains("<form")
				.contains("/p/partenaires/" + partenaireId + "/clients");
		mvc.perform(get("/p/partenaires/abc/clients/nouveau").with(user(admin))).andExpect(status().isNotFound());
	}

	// ---- (g) la boucle du lot B : le partenaire créé peut créer des clients ------------------------------------------

	@Test
	void g_le_premier_manager_du_partenaire_cree_a_le_droit_et_peut_creer_un_client() throws Exception {
		Map<String, String> p = creerPartenaire("Boucle Partenaire SARL", "Boucle Manager", null);
		long societeId = societeIdPartenaire(p.get("siren"));
		long managerId = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, p.get("email"));

		assertThat(droits.autorise(managerId, societeId, "clients.creer")).isTrue();
		assertThat(droits.autorise(managerId, societeId, "clients.exporter")).isFalse();

		ResultatCreation r = annuaire
			.creerClient(new CreationClient(societeId, false, siren(), null, false, email(), "Client test"));
		assertThat(r.issue()).isEqualTo("CREE");
		assertThat(jdbc.queryForObject("SELECT partenaire_societe_id FROM liens_clients WHERE client_societe_id = ?",
				Long.class, r.societeId())).isEqualTo(societeId);
	}

	// ---- (h) modèle de droits : non rétroactif, jamais vide, droit invalide refusé -----------------------------------

	@Test
	void h_le_modele_n_est_pas_retroactif_ne_peut_pas_devenir_vide_et_refuse_un_droit_invalide() throws Exception {
		Map<String, String> avant = creerPartenaire("Avant Modele SARL", "Avant Modele", null);

		String pageModele = page("/p/partenaires/modele", admin);
		assertThat(pageModele).contains("<code>clients.creer</code>").doesNotContain("<code>clients.exporter</code>");

		mvc.perform(post("/p/partenaires/modele/ajouter").param("droit", "clients.exporter").with(user(admin)).with(csrf()))
				.andExpect(status().isFound()).andExpect(header().string("Location", endsWith("/p/partenaires/modele?ok=modele")));
		assertThat(page("/p/partenaires/modele", admin)).contains("<code>clients.exporter</code>");

		Map<String, String> apres = creerPartenaire("Apres Modele SARL", "Apres Modele", null);
		assertThat(plafondDe(societeIdPartenaire(apres.get("siren")))).contains("clients.exporter");
		assertThat(plafondDe(societeIdPartenaire(avant.get("siren")))).doesNotContain("clients.exporter");

		// Droit invalide refusé, modèle inchangé.
		Set<String> actuel = modeleActuel();
		MockHttpServletResponse invalide = mvc.perform(post("/p/partenaires/modele/ajouter")
				.param("droit", "DROIT INVALIDE!").with(user(admin)).with(csrf())).andReturn().getResponse();
		assertThat(invalide.getStatus()).isEqualTo(400);
		assertThat(modeleActuel()).isEqualTo(actuel);

		// Retrait un à un jusqu'au dernier : le dernier retrait est refusé.
		List<String> aRetirer = new ArrayList<>(actuel);
		String dernier = aRetirer.remove(aRetirer.size() - 1);
		for (String d : aRetirer) {
			mvc.perform(post("/p/partenaires/modele/retirer").param("droit", d).with(user(admin)).with(csrf()))
					.andExpect(status().isFound());
		}
		assertThat(modeleActuel()).containsExactly(dernier);
		MockHttpServletResponse refus = mvc.perform(post("/p/partenaires/modele/retirer").param("droit", dernier)
				.with(user(admin)).with(csrf())).andReturn().getResponse();
		assertThat(refus.getStatus()).isEqualTo(400);
		assertThat(refus.getContentAsString()).contains("Droit invalide, ou modèle qui deviendrait vide.");
		assertThat(modeleActuel()).containsExactly(dernier);
	}

	// ---- (i) mise à jour à chaud puis désinstallation ----------------------------------------------------------------

	@Test
	void i_mise_a_jour_a_chaud_sans_erreur_de_gabarit_puis_desinstallation_complete() throws Exception {
		Map<String, String> p = creerPartenaire("Chaud Partenaire SARL", "Chaud Manager", null);
		assertThat(page("/p/partenaires/liste", admin)).contains("Chaud Partenaire SARL");

		Path v2 = JarPartenairesReel.deriver(RACINE.resolve("jar-" + UUID.randomUUID()), "1.1.0", "Partenaires V2");
		installer(v2);

		MockHttpServletResponse liste = mvc.perform(get("/p/partenaires/liste").with(user(admin))).andReturn()
				.getResponse();
		assertThat(liste.getStatus()).as("X-Plugin=" + liste.getHeader("X-Plugin") + " actifs=" + manager.actifs().keySet() + " corps=" + liste.getContentAsString()).isEqualTo(200);
		assertThat(liste.getHeader("X-Plugin")).startsWith("partenaires@1.1.0#");
		assertThat(liste.getContentAsString()).contains("Chaud Partenaire SARL").contains("<body class=\"socle\">");
		assertThat(page("/accueil", admin)).contains("Partenaires V2").contains("href=\"/p/partenaires/liste\"");
		assertThat(page("/p/partenaires/nouveau", admin)).contains("<form");
		assertThat(page("/p/partenaires/modele", admin)).contains("clients.creer");
		assertThat(page("/p/partenaires/" + societeIdPartenaire(p.get("siren")) + "/clients/nouveau", admin))
			.contains("<form");

		assertThat(attendre(() -> manager.desinstaller("partenaires"))).isTrue();

		assertThat(page("/accueil", admin)).doesNotContain("Partenaires V2").doesNotContain("/p/partenaires/");
		for (String url : new String[] { "/p/partenaires/liste", "/p/partenaires/nouveau", "/p/partenaires/modele" }) {
			MockHttpServletResponse r = mvc.perform(get(url).with(user(admin))).andReturn().getResponse();
			assertThat(r.getStatus()).as(url).isEqualTo(404);
			assertThat(r.getContentAsString()).as(url).doesNotContain("Chaud Partenaire SARL");
		}
		assertThat(mvc.perform(post("/p/partenaires/").with(user(admin)).with(csrf())).andReturn().getResponse()
				.getStatus()).isEqualTo(404);
		assertThat(resolveur.resolveTemplate(null, null, "plugins/partenaires/liste", Map.of())).isNull();
	}

	// ---- aides -------------------------------------------------------------------------------------------------------

	/** POST du formulaire de création (en admin, avec jeton) : la réponse brute. */
	private MockHttpServletResponse envoyer(String siren, String raisonSociale, String email, String nom) throws Exception {
		return mvc.perform(post("/p/partenaires/").param("siren", siren).param("raisonSociale", raisonSociale)
				.param("email", email).param("nom", nom).with(user(admin)).with(csrf())).andReturn().getResponse();
	}

	/** Crée un partenaire par la page (302 attendu) ; renvoie siren, email et nom utilisés. */
	private Map<String, String> creerPartenaire(String raisonSociale, String nom, String tva) throws Exception {
		String siren = siren();
		String email = email();
		MockHttpServletRequestBuilder req = post("/p/partenaires/").param("siren", siren)
			.param("raisonSociale", raisonSociale)
			.param("email", email)
			.param("nom", nom);
		if (tva != null) {
			req = req.param("tva", tva);
		}
		mvc.perform(req.with(user(admin)).with(csrf())).andExpect(status().isFound())
				.andExpect(header().string("Location", endsWith("/p/partenaires/liste?ok=partenaire")));
		return Map.of("siren", siren, "email", email, "nom", nom);
	}

	private long societeIdPartenaire(String siren) {
		return jdbc.queryForObject("SELECT id FROM societes_identites WHERE siren = ?", Long.class, siren);
	}

	private Set<String> plafondDe(long societeId) {
		return new TreeSet<>(jdbc.queryForList("SELECT droit FROM plafonds_client WHERE client_societe_id = ?",
				String.class, societeId));
	}

	private Set<String> modeleActuel() {
		return new TreeSet<>(jdbc.queryForList("SELECT droit FROM modele_droits_partenaire", String.class));
	}

	private List<String> rolesDe(String email) {
		return jdbc.queryForList("SELECT r.name FROM users_roles ur JOIN roles r ON r.id = ur.role_id "
				+ "JOIN users u ON u.id = ur.user_id WHERE u.email = ?", String.class, email);
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
		java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?is)<form\\b[^>]*>").matcher(html);
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
		assertThat(r.statut()).as("installation du plugin partenaires : " + r.raisons())
				.isEqualTo(ResultatInstallation.Statut.OK);
		assertThat(manager.actifs()).containsKey("partenaires");
	}

	/** Exécute hors du fil du test : le gestionnaire fait du DDL et ses propres connexions. */
	private static <T> T attendre(java.util.function.Supplier<T> action) {
		return CompletableFuture.supplyAsync(action).join();
	}

	private String siren() {
		for (int n = 700000000 + (int) (Math.random() * 90000000);; n++) {
			String s = String.valueOf(n);
			if (NumerosSociete.sirenValide(s) && !sirens.contains(s)
					&& jdbc.queryForObject("SELECT count(*) FROM societes_identites WHERE siren = ?", Long.class, s) == 0) {
				sirens.add(s);
				return s;
			}
		}
	}

	private String email() {
		String e = "partenaires-it-" + UUID.randomUUID() + "@example.test";
		emails.add(e);
		return e;
	}

	private Role role(String nom) {
		return roles.findByName(nom).orElseGet(() -> roles.save(new Role(nom, nom, "ESN")));
	}

	private User utilisateur(Role role) {
		User u = new User();
		String e = "partenaires-it-" + UUID.randomUUID() + "@logicielpourtous.com";
		emails.add(e);
		u.setEmail(e);
		u.setPassword("test");
		u.setFullName("Utilisateur partenaires IT");
		u.setActive(true);
		u.setTenant(tenantDuTest);
		u.setRoles(Set.of(role));
		return users.saveAndFlush(u);
	}

	private Appartenance appartenance(User u, Role role) {
		Appartenance a = new Appartenance();
		a.setUserId(u.getId());
		a.setTenant(tenantDuTest);
		a.setSocieteId(societeDuTest);
		a.setRoles(Set.of(role));
		return appartenances.saveAndFlush(a);
	}

	private long schemas() {
		return jdbc.queryForObject("SELECT count(*) FROM information_schema.schemata WHERE schema_name LIKE 'p\\_%'",
				Long.class);
	}

	private long compter(String table) {
		return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
	}

	private record Compteurs(List<Long> valeurs) {
	}

	private Compteurs compteurs() {
		return new Compteurs(List.of(compter("societes_identites"), compter("users"), compter("tenants"),
				compter("journal_actions_maitre"), compter("liens_clients"), compter("plafonds_client"),
				compter("reglages_manager"), compter("appartenances"), schemas()));
	}

	/** Supprime tout ce que le test a créé (jamais d'effet de bord sur la base partagée) et restaure le modèle. */
	private void nettoyer() {
		if (modeleInitial != null && !modeleInitial.equals(modeleActuel())) {
			jdbc.update("DELETE FROM modele_droits_partenaire");
			for (String d : modeleInitial) {
				jdbc.update("INSERT INTO modele_droits_partenaire (droit) VALUES (?)", d);
			}
		}
		// Tenants à supprimer : ceux des utilisateurs créés par le test (partenaires), plus celui du test.
		Set<Long> tenantsCrees = new LinkedHashSet<>();
		Set<Long> societes = new LinkedHashSet<>();
		for (String siren : sirens) {
			societes.addAll(jdbc.queryForList("SELECT id FROM societes_identites WHERE siren = ?", Long.class, siren));
		}
		for (Long partenaire : new ArrayList<>(societes)) {
			societes.addAll(jdbc.queryForList("SELECT client_societe_id FROM liens_clients WHERE partenaire_societe_id = ?",
					Long.class, partenaire));
		}
		for (String email : emails) {
			tenantsCrees.addAll(jdbc.queryForList("SELECT tenant_id FROM users WHERE email = ?", Long.class, email));
			societes.addAll(jdbc.queryForList("SELECT a.societe_id FROM appartenances a JOIN users u ON u.id = a.user_id "
					+ "WHERE u.email = ? AND a.societe_id <> ?", Long.class, email, societeDuTest));
		}
		// Les sociétés des clients particuliers n'ont pas de SIREN du test : retrouvées par les liens ci-dessus.
		for (String email : emails) {
			jdbc.update("DELETE FROM appartenances_roles WHERE appartenance_id IN "
					+ "(SELECT a.id FROM appartenances a JOIN users u ON u.id = a.user_id WHERE u.email = ?)", email);
			jdbc.update("DELETE FROM appartenances WHERE user_id IN (SELECT id FROM users WHERE email = ?)", email);
			jdbc.update("DELETE FROM users_roles WHERE user_id IN (SELECT id FROM users WHERE email = ?)", email);
			jdbc.update("DELETE FROM users WHERE email = ?", email);
		}
		societes.add(societeDuTest);
		for (Long id : societes) {
			jdbc.update("DELETE FROM liens_clients WHERE client_societe_id = ? OR partenaire_societe_id = ?", id, id);
			jdbc.update("DELETE FROM reglages_manager WHERE client_societe_id = ?", id);
			jdbc.update("DELETE FROM plafonds_client WHERE client_societe_id = ?", id);
			jdbc.update("DELETE FROM partenaires_etat WHERE societe_id = ?", id);
			jdbc.update("DELETE FROM societe_capacites WHERE societe_id = ?", id);
			jdbc.update("DELETE FROM appartenances WHERE societe_id = ?", id);
			jdbc.update("DELETE FROM societes_identites WHERE id = ?", id);
		}
		if (adminUser != null) {
			jdbc.update("DELETE FROM journal_actions_maitre WHERE admin_user_id = ?", adminUser.getId());
		}
		tenantsCrees.add(tenantDuTest.getId());
		for (Long tenant : tenantsCrees) {
			for (String schema : jdbc.queryForList("SELECT schema_name FROM tenants WHERE id = ?", String.class, tenant)) {
				jdbc.update("DELETE FROM tenants WHERE id = ?", tenant);
				jdbc.execute("DROP SCHEMA IF EXISTS \"" + schema + "\" CASCADE");
			}
		}
		assertThat(schemas()).as("schémas p_* laissés par le test").isEqualTo(schemasAvant);
		assertThat(compter("tenants")).as("tenants laissés par le test").isEqualTo(tenantsAvant);
		assertThat(modeleActuel()).as("modèle de droits restauré").isEqualTo(modeleInitial);
	}

}

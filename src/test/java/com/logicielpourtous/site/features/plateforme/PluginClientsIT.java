package com.logicielpourtous.site.features.plateforme;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Set;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
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
import com.logicielpourtous.site.features.user.Appartenance;
import com.logicielpourtous.site.features.user.AppartenanceRepository;
import com.logicielpourtous.site.features.user.User;
import com.logicielpourtous.site.features.user.UserRepository;

/**
 * Plugin « clients » de bout en bout, sur l'application complète ({@code app.plateforme.actif=true}) : le VRAI jar
 * construit par {@code mvn -f plugins-clients/pom.xml package}, installé par {@link PluginManager#installer} avec la
 * signature OBLIGATOIRE (clés de test, voir {@link CleDeTest}), vraie chaîne de sécurité et jetons Bearer réels,
 * données (sociétés, droits, utilisateurs) créées par les vrais services et annulées en fin de test. Le plugin est
 * installé dans un autre fil que le test : le gestionnaire (DDL, copie) ne participe pas à la transaction du test.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
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
		// La signature reste OBLIGATOIRE (valeur par défaut) : seules les clés de confiance sont celles du test.
		// Le secret JWT de la config locale est trop court pour HS256 (le code refuse, à juste titre) : secret de test.
		registre.add("app.jwt.secret", () -> UUID.randomUUID() + "-" + UUID.randomUUID());
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

	@Autowired
	private MockMvc mvc;

	@Autowired
	private PluginManager manager;

	@Autowired
	private PlateformeProperties proprietes;

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

	private Long partenaireA;

	private Long partenaireB;

	private Long clientDeA;

	private Long clientDeB;

	private String jetonAvecDroits;

	private String jetonSansDroit;

	private String jetonAutrePartenaire;

	@BeforeEach
	void preparer() {
		assertThat(proprietes.isSignatureObligatoire()).as("la signature doit rester obligatoire").isTrue();
		JarClientsReel.localiser(); // échoue avec un message clair si le jar n'est pas construit
		supprimerSchema();
		Tenant tenant = new Tenant();
		tenant.setName("Tenant clients IT");
		tenant.setSchemaName("test_tenant_" + UUID.randomUUID());
		tenant = tenants.save(tenant);

		Role gestionnaire = roles.findByName("ROLE_GESTIONNAIRE")
				.orElseGet(() -> roles.save(new Role("ROLE_GESTIONNAIRE", "Gestionnaire", "ESN")));
		Role sansDroit = roles.save(new Role("ROLE_T14_SANS_DROIT_" + UUID.randomUUID(), "Sans droit", "ESN"));

		partenaireA = societe("PARTENAIRE", tenant.getId());
		partenaireB = societe("PARTENAIRE", tenant.getId());
		clientDeA = societe("CLIENT", null);
		clientDeB = societe("CLIENT", null);
		lier(clientDeA, partenaireA);
		lier(clientDeB, partenaireB);
		// Premier membre du client de A : son nom complet est le nom affiché du client.
		utilisateur(tenant, clientDeA, gestionnaire);

		Set<String> tous = Set.of("clients.lire", "clients.creer", "clients.entrer");
		droits.fixerPlafond(partenaireA, tous);
		droits.accorderAuRole(partenaireA, "ROLE_GESTIONNAIRE", tous);
		droits.fixerPlafond(partenaireB, tous);
		droits.accorderAuRole(partenaireB, "ROLE_GESTIONNAIRE", tous);

		jetonAvecDroits = jeton(utilisateur(tenant, partenaireA, gestionnaire));
		// Même société, mais un rôle auquel aucun droit n'est accordé : doit tout se voir refuser.
		jetonSansDroit = jeton(utilisateur(tenant, partenaireA, sansDroit));
		jetonAutrePartenaire = jeton(utilisateur(tenant, partenaireB, gestionnaire));
	}

	@AfterEach
	void apres() {
		try {
			attendre(() -> manager.desinstaller("clients"));
		}
		finally {
			TenantContext.clear();
			org.springframework.security.core.context.SecurityContextHolder.clearContext();
			supprimerSchema();
		}
	}

	@AfterAll
	static void supprimerDossier() throws IOException {
		try (Stream<Path> arbre = Files.walk(RACINE)) {
			arbre.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
		}
	}

	// ---- 2. installation et navigation -------------------------------------------------------------------------

	@Test
	void installationEtNavigation_tuileVisibleSeulementAvecLeDroit() throws Exception {
		installer(JarClientsReel.localiser());

		assertThat(manager.actifs()).containsKey("clients");
		mvc.perform(navigation(jetonAvecDroits)).andExpect(status().isOk())
				.andExpect(jsonPath("$[?(@.plugin=='clients')]", hasSize(1)))
				.andExpect(jsonPath("$[?(@.plugin=='clients')].libelle").value("Clients"))
				.andExpect(jsonPath("$[?(@.plugin=='clients')].zone").value("PARTENAIRE"))
				.andExpect(jsonPath("$[?(@.plugin=='clients')].chemin").value("/clients"))
				.andExpect(jsonPath("$[?(@.plugin=='clients')].version").value("1.0.0"));
		// Zone décidée par le serveur (sans paramètre zone) : société partenaire + rôle ROLE_GESTIONNAIRE -> tuile
		// PARTENAIRE ; jamais devinée d'après le rôle. Société cliente seule : jamais de tuile PARTENAIRE.
		mvc.perform(get("/api/navigation").header("Authorization", "Bearer " + jetonAvecDroits))
				.andExpect(status().isOk()).andExpect(jsonPath("$[?(@.plugin=='clients')]", hasSize(1)))
				.andExpect(jsonPath("$[?(@.plugin=='clients')].zone").value("PARTENAIRE"));
		mvc.perform(get("/api/navigation").header("Authorization", "Bearer " + jetonSansDroit))
				.andExpect(status().isOk()).andExpect(jsonPath("$[?(@.plugin=='clients')]", hasSize(0)));
		mvc.perform(get("/api/navigation")).andExpect(status().isUnauthorized());
		// Société du partenaire, mais rôle sans droit : aucune tuile.
		mvc.perform(navigation(jetonSansDroit)).andExpect(status().isOk())
				.andExpect(jsonPath("$[?(@.plugin=='clients')]", hasSize(0)));
		// Sans jeton : 401.
		mvc.perform(get("/api/navigation").param("zone", "PARTENAIRE")).andExpect(status().isUnauthorized());
		// Le bloc « resume » suit le même droit.
		mvc.perform(get("/api/navigation/blocs").header("Authorization", "Bearer " + jetonAvecDroits))
				.andExpect(status().isOk()).andExpect(jsonPath("$[?(@.plugin=='clients')]", hasSize(1)));
		mvc.perform(get("/api/navigation/blocs").header("Authorization", "Bearer " + jetonSansDroit))
				.andExpect(status().isOk()).andExpect(jsonPath("$[?(@.plugin=='clients')]", hasSize(0)));
	}

	// ---- 3. routes de données ----------------------------------------------------------------------------------

	@Test
	void routesDeDonnees_listeFicheEtBloc_seulementPourSesClients() throws Exception {
		installer(JarClientsReel.localiser());

		mvc.perform(api("/liste", jetonAvecDroits)).andExpect(status().isOk())
				.andExpect(jsonPath("$", hasSize(1)))
				.andExpect(jsonPath("$[0].societeId").value(clientDeA.intValue()))
				.andExpect(jsonPath("$[0].nom").value("Utilisateur T14"));
		mvc.perform(api("/" + clientDeA + "/fiche", jetonAvecDroits)).andExpect(status().isOk())
				.andExpect(jsonPath("$.resume.nom").value("Utilisateur T14"))
				.andExpect(jsonPath("$.partenaireSocieteId").value(partenaireA.intValue()));
		mvc.perform(api("/" + clientDeA + "/blocs/resume", jetonAvecDroits)).andExpect(status().isOk())
				.andExpect(jsonPath("$.societeId").value(clientDeA.intValue()));

		// Client d'un autre partenaire et identifiant inconnu : le même 404 vide, sans aucune différence.
		for (String bloc : new String[] { "/fiche", "/blocs/resume" }) {
			String autre = mvc.perform(api("/" + clientDeB + bloc, jetonAvecDroits)).andExpect(status().isNotFound())
					.andReturn().getResponse().getContentAsString();
			String inconnu = mvc.perform(api("/999999999" + bloc, jetonAvecDroits)).andExpect(status().isNotFound())
					.andReturn().getResponse().getContentAsString();
			assertThat(autre).isEmpty();
			assertThat(inconnu).isEqualTo(autre);
		}
		// Symétrie : l'autre partenaire voit SON client et pas celui du premier.
		mvc.perform(api("/liste", jetonAutrePartenaire)).andExpect(status().isOk())
				.andExpect(jsonPath("$", hasSize(1)))
				.andExpect(jsonPath("$[0].societeId").value(clientDeB.intValue()));
		mvc.perform(api("/" + clientDeA + "/fiche", jetonAutrePartenaire)).andExpect(status().isNotFound());

		// Sans droit : 403 sur toutes les routes de données. Sans jeton : 401.
		mvc.perform(api("/liste", jetonSansDroit)).andExpect(status().isForbidden());
		mvc.perform(api("/" + clientDeA + "/fiche", jetonSansDroit)).andExpect(status().isForbidden());
		mvc.perform(api("/" + clientDeA + "/blocs/resume", jetonSansDroit)).andExpect(status().isForbidden());
		mvc.perform(get("/api/plugins/clients/liste")).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/plugins/clients/" + clientDeA + "/fiche")).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/plugins/clients/" + clientDeA + "/blocs/resume")).andExpect(status().isUnauthorized());
		// Jeton invalide : même refus qu'un anonyme.
		mvc.perform(api("/liste", "pas-un-jeton")).andExpect(status().isUnauthorized());
	}

	@Test
	void creation_201_puis409_etRefuseSansDroit() throws Exception {
		installer(JarClientsReel.localiser());
		String email = "t14-" + UUID.randomUUID() + "@logicielpourtous.com";
		String corps = "{\"particulier\":true,\"etrangere\":false,\"email\":\"" + email + "\",\"nom\":\"Client T14\"}";

		mvc.perform(post("/api/plugins/clients").header("Authorization", "Bearer " + jetonAvecDroits)
				.contentType(MediaType.APPLICATION_JSON).content(corps)).andExpect(status().isCreated())
				.andExpect(jsonPath("$.issue").value("CREE"));
		mvc.perform(post("/api/plugins/clients").header("Authorization", "Bearer " + jetonAvecDroits)
				.contentType(MediaType.APPLICATION_JSON).content(corps)).andExpect(status().isConflict())
				.andExpect(jsonPath("$.issue").value("EMAIL_DEJA_UTILISE"));
		mvc.perform(post("/api/plugins/clients").header("Authorization", "Bearer " + jetonSansDroit)
				.contentType(MediaType.APPLICATION_JSON).content(corps)).andExpect(status().isForbidden());
		mvc.perform(post("/api/plugins/clients").contentType(MediaType.APPLICATION_JSON).content(corps))
				.andExpect(status().isUnauthorized());
	}

	// ---- 4. fichiers du front ----------------------------------------------------------------------------------

	@Test
	void front_servi_sans_jeton_pour_la_version_active_seulement() throws Exception {
		installer(JarClientsReel.localiser());

		mvc.perform(get("/api/plugins/clients/ui/1.0.0/main.js")).andExpect(status().isOk())
				.andExpect(header().string("Content-Type", containsString("text/javascript")))
				.andExpect(header().string("X-Content-Type-Options", "nosniff"))
				.andExpect(header().string("Cache-Control", containsString("immutable")))
				.andExpect(header().string("Cache-Control", containsString("max-age=31536000")));
		mvc.perform(get("/api/plugins/clients/ui/9.9.9/main.js")).andExpect(status().isNotFound());
		mvc.perform(get("/api/plugins/clients/ui/1.0.0/absent.js")).andExpect(status().isNotFound());
		mvc.perform(get("/api/plugins/clients/ui/1.0.0/plugin.properties")).andExpect(status().isNotFound());
		// Tout le reste de /api/plugins/clients/** exige un jeton.
		mvc.perform(get("/api/plugins/clients/liste")).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/plugins/clients/ui/1.0.0/")).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/plugins/clients/ui/1.0.0/a/b.js")).andExpect(status().isUnauthorized());
	}

	// ---- 5. mise à jour à chaud --------------------------------------------------------------------------------

	@Test
	void miseAJourAChaud_nouvelleTuileEtNouveauFront_sansRedemarrage() throws Exception {
		installer(JarClientsReel.localiser());
		mvc.perform(navigation(jetonAvecDroits)).andExpect(jsonPath("$[?(@.plugin=='clients')]", hasSize(1)))
				.andExpect(jsonPath("$[?(@.plugin=='clients')].version").value("1.0.0"));

		Path v101 = JarClientsReel.deriver(RACINE.resolve("derives"), "1.0.1",
				"entree.2=PARTENAIRE|Clients bis|star|/clients-bis|clients.lire", "/* clients 1.0.1 marqueur */\n");
		JarDePluginDeTest.signer(v101);
		ResultatInstallation r = attendre(() -> manager.installer(v101));
		assertThat(r.statut()).as(r.raisons().toString()).isEqualTo(ResultatInstallation.Statut.OK);

		assertThat(manager.actifs().get("clients").version).isEqualTo("1.0.1");
		assertThat(manager.etats()).anySatisfy(e -> {
			assertThat(e.nom()).isEqualTo("clients");
			assertThat(e.version()).isEqualTo("1.0.1");
			assertThat(e.etat()).isEqualTo(EtatPlugin.Etat.ACTIF);
		});
		mvc.perform(navigation(jetonAvecDroits)).andExpect(status().isOk())
				.andExpect(jsonPath("$[?(@.plugin=='clients')]", hasSize(2)))
				.andExpect(jsonPath("$[?(@.plugin=='clients' && @.libelle=='Clients bis')].version").value("1.0.1"))
				.andExpect(jsonPath("$[?(@.plugin=='clients' && @.libelle=='Clients')].version").value("1.0.1"));
		mvc.perform(get("/api/plugins/clients/ui/1.0.1/main.js")).andExpect(status().isOk())
				.andExpect(content().string(containsString("clients 1.0.1 marqueur")));
		mvc.perform(get("/api/plugins/clients/ui/1.0.0/main.js")).andExpect(status().isNotFound());
		// Les routes de données répondent sur la nouvelle génération.
		mvc.perform(api("/liste", jetonAvecDroits)).andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(1)));
		mvc.perform(api("/" + clientDeB + "/fiche", jetonAvecDroits)).andExpect(status().isNotFound());
	}

	// ---- 6. désinstallation ------------------------------------------------------------------------------------

	@Test
	void desinstallation_tuiles_routes_et_front_disparaissent() throws Exception {
		installer(JarClientsReel.localiser());
		mvc.perform(api("/liste", jetonAvecDroits)).andExpect(status().isOk());
		mvc.perform(get("/api/plugins/clients/ui/1.0.0/main.js")).andExpect(status().isOk());

		assertThat(attendre(() -> manager.desinstaller("clients"))).isTrue();

		assertThat(manager.actifs()).doesNotContainKey("clients");
		mvc.perform(navigation(jetonAvecDroits)).andExpect(status().isOk())
				.andExpect(jsonPath("$[?(@.plugin=='clients')]", hasSize(0)));
		mvc.perform(api("/liste", jetonAvecDroits)).andExpect(status().isNotFound());
		mvc.perform(api("/" + clientDeA + "/fiche", jetonAvecDroits)).andExpect(status().isNotFound());
		mvc.perform(get("/api/plugins/clients/ui/1.0.0/main.js")).andExpect(status().isNotFound());
		// Sans jeton, la chaîne de sécurité refuse toujours avant même de chercher le plugin.
		mvc.perform(get("/api/plugins/clients/liste")).andExpect(status().isUnauthorized());
	}

	// ---- aides -------------------------------------------------------------------------------------------------

	private void installer(Path jarNonSigne) {
		Path dossier = RACINE.resolve("depot-" + UUID.randomUUID());
		try {
			Files.createDirectories(dossier);
			Path jar = Files.copy(jarNonSigne, dossier.resolve(jarNonSigne.getFileName()));
			JarDePluginDeTest.signer(jar);
			ResultatInstallation r = attendre(() -> manager.installer(jar));
			assertThat(r.statut()).as("installation du vrai jar clients : " + r.raisons())
					.isEqualTo(ResultatInstallation.Statut.OK);
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/** Exécute hors du fil (et de la transaction) du test : le gestionnaire fait du DDL et ses propres connexions. */
	private static <T> T attendre(java.util.function.Supplier<T> action) {
		return CompletableFuture.supplyAsync(action).join();
	}

	private MockHttpServletRequestBuilder navigation(String jeton) {
		return get("/api/navigation").param("zone", "PARTENAIRE").header("Authorization", "Bearer " + jeton);
	}

	private static MockHttpServletRequestBuilder api(String chemin, String jeton) {
		return get("/api/plugins/clients" + chemin).header("Authorization", "Bearer " + jeton);
	}

	private Long societe(String type, Long proprietaireTenantId) {
		String suffixe = String.valueOf(Math.abs(UUID.randomUUID().getMostSignificantBits() % 1_000_000_000L));
		Long id = jdbc.queryForObject(
				"INSERT INTO societes_identites (siren, tva, type, proprietaire_tenant_id) VALUES (?, ?, ?, ?) RETURNING id",
				Long.class, "T14" + suffixe, "FRT14" + suffixe, type, proprietaireTenantId);
		jdbc.update("INSERT INTO societe_capacites (societe_id, capacite) VALUES (?, 'CLIENT')"
				+ " ON CONFLICT DO NOTHING", id);
		if ("PARTENAIRE".equals(type)) {
			jdbc.update("INSERT INTO societe_capacites (societe_id, capacite) VALUES (?, 'PARTENAIRE')"
					+ " ON CONFLICT DO NOTHING", id);
		}
		return id;
	}

	private void lier(Long client, Long partenaire) {
		jdbc.update("INSERT INTO liens_clients (client_societe_id, partenaire_societe_id, origine) VALUES (?, ?, 'PARTENAIRE')",
				client, partenaire);
	}

	/** Utilisateur actif, membre de la société donnée avec ce rôle ; renvoie le compte tel que le charge la chaîne. */
	private CustomUserDetails utilisateur(Tenant tenant, Long societeId, Role role) {
		User u = new User();
		u.setEmail("t14-" + UUID.randomUUID() + "@logicielpourtous.com");
		u.setPassword("test");
		u.setFullName("Utilisateur T14");
		u.setActive(true);
		u.setTenant(tenant);
		u.setRoles(Set.of(role));
		u = users.saveAndFlush(u);
		Appartenance a = new Appartenance();
		a.setUserId(u.getId());
		a.setTenant(tenant);
		a.setSocieteId(societeId);
		a.setRoles(Set.of(role));
		a = appartenances.saveAndFlush(a);
		return (CustomUserDetails) detailsService.charger(u.getEmail(), a.getId());
	}

	private String jeton(CustomUserDetails compte) {
		return jwt.jetonDuCompte(compte);
	}

	private void supprimerSchema() {
		attendre(() -> {
			jdbc.execute("DROP SCHEMA IF EXISTS plugin_clients CASCADE");
			return null;
		});
	}

}

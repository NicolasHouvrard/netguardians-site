package com.logicielpourtous.site.features.plateforme;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.NestedTestConfiguration;
import org.springframework.test.context.NestedTestConfiguration.EnclosingConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * API du dépôt du Maître ({@code /api/plateforme/depot}) sur l'application complète : vraie chaîne de sécurité,
 * plateforme active, rôle MASTER (valeur par défaut de {@code app.role}).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(DepotControllerTest.ClesDeTest.class)
class DepotControllerTest {

	private static final Path RACINE;

	static {
		try {
			RACINE = Files.createTempDirectory("depot-controller-test");
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static final Path DEPOT = RACINE.resolve("depot");

	@DynamicPropertySource
	static void proprietes(DynamicPropertyRegistry registre) {
		registre.add("app.plateforme.actif", () -> "true");
		registre.add("app.plateforme.repertoire-plugins", () -> RACINE.resolve("plugins").toString());
		registre.add("app.plateforme.depot-maitre", DEPOT::toString);
		registre.add("app.plateforme.taille-max-jar-mo", () -> "1");
	}

	/** Clé de test de confiance (la clé livrée « dev- » n'a plus de clé privée). */
	@TestConfiguration
	static class ClesDeTest {

		@Bean
		@Primary
		ClesPubliques clesPubliquesDeTest() {
			return new ClesPubliques(JarDePluginDeTest.clesDeTest());
		}

	}

	@AfterAll
	static void supprimerDossiers() throws IOException {
		try (Stream<Path> s = Files.walk(RACINE)) {
			for (Path p : s.sorted(Comparator.reverseOrder()).toList()) {
				Files.deleteIfExists(p);
			}
		}
	}

	@Autowired
	private MockMvc mvc;

	private static int compteur;

	@BeforeEach
	void viderLeDepot() throws IOException {
		viderSousDossier(DEPOT);
	}

	static void viderSousDossier(Path dossier) throws IOException {
		if (!Files.exists(dossier)) {
			return;
		}
		try (Stream<Path> s = Files.walk(dossier)) {
			for (Path p : s.sorted(Comparator.reverseOrder()).filter(Files::isRegularFile).toList()) {
				Files.deleteIfExists(p);
			}
		}
	}

	static List<Path> fichiers(Path dossier) throws IOException {
		if (!Files.exists(dossier)) {
			return List.of();
		}
		try (Stream<Path> s = Files.walk(dossier)) {
			return s.filter(Files::isRegularFile).toList();
		}
	}

	static RequestPostProcessor admin() {
		return user("admin-depot").roles("ADMIN");
	}

	static RequestPostProcessor commercial() {
		return user("commercial-depot").roles("COMMERCIAL", "ESN_USER");
	}

	static Path jar(String nom, String version, Map<String, String> enPlus) {
		Map<String, String> props = new java.util.LinkedHashMap<>(Map.of("coeur-min", "1.0.0", "mise-a-jour", "coeur",
				"paquet", nom, "configuration", nom + ".Config"));
		props.putAll(enPlus);
		return JarDePluginDeTest.creer(RACINE.resolve("construction-" + (compteur++)), nom, version, props, Map.of());
	}

	/** Requête de publication ; le nom de fichier annoncé par le client est volontairement trompeur. */
	static MockMultipartHttpServletRequestBuilder publication(byte[] jar, byte[] sig) {
		return multipart("/api/plateforme/depot")
			.file(new MockMultipartFile("jar", "../../evil-6.6.6.jar", "application/java-archive", jar))
			.file(new MockMultipartFile("sig", "../../evil-6.6.6.jar.sig", "application/json", sig));
	}

	static MockMultipartHttpServletRequestBuilder publication(Path jar) throws IOException {
		return publication(Files.readAllBytes(jar), Files.readAllBytes(JarDePluginDeTest.sig(jar)));
	}

	@Test
	void sans_jeton_401() throws Exception {
		Path jar = JarDePluginDeTest.signer(jar("alpha", "1.0.0", Map.of()));
		mvc.perform(get("/api/plateforme/depot")).andExpect(status().isUnauthorized());
		mvc.perform(publication(jar)).andExpect(status().isUnauthorized());
		assertThat(fichiers(DEPOT)).isEmpty();
	}

	@Test
	void non_admin_403() throws Exception {
		Path jar = JarDePluginDeTest.signer(jar("alpha", "1.0.0", Map.of()));
		mvc.perform(get("/api/plateforme/depot").with(commercial())).andExpect(status().isForbidden());
		mvc.perform(publication(jar).with(commercial())).andExpect(status().isForbidden());
		assertThat(fichiers(DEPOT)).isEmpty();
	}

	@Test
	void admin_publie_200_et_entree_au_catalogue() throws Exception {
		Path jar = JarDePluginDeTest.signer(jar("alpha", "1.2.3", Map.of()));
		String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar)));

		mvc.perform(publication(jar).with(admin()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.nom").value("alpha"))
			.andExpect(jsonPath("$.version").value("1.2.3"))
			.andExpect(jsonPath("$.miseAJour").value("coeur"))
			.andExpect(jsonPath("$.coeurMin").value("1.0.0"))
			.andExpect(jsonPath("$.sha256").value(sha))
			.andExpect(jsonPath("$.taille").value(Files.size(jar)));

		mvc.perform(get("/api/plateforme/depot").with(admin()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(1))
			.andExpect(jsonPath("$[0].nom").value("alpha"))
			.andExpect(jsonPath("$[0].sha256").value(sha));
		// Nom stocké tiré du manifeste vérifié, jamais du nom de fichier annoncé.
		assertThat(fichiers(DEPOT)).extracting(p -> p.getFileName().toString())
			.containsExactlyInAnyOrder("alpha-1.2.3.jar", "alpha-1.2.3.jar.sig");
		assertThat(fichiers(RACINE)).noneMatch(p -> p.getFileName().toString().contains("evil"));
	}

	@Test
	void erreurs_400_409_413_sans_echo_du_texte_brut() throws Exception {
		Path jar = JarDePluginDeTest.signer(jar("alpha", "1.0.0", Map.of()));
		byte[] sigHostile = ("{\"keyId\":\"<script>‮" + "z".repeat(300) + "\",\"algo\":\"Ed25519\",\"signature\":\"AA==\"}")
			.getBytes(java.nio.charset.StandardCharsets.UTF_8);

		String corps = mvc.perform(publication(Files.readAllBytes(jar), sigHostile).with(admin()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.erreur").exists())
			.andReturn()
			.getResponse()
			.getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
		assertThat(corps).doesNotContain("<script>").doesNotContain("‮").doesNotContain("z".repeat(100));

		mvc.perform(publication(jar).with(admin())).andExpect(status().isOk());
		Path autre = JarDePluginDeTest.signer(jar("alpha", "1.0.0", Map.of("dependances", "beta")));
		mvc.perform(publication(autre).with(admin())).andExpect(status().isConflict());

		// Au-delà de taille-max-jar-mo (1 Mo ici) : 413, avant toute vérification (sinon ce serait 400).
		byte[] gros = new byte[1024 * 1024 + 1];
		new java.util.Random(42).nextBytes(gros);
		mvc.perform(publication(gros, "{}".getBytes()).with(admin())).andExpect(status().isPayloadTooLarge());

		mvc.perform(multipart("/api/plateforme/depot")
			.file(new MockMultipartFile("jar", "a.jar", "application/java-archive", Files.readAllBytes(jar)))
			.with(admin())).andExpect(status().isBadRequest());

		assertThat(fichiers(DEPOT)).extracting(p -> p.getFileName().toString())
			.containsExactlyInAnyOrder("alpha-1.0.0.jar", "alpha-1.0.0.jar.sig");
	}

	/** Rôle de serveur autre que MASTER : ni bean ni route du dépôt (le reste de la plateforme reste actif). */
	@Nested
	@NestedTestConfiguration(EnclosingConfiguration.OVERRIDE)
	@SpringBootTest
	@AutoConfigureMockMvc
	@TestPropertySource(properties = "app.role=partner")
	class RoleNonMaitre {

		@DynamicPropertySource
		static void proprietes(DynamicPropertyRegistry registre) {
			registre.add("app.plateforme.actif", () -> "true");
			registre.add("app.plateforme.repertoire-plugins", () -> RACINE.resolve("plugins-partenaire").toString());
			registre.add("app.plateforme.signature-obligatoire", () -> "false");
			registre.add("app.plateforme.depot-maitre", () -> RACINE.resolve("depot-partenaire").toString());
		}

		@Autowired
		private ApplicationContext contexte;

		@Autowired
		private MockMvc mvc;

		@Test
		void absent_quand_le_role_n_est_pas_master() throws Exception {
			assertThat(contexte.getBeansOfType(PluginManager.class)).hasSize(1);
			assertThat(contexte.getBeansOfType(DepotPlugins.class)).isEmpty();
			assertThat(contexte.getBeansOfType(DepotController.class)).isEmpty();

			Path jar = JarDePluginDeTest.signer(jar("alpha", "1.0.0", Map.of()));
			mvc.perform(get("/api/plateforme/depot").with(admin())).andExpect(status().isNotFound());
			mvc.perform(publication(jar).with(admin())).andExpect(status().isNotFound());
			assertThat(Files.exists(RACINE.resolve("depot-partenaire"))).isFalse();
		}

	}

	/**
	 * Réglages PAR DÉFAUT (signature obligatoire, clés livrées du cœur, aucune clé de test injectée) : un jar non
	 * signé ou signé d'une clé inconnue est refusé et rien n'est écrit.
	 */
	@Nested
	@NestedTestConfiguration(EnclosingConfiguration.OVERRIDE)
	@SpringBootTest
	@AutoConfigureMockMvc
	class ReglagesParDefaut {

		@DynamicPropertySource
		static void proprietes(DynamicPropertyRegistry registre) {
			registre.add("app.plateforme.actif", () -> "true");
			registre.add("app.plateforme.repertoire-plugins", () -> RACINE.resolve("plugins-defaut").toString());
			registre.add("app.plateforme.depot-maitre", () -> RACINE.resolve("depot-defaut").toString());
		}

		@Autowired
		private PlateformeProperties proprietes;

		@Autowired
		private MockMvc mvc;

		@Test
		void jar_non_signe_refuse_et_rien_ecrit() throws Exception {
			assertThat(proprietes.isSignatureObligatoire()).isTrue();
			Path nonSigne = jar("alpha", "1.0.0", Map.of());
			Path signeParLaCleDeTest = JarDePluginDeTest.signer(jar("alpha", "1.0.1", Map.of()));

			mvc.perform(publication(Files.readAllBytes(nonSigne), new byte[0]).with(admin()))
				.andExpect(status().isBadRequest());
			mvc.perform(publication(Files.readAllBytes(nonSigne), "{}".getBytes()).with(admin()))
				.andExpect(status().isBadRequest());
			mvc.perform(publication(signeParLaCleDeTest).with(admin())).andExpect(status().isBadRequest());

			assertThat(fichiers(RACINE.resolve("depot-defaut"))).isEmpty();
			mvc.perform(get("/api/plateforme/depot").with(admin()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(0));
		}

	}

}

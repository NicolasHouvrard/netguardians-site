package com.logicielpourtous.site.features.plateforme;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
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
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * L'écran {@code /admin/plugins} avec le VRAI dépôt et le VRAI gestionnaire : jar réel du plugin « partenaires »,
 * signé avec la clé de test (copies dans un dossier temporaire, rien n'est écrit dans {@code target/}).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(PluginsEcranIT.CleDeTest.class)
class PluginsEcranIT {

	private static final Path RACINE;

	private static final Path REPERTOIRE;

	private static final Path DEPOT;

	static {
		try {
			RACINE = Files.createTempDirectory("plugins-ecran-it");
			REPERTOIRE = Files.createDirectories(RACINE.resolve("plugins"));
			DEPOT = RACINE.resolve("depot");
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	@DynamicPropertySource
	static void proprietes(DynamicPropertyRegistry registre) {
		registre.add("app.plateforme.actif", () -> "true");
		registre.add("app.plateforme.repertoire-plugins", REPERTOIRE::toString);
		registre.add("app.plateforme.depot-maitre", DEPOT::toString);
		registre.add("app.plateforme.delai-drainage-ms", () -> "5000");
		registre.add("app.role", () -> "MASTER");
		registre.add("captcha.enabled", () -> "false");
		registre.add("app.jwt.secret", () -> UUID.randomUUID() + "-" + UUID.randomUUID().toString().substring(0, 27));
	}

	/** Clés de confiance du test (paire éphémère en mémoire). */
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
	private DepotPlugins depot;

	@Autowired
	private PluginManager manager;

	private static RequestPostProcessor admin() {
		return user("admin-ecran-it").roles("ADMIN");
	}

	@BeforeEach
	void viderLeDepot() throws IOException {
		JarPartenairesReel.localiser(); // échoue avec la commande de build si le jar n'est pas construit
		DepotControllerTest.viderSousDossier(DEPOT);
	}

	@AfterEach
	void desinstaller() {
		CompletableFuture.supplyAsync(() -> manager.desinstaller("partenaires")).join();
	}

	@AfterAll
	static void supprimerDossier() throws IOException {
		try (Stream<Path> arbre = Files.walk(RACINE)) {
			arbre.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
		}
	}

	private static MockMultipartHttpServletRequestBuilder envoi(byte[] jar, byte[] sig) {
		return multipart("/admin/plugins/publier")
			.file(new MockMultipartFile("jar", "../../partenaires-1.1.0.jar", "application/java-archive", jar))
			.file(new MockMultipartFile("sig", "partenaires-1.1.0.jar.sig", "application/json", sig));
	}

	private static MockMultipartHttpServletRequestBuilder envoi(Path jar) throws IOException {
		return envoi(Files.readAllBytes(jar), Files.readAllBytes(JarDePluginDeTest.sig(jar)));
	}

	private Path jarSigne() {
		return JarPartenairesReel.copieSignee(RACINE.resolve("jar-" + UUID.randomUUID()));
	}

	@Test
	void publier_puis_republier_identique_puis_la_page_liste_le_plugin() throws Exception {
		Path jar = jarSigne();

		mvc.perform(envoi(jar).with(admin()).with(csrf())).andExpect(status().isFound())
			.andExpect(redirectedUrl("/admin/plugins?ok=publie"));
		assertThat(depot.catalogue()).extracting(EntreeCatalogue::nom).containsExactly("partenaires");

		mvc.perform(envoi(jar).with(admin()).with(csrf())).andExpect(status().isFound())
			.andExpect(redirectedUrl("/admin/plugins?ok=identique"));
		assertThat(depot.catalogue()).hasSize(1);

		String html = mvc.perform(get("/admin/plugins").with(admin())).andExpect(status().isOk()).andReturn()
			.getResponse().getContentAsString();
		assertThat(html).contains("partenaires").contains("1.1.0");
	}

	@Test
	void jar_modifie_d_un_octet_avec_l_ancienne_signature_400_et_depot_inchange() throws Exception {
		byte[] jar = Files.readAllBytes(jarSigne());
		byte[] sig = Files.readAllBytes(JarDePluginDeTest.sig(jarSigne()));
		jar[jar.length / 2] ^= 1;

		String html = mvc.perform(envoi(jar, sig).with(admin()).with(csrf())).andExpect(status().isBadRequest())
			.andReturn().getResponse().getContentAsString();

		assertThat(depot.catalogue()).isEmpty();
		assertThat(DepotControllerTest.fichiers(DEPOT)).isEmpty();
		assertThat(html).contains("signature invalide");
		assertSansChemin(html);
	}

	@Test
	void signature_d_une_cle_inconnue_400_et_depot_inchange() throws Exception {
		Path jar = JarPartenairesReel.copieSignee(RACINE.resolve("inconnue-" + UUID.randomUUID()));
		JarDePluginDeTest.signer(jar, JarDePluginDeTest.nouvellePaire().getPrivate(), "cle-inconnue-9");

		String html = mvc.perform(envoi(jar).with(admin()).with(csrf())).andExpect(status().isBadRequest())
			.andReturn().getResponse().getContentAsString();

		assertThat(depot.catalogue()).isEmpty();
		assertThat(DepotControllerTest.fichiers(DEPOT)).isEmpty();
		assertSansChemin(html);
	}

	@Test
	void meme_version_contenu_different_409_et_depot_inchange() throws Exception {
		Path jar = jarSigne();
		mvc.perform(envoi(jar).with(admin()).with(csrf())).andExpect(status().isFound());
		List<Path> avant = DepotControllerTest.fichiers(DEPOT);

		Path autre = JarPartenairesReel.deriver(RACINE.resolve("autre-" + UUID.randomUUID()), "1.1.0", "Autre libellé");
		String html = mvc.perform(envoi(autre).with(admin()).with(csrf())).andExpect(status().isConflict()).andReturn()
			.getResponse().getContentAsString();

		assertThat(html).contains("version déjà publiée avec un contenu différent : publier un numéro plus haut");
		assertThat(DepotControllerTest.fichiers(DEPOT)).containsExactlyInAnyOrderElementsOf(avant);
		assertSansChemin(html);
	}

	@Test
	void installer_puis_desinstaller_via_l_ecran_retire_le_plugin_en_service() throws Exception {
		Path jar = jarSigne();
		Path dansRepertoire = Files.copy(jar, REPERTOIRE.resolve(jar.getFileName()),
				java.nio.file.StandardCopyOption.REPLACE_EXISTING);
		Files.copy(JarDePluginDeTest.sig(jar), JarDePluginDeTest.sig(dansRepertoire),
				java.nio.file.StandardCopyOption.REPLACE_EXISTING);
		ResultatInstallation r = CompletableFuture.supplyAsync(() -> manager.installer(dansRepertoire)).join();
		assertThat(r.statut()).as("installation : " + r.raisons()).isEqualTo(ResultatInstallation.Statut.OK);
		assertThat(manager.actifs()).containsKey("partenaires");
		assertThat(mvc.perform(get("/admin/plugins").with(admin())).andReturn().getResponse().getContentAsString())
			.contains("/admin/plugins/partenaires/desinstaller");

		mvc.perform(post("/admin/plugins/partenaires/desinstaller").with(admin()).with(csrf()))
			.andExpect(status().isFound()).andExpect(redirectedUrl("/admin/plugins?ok=desinstalle"));

		assertThat(manager.actifs()).doesNotContainKey("partenaires");
		assertThat(manager.etats()).extracting(EtatPlugin::nom).doesNotContain("partenaires");
		// Sa route et donc sa tuile (déduite des plugins en service) ont disparu.
		assertThat(mvc.perform(get("/p/partenaires/liste").with(admin())).andReturn().getResponse().getStatus())
			.isIn(404, 503);
		assertThat(mvc.perform(get("/admin/plugins").with(admin())).andReturn().getResponse().getContentAsString())
			.doesNotContain("/admin/plugins/partenaires/desinstaller");

		// Deuxième désinstallation : plugin inconnu, message sans erreur serveur.
		mvc.perform(post("/admin/plugins/partenaires/desinstaller").with(admin()).with(csrf()))
			.andExpect(status().isNotFound());
	}

	/** Aucun chemin de fichier (dossier temporaire, répertoire de travail, séparateur de chemin) dans les messages. */
	private static void assertSansChemin(String html) {
		String page = html.replaceAll("(?s)<(script|style)\\b.*?</\\1>", "");
		assertThat(page).doesNotContain(RACINE.toString()).doesNotContain(System.getProperty("java.io.tmpdir"))
			.doesNotContain(Path.of("").toAbsolutePath().toString());
	}

}

package com.logicielpourtous.site.features.plateforme;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * API d'administration de la plateforme, sur l'application complète avec {@code app.plateforme.actif=true} : vraie
 * chaîne de sécurité, vrai gestionnaire, plugin de test « testnotes » sur la base PostgreSQL locale (schéma jetable
 * {@code plugin_testnotes}).
 */
@SpringBootTest
@AutoConfigureMockMvc
class PluginAdminControllerTest {

	private static final Path RACINE;

	private static final Path REPERTOIRE;

	static {
		try {
			RACINE = Files.createTempDirectory("plugin-admin-test");
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
	}

	@Autowired
	private MockMvc mvc;

	@Autowired
	private PluginManager manager;

	@Autowired
	private JdbcTemplate jdbc;

	@BeforeEach
	void preparer() {
		nettoyer();
	}

	@AfterEach
	void apres() {
		nettoyer();
	}

	@AfterAll
	static void supprimerDossiers() throws IOException {
		try (Stream<Path> s = Files.walk(RACINE)) {
			for (Path p : s.sorted(Comparator.reverseOrder()).toList()) {
				Files.deleteIfExists(p);
			}
		}
	}

	private void nettoyer() {
		manager.desinstaller("testnotes");
		jdbc.execute("DROP SCHEMA IF EXISTS plugin_testnotes CASCADE");
	}

	private static RequestPostProcessor admin() {
		return user("admin-plateforme").roles("ADMIN");
	}

	private static RequestPostProcessor commercial() {
		return user("commercial-plateforme").roles("COMMERCIAL", "ESN_USER");
	}

	/** Dépose {@code testnotes-<version>.jar} dans le répertoire des plugins (construit à côté, hors du répertoire). */
	private static String deposer(String version) throws IOException {
		Path jar = JarDePluginDeTest.pluginNotes(RACINE.resolve("construction-" + version), version,
				Map.of("V1__note.sql", JarDePluginDeTest.MIGRATION_NOTES_V1));
		String nom = "testnotes-" + version + ".jar";
		Files.copy(jar, REPERTOIRE.resolve(nom), StandardCopyOption.REPLACE_EXISTING);
		return nom;
	}

	private static MockHttpServletRequestBuilder installer(String fichier) {
		String corps = fichier == null ? "{}" : "{\"fichier\":\"" + fichier.replace("\\", "\\\\") + "\"}";
		return post("/api/plateforme/plugins/installer").contentType(MediaType.APPLICATION_JSON).content(corps);
	}

	@Test
	void installer_refuse_un_nom_avec_chemin() throws Exception {
		for (String nom : new String[] { "../x.jar", "a/b.jar", "a\\b.jar", "..", "", "/etc/x.jar", "C:\\x.jar" }) {
			mvc.perform(installer(nom).with(admin())).andExpect(status().isBadRequest());
		}
		mvc.perform(installer(null).with(admin())).andExpect(status().isBadRequest());
		assertThat(manager.actifs()).isEmpty();
	}

	@Test
	void installer_un_fichier_absent_ou_qui_n_est_pas_un_fichier_repond_404() throws Exception {
		mvc.perform(installer("inconnu.jar").with(admin())).andExpect(status().isNotFound());
		Files.createDirectories(REPERTOIRE.resolve("dossier.jar"));
		mvc.perform(installer("dossier.jar").with(admin())).andExpect(status().isNotFound());
	}

	@Test
	void installer_un_jar_valide_repond_ok() throws Exception {
		String fichier = deposer("1.0.0");

		mvc.perform(installer(fichier).with(admin()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.statut").value("OK"))
			.andExpect(jsonPath("$.plugin").value("testnotes"))
			.andExpect(jsonPath("$.version").value("1.0.0"));

		assertThat(manager.actifs()).containsKey("testnotes");
		// Le plugin est réellement servi par l'application complète.
		mvc.perform(get("/api/plugins/testnotes/notes").with(commercial())).andExpect(status().isOk());
	}

	@Test
	void liste_montre_la_version_active() throws Exception {
		mvc.perform(get("/api/plateforme/plugins").with(admin()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(0));

		mvc.perform(installer(deposer("1.0.0")).with(admin())).andExpect(jsonPath("$.statut").value("OK"));

		mvc.perform(get("/api/plateforme/plugins").with(admin()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(1))
			.andExpect(jsonPath("$[0].nom").value("testnotes"))
			.andExpect(jsonPath("$[0].version").value("1.0.0"))
			.andExpect(jsonPath("$[0].etat").value("ACTIF"));
	}

	@Test
	void liste_montre_maintenance_pendant_une_mise_a_jour() throws Exception {
		mvc.perform(installer(deposer("1.0.0")).with(admin())).andExpect(jsonPath("$.statut").value("OK"));
		PluginGeneration v1 = manager.actifs().get("testnotes");
		String v2 = deposer("1.1.0");

		// Une requête lente tient la v1 en vol : la mise à jour reste en drainage (maintenance) pendant ce temps.
		CompletableFuture<Integer> lente = CompletableFuture.supplyAsync(() -> {
			try {
				return mvc.perform(get("/api/plugins/testnotes/notes/lent").param("ms", "2500").with(commercial()))
					.andReturn()
					.getResponse()
					.getStatus();
			}
			catch (Exception e) {
				throw new IllegalStateException(e);
			}
		});
		long limite = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (v1.enVol.get() == 0 && System.nanoTime() < limite) {
			Thread.sleep(5);
		}
		assertThat(v1.enVol.get()).as("requête lente en vol sur la v1").isPositive();

		CompletableFuture<String> miseAJour = CompletableFuture.supplyAsync(() -> {
			try {
				return mvc.perform(installer(v2).with(admin())).andReturn().getResponse().getContentAsString();
			}
			catch (Exception e) {
				throw new IllegalStateException(e);
			}
		});
		boolean vu = false;
		while (!vu && !miseAJour.isDone() && System.nanoTime() < limite + TimeUnit.SECONDS.toNanos(5)) {
			String liste = mvc.perform(get("/api/plateforme/plugins").with(admin()))
				.andExpect(status().isOk())
				.andReturn()
				.getResponse()
				.getContentAsString();
			vu = liste.contains("\"etat\":\"MAINTENANCE\"") && liste.contains("\"nom\":\"testnotes\"");
			Thread.sleep(5);
		}
		assertThat(vu).as("état MAINTENANCE visible pendant la mise à jour").isTrue();
		assertThat(lente.get(10, TimeUnit.SECONDS)).isEqualTo(200);
		assertThat(miseAJour.get(10, TimeUnit.SECONDS)).contains("\"statut\":\"OK\"");

		mvc.perform(get("/api/plateforme/plugins").with(admin()))
			.andExpect(jsonPath("$[0].version").value("1.1.0"))
			.andExpect(jsonPath("$[0].etat").value("ACTIF"));
	}

	@Test
	void fuites_repond_un_rapport() throws Exception {
		mvc.perform(get("/api/plateforme/fuites").with(admin()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.retires").isNumber())
			.andExpect(jsonPath("$.toujoursVivants").isNumber())
			.andExpect(jsonPath("$.etiquettesVivantes").isArray())
			.andExpect(jsonPath("$.metaspaceMo").isNumber());
		// Sans app.plateforme.diagnostic-fuites, la pression demandée est ignorée (pas de remplissage du tas).
		mvc.perform(get("/api/plateforme/fuites").param("pression", "true").with(admin()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.retires").isNumber());
	}

	@Test
	void desinstaller_retire_le_plugin() throws Exception {
		mvc.perform(installer(deposer("1.0.0")).with(admin())).andExpect(jsonPath("$.statut").value("OK"));

		mvc.perform(delete("/api/plateforme/plugins/testnotes").with(admin())).andExpect(status().isNoContent());

		assertThat(manager.actifs()).doesNotContainKey("testnotes");
		mvc.perform(get("/api/plateforme/plugins").with(admin())).andExpect(jsonPath("$.length()").value(0));
		mvc.perform(delete("/api/plateforme/plugins/testnotes").with(admin())).andExpect(status().isNotFound());
	}

	@Test
	void non_admin_refuse_403() throws Exception {
		String fichier = deposer("1.0.0");
		mvc.perform(get("/api/plateforme/plugins").with(commercial())).andExpect(status().isForbidden());
		mvc.perform(get("/api/plateforme/fuites").with(commercial())).andExpect(status().isForbidden());
		mvc.perform(installer(fichier).with(commercial())).andExpect(status().isForbidden());
		mvc.perform(delete("/api/plateforme/plugins/testnotes").with(commercial())).andExpect(status().isForbidden());
		assertThat(manager.actifs()).isEmpty();
	}

	@Test
	void sans_authentification_refuse_401() throws Exception {
		String fichier = deposer("1.0.0");
		mvc.perform(get("/api/plateforme/plugins")).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/plateforme/fuites")).andExpect(status().isUnauthorized());
		mvc.perform(installer(fichier)).andExpect(status().isUnauthorized());
		mvc.perform(delete("/api/plateforme/plugins/testnotes")).andExpect(status().isUnauthorized());
		assertThat(manager.actifs()).isEmpty();
	}

}

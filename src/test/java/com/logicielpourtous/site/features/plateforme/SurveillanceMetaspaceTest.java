package com.logicielpourtous.site.features.plateforme;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** Surveillance du Metaspace : repère de redémarrage (unitaire) et route {@code /api/plateforme/metaspace} (chaîne réelle). */
@SpringBootTest
@AutoConfigureMockMvc
class SurveillanceMetaspaceTest {

	private static final long MO = 1024L * 1024L;

	private static final Path RACINE;

	static {
		try {
			RACINE = Files.createTempDirectory("surveillance-metaspace-test");
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	@DynamicPropertySource
	static void proprietes(DynamicPropertyRegistry registre) {
		registre.add("app.plateforme.actif", () -> "true");
		registre.add("app.plateforme.signature-obligatoire", () -> "false");
		registre.add("app.plateforme.repertoire-plugins", () -> RACINE.resolve("plugins").toString());
		registre.add("app.plateforme.repertoire-donnees", () -> RACINE.resolve("route").toString());
	}

	@AfterAll
	static void supprimer() throws IOException {
		try (Stream<Path> s = Files.walk(RACINE)) {
			for (Path p : s.sorted(Comparator.reverseOrder()).toList()) {
				Files.deleteIfExists(p);
			}
		}
	}

	@Autowired
	private MockMvc mvc;

	private static PlateformeProperties proprietes(Path donnees, long seuilMo) {
		PlateformeProperties p = new PlateformeProperties();
		p.setRepertoireDonnees(donnees.toString());
		p.setMetaspaceSeuilMo(seuilMo);
		return p;
	}

	private static Path dossier() throws IOException {
		return Files.createTempDirectory(RACINE, "d");
	}

	@Test
	void repere_ecrit_au_dessus_du_seuil_une_seule_fois() throws Exception {
		Path donnees = dossier();
		AtomicLong mesure = new AtomicLong(600 * MO);
		SurveillanceMetaspace s = new SurveillanceMetaspace(proprietes(donnees, 512), mesure::get);
		Path repere = donnees.resolve(SurveillanceMetaspace.NOM_REPERE);

		s.controler();
		assertThat(repere).isRegularFile();
		JsonNode json = new ObjectMapper().readTree(Files.readAllBytes(repere));
		assertThat(json.get("metaspaceMo").asLong()).isEqualTo(600);
		assertThat(json.get("seuilMo").asLong()).isEqualTo(512);
		assertThat(json.get("depuis").asText()).isNotBlank();
		// Aucun fichier temporaire ne reste.
		try (Stream<Path> fichiers = Files.list(donnees)) {
			assertThat(fichiers.map(p -> p.getFileName().toString())).containsExactly(SurveillanceMetaspace.NOM_REPERE);
		}

		// Deuxième contrôle, mesure plus haute : le repère n'est pas réécrit (même contenu, même date).
		byte[] avant = Files.readAllBytes(repere);
		mesure.set(700 * MO);
		s.controler();
		assertThat(Files.readAllBytes(repere)).isEqualTo(avant);
	}

	@Test
	void repere_supprime_quand_la_mesure_redescend() throws Exception {
		Path donnees = dossier();
		AtomicLong mesure = new AtomicLong(600 * MO);
		SurveillanceMetaspace s = new SurveillanceMetaspace(proprietes(donnees, 512), mesure::get);
		Path repere = donnees.resolve(SurveillanceMetaspace.NOM_REPERE);
		s.controler();
		assertThat(repere).exists();

		mesure.set(100 * MO);
		s.controler();
		assertThat(repere).doesNotExist();
		assertThat(s.redemarrageRecommande()).isFalse();

		// Remonter au-dessus du seuil réécrit le repère.
		mesure.set(900 * MO);
		s.controler();
		assertThat(repere).exists();
		assertThat(s.redemarrageRecommande()).isTrue();
	}

	@Test
	void aucune_ecriture_sous_le_seuil() throws Exception {
		Path donnees = dossier();
		SurveillanceMetaspace s = new SurveillanceMetaspace(proprietes(donnees, 512), () -> 512 * MO);
		s.controler();
		try (Stream<Path> fichiers = Files.list(donnees)) {
			assertThat(fichiers).isEmpty();
		}
		assertThat(s.redemarrageRecommande()).isFalse();
	}

	@Test
	void erreur_d_ecriture_ne_leve_pas() throws Exception {
		// Le « répertoire de données » est sous un fichier ordinaire : impossible à créer, sur tout système.
		Path fichier = Files.createTempFile(RACINE, "bloquant", ".txt");
		SurveillanceMetaspace s = new SurveillanceMetaspace(proprietes(fichier.resolve("sous"), 512), () -> 900 * MO);
		assertThatCode(s::controler).doesNotThrowAnyException();
		assertThatCode(s::controler).doesNotThrowAnyException();
		// La mesure reste exposée ; le repère n'a pas pu être écrit.
		assertThat(s.metaspaceMo()).isEqualTo(900);
	}

	@Test
	void mesure_qui_leve_ne_leve_pas() throws Exception {
		SurveillanceMetaspace s = new SurveillanceMetaspace(proprietes(dossier(), 512), () -> {
			throw new IllegalStateException("capteur");
		});
		assertThatCode(s::controler).doesNotThrowAnyException();
	}

	@Test
	void route_metaspace_reservee_admin() throws Exception {
		mvc.perform(get("/api/plateforme/metaspace").with(user("admin").roles("ADMIN"))).andExpect(status().isOk())
				.andExpect(jsonPath("$.metaspaceMo").isNumber()).andExpect(jsonPath("$.seuilMo").value(512))
				.andExpect(jsonPath("$.redemarrageRecommande").isBoolean());
		mvc.perform(get("/api/plateforme/metaspace").with(user("c").roles("COMMERCIAL", "ESN_USER")))
				.andExpect(status().isForbidden());
		mvc.perform(get("/api/plateforme/metaspace")).andExpect(status().isUnauthorized());
	}

}

package com.logicielpourtous.site.plugins.clients;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** Le plugin n'embarque plus aucun front : ni build npm, ni ui/, ni .js, ni route JSON. */
class PluginSansFrontTest {

	private static final Path RACINE = Path.of("").toAbsolutePath();

	@Test
	void pomSansFrontendNiNpm() throws Exception {
		String pom = Files.readString(RACINE.resolve("pom.xml"));
		assertFalse(pom.contains("frontend-maven-plugin"), "frontend-maven-plugin present");
		assertFalse(pom.contains("npm"), "npm present");
	}

	@Test
	void ressourcesSansUiNiJs() throws Exception {
		Path res = RACINE.resolve("src/main/resources");
		assertFalse(Files.exists(res.resolve("ui")), "dossier ui present");
		try (Stream<Path> s = Files.walk(res)) {
			List<Path> js = s.filter(p -> p.getFileName().toString().endsWith(".js")).toList();
			assertTrue(js.isEmpty(), "fichiers .js : " + js);
		}
	}

	@Test
	void pasDeDossierFrontend() {
		assertFalse(Files.exists(RACINE.resolve("frontend")), "dossier frontend present");
	}

	@Test
	void aucunRestController() throws Exception {
		try (Stream<Path> s = Files.walk(RACINE.resolve("src/main/java"))) {
			for (Path p : s.filter(f -> f.toString().endsWith(".java")).toList()) {
				assertFalse(Files.readString(p).contains("@RestController"), "@RestController dans " + p);
			}
		}
	}

}

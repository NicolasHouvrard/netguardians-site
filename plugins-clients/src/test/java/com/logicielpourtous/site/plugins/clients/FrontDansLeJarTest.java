package com.logicielpourtous.site.plugins.clients;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;

/**
 * Le jar construit embarque le front du plugin : {@code ui/main.js}, le module ES que le noyau sert sous
 * {@code /api/plugins/clients/ui/<version>/main.js}. S'execute APRES le jar (phase package, voir le pom).
 */
class FrontDansLeJarTest {

	private static final Path JAR = Path.of("target", "clients-1.0.0.jar");

	@Test
	void leJarContientLeFrontEtDeclareLElementPersonnalise() throws Exception {
		assertTrue(Files.isRegularFile(JAR), "jar non construit : " + JAR.toAbsolutePath());
		try (JarFile jar = new JarFile(JAR.toFile())) {
			var entree = jar.getJarEntry("ui/main.js");
			assertNotNull(entree, "ui/main.js absent du jar");
			try (InputStream in = jar.getInputStream(entree)) {
				String js = new String(in.readAllBytes(), StandardCharsets.UTF_8);
				assertTrue(js.length() > 10_000, "main.js suspect (trop petit)");
				assertTrue(js.contains("clients-app-v100"), "l'element clients-app-v100 n'est pas declare");
			}
			assertTrue(jar.stream().noneMatch(e -> e.getName().contains("node_modules")), "node_modules dans le jar");
		}
	}

}

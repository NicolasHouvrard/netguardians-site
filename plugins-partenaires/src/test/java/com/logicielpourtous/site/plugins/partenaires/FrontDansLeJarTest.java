package com.logicielpourtous.site.plugins.partenaires;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;

/**
 * Le jar construit embarque le front du plugin : {@code ui/main.js}, le module ES que le noyau sert sous
 * {@code /api/plugins/partenaires/ui/<version>/main.js}. S'execute APRES le jar (phase package, voir le pom). La version
 * et le nom d'element attendus sont derives de {@code plugin.properties} (rien n'est code en dur) avec la regle
 * injective de {@code PluginLoaderService.nomElement} et {@code scripts/version.mjs}.
 */
class FrontDansLeJarTest {

	/** Meme regle que le noyau et que version.mjs : '.' -> '-', '-' -> '_d', autre hors [a-z0-9] -> '_u<hex>_'. */
	static String nomElement(String plugin, String version) {
		StringBuilder s = new StringBuilder();
		version.toLowerCase().codePoints().forEach(c -> {
			if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
				s.appendCodePoint(c);
			}
			else if (c == '.') {
				s.append('-');
			}
			else if (c == '-') {
				s.append("_d");
			}
			else {
				s.append("_u").append(Integer.toHexString(c)).append('_');
			}
		});
		return plugin + "-app-v" + s;
	}

	@Test
	void nomElementInjectif() {
		assertTrue(nomElement("partenaires", "1.1.10").equals("partenaires-app-v1-1-10"));
		assertTrue(!nomElement("partenaires", "1.1.10").equals(nomElement("partenaires", "1.11.0")));
		assertTrue(!nomElement("partenaires", "1.10.0").equals(nomElement("partenaires", "11.0.0")));
		// Valeurs identiques a celles de plugin-loader.service.spec.ts (noyau) : les deux cotes s'accordent.
		assertTrue(nomElement("partenaires", "1.2.3").equals("partenaires-app-v1-2-3"));
		assertTrue(nomElement("partenaires", "1.0.0-SNAPSHOT").equals("partenaires-app-v1-0-0_dsnapshot"));
		assertTrue(nomElement("partenaires", "1.0.0-beta.1").equals("partenaires-app-v1-0-0_dbeta-1"));
		assertTrue(nomElement("partenaires", "1.0.0+Build.5").equals("partenaires-app-v1-0-0_u2b_build-5"));
	}

	@Test
	void leJarContientLeFrontEtDeclareLElementPersonnalise() throws Exception {
		Properties manifeste = new Properties();
		try (InputStream in = Files.newInputStream(Path.of("src", "main", "resources", "plugin.properties"))) {
			manifeste.load(in);
		}
		String version = manifeste.getProperty("version").trim();
		String element = nomElement(manifeste.getProperty("nom").trim(), version);
		Path jarChemin = Path.of("target", "partenaires-" + version + ".jar");
		assertTrue(Files.isRegularFile(jarChemin), "jar non construit : " + jarChemin.toAbsolutePath());
		try (JarFile jar = new JarFile(jarChemin.toFile())) {
			var entree = jar.getJarEntry("ui/main.js");
			assertNotNull(entree, "ui/main.js absent du jar");
			try (InputStream in = jar.getInputStream(entree)) {
				String js = new String(in.readAllBytes(), StandardCharsets.UTF_8);
				assertTrue(js.length() > 10_000, "main.js suspect (trop petit)");
				assertTrue(js.contains(element), "l'element " + element + " n'est pas declare");
			}
			assertTrue(jar.stream().noneMatch(e -> e.getName().contains("node_modules")), "node_modules dans le jar");
		}
	}

}

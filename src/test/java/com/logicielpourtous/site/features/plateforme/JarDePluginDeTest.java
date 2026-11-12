package com.logicielpourtous.site.features.plateforme;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

/** Aide de test : fabrique un jar de plugin minimal (manifeste + migrations). */
public final class JarDePluginDeTest {

	private JarDePluginDeTest() {
	}

	/**
	 * Crée {@code <dossier>/<nom>-<version>.jar} contenant {@code plugin.properties} (nom et version, plus les
	 * propriétés fournies) et {@code db/plugin/<nom>/<fichier>} pour chaque migration.
	 */
	public static Path creer(Path dossier, String nom, String version, Map<String, String> proprietes,
			Map<String, String> migrations) {
		try {
			Files.createDirectories(dossier);
			Path jar = dossier.resolve(nom + "-" + version + ".jar");
			StringBuilder props = new StringBuilder();
			props.append("nom=").append(nom).append('\n');
			props.append("version=").append(version).append('\n');
			proprietes.forEach((cle, valeur) -> props.append(cle).append('=').append(valeur).append('\n'));
			try (OutputStream sortie = Files.newOutputStream(jar); JarOutputStream jos = new JarOutputStream(sortie)) {
				ajouter(jos, "plugin.properties", props.toString());
				for (Map.Entry<String, String> m : migrations.entrySet()) {
					ajouter(jos, "db/plugin/" + nom + "/" + m.getKey(), m.getValue());
				}
			}
			return jar;
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static void ajouter(JarOutputStream jos, String chemin, String contenu) throws IOException {
		jos.putNextEntry(new JarEntry(chemin));
		jos.write(contenu.getBytes(StandardCharsets.UTF_8));
		jos.closeEntry();
	}
}

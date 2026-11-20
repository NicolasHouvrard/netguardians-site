package com.logicielpourtous.site.features.plateforme;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

/**
 * Aide de test : le VRAI jar du plugin « clients », celui que produit {@code mvn -f plugins-clients/pom.xml package}.
 * Jamais d'ignorance silencieuse : un jar absent fait ÉCHOUER le test, avec la commande à lancer.
 */
final class JarClientsReel {

	static final Path JAR = Path.of("plugins-clients", "target", "clients-1.0.0.jar");

	private JarClientsReel() {
	}

	/** Le jar construit, ou une erreur claire s'il ne l'est pas (ne jamais sauter le test). */
	static Path localiser() {
		if (!Files.isRegularFile(JAR)) {
			throw new IllegalStateException("Jar du plugin clients introuvable : " + JAR.toAbsolutePath()
					+ ". Construisez-le d'abord : mvn -f plugins-clients/pom.xml package");
		}
		return JAR;
	}

	/**
	 * Dérive une autre version du vrai jar : mêmes classes, {@code plugin.properties} avec la version donnée et les
	 * lignes de manifeste en plus, et {@code ui/main.js} remplacé par {@code contenuMain}. Écrit
	 * {@code <dossier>/clients-<version>.jar}.
	 */
	static Path deriver(Path dossier, String version, String lignesEnPlus, String contenuMain) {
		try {
			Files.createDirectories(dossier);
			Path sortie = dossier.resolve("clients-" + version + ".jar");
			try (JarFile source = new JarFile(localiser().toFile());
					OutputStream os = Files.newOutputStream(sortie);
					JarOutputStream jos = new JarOutputStream(os)) {
				Enumeration<JarEntry> entrees = source.entries();
				while (entrees.hasMoreElements()) {
					JarEntry e = entrees.nextElement();
					if (e.getName().equals("META-INF/MANIFEST.MF")) {
						continue; // JarOutputStream écrit le sien
					}
					jos.putNextEntry(new JarEntry(e.getName()));
					if (e.getName().equals("plugin.properties")) {
						String props = new String(source.getInputStream(e).readAllBytes(), StandardCharsets.UTF_8);
						String neuf = props.replaceAll("(?m)^version=.*$", "version=" + version);
						jos.write((neuf.stripTrailing() + "\n" + lignesEnPlus + "\n").getBytes(StandardCharsets.UTF_8));
					}
					else if (e.getName().equals("ui/main.js")) {
						jos.write(contenuMain.getBytes(StandardCharsets.UTF_8));
					}
					else if (!e.isDirectory()) {
						source.getInputStream(e).transferTo(jos);
					}
					jos.closeEntry();
				}
			}
			return sortie;
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

}

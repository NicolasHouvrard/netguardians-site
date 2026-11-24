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
 * Aide de test : le VRAI jar du plugin « partenaires », celui que produit {@code mvn -f plugins-partenaires/pom.xml
 * package}. Jamais d'ignorance silencieuse : un jar absent fait ÉCHOUER le test, avec la commande à lancer. Les jars
 * rendus au test sont des COPIES signées avec la clé de test, dans un dossier du test : rien n'est écrit dans
 * {@code target/}.
 */
final class JarPartenairesReel {

	static final Path JAR = Path.of("plugins-partenaires", "target", "partenaires-1.0.0.jar");

	private JarPartenairesReel() {
	}

	/** Le jar construit, ou une erreur claire s'il ne l'est pas (ne jamais sauter le test). */
	static Path localiser() {
		if (!Files.isRegularFile(JAR)) {
			throw new IllegalStateException("Jar du plugin partenaires introuvable : " + JAR.toAbsolutePath()
					+ ". Construisez-le d'abord : mvn -B -o -f plugins-partenaires/pom.xml package");
		}
		return JAR;
	}

	/** Copie du vrai jar dans {@code dossier}, signée avec la clé de test. */
	static Path copieSignee(Path dossier) {
		try {
			Files.createDirectories(dossier);
			Path copie = dossier.resolve("partenaires-1.0.0.jar");
			Files.copy(localiser(), copie);
			return JarDePluginDeTest.signer(copie);
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/**
	 * Dérive une autre version du vrai jar : mêmes classes et mêmes gabarits, {@code plugin.properties} avec la version
	 * donnée et le libellé de la tuile {@code entree.1} remplacé ; signée avec la clé de test.
	 */
	static Path deriver(Path dossier, String version, String libelleTuile) {
		try {
			Files.createDirectories(dossier);
			Path sortie = dossier.resolve("partenaires-" + version + ".jar");
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
						String neuf = props.replaceAll("(?m)^version=.*$", "version=" + version)
							.replace("ADMIN|Partenaires|", "ADMIN|" + libelleTuile + "|");
						jos.write(neuf.getBytes(StandardCharsets.UTF_8));
					}
					else if (!e.isDirectory()) {
						source.getInputStream(e).transferTo(jos);
					}
					jos.closeEntry();
				}
			}
			return JarDePluginDeTest.signer(sortie);
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

}

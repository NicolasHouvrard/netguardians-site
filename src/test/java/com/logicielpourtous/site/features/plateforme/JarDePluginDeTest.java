package com.logicielpourtous.site.features.plateforme;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

/** Aide de test : fabrique un jar de plugin minimal (manifeste + migrations, et classes compilées à la volée). */
public final class JarDePluginDeTest {

	private JarDePluginDeTest() {
	}

	/**
	 * Crée {@code <dossier>/<nom>-<version>.jar} contenant {@code plugin.properties} (nom et version, plus les
	 * propriétés fournies) et {@code db/plugin/<nom>/<fichier>} pour chaque migration.
	 */
	public static Path creer(Path dossier, String nom, String version, Map<String, String> proprietes,
			Map<String, String> migrations) {
		return creer(dossier, nom, version, proprietes, migrations, Map.of());
	}

	/**
	 * Comme {@link #creer(Path, String, String, Map, Map)}, et ajoute au jar les classes compilées à partir de
	 * {@code sources} (nom de classe complet, par exemple {@code x.Hello}, vers son code source Java). La compilation
	 * utilise le classpath du test ({@code java.class.path}).
	 */
	public static Path creer(Path dossier, String nom, String version, Map<String, String> proprietes,
			Map<String, String> migrations, Map<String, String> sources) {
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
				if (!sources.isEmpty()) {
					Path classes = compiler(dossier.resolve("classes-" + nom + "-" + version), sources);
					try (Stream<Path> fichiers = Files.walk(classes)) {
						for (Path f : fichiers.filter(Files::isRegularFile).toList()) {
							jos.putNextEntry(new JarEntry(classes.relativize(f).toString().replace('\\', '/')));
							Files.copy(f, jos);
							jos.closeEntry();
						}
					}
				}
			}
			return jar;
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/** Compile les sources (classe complète vers code) dans {@code sortie} et renvoie ce dossier. */
	private static Path compiler(Path sortie, Map<String, String> sources) throws IOException {
		Path dossierSources = sortie.resolveSibling(sortie.getFileName() + "-src");
		Files.createDirectories(sortie);
		List<String> arguments = new ArrayList<>(
				List.of("-classpath", System.getProperty("java.class.path"), "-d", sortie.toString(), "-proc:none"));
		for (Map.Entry<String, String> s : sources.entrySet()) {
			Path fichier = dossierSources.resolve(s.getKey().replace('.', '/') + ".java");
			Files.createDirectories(fichier.getParent());
			Files.writeString(fichier, s.getValue());
			arguments.add(fichier.toString());
		}
		JavaCompiler compilateur = ToolProvider.getSystemJavaCompiler();
		if (compilateur.run(null, null, null, arguments.toArray(String[]::new)) != 0) {
			throw new IllegalStateException("Compilation des sources de test échouée : " + sources.keySet());
		}
		return sortie;
	}

	private static void ajouter(JarOutputStream jos, String chemin, String contenu) throws IOException {
		jos.putNextEntry(new JarEntry(chemin));
		jos.write(contenu.getBytes(StandardCharsets.UTF_8));
		jos.closeEntry();
	}
}

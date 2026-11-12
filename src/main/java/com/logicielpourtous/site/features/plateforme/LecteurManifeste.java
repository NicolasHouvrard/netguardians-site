package com.logicielpourtous.site.features.plateforme;

import com.logicielpourtous.site.sdk.ManifestePlugin;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/** Lit le manifeste et les migrations SQL d'un jar de plugin, sans le charger. */
public final class LecteurManifeste {

	private static final String MANIFESTE = "plugin.properties";

	private LecteurManifeste() {
	}

	/** Lit {@code plugin.properties} à la racine du jar ; lève {@link IllegalArgumentException} si absent ou invalide. */
	public static ManifestePlugin lire(Path jar) {
		try (JarFile jf = new JarFile(jar.toFile())) {
			JarEntry entree = jf.getJarEntry(MANIFESTE);
			if (entree == null) {
				throw new IllegalArgumentException("Le jar ne contient pas " + MANIFESTE + " : " + jar.getFileName());
			}
			Properties p = new Properties();
			try (InputStream in = jf.getInputStream(entree);
					Reader lecteur = new InputStreamReader(in, StandardCharsets.UTF_8)) {
				p.load(lecteur);
			}
			return ManifestePlugin.depuis(p);
		}
		catch (IOException e) {
			throw new IllegalArgumentException("Jar de plugin illisible : " + jar.getFileName(), e);
		}
	}

	/** Seule forme admise sous {@code db/plugin/<nom>/} : migration versionnée de premier niveau. */
	private static final Pattern MIGRATION_VERSIONNEE = Pattern.compile("^V\\d+(?:[._]\\d+)*__[^/]+\\.sql$");

	/**
	 * Migrations {@code db/plugin/<nom>/V<n>__*.sql} du jar : nom de fichier vers contenu SQL, triées par nom.
	 * <p>
	 * Flyway parcourt {@code db/plugin/<nom>} récursivement et exécute aussi les migrations répétables ({@code R__}),
	 * d'annulation ({@code U__}), de ligne de base ({@code B__}), les rappels ({@code beforeMigrate.sql}...) et les
	 * migrations Java. Rien de cela ne passerait par l'analyse de sûreté : tout autre fichier sous ce préfixe (sous-dossier
	 * compris) fait refuser le jar ({@link IllegalArgumentException} qui le nomme).
	 */
	public static Map<String, String> migrations(Path jar, String nomPlugin) {
		String prefixe = "db/plugin/" + nomPlugin + "/";
		Map<String, String> resultat = new TreeMap<>();
		List<String> interdits = new ArrayList<>();
		try (JarFile jf = new JarFile(jar.toFile())) {
			for (JarEntry e : Collections.list(jf.entries())) {
				String nom = e.getName();
				if (e.isDirectory() || !nom.startsWith(prefixe)) {
					continue;
				}
				String fichier = nom.substring(prefixe.length());
				if (!MIGRATION_VERSIONNEE.matcher(fichier).matches()) {
					interdits.add(fichier);
					continue;
				}
				try (InputStream in = jf.getInputStream(e)) {
					resultat.put(fichier, new String(in.readAllBytes(), StandardCharsets.UTF_8));
				}
			}
			if (!interdits.isEmpty()) {
				Collections.sort(interdits);
				throw new IllegalArgumentException("Fichiers non admis sous " + prefixe + " : " + interdits
						+ " (seules les migrations versionnées de premier niveau V<n>__*.sql sont acceptées : "
						+ "Flyway exécuterait les autres sans analyse de sûreté)");
			}
			return resultat;
		}
		catch (IOException e) {
			throw new IllegalArgumentException("Jar de plugin illisible : " + jar.getFileName(), e);
		}
	}
}

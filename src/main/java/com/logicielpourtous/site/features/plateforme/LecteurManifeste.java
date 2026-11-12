package com.logicielpourtous.site.features.plateforme;

import com.logicielpourtous.site.sdk.ManifestePlugin;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Collections;
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

	/** Migrations {@code db/plugin/<nom>/V*.sql} du jar : nom de fichier vers contenu SQL, triées par nom. */
	public static Map<String, String> migrations(Path jar, String nomPlugin) {
		String prefixe = "db/plugin/" + nomPlugin + "/";
		Map<String, String> resultat = new TreeMap<>();
		try (JarFile jf = new JarFile(jar.toFile())) {
			for (JarEntry e : Collections.list(jf.entries())) {
				String nom = e.getName();
				if (e.isDirectory() || !nom.startsWith(prefixe)) {
					continue;
				}
				String fichier = nom.substring(prefixe.length());
				if (fichier.contains("/") || !fichier.startsWith("V") || !fichier.endsWith(".sql")) {
					continue;
				}
				try (InputStream in = jf.getInputStream(e)) {
					resultat.put(fichier, new String(in.readAllBytes(), StandardCharsets.UTF_8));
				}
			}
			return resultat;
		}
		catch (IOException e) {
			throw new IllegalArgumentException("Jar de plugin illisible : " + jar.getFileName(), e);
		}
	}
}

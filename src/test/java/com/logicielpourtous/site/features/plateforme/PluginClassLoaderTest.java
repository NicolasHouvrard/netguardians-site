package com.logicielpourtous.site.features.plateforme;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PluginClassLoaderTest {

	@TempDir
	Path dossier;

	private static String source(String valeur) {
		return "package x; public class Hello { public static String qui() { return \"" + valeur + "\"; } }";
	}

	@Test
	void charge_enfant_d_abord() throws Exception {
		Path jarParent = JarDePluginDeTest.creer(dossier.resolve("parent"), "p", "1", Map.of(), Map.of(),
				Map.of("x.Hello", source("parent")));
		Path jarPlugin = JarDePluginDeTest.creer(dossier.resolve("plugin"), "a", "1", Map.of(), Map.of(),
				Map.of("x.Hello", source("jar")));
		try (URLClassLoader parent = new URLClassLoader(new URL[] { jarParent.toUri().toURL() }, null);
				PluginClassLoader loader = new PluginClassLoader("a@1#1", jarPlugin.toUri().toURL(), parent)) {
			Class<?> hello = loader.loadClass("x.Hello");
			assertThat(hello.getClassLoader()).isSameAs(loader);
			assertThat(hello.getMethod("qui").invoke(null)).isEqualTo("jar");
			assertThat(parent.loadClass("x.Hello").getMethod("qui").invoke(null)).isEqualTo("parent");
		}
	}

	@Test
	void parent_d_abord_pour_les_prefixes_partages() throws Exception {
		Path jar = JarDePluginDeTest.creer(dossier, "a", "1", Map.of(), Map.of(), Map.of("x.Hello", source("jar")));
		ClassLoader parent = getClass().getClassLoader();
		try (PluginClassLoader loader = new PluginClassLoader("a@1#1", jar.toUri().toURL(), parent)) {
			assertThat(loader.loadClass("java.lang.String")).isSameAs(String.class);
			Class<?> contexte = loader.loadClass("org.springframework.context.ApplicationContext");
			assertThat(contexte).isSameAs(org.springframework.context.ApplicationContext.class);
		}
	}

	@Test
	void close_libere_le_fichier() throws Exception {
		Path jar = JarDePluginDeTest.creer(dossier, "a", "1", Map.of(), Map.of(), Map.of("x.Hello", source("jar")));
		PluginClassLoader loader = new PluginClassLoader("a@1#1", jar.toUri().toURL(), getClass().getClassLoader());
		loader.loadClass("x.Hello");
		loader.close();
		Files.delete(jar);
		assertThat(jar).doesNotExist();
	}

	@Test
	void close_libere_le_fichier_apres_ouverture_d_une_ressource() throws Exception {
		Path jar = JarDePluginDeTest.creer(dossier, "a", "1", Map.of(), Map.of("V1__a.sql", "SELECT 1;"),
				Map.of("x.Hello", source("jar")));
		PluginClassLoader loader = new PluginClassLoader("a@1#1", jar.toUri().toURL(), getClass().getClassLoader());
		URL url = loader.getResource("db/plugin/a/V1__a.sql");
		try (java.io.InputStream in = url.openStream()) {
			assertThat(new String(in.readAllBytes())).isEqualTo("SELECT 1;");
		}
		for (URL u : java.util.Collections.list(loader.getResources("db/plugin/a/V1__a.sql"))) {
			u.openConnection().getInputStream().close();
		}
		loader.close();
		Files.delete(jar);
		assertThat(jar).doesNotExist();
	}

	@Test
	void close_libere_le_fichier_apres_un_scan_spring() throws Exception {
		Path jar = JarDePluginDeTest.creer(dossier, "a", "1", Map.of(), Map.of(), Map.of("x.Hello", source("jar")));
		PluginClassLoader loader = new PluginClassLoader("a@1#1", jar.toUri().toURL(), getClass().getClassLoader());
		var resolveur = new org.springframework.core.io.support.PathMatchingResourcePatternResolver(loader);
		assertThat(resolveur.getResources("classpath*:x/**/*.class")).hasSize(1);
		loader.close();
		Files.delete(jar);
		assertThat(jar).doesNotExist();
	}

}

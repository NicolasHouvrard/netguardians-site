package com.logicielpourtous.site.features.plateforme;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.logicielpourtous.site.sdk.ManifestePlugin;
import com.logicielpourtous.site.sdk.ModeMiseAJour;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LecteurManifesteTest {

	@TempDir
	Path dossier;

	@Test
	void jar_valide_lu() {
		Path jar = JarDePluginDeTest.creer(dossier, "facturation", "1.2.0",
				Map.of("coeur-min", "0.0.1", "mise-a-jour", "chaud", "paquet", "com.exemple", "configuration",
						"com.exemple.Config"),
				Map.of("V1__init.sql", "CREATE TABLE t (id int);", "V2__idx.sql", "CREATE INDEX i ON t(id);"));

		ManifestePlugin m = LecteurManifeste.lire(jar);
		assertThat(m.nom()).isEqualTo("facturation");
		assertThat(m.version()).isEqualTo("1.2.0");
		assertThat(m.miseAJour()).isEqualTo(ModeMiseAJour.CHAUD);

		Map<String, String> migrations = LecteurManifeste.migrations(jar, "facturation");
		assertThat(migrations).containsOnlyKeys("V1__init.sql", "V2__idx.sql");
		assertThat(migrations.get("V1__init.sql")).isEqualTo("CREATE TABLE t (id int);");
	}

	@Test
	void jar_sans_plugin_properties_refuse_proprement() throws IOException {
		Path sans = dossier.resolve("vide.jar");
		try (var os = Files.newOutputStream(sans); var jos = new JarOutputStream(os)) {
			jos.putNextEntry(new JarEntry("autre.txt"));
			jos.closeEntry();
		}
		assertThatThrownBy(() -> LecteurManifeste.lire(sans)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void fichier_qui_n_est_pas_un_jar_refuse_proprement() throws IOException {
		Path faux = dossier.resolve("faux.jar");
		Files.writeString(faux, "ceci n est pas un zip");
		assertThatThrownBy(() -> LecteurManifeste.lire(faux)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> LecteurManifeste.migrations(faux, "x")).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> LecteurManifeste.lire(dossier.resolve("absent.jar")))
				.isInstanceOf(IllegalArgumentException.class);
	}
}

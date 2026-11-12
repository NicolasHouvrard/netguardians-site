package com.logicielpourtous.site.features.plateforme;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Lecture du catalogue PostgreSQL local : deux schémas jetables reliés par une clé étrangère inter-schémas. */
class ReferencesEntrantesIT {

	private JdbcTemplate jdbc;

	private ReferencesEntrantes references;

	@BeforeEach
	void preparer() throws Exception {
		Properties p = new Properties();
		try (InputStream in = Files.newInputStream(Path.of("src/main/resources/application-local.properties"))) {
			p.load(in);
		}
		DriverManagerDataSource ds = new DriverManagerDataSource(p.getProperty("spring.datasource.url"),
				p.getProperty("spring.datasource.username"), p.getProperty("spring.datasource.password"));
		jdbc = new JdbcTemplate(ds);
		references = new ReferencesEntrantes(ds);
		nettoyer();
		jdbc.execute("CREATE SCHEMA t6ref_cible");
		jdbc.execute("CREATE SCHEMA t6ref_source");
		jdbc.execute("CREATE TABLE t6ref_cible.parent (id BIGINT PRIMARY KEY)");
		jdbc.execute("CREATE TABLE t6ref_cible.locale (id BIGINT PRIMARY KEY, parent_id BIGINT "
				+ "REFERENCES t6ref_cible.parent(id))");
		jdbc.execute("CREATE TABLE t6ref_cible.non_referencee (id BIGINT PRIMARY KEY)");
		jdbc.execute("CREATE TABLE t6ref_source.enfant (id BIGINT PRIMARY KEY, parent_id BIGINT "
				+ "REFERENCES t6ref_cible.parent(id))");
	}

	@AfterEach
	void nettoyer() {
		jdbc.execute("DROP SCHEMA IF EXISTS t6ref_source CASCADE");
		jdbc.execute("DROP SCHEMA IF EXISTS t6ref_cible CASCADE");
	}

	@Test
	void signale_la_cible_d_une_cle_etrangere_venant_d_un_autre_schema() {
		assertThat(references.ciblesInterSchemas("plugin_autre")).contains("t6ref_cible.parent")
			.doesNotContain("t6ref_cible.non_referencee", "t6ref_cible.locale");
	}

	@Test
	void ignore_les_cles_etrangeres_du_schema_du_plugin_lui_meme() {
		// Les clés sortantes du plugin mis à jour (schéma source) ne le contraignent pas : seules les entrantes comptent.
		assertThat(references.ciblesInterSchemas("t6ref_source")).doesNotContain("t6ref_cible.parent");
	}

	@Test
	void ignore_les_cles_etrangeres_internes_a_un_schema() {
		jdbc.execute("ALTER TABLE t6ref_source.enfant DROP CONSTRAINT enfant_parent_id_fkey");

		assertThat(references.ciblesInterSchemas("plugin_autre")).doesNotContain("t6ref_cible.parent");
	}

}

package com.logicielpourtous.site.features.plateforme;

import static org.assertj.core.api.Assertions.assertThat;

import com.logicielpourtous.site.features.plateforme.AnalyseurMigrations.Constat;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AnalyseurMigrationsTest {

	private static List<Constat> analyser(String sql) {
		return AnalyseurMigrations.analyser(sql, Set.of());
	}

	@Test
	void rename_colonne_en_minuscules_multiligne() {
		var c = analyser("alter table\n  factures\n  rename column\n montant to total;");
		assertThat(c).extracting(Constat::regle).containsExactly("RENAME");
	}

	@Test
	void rename_table() {
		assertThat(analyser("ALTER TABLE IF EXISTS a RENAME TO b;")).extracting(Constat::regle)
				.containsExactly("RENAME");
	}

	@Test
	void drop_column() {
		var c = analyser("ALTER TABLE \"factures\" DROP COLUMN montant;");
		assertThat(c).extracting(Constat::regle).containsExactly("DROP COLUMN");
	}

	@Test
	void drop_table() {
		var c = analyser("DROP TABLE factures;");
		assertThat(c).extracting(Constat::regle).containsExactly("DROP TABLE");
	}

	@Test
	void type_change() {
		var c = analyser("ALTER TABLE factures ALTER COLUMN montant TYPE numeric(12,2);");
		assertThat(c).extracting(Constat::regle).containsExactly("ALTER COLUMN TYPE");
		var c2 = analyser("ALTER TABLE factures ALTER COLUMN montant SET DATA TYPE bigint;");
		assertThat(c2).extracting(Constat::regle).containsExactly("ALTER COLUMN TYPE");
	}

	@Test
	void set_not_null() {
		var c = analyser("ALTER TABLE factures ALTER COLUMN montant SET NOT NULL;");
		assertThat(c).extracting(Constat::regle).containsExactly("SET NOT NULL");
	}

	@Test
	void add_column_not_null_sans_default_sur_table_existante() {
		var c = analyser("ALTER TABLE factures ADD COLUMN statut varchar(20) NOT NULL;");
		assertThat(c).extracting(Constat::regle).containsExactly("ADD COLUMN NOT NULL");
	}

	@Test
	void add_column_not_null_sur_table_creee_dans_le_meme_fichier() {
		var c = analyser("CREATE TABLE factures (id int);\nALTER TABLE factures ADD COLUMN statut varchar(20) NOT NULL;");
		assertThat(c).isEmpty();
	}

	@Test
	void add_column_not_null_avec_default() {
		var c = analyser("ALTER TABLE factures ADD COLUMN statut varchar(20) NOT NULL DEFAULT 'x';");
		assertThat(c).isEmpty();
	}

	@Test
	void add_column_nullable_ok() {
		assertThat(analyser("ALTER TABLE factures ADD COLUMN note text;")).isEmpty();
	}

	@Test
	void commentaire_ne_declenche_pas() {
		assertThat(analyser("-- DROP TABLE x")).isEmpty();
		assertThat(analyser("/* DROP TABLE x;\n ALTER TABLE a DROP COLUMN b; */ SELECT 1;")).isEmpty();
	}

	@Test
	void deux_instructions_dans_un_fichier_detecte_la_seconde() {
		var c = analyser("CREATE TABLE a (id int);\nINSERT INTO a VALUES (1);\nDROP TABLE b;");
		assertThat(c).extracting(Constat::regle).containsExactly("DROP TABLE");
	}

	@Test
	void table_referencee_drop_detecte_avec_schema() {
		var c = AnalyseurMigrations.analyser("DROP TABLE public.Customers;", Set.of("customers"));
		assertThat(c).extracting(Constat::regle).contains("TABLE REFERENCEE");
	}

	@Test
	void table_referencee_alter_type_detecte() {
		var c = AnalyseurMigrations.analyser("ALTER TABLE public.customers ALTER COLUMN a TYPE text;",
				Set.of("customers"));
		assertThat(c).extracting(Constat::regle).contains("TABLE REFERENCEE");
	}

	@Test
	void table_referencee_ajout_de_colonne_ne_declenche_pas() {
		var c = AnalyseurMigrations.analyser("ALTER TABLE customers ADD COLUMN note text;", Set.of("customers"));
		assertThat(c).isEmpty();
	}

	@Test
	void create_table_et_index_ok() {
		var c = analyser("""
				CREATE TABLE factures (id bigint primary key, montant numeric NOT NULL);
				CREATE INDEX idx_f ON factures (montant);
				ALTER TABLE factures ADD CONSTRAINT uq UNIQUE (id);
				INSERT INTO factures (id, montant) VALUES (1, 2);
				""");
		assertThat(c).isEmpty();
	}
}

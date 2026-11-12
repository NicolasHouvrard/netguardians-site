package com.logicielpourtous.site.features.plateforme;

import java.util.Set;
import java.util.TreeSet;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Lit dans le catalogue PostgreSQL (source autoritaire) les tables ciblées par une clé étrangère qui traverse une
 * frontière de schéma. Une migration « à chaud » ne doit rien détruire dans ces tables : un autre plugin (ou le cœur)
 * en dépend.
 */
@Component
@ConditionalOnProperty(name = "app.plateforme.actif", havingValue = "true")
public class ReferencesEntrantes {

	private static final String SQL = """
			SELECT DISTINCT tn.nspname || '.' || tc.relname
			FROM pg_constraint c
			JOIN pg_class sc ON sc.oid = c.conrelid
			JOIN pg_namespace sn ON sn.oid = sc.relnamespace
			JOIN pg_class tc ON tc.oid = c.confrelid
			JOIN pg_namespace tn ON tn.oid = tc.relnamespace
			WHERE c.contype = 'f'
			  AND sn.oid <> tn.oid
			  AND sn.nspname <> ?
			""";

	private final JdbcTemplate jdbc;

	public ReferencesEntrantes(DataSource dataSource) {
		this.jdbc = new JdbcTemplate(dataSource);
	}

	/**
	 * Tables ({@code schema.table}) ciblées par une clé étrangère dont la table source est dans un AUTRE schéma que sa
	 * cible, en excluant les clés qui partent de {@code schemaDuPlugin} lui-même (les clés sortantes du plugin mis à
	 * jour ne le contraignent pas).
	 */
	public Set<String> ciblesInterSchemas(String schemaDuPlugin) {
		return new TreeSet<>(jdbc.queryForList(SQL, String.class, schemaDuPlugin));
	}

}

package com.logicielpourtous.site.features.plateforme;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Détecte les migrations SQL « sensibles » (destructives ou bloquantes) qui interdisent une mise à jour à chaud.
 * Analyse textuelle prudente : en cas de doute elle signale plutôt qu'elle ne laisse passer.
 */
public final class AnalyseurMigrations {

	/** Une construction sensible : règle déclenchée et extrait de l'instruction normalisée. */
	public record Constat(String regle, String extrait) {
	}

	private static final Pattern PROCEDURAL = Pattern
			.compile("^(?:do\\b|execute\\b|create (?:or replace )?(?:function|procedure)\\b)");
	private static final String REGLE_PROCEDURAL = "sql-procedural-non-analysable";
	private static final Pattern DOLLAR = Pattern.compile("\\$[A-Za-z_0-9]*\\$");
	private static final Pattern ESPACES = Pattern.compile("\\s+");

	private static final Pattern CREATE_TABLE = Pattern.compile(
			"^create (?:(?:temp|temporary|unlogged) )?table (?:if not exists )?([^\\s(]+)");
	private static final Pattern ALTER_TABLE = Pattern
			.compile("^alter table (?:if exists )?(?:only )?(\\S+) (.*)$");
	private static final Pattern DROP_TABLE = Pattern
			.compile("^drop table (?:if exists )?(.*?)(?: cascade| restrict)?$");

	private static final Pattern RENAME = Pattern.compile("^rename\\b");
	private static final Pattern DROP_COLONNE = Pattern.compile("^drop (?:column\\b|(?!constraint\\b))");
	private static final Pattern CHANGE_TYPE = Pattern.compile("^alter (?:column )?\\S+ (?:set data )?type\\b");
	private static final Pattern SET_NOT_NULL = Pattern.compile("^alter (?:column )?\\S+ set not null\\b");
	private static final Pattern ADD_COLONNE = Pattern
			.compile("^add (?:column )?(?!constraint\\b|primary\\b|unique\\b|foreign\\b|check\\b|exclude\\b)");
	private static final Pattern NOT_NULL = Pattern.compile("\\bnot null\\b");
	private static final Pattern DEFAULT = Pattern
			.compile("\\bdefault\\b|\\bgenerated\\b|\\bserial\\b|\\bbigserial\\b|\\bsmallserial\\b");

	private AnalyseurMigrations() {
	}

	public static List<Constat> analyser(String sql, Set<String> tablesReferencees) {
		Set<String> referencees = new HashSet<>();
		for (String t : tablesReferencees) {
			referencees.add(nomSimple(t));
		}
		List<Constat> constats = new ArrayList<>();
		List<String> instructions = decouper(sql, constats);

		Set<String> creees = new HashSet<>();
		for (String s : instructions) {
			Matcher m = CREATE_TABLE.matcher(s);
			if (m.find()) {
				creees.add(nomSimple(m.group(1)));
			}
		}

		for (String s : instructions) {
			if (PROCEDURAL.matcher(s).find()) {
				constats.add(new Constat(REGLE_PROCEDURAL, extrait(s)));
				continue;
			}
			Matcher drop = DROP_TABLE.matcher(s);
			if (drop.matches()) {
				constats.add(new Constat("DROP TABLE", extrait(s)));
				for (String nom : drop.group(1).split(",")) {
					if (referencees.contains(nomSimple(nom))) {
						constats.add(new Constat("TABLE REFERENCEE", extrait(s)));
						break;
					}
				}
				continue;
			}
			Matcher alter = ALTER_TABLE.matcher(s);
			if (!alter.matches()) {
				continue;
			}
			String table = nomSimple(alter.group(1));
			boolean tableCreeeIci = creees.contains(table);
			boolean destructif = false;
			for (String segment : segments(alter.group(2))) {
				if (RENAME.matcher(segment).find()) {
					constats.add(new Constat("RENAME", extrait(s)));
					destructif = true;
				}
				else if (DROP_COLONNE.matcher(segment).find()) {
					constats.add(new Constat("DROP COLUMN", extrait(s)));
					destructif = true;
				}
				else if (CHANGE_TYPE.matcher(segment).find()) {
					constats.add(new Constat("ALTER COLUMN TYPE", extrait(s)));
					destructif = true;
				}
				else if (SET_NOT_NULL.matcher(segment).find()) {
					constats.add(new Constat("SET NOT NULL", extrait(s)));
				}
				else if (ADD_COLONNE.matcher(segment).find() && NOT_NULL.matcher(segment).find()
						&& !DEFAULT.matcher(segment).find() && !tableCreeeIci) {
					constats.add(new Constat("ADD COLUMN NOT NULL", extrait(s)));
				}
			}
			if (destructif && referencees.contains(table)) {
				constats.add(new Constat("TABLE REFERENCEE", extrait(s)));
			}
		}
		return constats;
	}

	/**
	 * Retire les commentaires (hors littéraux), remplace chaque littéral entre apostrophes par {@code ''}, passe en
	 * minuscules, retire les guillemets, regroupe les espaces, découpe sur « ; » hors littéraux. Un guillemet dollar
	 * ({@code $$} ou {@code $tag$}) ajoute un constat prudent dans {@code constats}.
	 */
	private static List<String> decouper(String sql, List<Constat> constats) {
		List<String> resultat = new ArrayList<>();
		StringBuilder courant = new StringBuilder();
		boolean dollarSignale = false;
		int n = sql.length();
		int i = 0;
		while (i < n) {
			char c = sql.charAt(i);
			if (c == '\'') {
				int fin = i + 1;
				while (fin < n && sql.charAt(fin) != '\'') {
					fin++;
				}
				courant.append("''");
				i = fin + 1;
			}
			else if (c == '"') {
				int fin = sql.indexOf('"', i + 1);
				fin = fin < 0 ? n : fin;
				courant.append(sql, i + 1, fin);
				i = fin + 1;
			}
			else if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {
				while (i < n && sql.charAt(i) != '\n') {
					i++;
				}
				courant.append(' ');
			}
			else if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
				int fin = sql.indexOf("*/", i + 2);
				i = fin < 0 ? n : fin + 2;
				courant.append(' ');
			}
			else if (c == ';') {
				ajouter(resultat, courant);
				i++;
			}
			else {
				if (c == '$' && !dollarSignale && DOLLAR.matcher(sql).region(i, n).lookingAt()) {
					dollarSignale = true;
					String debut = ESPACES.matcher(sql.substring(i, Math.min(n, i + 120))).replaceAll(" ");
					constats.add(new Constat(REGLE_PROCEDURAL, extrait(debut)));
				}
				courant.append(c);
				i++;
			}
		}
		ajouter(resultat, courant);
		return resultat;
	}

	private static void ajouter(List<String> resultat, StringBuilder courant) {
		String instruction = ESPACES.matcher(courant.toString().toLowerCase(Locale.ROOT)).replaceAll(" ").trim();
		courant.setLength(0);
		if (!instruction.isEmpty()) {
			resultat.add(instruction);
		}
	}

	/** Sépare le corps d'un ALTER TABLE sur les virgules de premier niveau (une action par segment). */
	private static List<String> segments(String corps) {
		List<String> resultat = new ArrayList<>();
		int profondeur = 0;
		int debut = 0;
		for (int i = 0; i < corps.length(); i++) {
			char c = corps.charAt(i);
			if (c == '(') {
				profondeur++;
			}
			else if (c == ')') {
				profondeur--;
			}
			else if (c == ',' && profondeur == 0) {
				resultat.add(corps.substring(debut, i).trim());
				debut = i + 1;
			}
		}
		resultat.add(corps.substring(debut).trim());
		return resultat;
	}

	/** Nom en minuscules sans guillemets ni préfixe de schéma ({@code public.Customers} devient {@code customers}). */
	private static String nomSimple(String nom) {
		String n = nom.toLowerCase(Locale.ROOT).replace("\"", "").trim();
		int point = n.lastIndexOf('.');
		return point >= 0 ? n.substring(point + 1) : n;
	}

	private static String extrait(String instruction) {
		return instruction.length() <= 120 ? instruction : instruction.substring(0, 120) + "...";
	}
}

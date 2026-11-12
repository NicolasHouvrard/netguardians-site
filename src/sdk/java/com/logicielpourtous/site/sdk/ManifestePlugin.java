package com.logicielpourtous.site.sdk;

import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.regex.Pattern;

/** Manifeste d'un plugin, lu depuis son fichier {@code plugin.properties}. */
public record ManifestePlugin(
		String nom,
		String version,
		String coeurMin,
		String coeurMax,
		ModeMiseAJour miseAJour,
		String paquet,
		String configuration,
		List<String> dependances,
		List<String> tablesReferencees) {

	/** Nom sûr : il sert tel quel dans {@code plugin_<nom>}, {@code flyway_schema_history_<nom>} et {@code /api/plugins/<nom>}. */
	public static final Pattern NOM_VALIDE = Pattern.compile("^[a-z][a-z0-9_]{0,39}$");

	public static ManifestePlugin depuis(Properties p) {
		String miseAJour = obligatoire(p, "mise-a-jour");
		ModeMiseAJour mode = switch (miseAJour.toLowerCase()) {
			case "chaud" -> ModeMiseAJour.CHAUD;
			case "coeur" -> ModeMiseAJour.COEUR;
			default -> throw new IllegalArgumentException(
					"Valeur inconnue pour 'mise-a-jour' : " + miseAJour + " (attendu : chaud ou coeur)");
		};
		String nom = obligatoire(p, "nom");
		if (!NOM_VALIDE.matcher(nom).matches()) {
			throw new IllegalArgumentException("Nom de plugin invalide dans le manifeste : '" + nom
					+ "' (attendu : " + NOM_VALIDE.pattern()
					+ ", car le nom entre dans des noms de schéma et de table SQL et dans des URL)");
		}
		return new ManifestePlugin(
				nom,
				obligatoire(p, "version"),
				obligatoire(p, "coeur-min"),
				facultative(p, "coeur-max"),
				mode,
				obligatoire(p, "paquet"),
				obligatoire(p, "configuration"),
				liste(p, "dependances"),
				liste(p, "tables-referencees"));
	}

	private static String obligatoire(Properties p, String cle) {
		String valeur = p.getProperty(cle);
		if (valeur == null || valeur.isBlank()) {
			throw new IllegalArgumentException("Clé obligatoire absente du manifeste : " + cle);
		}
		return valeur.trim();
	}

	private static String facultative(Properties p, String cle) {
		String valeur = p.getProperty(cle);
		return valeur == null || valeur.isBlank() ? null : valeur.trim();
	}

	private static List<String> liste(Properties p, String cle) {
		String valeur = p.getProperty(cle);
		if (valeur == null || valeur.isBlank()) {
			return List.of();
		}
		return Arrays.stream(valeur.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
	}
}

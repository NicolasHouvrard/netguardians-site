package com.logicielpourtous.site.sdk;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.TreeSet;
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
		List<String> tablesReferencees,
		List<EntreeNavigation> entrees,
		List<BlocFiche> blocs) {

	/** Nom sûr : il sert tel quel dans {@code plugin_<nom>}, {@code flyway_schema_history_<nom>} et {@code /api/plugins/<nom>}. */
	public static final Pattern NOM_VALIDE = Pattern.compile("^[a-z][a-z0-9_]{0,39}$");

	/** Droit sûr : il est comparé tel quel aux droits de l'utilisateur. */
	public static final Pattern DROIT_VALIDE = Pattern.compile("^[a-z][a-z0-9_.]{0,59}$");

	/** Le nom est validé quelle que soit la façon de construire le manifeste (il entre dans du SQL et des URL). */
	public ManifestePlugin {
		if (nom == null || !NOM_VALIDE.matcher(nom).matches()) {
			throw new IllegalArgumentException("Nom de plugin invalide dans le manifeste : '" + nom
					+ "' (attendu : " + NOM_VALIDE.pattern()
					+ ", car le nom entre dans des noms de schéma et de table SQL et dans des URL)");
		}
	}

	public static ManifestePlugin depuis(Properties p) {
		String miseAJour = obligatoire(p, "mise-a-jour");
		ModeMiseAJour mode = switch (miseAJour.toLowerCase()) {
			case "chaud" -> ModeMiseAJour.CHAUD;
			case "coeur" -> ModeMiseAJour.COEUR;
			default -> throw new IllegalArgumentException(
					"Valeur inconnue pour 'mise-a-jour' : " + miseAJour + " (attendu : chaud ou coeur)");
		};
		return new ManifestePlugin(
				obligatoire(p, "nom"),
				obligatoire(p, "version"),
				obligatoire(p, "coeur-min"),
				facultative(p, "coeur-max"),
				mode,
				obligatoire(p, "paquet"),
				obligatoire(p, "configuration"),
				liste(p, "dependances"),
				liste(p, "tables-referencees"),
				entrees(p),
				blocs(p));
	}

	/** Lit {@code entree.1}, {@code entree.2}, … : {@code ZONE|libellé|icône|chemin|droit}. */
	private static List<EntreeNavigation> entrees(Properties p) {
		List<EntreeNavigation> entrees = new ArrayList<>();
		for (int n = 1; p.getProperty("entree." + n) != null; n++) {
			String cle = "entree." + n;
			String[] champs = champs(p.getProperty(cle), 5, cle, "ZONE|libellé|icône|chemin|droit");
			ZoneNavigation zone;
			try {
				zone = ZoneNavigation.valueOf(champs[0]);
			}
			catch (IllegalArgumentException e) {
				throw new IllegalArgumentException("Zone inconnue dans " + cle + " : '" + champs[0]
						+ "' (attendu : " + Arrays.toString(ZoneNavigation.values()) + ")");
			}
			verifierChemin(champs[3], cle);
			verifierDroit(champs[4], cle);
			entrees.add(new EntreeNavigation(zone, champs[1], champs[2], champs[3], champs[4]));
		}
		verifierNumerotation(p, "entree.", entrees.size());
		return List.copyOf(entrees);
	}

	/** Lit {@code bloc.1}, {@code bloc.2}, … : {@code id|libellé|chemin|droit}. */
	private static List<BlocFiche> blocs(Properties p) {
		List<BlocFiche> blocs = new ArrayList<>();
		for (int n = 1; p.getProperty("bloc." + n) != null; n++) {
			String cle = "bloc." + n;
			String[] champs = champs(p.getProperty(cle), 4, cle, "id|libellé|chemin|droit");
			if (!NOM_VALIDE.matcher(champs[0]).matches()) {
				throw new IllegalArgumentException("id de bloc invalide dans " + cle + " : '" + champs[0]
						+ "' (attendu : " + NOM_VALIDE.pattern() + ")");
			}
			verifierChemin(champs[2], cle);
			verifierDroit(champs[3], cle);
			blocs.add(new BlocFiche(champs[0], champs[1], champs[2], champs[3]));
		}
		verifierNumerotation(p, "bloc.", blocs.size());
		return List.copyOf(blocs);
	}

	/** Toute clé {@code prefixe*} non lue par la boucle contiguë (trou, {@code .0}, {@code .x}, {@code .01}) est refusée. */
	private static void verifierNumerotation(Properties p, String prefixe, int lues) {
		for (String cle : new TreeSet<>(p.stringPropertyNames())) {
			if (cle.startsWith(prefixe) && !estLue(cle, prefixe, lues)) {
				throw new IllegalArgumentException("Clé " + cle + " non contiguë ou hors numérotation (attendu : "
						+ prefixe + "1, " + prefixe + "2, …)");
			}
		}
	}

	private static boolean estLue(String cle, String prefixe, int lues) {
		for (int n = 1; n <= lues; n++) {
			if (cle.equals(prefixe + n)) {
				return true;
			}
		}
		return false;
	}

	private static String[] champs(String valeur, int attendus, String cle, String format) {
		String[] champs = valeur.split("\\|", -1);
		if (champs.length != attendus) {
			throw new IllegalArgumentException("Format invalide pour " + cle + " : '" + valeur
					+ "' (attendu : " + format + ")");
		}
		for (int i = 0; i < champs.length; i++) {
			champs[i] = champs[i].trim();
		}
		return champs;
	}

	private static void verifierChemin(String chemin, String cle) {
		if (!chemin.startsWith("/")) {
			throw new IllegalArgumentException("chemin invalide dans " + cle + " : '" + chemin
					+ "' (doit commencer par /)");
		}
	}

	private static void verifierDroit(String droit, String cle) {
		if (!DROIT_VALIDE.matcher(droit).matches()) {
			throw new IllegalArgumentException("droit invalide dans " + cle + " : '" + droit
					+ "' (attendu : " + DROIT_VALIDE.pattern() + ")");
		}
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

package com.logicielpourtous.site.features.plateforme;

import com.logicielpourtous.site.sdk.ManifestePlugin;
import com.logicielpourtous.site.sdk.ModeMiseAJour;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Décide si une nouvelle version de plugin peut être installée (compatibilité du coeur, sûreté de la mise à jour à chaud). */
public final class VerificateurMiseAJour {

	public record Verdict(boolean accepte, List<String> raisons) {
	}

	/** Dépendance du manifeste : {@code nom} ou {@code nom>=version}. */
	private static final Pattern DEPENDANCE = Pattern.compile("^([a-z][a-z0-9_]{0,39})\\s*(?:>=\\s*(\\S+))?$");

	private VerificateurMiseAJour() {
	}

	public static Verdict verifier(ManifestePlugin m, Map<String, String> migrations, Set<String> tablesReferencees,
			String versionCoeur) {
		List<String> raisons = new ArrayList<>();
		if (comparer(versionCoeur, m.coeurMin()) < 0) {
			raisons.add("Version du coeur " + versionCoeur + " inférieure au minimum requis " + m.coeurMin());
		}
		if (m.coeurMax() != null && comparer(versionCoeur, m.coeurMax()) > 0) {
			raisons.add("Version du coeur " + versionCoeur + " supérieure au maximum supporté " + m.coeurMax());
		}
		if (m.miseAJour() == ModeMiseAJour.CHAUD) {
			migrations.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e -> {
				for (AnalyseurMigrations.Constat c : AnalyseurMigrations.analyser(e.getValue(), tablesReferencees)) {
					raisons.add(e.getKey() + " : " + c.regle() + " (" + c.extrait() + ")");
				}
			});
		}
		return new Verdict(raisons.isEmpty(), List.copyOf(raisons));
	}

	/**
	 * Comme {@link #verifier(ManifestePlugin, Map, Set, String)}, pour une mise à jour à chaud quand une version est
	 * déjà installée ({@code versionInstallee} non nulle) : un changement de version majeure, ou un manifeste qui
	 * déclare {@code mise-a-jour=coeur}, impose une mise à jour du cœur (redémarrage) et est donc refusé à chaud.
	 * Avec {@code versionInstallee} nulle (première installation), seules les règles de base s'appliquent.
	 */
	public static Verdict verifier(ManifestePlugin m, Map<String, String> migrations, Set<String> tablesReferencees,
			String versionCoeur, String versionInstallee) {
		Verdict base = verifier(m, migrations, tablesReferencees, versionCoeur);
		if (versionInstallee == null) {
			return base;
		}
		List<String> raisons = new ArrayList<>(base.raisons());
		long majeureInstallee = majeure(versionInstallee);
		long majeureProposee = majeure(m.version());
		if (majeureInstallee != majeureProposee) {
			raisons.add(0, "Changement de version majeure (" + versionInstallee + " -> " + m.version()
					+ ") : mise à jour du cœur requise, même si le manifeste déclare 'chaud'");
		}
		if (m.miseAJour() == ModeMiseAJour.COEUR) {
			raisons.add(0, "Le manifeste déclare mise-a-jour=coeur : mise à jour du cœur requise (redémarrage), "
					+ "pas de mise à jour à chaud");
		}
		return new Verdict(raisons.isEmpty(), List.copyOf(raisons));
	}

	/** Racine des paquets du cœur. */
	static final String RACINE_COEUR = "com.logicielpourtous.site";

	/** Seul espace de nommage admis sous la racine du cœur : un sous-paquet par plugin. */
	static final String ESPACE_PLUGINS = RACINE_COEUR + ".plugins.";

	/**
	 * Le {@code paquet} scanné par le plugin et le paquet de sa classe {@code configuration} ne doivent ni être dans
	 * les paquets du cœur, ni les englober (un scan ou une classe de configuration du cœur se retrouverait dans le
	 * contexte du plugin, avec ses contrôleurs et ses beans). Seul l'espace {@value #ESPACE_PLUGINS}{@code <plugin>}
	 * est admis sous la racine du cœur.
	 */
	public static List<String> verifierPaquets(ManifestePlugin m) {
		List<String> raisons = new ArrayList<>();
		if (empieteSurLeCoeur(m.paquet())) {
			raisons.add("Paquet '" + m.paquet() + "' refusé : il appartient au cœur ou l'englobe ; utiliser un paquet "
					+ "propre au plugin, par exemple " + ESPACE_PLUGINS + m.nom());
		}
		int point = m.configuration().lastIndexOf('.');
		String paquetConfiguration = point < 0 ? "" : m.configuration().substring(0, point);
		if (empieteSurLeCoeur(paquetConfiguration)) {
			raisons.add("Classe de configuration '" + m.configuration() + "' refusée : elle est dans un paquet du "
					+ "cœur ; utiliser un paquet propre au plugin, par exemple " + ESPACE_PLUGINS + m.nom());
		}
		return List.copyOf(raisons);
	}

	private static boolean empieteSurLeCoeur(String paquet) {
		if (paquet.isEmpty()) {
			return false;
		}
		if (paquet.startsWith(ESPACE_PLUGINS)) {
			return false;
		}
		return paquet.equals(RACINE_COEUR) || paquet.startsWith(RACINE_COEUR + ".")
				|| RACINE_COEUR.startsWith(paquet + ".");
	}

	/**
	 * Vérifie les dépendances du manifeste ({@code nom} ou {@code nom>=version}) contre les versions des plugins actifs
	 * (nom vers version). Renvoie une raison par dépendance mal formée, absente ou trop ancienne.
	 */
	public static List<String> verifierDependances(List<String> dependances, Map<String, String> versionsActives) {
		List<String> raisons = new ArrayList<>();
		for (String dependance : dependances) {
			Matcher d = DEPENDANCE.matcher(dependance.trim());
			if (!d.matches()) {
				raisons.add("Dépendance mal formée : '" + dependance + "' (attendu : nom ou nom>=version)");
				continue;
			}
			String nom = d.group(1);
			String minimum = d.group(2);
			String active = versionsActives.get(nom);
			if (active == null) {
				raisons.add("Dépendance absente : le plugin '" + nom + "' n'est pas actif"
						+ (minimum == null ? "" : " (requis : >= " + minimum + ")"));
			}
			else if (minimum != null && comparer(active, minimum) < 0) {
				raisons.add("Dépendance trop ancienne : '" + nom + "' actif en " + active + ", requis >= " + minimum);
			}
		}
		return List.copyOf(raisons);
	}

	/**
	 * Compare deux versions semver : noyau numérique segment par segment (un segment non numérique compte pour ses
	 * chiffres de tête, sinon 0), puis pré-version ({@code 1.5.0-rc1 < 1.5.0}) ; les métadonnées {@code +build} sont
	 * ignorées.
	 */
	static int comparer(String a, String b) {
		String[] va = scinder(a);
		String[] vb = scinder(b);
		String[] sa = va[0].split("\\.");
		String[] sb = vb[0].split("\\.");
		for (int i = 0; i < Math.max(sa.length, sb.length); i++) {
			int c = Long.compare(segment(sa, i), segment(sb, i));
			if (c != 0) {
				return c;
			}
		}
		return comparerPreVersions(va[1], vb[1]);
	}

	/** Noyau et pré-version (ou {@code null}) d'une version, sans métadonnées de build. */
	private static String[] scinder(String version) {
		String v = version.trim();
		int plus = v.indexOf('+');
		if (plus >= 0) {
			v = v.substring(0, plus);
		}
		int tiret = v.indexOf('-');
		return tiret < 0 ? new String[] { v, null } : new String[] { v.substring(0, tiret), v.substring(tiret + 1) };
	}

	/** Règles semver 2.0 : sans pré-version > avec ; identifiants numériques comparés numériquement et < alphanumériques. */
	private static int comparerPreVersions(String a, String b) {
		if (a == null || b == null) {
			return a == null ? (b == null ? 0 : 1) : -1;
		}
		String[] ia = a.split("\\.");
		String[] ib = b.split("\\.");
		for (int i = 0; i < Math.min(ia.length, ib.length); i++) {
			boolean na = ia[i].matches("\\d+");
			boolean nb = ib[i].matches("\\d+");
			int c;
			if (na && nb) {
				c = new java.math.BigInteger(ia[i]).compareTo(new java.math.BigInteger(ib[i]));
			}
			else if (na || nb) {
				c = na ? -1 : 1;
			}
			else {
				c = ia[i].compareTo(ib[i]);
			}
			if (c != 0) {
				return c;
			}
		}
		return Integer.compare(ia.length, ib.length);
	}

	private static long majeure(String version) {
		return segment(scinder(version)[0].split("\\."), 0);
	}

	private static long segment(String[] segments, int i) {
		if (i >= segments.length) {
			return 0;
		}
		String chiffres = segments[i].trim().replaceFirst("^(\\d*).*$", "$1");
		return chiffres.isEmpty() ? 0 : Long.parseLong(chiffres);
	}
}

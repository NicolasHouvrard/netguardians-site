package com.logicielpourtous.site.features.plateforme;

import com.logicielpourtous.site.sdk.ManifestePlugin;
import com.logicielpourtous.site.sdk.ModeMiseAJour;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Décide si une nouvelle version de plugin peut être installée (compatibilité du coeur, sûreté de la mise à jour à chaud). */
public final class VerificateurMiseAJour {

	public record Verdict(boolean accepte, List<String> raisons) {
	}

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

	/** Comparaison numérique segment par segment ; un segment non numérique compte pour ses chiffres de tête (sinon 0). */
	static int comparer(String a, String b) {
		String[] sa = a.split("\\.");
		String[] sb = b.split("\\.");
		for (int i = 0; i < Math.max(sa.length, sb.length); i++) {
			int c = Long.compare(segment(sa, i), segment(sb, i));
			if (c != 0) {
				return c;
			}
		}
		return 0;
	}

	private static long segment(String[] segments, int i) {
		if (i >= segments.length) {
			return 0;
		}
		String chiffres = segments[i].trim().replaceFirst("^(\\d*).*$", "$1");
		return chiffres.isEmpty() ? 0 : Long.parseLong(chiffres);
	}
}

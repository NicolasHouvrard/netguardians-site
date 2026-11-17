package com.logicielpourtous.site.features.plateforme;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Source des versions publiées pour le tireur ({@link TireurPlugins}) : le Maître par HTTP ({@link SourceMaitreHttp})
 * sur un serveur PARTNER/CLIENT, ou le dépôt local ({@link SourceDepotLocal}) sur le Maître. Tout ce qu'elle renvoie
 * est traité comme non fiable par le tireur (validation des entrées, taille, sha256, signature, manifeste).
 * <p>
 * Une {@link IOException} signale une indisponibilité passagère (Maître injoignable, réponse illisible...) : le tireur
 * ne change alors rien et réessaiera au tirage suivant.
 */
public interface SourcePlugins {

	/** Versions publiées. */
	List<EntreeCatalogue> catalogue() throws IOException;

	/**
	 * Télécharge le jar de {@code e} dans {@code dossier} ({@code <nom>-<version>.jar}, au plus {@code e.taille()}
	 * octets) et sa signature à côté ({@code <nom>-<version>.jar.sig}, au plus 16 Kio), sans jamais écraser un fichier
	 * existant ; renvoie le chemin du jar.
	 */
	Path telecharger(EntreeCatalogue e, Path dossier) throws IOException;

	/**
	 * Rapporte l'issue d'une tentative d'installation ({@code statut} tel que renvoyé par l'installation). Retour
	 * normal : rapport remis, ou refusé définitivement par le Maître (ex. 404 plugin hors catalogue : on n'insiste pas).
	 *
	 * @throws IOException échec passager : le tireur retentera ce rapport au tirage suivant
	 */
	void rapporter(String nom, String version, ResultatInstallation.Statut statut, List<String> raisons)
			throws IOException;

}

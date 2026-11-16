package com.logicielpourtous.site.features.plateforme;

import com.logicielpourtous.site.sdk.ClePublique;
import com.logicielpourtous.site.sdk.SignatureJar;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Clés publiques de confiance pour la signature des jars de plugins. Elles ne viennent QUE de la ressource du cœur
 * {@value #RESSOURCE} (aucune propriété ne permet d'en ajouter) ; le constructeur sert à injecter des clés de test.
 * <p>
 * Jamais d'ouverture par défaut : une ressource absente, vide ou sans aucune clé lisible, alors que la signature est
 * obligatoire, empêche la plateforme de démarrer ({@link IllegalStateException}).
 */
public final class ClesPubliques {

	private static final Logger log = LoggerFactory.getLogger(ClesPubliques.class);

	/** Ressource du cœur : {@code <keyId>=<clé publique X.509 en base64>}. */
	public static final String RESSOURCE = "plateforme/cles-publiques.properties";

	private final List<ClePublique> cles;

	private final int ignorees;

	/** Clés fournies telles quelles (tests). Avertit sous deux clés. */
	public ClesPubliques(List<ClePublique> cles) {
		this(cles, 0);
	}

	private ClesPubliques(List<ClePublique> cles, int ignorees) {
		this.cles = List.copyOf(cles);
		this.ignorees = ignorees;
		if (!this.cles.isEmpty() && this.cles.size() < 2) {
			log.warn("Plateforme de plugins : moins de deux clés publiques de confiance ({}) : aucune clé de secours "
					+ "pour la rotation", this.cles.size());
		}
	}

	/** Clés de confiance (non modifiable). */
	public List<ClePublique> cles() {
		return cles;
	}

	/** Nombre d'entrées de la ressource ignorées parce qu'illisibles (0 si les clés ont été injectées). */
	public int ignorees() {
		return ignorees;
	}

	/** Lit la ressource du cœur {@value #RESSOURCE}. */
	public static ClesPubliques depuisRessource(boolean signatureObligatoire) {
		return depuisRessource(RESSOURCE, signatureObligatoire);
	}

	static ClesPubliques depuisRessource(String ressource, boolean signatureObligatoire) {
		ClassLoader chargeur = ClesPubliques.class.getClassLoader();
		try (InputStream in = chargeur.getResourceAsStream(ressource)) {
			return lire(in, ressource, signatureObligatoire);
		}
		catch (IOException e) {
			throw new UncheckedIOException("Lecture des clés publiques impossible : " + ressource, e);
		}
	}

	/**
	 * Lit des clés au format {@code <keyId>=<base64 X.509>}. {@code in} nul = ressource absente. Compare le nombre
	 * d'entrées au nombre de clés lisibles : toute entrée ignorée est signalée.
	 *
	 * @throws IllegalStateException si la signature est obligatoire et qu'aucune clé n'est utilisable
	 */
	static ClesPubliques lire(InputStream in, String origine, boolean signatureObligatoire) {
		if (in == null) {
			return aucune(origine, "ressource absente", signatureObligatoire);
		}
		Properties props = new Properties();
		try {
			props.load(in);
		}
		catch (IOException | IllegalArgumentException e) {
			return aucune(origine, "ressource illisible", signatureObligatoire);
		}
		int entrees = props.stringPropertyNames().size();
		List<ClePublique> lues = SignatureJar.lireCles(props);
		int ignorees = entrees - lues.size();
		if (ignorees > 0) {
			log.warn("Plateforme de plugins : {} entrée(s) de {} ignorée(s) car illisible(s) ({} clé(s) utilisable(s))",
					ignorees, origine, lues.size());
		}
		if (lues.isEmpty()) {
			return aucune(origine, entrees == 0 ? "ressource vide" : "aucune entrée lisible", signatureObligatoire);
		}
		ClesPubliques cles = new ClesPubliques(lues, ignorees);
		log.info("Plateforme de plugins : {} clé(s) publique(s) de confiance lue(s) dans {} : {}", lues.size(),
				origine, lues.stream().map(ClePublique::keyId).sorted().toList());
		return cles;
	}

	private static ClesPubliques aucune(String origine, String cause, boolean signatureObligatoire) {
		String message = "Plateforme de plugins : aucune clé publique de confiance (" + cause + " : " + origine + ")";
		if (signatureObligatoire) {
			throw new IllegalStateException(message
					+ " alors que app.plateforme.signature-obligatoire=true : la plateforme refuse de démarrer");
		}
		log.warn("{} ; signature non obligatoire, aucun jar n'est vérifié", message);
		return new ClesPubliques(List.of(), 0);
	}

}

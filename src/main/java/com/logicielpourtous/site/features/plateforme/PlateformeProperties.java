package com.logicielpourtous.site.features.plateforme;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Propriétés de la plateforme de plugins ({@code app.plateforme.*}). */
@ConfigurationProperties("app.plateforme")
public class PlateformeProperties {

	/** Active la plateforme de plugins. */
	private boolean actif = false;

	/** Répertoire où sont déposés les jars de plugins. */
	private String repertoirePlugins = "plugins";

	/** Délai maximal de drainage d'une génération avant fermeture, en millisecondes. */
	private long delaiDrainageMs = 30000;

	/** Version du cœur, comparée aux bornes coeur-min / coeur-max des manifestes. */
	private String versionCoeur = "0.0.0";

	/**
	 * Purge des caches qui retiennent le chargeur d'une génération fermée. Ne se désactive que pour le test qui
	 * prouve que la mesure de fuite sait détecter une fuite ; en production, toujours {@code true}.
	 */
	private boolean nettoyageActif = true;

	/**
	 * Autorise {@code rapportFuites(true)} à remplir le tas jusqu'à {@code OutOfMemoryError} pour effacer les
	 * références souples. Brutal pour les autres threads : réservé aux tests et au diagnostic ; désactivé, une demande
	 * de pression est ignorée (avertissement) et le rapport se fait avec de simples GC.
	 */
	private boolean diagnosticFuites = false;

	/**
	 * Exige une signature valide ({@code <jar>.sig}, clé de {@code plateforme/cles-publiques.properties}) pour
	 * installer un jar. {@code false} seulement pour le développement local et les tests : aucun jar n'est alors
	 * vérifié (avertissement au démarrage et à chaque installation).
	 */
	private boolean signatureObligatoire = true;

	/** Bornes de {@link #tailleMaxJarMo} (la vérification lit le jar en mémoire : pas plus de 1 Gio). */
	static final long TAILLE_MAX_JAR_MO_MIN = 1;

	static final long TAILLE_MAX_JAR_MO_MAX = 1024;

	/**
	 * Taille maximale d'un jar de plugin, en Mo, entre {@value #TAILLE_MAX_JAR_MO_MIN} et
	 * {@value #TAILLE_MAX_JAR_MO_MAX} : au-delà, il est refusé avant toute vérification.
	 */
	private long tailleMaxJarMo = 100;

	/**
	 * Dossier de travail privé du cœur, où chaque jar est copié, vérifié puis chargé. Vide (défaut) : dossier frère
	 * du répertoire des plugins, {@code <repertoire-plugins>.work}, hors du répertoire de dépôt. Il doit appartenir à
	 * l'utilisateur du processus ; il est restreint à ce seul utilisateur, et refusé s'il est un lien.
	 */
	private String repertoireTravail = "";

	/**
	 * Dépôt du Maître (seulement avec {@code app.role=MASTER}) : jars publiés et leurs {@code .sig}. Dossier privé du
	 * processus (réservé à son propriétaire, refusé s'il est un lien), distinct du répertoire des plugins.
	 */
	private String depotMaitre = "depot-plugins";

	/**
	 * URL du Maître d'où un serveur PARTNER/CLIENT tire ses plugins. Vide (défaut) : celle de la licence,
	 * {@code app.licence.master-url}. Sans effet sur le Maître (il tire de son propre dépôt).
	 */
	private String masterUrl = "";

	/** Borne basse de {@link #intervalleTirageMs}. */
	static final long INTERVALLE_TIRAGE_MS_MIN = 10_000;

	/**
	 * Intervalle entre deux tirages de plugins, en millisecondes (10 min par défaut ; au moins
	 * {@value #INTERVALLE_TIRAGE_MS_MIN}). Le premier tirage a lieu un intervalle après le démarrage.
	 */
	private long intervalleTirageMs = 600_000;

	public String getMasterUrl() {
		return masterUrl;
	}

	public void setMasterUrl(String masterUrl) {
		this.masterUrl = masterUrl == null ? "" : masterUrl.trim();
	}

	public long getIntervalleTirageMs() {
		return intervalleTirageMs;
	}

	/** @throws IllegalArgumentException sous la borne : la plateforme ne démarre pas, avec ce message. */
	public void setIntervalleTirageMs(long intervalleTirageMs) {
		if (intervalleTirageMs < INTERVALLE_TIRAGE_MS_MIN) {
			throw new IllegalArgumentException("app.plateforme.intervalle-tirage-ms doit valoir au moins "
					+ INTERVALLE_TIRAGE_MS_MIN + " : " + intervalleTirageMs);
		}
		this.intervalleTirageMs = intervalleTirageMs;
	}

	public String getDepotMaitre() {
		return depotMaitre;
	}

	public void setDepotMaitre(String depotMaitre) {
		this.depotMaitre = depotMaitre;
	}

	public String getRepertoireTravail() {
		return repertoireTravail;
	}

	public void setRepertoireTravail(String repertoireTravail) {
		this.repertoireTravail = repertoireTravail == null ? "" : repertoireTravail;
	}

	public boolean isSignatureObligatoire() {
		return signatureObligatoire;
	}

	public void setSignatureObligatoire(boolean signatureObligatoire) {
		this.signatureObligatoire = signatureObligatoire;
	}

	public long getTailleMaxJarMo() {
		return tailleMaxJarMo;
	}

	/** @throws IllegalArgumentException hors bornes : la plateforme ne démarre pas, avec ce message. */
	public void setTailleMaxJarMo(long tailleMaxJarMo) {
		if (tailleMaxJarMo < TAILLE_MAX_JAR_MO_MIN || tailleMaxJarMo > TAILLE_MAX_JAR_MO_MAX) {
			throw new IllegalArgumentException("app.plateforme.taille-max-jar-mo doit être compris entre "
					+ TAILLE_MAX_JAR_MO_MIN + " et " + TAILLE_MAX_JAR_MO_MAX + " (Mo) : " + tailleMaxJarMo);
		}
		this.tailleMaxJarMo = tailleMaxJarMo;
	}

	public boolean isDiagnosticFuites() {
		return diagnosticFuites;
	}

	public void setDiagnosticFuites(boolean diagnosticFuites) {
		this.diagnosticFuites = diagnosticFuites;
	}

	public boolean isNettoyageActif() {
		return nettoyageActif;
	}

	public void setNettoyageActif(boolean nettoyageActif) {
		this.nettoyageActif = nettoyageActif;
	}

	public boolean isActif() {
		return actif;
	}

	public void setActif(boolean actif) {
		this.actif = actif;
	}

	public String getRepertoirePlugins() {
		return repertoirePlugins;
	}

	public void setRepertoirePlugins(String repertoirePlugins) {
		this.repertoirePlugins = repertoirePlugins;
	}

	public long getDelaiDrainageMs() {
		return delaiDrainageMs;
	}

	public void setDelaiDrainageMs(long delaiDrainageMs) {
		this.delaiDrainageMs = delaiDrainageMs;
	}

	public String getVersionCoeur() {
		return versionCoeur;
	}

	public void setVersionCoeur(String versionCoeur) {
		this.versionCoeur = versionCoeur;
	}

}

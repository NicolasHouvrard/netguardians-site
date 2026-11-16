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

	/** Taille maximale d'un jar de plugin, en Mo : au-delà, il est refusé avant toute vérification. */
	private long tailleMaxJarMo = 100;

	public boolean isSignatureObligatoire() {
		return signatureObligatoire;
	}

	public void setSignatureObligatoire(boolean signatureObligatoire) {
		this.signatureObligatoire = signatureObligatoire;
	}

	public long getTailleMaxJarMo() {
		return tailleMaxJarMo;
	}

	public void setTailleMaxJarMo(long tailleMaxJarMo) {
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

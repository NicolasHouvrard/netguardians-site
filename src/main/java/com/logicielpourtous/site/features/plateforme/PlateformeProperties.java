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

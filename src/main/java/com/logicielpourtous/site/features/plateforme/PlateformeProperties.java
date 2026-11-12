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

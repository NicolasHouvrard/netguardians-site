package com.logicielpourtous.site.features.plateforme;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/** Une instance chargée d'un plugin : nom, version, chargeur de classes et contexte Spring enfant. */
public class PluginGeneration {

	private static final AtomicInteger SEQUENCE = new AtomicInteger();

	final int seq = SEQUENCE.incrementAndGet();

	final String nom;

	final String version;

	/** Copie du jar réellement chargée (celle qu'on supprime à la fermeture). */
	final Path jarCopie;

	/** Jar d'origine (pour un retour arrière). */
	Path source;

	PluginClassLoader loader;

	ConfigurableApplicationContext contexte;

	/** Mapping MVC propre au plugin. */
	RequestMappingHandlerMapping mapping;

	/** Nombre d'appels de contrat en cours vers cette génération (utilisé pour le drainage). */
	final AtomicInteger enVol = new AtomicInteger();

	PluginGeneration(String nom, String version, Path jarCopie) {
		this.nom = nom;
		this.version = version;
		this.jarCopie = jarCopie;
	}

	public String etiquette() {
		return nom + "@" + version + "#" + seq;
	}

}

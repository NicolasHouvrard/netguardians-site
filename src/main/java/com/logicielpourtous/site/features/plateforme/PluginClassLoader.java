package com.logicielpourtous.site.features.plateforme;

import java.io.IOException;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

/**
 * Chargeur de classes d'un plugin : enfant d'abord, sauf pour les paquets partagés (JDK, Spring, Hibernate,
 * Jackson, SDK...) qui doivent venir du cœur pour que les types soient identiques des deux côtés.
 */
public class PluginClassLoader extends URLClassLoader {

	private static final String[] PREFIXES_PARENT_D_ABORD = { "java.", "javax.", "jakarta.", "org.springframework.",
			"org.hibernate.", "tools.jackson.", "com.fasterxml.", "org.slf4j.", "org.flywaydb.", "org.postgresql.",
			"com.logicielpourtous.site.sdk." };

	static {
		registerAsParallelCapable();
	}

	private final String etiquette;

	public PluginClassLoader(String etiquette, URL jar, ClassLoader parent) {
		super("plugin:" + etiquette, new URL[] { jar }, parent);
		this.etiquette = etiquette;
	}

	@Override
	protected Class<?> loadClass(String nom, boolean resoudre) throws ClassNotFoundException {
		synchronized (getClassLoadingLock(nom)) {
			Class<?> classe = findLoadedClass(nom);
			if (classe == null) {
				if (estParentDAbord(nom)) {
					classe = getParent().loadClass(nom);
				}
				else {
					try {
						classe = findClass(nom);
					}
					catch (ClassNotFoundException e) {
						classe = getParent().loadClass(nom);
					}
				}
			}
			if (resoudre) {
				resolveClass(classe);
			}
			return classe;
		}
	}

	private static boolean estParentDAbord(String nom) {
		for (String prefixe : PREFIXES_PARENT_D_ABORD) {
			if (nom.startsWith(prefixe)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Les URL {@code jar:} du plugin sont rendues sans cache : par défaut, {@code JarURLConnection} garde le
	 * {@code JarFile} ouvert dans un cache global de la JVM que {@link #close()} ne libère pas, ce qui verrouille le
	 * jar (Windows) ou fuit un descripteur (Linux). Sans cache, chaque connexion ouvre et ferme son propre JarFile.
	 * Seules les URL de ce chargeur sont concernées, le cache global de la JVM reste inchangé.
	 */
	@Override
	public URL findResource(String nom) {
		return sansCache(super.findResource(nom));
	}

	@Override
	public Enumeration<URL> findResources(String nom) throws IOException {
		List<URL> urls = new ArrayList<>();
		for (URL url : Collections.list(super.findResources(nom))) {
			urls.add(sansCache(url));
		}
		return Collections.enumeration(urls);
	}

	private static URL sansCache(URL url) {
		if (url == null || !"jar".equals(url.getProtocol())) {
			return url;
		}
		try {
			return URL.of(url.toURI(), new URLStreamHandler() {
				@Override
				protected URLConnection openConnection(URL u) throws IOException {
					URLConnection connexion = new URL(u.toExternalForm()).openConnection();
					connexion.setUseCaches(false);
					return connexion;
				}
			});
		}
		catch (java.net.URISyntaxException | java.net.MalformedURLException e) {
			throw new IllegalStateException("URL de ressource invalide : " + url, e);
		}
	}

	@Override
	public URL getResource(String nom) {
		URL url = findResource(nom);
		return (url != null) ? url : super.getResource(nom);
	}

	@Override
	public Enumeration<URL> getResources(String nom) throws IOException {
		List<URL> toutes = new ArrayList<>(Collections.list(findResources(nom)));
		toutes.addAll(Collections.list(getParent().getResources(nom)));
		return Collections.enumeration(toutes);
	}

	/** Ferme le chargeur et libère le fichier jar (indispensable sous Windows pour pouvoir le supprimer). */
	@Override
	public void close() throws IOException {
		super.close();
	}

	@Override
	public String toString() {
		return "PluginClassLoader[" + etiquette + "]";
	}

}

package com.logicielpourtous.site.features.plateforme;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.logicielpourtous.site.sdk.ContratIndisponibleException;

/**
 * Registre des fournisseurs de contrats. Les consommateurs reçoivent un proxy stable par contrat ; le fournisseur
 * derrière le proxy peut changer (mise à jour à chaud) sans que le consommateur ne s'en aperçoive.
 */
public class ContratRegistre {

	private record Fournisseur(Object impl, PluginGeneration generation) {
	}

	private final Map<Class<?>, Fournisseur> fournisseurs = new ConcurrentHashMap<>();

	private final Map<Class<?>, Object> proxies = new ConcurrentHashMap<>();

	/** Déclare un contrat (sans fournisseur pour l'instant). */
	public void declarer(Class<?> type) {
		obtenir(type);
	}

	/** Contrats connus (déclarés, demandés ou publiés), vue en lecture seule. */
	public Set<Class<?>> types() {
		return Set.copyOf(proxies.keySet());
	}

	/** Renvoie toujours le même proxy pour un type donné. */
	@SuppressWarnings("unchecked")
	public <T> T obtenir(Class<T> type) {
		verifierType(type);
		return (T) proxies.computeIfAbsent(type, this::creerProxy);
	}

	/** Invariant : les types de contrat appartiennent au cœur ou au SDK, jamais à un plugin. */
	private static void verifierType(Class<?> type) {
		if (type.getClassLoader() instanceof PluginClassLoader) {
			throw new IllegalArgumentException("Le type de contrat " + type.getName()
					+ " est défini par un plugin : un contrat doit appartenir au cœur ou au SDK");
		}
	}

	public void publier(Class<?> type, Object impl, PluginGeneration generation) {
		obtenir(type);
		fournisseurs.put(type, new Fournisseur(impl, generation));
	}

	/** Retire les fournisseurs de cette génération seulement (pas ceux d'une génération plus récente). */
	public void retirer(PluginGeneration generation) {
		fournisseurs.entrySet().removeIf(e -> e.getValue().generation() == generation);
	}

	private Object creerProxy(Class<?> type) {
		return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type }, (proxy, methode, args) -> {
			if (methode.getDeclaringClass() == Object.class) {
				return switch (methode.getName()) {
					case "toString" -> "ContratProxy[" + type.getSimpleName() + "]";
					case "hashCode" -> System.identityHashCode(proxy);
					case "equals" -> proxy == args[0];
					default -> throw new UnsupportedOperationException(methode.getName());
				};
			}
			while (true) {
				Fournisseur fournisseur = fournisseurs.get(type);
				if (fournisseur == null) {
					throw new ContratIndisponibleException(type.getName());
				}
				// On s'annonce AVANT de revérifier : un drainage qui voit enVol == 0 après retirer() est ainsi sûr
				// qu'aucun appel ne partira vers cette génération.
				fournisseur.generation().enVol.incrementAndGet();
				try {
					if (fournisseurs.get(type) != fournisseur) {
						continue; // retiré ou remplacé entre-temps : on réessaie avec le fournisseur courant
					}
					return methode.invoke(fournisseur.impl(), args);
				}
				catch (InvocationTargetException e) {
					throw e.getCause();
				}
				finally {
					fournisseur.generation().enVol.decrementAndGet();
				}
			}
		});
	}

}

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

	/** Générations démarrées qui peuvent fournir des contrats (voir {@link #activer}). */
	private final Set<PluginGeneration> actives = ConcurrentHashMap.newKeySet();

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
		if (estUnProxyDuRegistre(impl)) {
			throw new IllegalArgumentException("Le proxy du registre ne peut pas être publié comme fournisseur de "
					+ type.getName());
		}
		fournisseurs.put(type, new Fournisseur(impl, generation));
	}

	/**
	 * Déclare une génération démarrée comme fournisseur potentiel : ses beans qui implémentent un contrat déjà connu
	 * sont publiés tout de suite, et un contrat demandé plus tard sera cherché parmi les générations actives (le
	 * fournisseur peut donc démarrer avant que quiconque ait demandé le type).
	 */
	public synchronized void activer(PluginGeneration generation) {
		actives.add(generation);
		for (Class<?> type : proxies.keySet()) {
			Object impl = chercher(type, generation);
			if (impl != null) {
				fournisseurs.put(type, new Fournisseur(impl, generation));
			}
		}
	}

	/** Retire les fournisseurs de cette génération seulement (pas ceux d'une génération plus récente). */
	public synchronized void retirer(PluginGeneration generation) {
		actives.remove(generation);
		fournisseurs.entrySet().removeIf(e -> e.getValue().generation() == generation);
	}

	/**
	 * Cherche un fournisseur du type parmi les générations actives (la plus récente d'abord) et le publie. Verrou commun
	 * avec {@link #retirer} : une génération retirée ne peut pas être republiée après coup (son drainage serait faux).
	 */
	private synchronized Fournisseur resoudre(Class<?> type) {
		Fournisseur courant = fournisseurs.get(type);
		if (courant != null) {
			return courant;
		}
		PluginGeneration choisie = null;
		Object impl = null;
		for (PluginGeneration gen : actives) {
			Object candidat = chercher(type, gen);
			if (candidat != null && (choisie == null || gen.seq > choisie.seq)) {
				choisie = gen;
				impl = candidat;
			}
		}
		if (choisie == null) {
			return null;
		}
		Fournisseur f = new Fournisseur(impl, choisie);
		fournisseurs.put(type, f);
		return f;
	}

	/**
	 * Premier bean du type défini dans le contexte propre de la génération (pas dans le cœur), ou {@code null}. Les
	 * proxies de ce registre ne sont jamais des fournisseurs : un plugin consommateur qui expose le proxy comme bean
	 * du type contrat le ferait sinon s'appeler lui-même (récursion infinie).
	 */
	private Object chercher(Class<?> type, PluginGeneration generation) {
		var contexte = generation.contexte;
		if (contexte == null || !contexte.isActive()) {
			return null;
		}
		return contexte.getBeanFactory()
			.getBeansOfType(type, false, false)
			.values()
			.stream()
			.filter(bean -> !estUnProxyDuRegistre(bean))
			.findFirst()
			.orElse(null);
	}

	private boolean estUnProxyDuRegistre(Object bean) {
		if (!Proxy.isProxyClass(bean.getClass())) {
			return false;
		}
		for (Object proxy : proxies.values()) {
			if (proxy == bean) {
				return true;
			}
		}
		return false;
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
					fournisseur = resoudre(type);
				}
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

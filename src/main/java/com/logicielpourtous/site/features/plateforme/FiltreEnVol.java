package com.logicielpourtous.site.features.plateforme;

import com.logicielpourtous.site.features.commun.TenantContext;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Compte, pendant toute la requête (y compris un traitement asynchrone), les requêtes en vol de la génération de
 * plugin visée. Même discipline que {@link ContratRegistre} : on s'annonce AVANT de revérifier que la génération est
 * toujours la courante, de sorte qu'un drainage qui voit {@code enVol == 0} après le retrait de la route est sûr
 * qu'aucune requête n'y arrivera.
 * <p>
 * Le filtre est aussi l'unique point de nettoyage du {@link TenantContext} pour les requêtes de plugin (elles ne
 * passent pas par les intercepteurs du cœur) : le schéma de tenant ne doit jamais rester sur un thread du pool.
 */
@Component
@ConditionalOnProperty(name = "app.plateforme.actif", havingValue = "true")
public class FiltreEnVol extends OncePerRequestFilter {

	private final RoutagePlugins routage;

	public FiltreEnVol(RoutagePlugins routage) {
		this.routage = routage;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest requete, HttpServletResponse reponse, FilterChain chaine)
			throws ServletException, IOException {
		String nom = RoutagePlugins.nomDuPlugin(requete.getRequestURI().substring(requete.getContextPath().length()));
		if (nom == null) {
			chaine.doFilter(requete, reponse);
			return;
		}
		try {
			traiter(nom, requete, reponse, chaine);
		}
		finally {
			TenantContext.clear();
		}
	}

	private void traiter(String nom, HttpServletRequest requete, HttpServletResponse reponse, FilterChain chaine)
			throws ServletException, IOException {
		while (true) {
			PluginGeneration gen = routage.generationActive(nom);
			if (gen == null) {
				// Plugin inconnu ou retiré : on le dit explicitement au routage, qui répondra 404 (jamais de nouvelle
				// consultation de la table des routes, sinon une génération non comptée pourrait servir la requête).
				requete.setAttribute(RoutagePlugins.ATTRIBUT_GENERATION, RoutagePlugins.AUCUNE_GENERATION);
				chaine.doFilter(requete, reponse);
				return;
			}
			gen.enVol.incrementAndGet();
			boolean decrementerMaintenant = true;
			try {
				if (routage.generationActive(nom) != gen) {
					continue; // basculée ou retirée entre-temps : on réessaie avec la génération courante
				}
				requete.setAttribute(RoutagePlugins.ATTRIBUT_GENERATION, gen);
				chaine.doFilter(requete, reponse);
				if (requete.isAsyncStarted()) {
					decrementerMaintenant = !differerJusquALaFinAsynchrone(requete, gen);
				}
				return;
			}
			finally {
				if (decrementerMaintenant) {
					gen.enVol.decrementAndGet();
				}
			}
		}
	}

	/**
	 * Reporte le décrément à la fin du cycle asynchrone. Renvoie {@code false} si le cycle s'est déjà terminé entre-temps
	 * (l'appelant décrémente alors lui-même).
	 */
	static boolean differerJusquALaFinAsynchrone(HttpServletRequest requete, PluginGeneration gen) {
		try {
			requete.getAsyncContext().addListener(new EcouteurFinAsync(gen));
			return true;
		}
		catch (IllegalStateException dejaTermine) {
			return false;
		}
	}

	/**
	 * Décrémente {@code enVol} et nettoie le {@link TenantContext} uniquement à {@code onComplete}, que le conteneur
	 * déclenche toujours en fin de cycle (y compris après un timeout ou une erreur). Sur timeout ou erreur, Spring
	 * (dont l'écouteur passe avant le nôtre) exécute encore les rappels du plugin puis redistribue : la requête reste
	 * donc en vol jusqu'à {@code onComplete}.
	 */
	static final class EcouteurFinAsync implements AsyncListener {

		private final PluginGeneration gen;

		private final AtomicBoolean fait = new AtomicBoolean();

		EcouteurFinAsync(PluginGeneration gen) {
			this.gen = gen;
		}

		@Override
		public void onComplete(AsyncEvent event) {
			if (fait.compareAndSet(false, true)) {
				gen.enVol.decrementAndGet();
				TenantContext.clear();
			}
		}

		@Override
		public void onTimeout(AsyncEvent event) {
			// Volontairement rien : onComplete suivra.
		}

		@Override
		public void onError(AsyncEvent event) {
			// Volontairement rien : onComplete suivra.
		}

		@Override
		public void onStartAsync(AsyncEvent event) {
			// Un nouveau cycle asynchrone réutilise le même écouteur.
			event.getAsyncContext().addListener(this);
		}

	}

}

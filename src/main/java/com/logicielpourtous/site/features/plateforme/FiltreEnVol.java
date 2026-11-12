package com.logicielpourtous.site.features.plateforme;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Compte, pendant toute la requête, les requêtes en vol de la génération de plugin visée. Même discipline que
 * {@link ContratRegistre} : on s'annonce AVANT de revérifier que la génération est toujours la courante, de sorte
 * qu'un drainage qui voit {@code enVol == 0} après le retrait de la route est sûr qu'aucune requête n'y arrivera.
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
		while (true) {
			PluginGeneration gen = routage.generationActive(nom);
			if (gen == null) {
				chaine.doFilter(requete, reponse); // plugin inconnu ou retiré : le routage répondra 404
				return;
			}
			gen.enVol.incrementAndGet();
			try {
				if (routage.generationActive(nom) != gen) {
					continue; // basculée ou retirée entre-temps : on réessaie avec la génération courante
				}
				requete.setAttribute(RoutagePlugins.ATTRIBUT_GENERATION, gen);
				chaine.doFilter(requete, reponse);
				return;
			}
			finally {
				gen.enVol.decrementAndGet();
			}
		}
	}

}

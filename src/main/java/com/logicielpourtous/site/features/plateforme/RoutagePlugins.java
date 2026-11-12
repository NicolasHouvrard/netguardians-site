package com.logicielpourtous.site.features.plateforme;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.HttpRequestHandler;
import org.springframework.web.servlet.HandlerExecutionChain;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Route {@code /api/plugins/<nom>/...} vers le {@link RequestMappingHandlerMapping} propre à la génération active du
 * plugin. Aucune route de plugin n'est jamais ajoutée au mapping du cœur : la bascule est une simple écriture dans
 * une table de génération active.
 */
@Component
@ConditionalOnProperty(name = "app.plateforme.actif", havingValue = "true")
public class RoutagePlugins implements HandlerMapping, Ordered {

	static final String PREFIXE = "/api/plugins/";

	/** Attribut de requête : génération annoncée par {@link FiltreEnVol} (celle qui sera réellement servie). */
	static final String ATTRIBUT_GENERATION = RoutagePlugins.class.getName() + ".generation";

	private static final HttpRequestHandler MAINTENANCE = (requete, reponse) -> {
		reponse.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
		reponse.setHeader("Retry-After", "2");
		reponse.setContentType("application/json;charset=UTF-8");
		reponse.getWriter().write("{\"erreur\":\"mise a jour en cours\"}");
	};

	private final Map<String, PluginGeneration> routes = new ConcurrentHashMap<>();

	private final Set<String> maintenance = ConcurrentHashMap.newKeySet();

	/**
	 * Construit le mapping MVC propre à la génération (contexte du plugin, préfixe {@code /api/plugins/<nom>}).
	 * Le mapping résout lui-même les beans dans le contexte du plugin (pas dans celui du cœur).
	 */
	public static RequestMappingHandlerMapping creerMapping(PluginGeneration gen) {
		RequestMappingHandlerMapping mapping = new RequestMappingHandlerMapping();
		mapping.setApplicationContext(gen.contexte);
		mapping.setPathPrefixes(Map.of(PREFIXE + gen.nom, c -> true));
		mapping.afterPropertiesSet();
		gen.mapping = mapping;
		return mapping;
	}

	public void router(String nom, PluginGeneration gen) {
		routes.put(nom, gen);
	}

	/** Retire la route seulement si elle pointe encore sur cette génération (pas sur une plus récente). */
	public void retirerRoute(String nom, PluginGeneration gen) {
		routes.remove(nom, gen);
	}

	public void maintenance(String nom, boolean actif) {
		if (actif) {
			maintenance.add(nom);
		}
		else {
			maintenance.remove(nom);
		}
	}

	public boolean enMaintenance(String nom) {
		return maintenance.contains(nom);
	}

	/** Génération actuellement routée pour ce plugin, ou {@code null}. */
	PluginGeneration generationActive(String nom) {
		return routes.get(nom);
	}

	/** Nom du plugin visé par un chemin {@code /api/plugins/<nom>/...}, ou {@code null} si le chemin n'en est pas un. */
	static String nomDuPlugin(String chemin) {
		if (chemin == null || !chemin.startsWith(PREFIXE)) {
			return null;
		}
		int fin = chemin.indexOf('/', PREFIXE.length());
		String nom = fin < 0 ? chemin.substring(PREFIXE.length()) : chemin.substring(PREFIXE.length(), fin);
		return nom.isEmpty() ? null : nom;
	}

	@Override
	public HandlerExecutionChain getHandler(HttpServletRequest requete) throws Exception {
		String nom = nomDuPlugin(requete.getRequestURI().substring(requete.getContextPath().length()));
		if (nom == null) {
			return null;
		}
		if (maintenance.contains(nom)) {
			return new HandlerExecutionChain(MAINTENANCE);
		}
		// La génération comptée par le filtre prime : c'est elle qu'un drainage attend.
		PluginGeneration gen = requete.getAttribute(ATTRIBUT_GENERATION) instanceof PluginGeneration annoncee
				&& annoncee.nom.equals(nom) ? annoncee : routes.get(nom);
		if (gen == null || gen.mapping == null) {
			return null;
		}
		HandlerExecutionChain chaine = gen.mapping.getHandler(requete);
		if (chaine == null) {
			return null;
		}
		String etiquette = gen.etiquette();
		chaine.addInterceptor(0, new HandlerInterceptor() {
			@Override
			public boolean preHandle(HttpServletRequest req, HttpServletResponse rep, Object handler) {
				rep.setHeader("X-Plugin", etiquette);
				return true;
			}
		});
		return chaine;
	}

	@Override
	public boolean usesPathPatterns() {
		return true;
	}

	/** Juste avant le {@code RequestMappingHandlerMapping} du cœur (ordre 0). */
	@Override
	public int getOrder() {
		return -1;
	}

}

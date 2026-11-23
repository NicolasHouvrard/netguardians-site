package com.logicielpourtous.site.features.plateforme;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.stereotype.Component;
import org.springframework.web.HttpRequestHandler;
import org.springframework.web.servlet.HandlerExecutionChain;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Route les requêtes des plugins vers le {@link RequestMappingHandlerMapping} propre à la génération active du
 * plugin : {@code /api/plugins/<nom>/...} (API JSON, chaîne à jeton) pour les {@code @RestController}, et
 * {@code /p/<nom>/...} (pages HTML et fragments HTMX, chaîne à session et CSRF) pour les autres {@code @Controller}.
 * Aucune route de plugin n'est jamais ajoutée au mapping du cœur : la bascule est une simple écriture dans une table
 * de génération active.
 * <p>
 * Deux espaces sont RÉSERVÉS au noyau, aucun mapping de plugin ne peut les revendiquer :
 * {@code /api/plugins/*}{@code /ui/**} ({@link PluginUiController}, ouvert sans jeton) et
 * {@code /p/*}{@code /static/**} ({@link PluginStatiquesController}).
 * <p>
 * <b>Règles pour les auteurs de plugins</b> :
 * <ul>
 * <li>{@code @RestController} : routé sous {@code /api/plugins/<nom>} (JWT, sans CSRF) ;</li>
 * <li>{@code @Controller} (page ou fragment ; noms de vue {@code plugins/<nom>/...}, gabarit
 * {@code templates/plugins/<nom>/....html} dans le jar) : routé sous {@code /p/<nom>} (session et CSRF) ;</li>
 * <li>une classe {@code @Controller} avec {@code @ResponseBody} au niveau de la classe est routée sous
 * {@code /p}, pas sous {@code /api/plugins} ;</li>
 * <li>un plugin ne doit rendre que SES PROPRES gabarits {@code plugins/<nom>/...} : rendre ceux d'un autre plugin
 * est techniquement possible mais non compté en vol (le jar peut être fermé pendant le rendu) et non supporté ;</li>
 * <li>ne jamais placer d'objet d'une classe du plugin dans la session HTTP ni dans les attributs flash : ils
 * retiendraient l'ancien chargeur de classes après une mise à jour à chaud ;</li>
 * <li>page ou fragment : le choix est celui du plugin. Les écrans du socle ne renvoient un fragment que pour une requête
 * HTMX ordinaire ({@code HtmxRequetes#fragmentSeulement} : {@code HX-Request} sans {@code HX-Boosted} ni
 * {@code HX-History-Restore-Request}). Une page de plugin qui répond par un fragment doit appliquer la même règle,
 * sinon une navigation boostée (clic sur une tuile) ou une restauration de l'historique remplacerait tout le corps de
 * la page par ce fragment ;</li>
 * <li>noms d'attributs de modèle RÉSERVÉS au socle, à ne pas réutiliser pour autre chose : {@code apps},
 * {@code uiSlots}, {@code tuiles}, {@code titre}, {@code moduleActif}, {@code menuModule}, {@code fil} ;</li>
 * <li>le cœur applique aux pages {@code /p/**} les mêmes gardes que ses écrans : contexte de tenant et obligation de
 * changer un mot de passe temporaire (redirection vers {@code /change-password}), avant tout intercepteur du plugin.</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(name = "app.plateforme.actif", havingValue = "true")
public class RoutagePlugins implements HandlerMapping, Ordered {

	static final String PREFIXE = "/api/plugins/";

	/** Pages HTML (et fragments HTMX) des plugins, servies sur la chaine web a session. */
	static final String PREFIXE_PAGES = "/p/";

	/** Attribut de requête : génération annoncée par {@link FiltreEnVol} (celle qui sera réellement servie). */
	static final String ATTRIBUT_GENERATION = RoutagePlugins.class.getName() + ".generation";

	/** Valeur de l'attribut quand le filtre n'a trouvé aucune génération active : le routage répond 404. */
	static final Object AUCUNE_GENERATION = new Object();

	private static final HttpRequestHandler MAINTENANCE = (requete, reponse) -> {
		reponse.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
		reponse.setHeader("Retry-After", "2");
		reponse.setContentType("application/json;charset=UTF-8");
		reponse.getWriter().write("{\"erreur\":\"mise a jour en cours\"}");
	};

	/**
	 * Espace {@code /api/plugins/<nom>/ui/**} réservé au noyau ({@link PluginUiController}) : il est ouvert sans jeton
	 * par la sécurité, aucun mapping de plugin ne doit donc pouvoir le revendiquer (ni y masquer les fichiers). Le
	 * matcher travaille sur le chemin décodé et normalisé de la requête (jamais sur la chaîne brute : {@code %75i} est
	 * {@code ui} pour la sécurité comme pour lui).
	 */
	private static final RequestMatcher ESPACE_UI = PathPatternRequestMatcher
			.pathPattern("/api/plugins/*/ui/**");

	/**
	 * Espace {@code /p/<nom>/static/**} réservé au noyau ({@link PluginStatiquesController}), pour les mêmes raisons
	 * que {@link #ESPACE_UI} : aucun mapping de plugin ne peut y répondre ni y masquer les fichiers.
	 */
	private static final RequestMatcher ESPACE_STATIC = PathPatternRequestMatcher.pathPattern("/p/*/static/**");

	/** Gardes du noyau (tenant, changement de mot de passe) appliqués aux pages {@code /p/**} ; {@code null} : aucun. */
	private HandlerInterceptor gardesPages;

	@org.springframework.beans.factory.annotation.Autowired
	void setGardesPages(
			@org.springframework.beans.factory.annotation.Qualifier("interceptorPagesPlugins") org.springframework.beans.factory.ObjectProvider<HandlerInterceptor> gardes) {
		this.gardesPages = gardes.getIfAvailable();
	}

	private final Map<String, PluginGeneration> routes = new ConcurrentHashMap<>();

	private final Set<String> maintenance = ConcurrentHashMap.newKeySet();

	/**
	 * Construit le mapping MVC propre à la génération (contexte du plugin) : préfixe {@code /api/plugins/<nom>} pour
	 * les {@code @RestController}, {@code /p/<nom>} pour les autres {@code @Controller} (prédicats disjoints).
	 * Le mapping résout lui-même les beans dans le contexte du plugin (pas dans celui du cœur).
	 */
	public static RequestMappingHandlerMapping creerMapping(PluginGeneration gen) {
		ClassLoader chargeurDuPlugin = gen.loader;
		RequestMappingHandlerMapping mapping = new RequestMappingHandlerMapping() {
			/**
			 * Seules les classes chargées par le chargeur de la génération deviennent des routes du plugin : un
			 * contrôleur du cœur (importé ou scanné par le contexte du plugin) n'est jamais exposé sous /p/<nom> ni sous
			 * {@code /api/plugins/<nom>}. Une sous-classe CGLIB d'une classe du plugin garde le chargeur du plugin.
			 */
			@Override
			protected boolean isHandler(Class<?> beanType) {
				// Doit etre un @Controller (donc recevoir l'un des deux prefixes) : une classe qui ne porterait que
				// @RequestMapping serait sinon montee sans prefixe.
				return beanType.getClassLoader() == chargeurDuPlugin
						&& AnnotatedElementUtils.hasAnnotation(beanType, Controller.class) && super.isHandler(beanType);
			}
		};
		mapping.setApplicationContext(gen.contexte);
		// Deux prefixes aux predicats DISJOINTS : une classe en recoit exactement un.
		// @RestController (y compris par meta-annotation) -> API JSON ; autre @Controller -> pages HTML.
		Map<String, Predicate<Class<?>>> prefixes = new LinkedHashMap<>();
		prefixes.put(PREFIXE + gen.nom, c -> AnnotatedElementUtils.hasAnnotation(c, RestController.class));
		prefixes.put(PREFIXE_PAGES + gen.nom, c -> !AnnotatedElementUtils.hasAnnotation(c, RestController.class));
		mapping.setPathPrefixes(prefixes);
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

	/** Nom du plugin visé par un chemin {@code /api/plugins/<nom>/...} ou {@code /p/<nom>/...}, ou {@code null} sinon. */
	static String nomDuPlugin(String chemin) {
		if (chemin == null) {
			return null;
		}
		String prefixe = chemin.startsWith(PREFIXE) ? PREFIXE : chemin.startsWith(PREFIXE_PAGES) ? PREFIXE_PAGES : null;
		if (prefixe == null) {
			return null;
		}
		int fin = chemin.indexOf('/', prefixe.length());
		String nom = fin < 0 ? chemin.substring(prefixe.length()) : chemin.substring(prefixe.length(), fin);
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
		if (ESPACE_UI.matches(requete) || ESPACE_STATIC.matches(requete)) {
			return null; // mapping du cœur (PluginUiController, PluginStatiquesController), jamais celui du plugin
		}
		// Seule la génération annoncée (et donc comptée) par FiltreEnVol peut servir la requête : jamais de nouvelle
		// consultation des routes ici, sinon une génération non comptée pourrait être drainée en pleine requête.
		// Attribut absent ou AUCUNE_GENERATION : 404.
		PluginGeneration gen = requete.getAttribute(ATTRIBUT_GENERATION) instanceof PluginGeneration annoncee
				&& annoncee.nom.equals(nom) ? annoncee : null;
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
		if (gardesPages != null && requete.getRequestURI().startsWith(requete.getContextPath() + PREFIXE_PAGES)) {
			// Pages : mêmes gardes que les écrans du cœur (tenant, mot de passe temporaire), avant celles du plugin.
			chaine.addInterceptor(0, gardesPages);
		}
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

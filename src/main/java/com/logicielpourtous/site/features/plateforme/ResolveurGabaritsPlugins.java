package com.logicielpourtous.site.features.plateforme;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.thymeleaf.IEngineConfiguration;
import org.thymeleaf.cache.NonCacheableCacheEntryValidity;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ITemplateResolver;
import org.thymeleaf.templateresolver.TemplateResolution;
import org.thymeleaf.templateresource.ITemplateResource;

import com.logicielpourtous.site.sdk.ManifestePlugin;

/**
 * Résout les gabarits Thymeleaf livrés par les plugins.
 * <p>
 * Ne résout QUE les noms de la forme exacte {@code plugins/<nom>/<chemin>} : {@code <nom>} suit le format des noms de
 * plugin ({@link ManifestePlugin#NOM_VALIDE}), {@code <chemin>} est fait d'un ou plusieurs segments
 * {@code [a-z0-9_-]+} séparés par {@code /} (pas de point : aucune extension, aucune remontée {@code ..}, aucun
 * séparateur {@code \}, aucun chemin absolu ni {@code //}, aucun caractère de contrôle ni majuscule). Le fichier lu est
 * {@code templates/plugins/<nom>/<chemin>.html} DANS LE JAR de la génération ACTIVE de {@code <nom>}, par
 * {@code findResource} du chargeur du plugin : jamais {@code getResource}, qui retomberait sur le chargeur du cœur
 * (un plugin ne peut donc ni résoudre ni masquer un gabarit du cœur, et un gabarit du cœur n'est jamais servi pour un
 * nom de plugin). Tout autre nom, plugin inconnu ou non actif : {@code null} (non résolu).
 * <p>
 * Pas de cache : le moteur ne conserve ni gabarit analysé ni ressource d'un plugin. Une mise à jour à chaud ou une
 * désinstallation est donc visible à la requête suivante, et le cache du moteur ne retient jamais le chargeur d'une
 * génération fermée (voir {@code FuiteMemoireIT}). La lecture se fait pendant la requête, que {@link FiltreEnVol}
 * compte sur la génération : le jar ne peut pas être fermé sous ce rendu.
 * <p>
 * Le résolveur du cœur doit être enveloppé par {@link #sansEspacePlugins(ITemplateResolver)} : sinon un nom
 * {@code plugins/...} refusé ici retomberait sur lui.
 */
@Component
@ConditionalOnProperty(name = "app.plateforme.actif", havingValue = "true")
public class ResolveurGabaritsPlugins implements ITemplateResolver {

	/** Ordre du résolveur : avant celui du cœur (qui reçoit un ordre supérieur). */
	static final int ORDRE = 1;

	private static final int LONGUEUR_MAX = 200;

	private static final Pattern FORME = Pattern
			.compile("plugins/([a-z][a-z0-9_]*)/([a-z0-9_-]+(?:/[a-z0-9_-]+)*)");

	private final Function<String, PluginGeneration> actifs;

	@Autowired
	public ResolveurGabaritsPlugins(PluginManager manager) {
		this(nom -> manager.actifs().get(nom));
	}

	/** Pour les tests : source des générations actives. */
	ResolveurGabaritsPlugins(Function<String, PluginGeneration> actifs) {
		this.actifs = actifs;
	}

	@Override
	public String getName() {
		return "gabarits-plugins";
	}

	@Override
	public Integer getOrder() {
		return ORDRE;
	}

	@Override
	public TemplateResolution resolveTemplate(IEngineConfiguration configuration, String ownerTemplate,
			String template, Map<String, Object> templateResolutionAttributes) {
		if (template == null || template.length() > LONGUEUR_MAX) {
			return null;
		}
		Matcher m = FORME.matcher(template);
		if (!m.matches() || !ManifestePlugin.NOM_VALIDE.matcher(m.group(1)).matches()) {
			return null;
		}
		String nom = m.group(1);
		PluginGeneration gen = generationAnnoncee(nom);
		if (gen == null) {
			gen = actifs.apply(nom);
		}
		PluginClassLoader chargeur = gen == null ? null : gen.loader;
		if (chargeur == null || !nom.equals(gen.nom)) {
			return null;
		}
		String chemin = "templates/plugins/" + nom + "/" + m.group(2) + ".html";
		// findResource : uniquement le jar du plugin (getResource retomberait sur le chargeur du cœur).
		URL url = chargeur.findResource(chemin);
		if (url == null) {
			return null;
		}
		return new TemplateResolution(new Ressource(url, gen.etiquette() + ":" + chemin, m.group(2)), true,
				TemplateMode.HTML, false, NonCacheableCacheEntryValidity.INSTANCE);
	}

	/**
	 * Génération annoncée (et comptée en vol) par {@link FiltreEnVol} pour la requête en cours, si elle est du plugin
	 * {@code nom} : elle porte encore son jar même si le plugin vient d'être retiré des actifs (désinstallation qui
	 * attend le drainage). Sans requête, ou pour un AUTRE plugin que celui de la requête, {@code null} : on lit alors
	 * les actifs, et cette lecture n'est pas comptée en vol (un plugin ne doit rendre que ses propres gabarits).
	 */
	private static PluginGeneration generationAnnoncee(String nom) {
		if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributs
				&& attributs.getRequest().getAttribute(RoutagePlugins.ATTRIBUT_GENERATION) instanceof PluginGeneration annoncee
				&& annoncee.nom.equals(nom)) {
			return annoncee;
		}
		return null;
	}

	/**
	 * Enveloppe le résolveur du cœur : tout nom de l'espace {@code plugins/} lui est refusé (il appartient au seul
	 * {@link ResolveurGabaritsPlugins}). Insensible à la casse et aux séparateurs de tête, car un système de fichiers
	 * peut l'être.
	 */
	public static ITemplateResolver sansEspacePlugins(ITemplateResolver noyau) {
		return new ITemplateResolver() {
			@Override
			public String getName() {
				return noyau.getName();
			}

			@Override
			public Integer getOrder() {
				return noyau.getOrder();
			}

			@Override
			public TemplateResolution resolveTemplate(IEngineConfiguration configuration, String ownerTemplate,
					String template, Map<String, Object> attributs) {
				if (template == null || estEspacePlugins(template)) {
					return null;
				}
				return noyau.resolveTemplate(configuration, ownerTemplate, template, attributs);
			}
		};
	}

	private static boolean estEspacePlugins(String nom) {
		String n = nom.strip().replace('\\', '/').replaceAll("/+", "/");
		n = StringUtils.cleanPath(n);
		// Après nettoyage, une remontée restante ne peut viser qu'un chemin hors du cœur : refusée.
		if (n.contains("..")) {
			return true;
		}
		int debut = 0;
		while (debut < n.length() && (n.charAt(debut) == '/' || n.startsWith("./", debut))) {
			debut += n.charAt(debut) == '/' ? 1 : 2;
		}
		String suite = n.substring(debut).toLowerCase(Locale.ROOT);
		return suite.startsWith("plugins") && (suite.length() == 7 || suite.charAt(7) == '/');
	}

	/** Gabarit lu dans le jar du plugin (URL sans cache : chaque lecture ouvre et ferme son propre fichier). */
	private static final class Ressource implements ITemplateResource {

		private final URL url;

		private final String description;

		private final String nomDeBase;

		Ressource(URL url, String description, String chemin) {
			this.url = url;
			this.description = description;
			this.nomDeBase = chemin.substring(chemin.lastIndexOf('/') + 1);
		}

		@Override
		public String getDescription() {
			return description;
		}

		@Override
		public String getBaseName() {
			return nomDeBase;
		}

		@Override
		public boolean exists() {
			return url != null;
		}

		@Override
		public Reader reader() throws IOException {
			if (url == null) {
				throw new IOException("Gabarit de plugin inexistant : " + description);
			}
			return new InputStreamReader(url.openStream(), StandardCharsets.UTF_8);
		}

		/** Jamais de ressource voisine : aucun accès relatif depuis un gabarit de plugin. */
		@Override
		public ITemplateResource relative(String emplacementRelatif) {
			return new Ressource(null, description + " (relatif refusé)", nomDeBase);
		}

	}

}

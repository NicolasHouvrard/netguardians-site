package com.logicielpourtous.site.features.plateforme;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.CachedIntrospectionResults;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationContext;
import org.springframework.core.MethodParameter;
import org.springframework.core.ResolvableType;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.http.converter.AbstractJacksonHttpMessageConverter;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.stereotype.Component;
import org.springframework.util.ConcurrentLruCache;
import org.springframework.util.ReflectionUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.method.annotation.ExceptionHandlerMethodResolver;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.HandlerMethodArgumentResolverComposite;
import org.springframework.web.servlet.HandlerAdapter;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.handler.HandlerExceptionResolverComposite;
import org.springframework.web.servlet.mvc.method.annotation.ExceptionHandlerExceptionResolver;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;
import tools.jackson.databind.ObjectMapper;

/**
 * Purge des caches du cœur qui retiennent les classes d'une génération de plugin fermée (port du {@code LeakCleaner}
 * du prototype). Sans elle, chaque mise à jour garde toute l'ancienne génération en mémoire (chargeur, classes,
 * EntityManagerFactory).
 * <p>
 * La plupart de ces caches sont des <b>internes</b> de Spring Data, Hibernate, Jackson et Spring MVC, lus par
 * réflexion : chaque purge est isolée dans son propre {@code try/catch} et un champ ou une classe introuvable produit
 * un avertissement (« structure interne changée ») sans jamais lever. Après une montée de version de ces
 * bibliothèques, ces avertissements et {@code FuiteMemoireIT} disent quoi reprendre. Un parcours générique des objets
 * MVC et Jackson du cœur sert de filet pour les caches qu'on ne connaît pas encore.
 */
@Component
@ConditionalOnProperty(name = "app.plateforme.actif", havingValue = "true")
public class NettoyeurFuites implements Consumer<ClassLoader> {

	private static final Logger log = LoggerFactory.getLogger(NettoyeurFuites.class);

	private static final int PROFONDEUR_MAX = 60;

	private final ApplicationContext coeur;

	private final PlateformeProperties proprietes;

	public NettoyeurFuites(ApplicationContext coeur, PlateformeProperties proprietes) {
		this.coeur = coeur;
		this.proprietes = proprietes;
	}

	@Override
	public void accept(ClassLoader chargeur) {
		nettoyer(chargeur);
	}

	/** Purge tout ce qui retient {@code chargeur}. Ne lève jamais. */
	public void nettoyer(ClassLoader chargeur) {
		if (!proprietes.isNettoyageActif()) {
			log.warn("Nettoyage des fuites désactivé (app.plateforme.nettoyage-actif=false) : {} restera en mémoire",
					chargeur);
			return;
		}
		int[] purges = { 0 };

		// Spring (API publiques)
		etape("CachedIntrospectionResults", () -> CachedIntrospectionResults.clearClassLoader(chargeur));
		etape("ResolvableType", ResolvableType::clearCache);
		etape("ReflectionUtils", ReflectionUtils::clearCache);
		etape("AnnotationUtils", AnnotationUtils::clearCache);

		// Spring Data : caches statiques de types
		purges[0] += viderChampStatique("org.springframework.data.core.ClassTypeInformation", "cache", chargeur);
		purges[0] += viderChampStatique("org.springframework.data.core.ClassTypeInformation", "resolvableTypeCache",
				chargeur);
		purges[0] += viderChampStatique("org.springframework.data.core.TypeDiscoverer", "CACHE", chargeur);

		// Hibernate : registre global des SessionFactory
		etape("SessionFactoryRegistry", () -> purges[0] += purgerRegistreHibernate());

		// Spring MVC
		for (RequestMappingHandlerAdapter adaptateur : beans(RequestMappingHandlerAdapter.class)) {
			purges[0] += retirerEntreesDuChamp(adaptateur, "initBinderCache", chargeur);
			purges[0] += retirerEntreesDuChamp(adaptateur, "modelAttributeCache", chargeur);
			purges[0] += retirerEntreesDuChamp(adaptateur, "sessionAttributesHandlerCache", chargeur);
			purges[0] += purgerComposite(champ(adaptateur, "argumentResolvers"), chargeur);
			purges[0] += purgerComposite(champ(adaptateur, "initBinderArgumentResolvers"), chargeur);
			etape("Jackson (convertisseurs de l'adaptateur)", () -> viderJackson(adaptateur.getMessageConverters()));
		}
		for (ExceptionHandlerExceptionResolver resolveur : resolveursDExceptions()) {
			purges[0] += retirerEntreesDuChamp(resolveur, "exceptionHandlerCache", chargeur);
			etape("ExceptionHandlerExceptionResolver.exceptionHandlerAdviceCache", () -> {
				// Les résolveurs des @ControllerAdvice du cœur mémorisent type d'exception -> méthode : une exception
				// propre au plugin y reste. Le cache se réchauffe tout seul.
				for (ExceptionHandlerMethodResolver r : resolveur.getExceptionHandlerAdviceCache().values()) {
					purges[0] += retirerEntreesDuChamp(r, "lookupCache", chargeur);
				}
			});
			purges[0] += purgerComposite(resolveur.getArgumentResolvers(), chargeur);
			etape("Jackson (convertisseurs du résolveur d'exceptions)",
					() -> viderJackson(resolveur.getMessageConverters()));
		}

		// Jackson : mappers du cœur (serializer/deserializer caches, _rootDeserializers, TypeFactory)
		etape("Jackson (ObjectMapper du cœur)", () -> beans(ObjectMapper.class).forEach(ObjectMapper::clearCaches));

		// Jackson 2 (com.fasterxml, encore utilisé par le cœur et par des bibliothèques) : TypeFactory statique
		// partagée (trouvée par analyse de heap : _typeCache garde les classes d'entités du plugin) et mappers du cœur.
		etape("Jackson 2 (TypeFactory par défaut)", this::purgerJackson2Statique);
		etape("Jackson 2 (ObjectMapper du cœur)", this::purgerJackson2Beans);

		// Flyway : LogFactory garde en statique la dernière configuration, donc son chargeur (celui du plugin migré).
		etape("Flyway LogFactory.configuration", () -> purgerFlyway(chargeur));

		// Filet : parcours générique des objets MVC / Jackson du cœur (caches pas encore connus)
		etape("parcours générique", () -> purges[0] += parcoursGenerique(chargeur));

		log.info("Nettoyage des fuites de {} : {} entrée(s) de cache purgée(s)", chargeur, purges[0]);
	}

	@FunctionalInterface
	private interface Purge {

		void executer() throws Exception;

	}

	/** Exécute une purge ; toute erreur est journalisée en avertissement, jamais propagée. */
	private void etape(String nom, Purge purge) {
		try {
			purge.executer();
		}
		catch (Throwable e) {
			if (e instanceof VirtualMachineError fatale) {
				throw fatale;
			}
			log.warn("Nettoyage des fuites : purge '{}' en erreur (structure interne changée ?) : {}", nom,
					e.toString());
		}
	}

	/**
	 * Vide le champ statique {@code champ} de {@code classe} : entrées d'une {@code Map} qui désignent
	 * {@code chargeur}, ou tout le cache s'il ne se parcourt pas ({@code ConcurrentLruCache}). Classe ou champ
	 * introuvable : avertissement.
	 */
	int viderChampStatique(String classe, String champ, ClassLoader chargeur) {
		try {
			Class<?> c = Class.forName(classe, false, NettoyeurFuites.class.getClassLoader());
			Field f = c.getDeclaredField(champ);
			if (!Modifier.isStatic(f.getModifiers())) {
				log.warn("Nettoyage des fuites : {}.{} n'est plus statique (structure interne changée)", classe, champ);
				return 0;
			}
			f.setAccessible(true);
			return vider(f.get(null), chargeur, classe + "." + champ);
		}
		catch (ClassNotFoundException | NoSuchFieldException e) {
			log.warn("Nettoyage des fuites : {}.{} introuvable (structure interne changée) : {}", classe, champ,
					e.toString());
		}
		catch (Throwable e) {
			if (e instanceof VirtualMachineError fatale) {
				throw fatale;
			}
			log.warn("Nettoyage des fuites : {}.{} non purgé : {}", classe, champ, e.toString());
		}
		return 0;
	}

	/** Comme {@link #viderChampStatique}, pour un champ d'instance de {@code cible} (cherché dans ses superclasses). */
	int retirerEntreesDuChamp(Object cible, String champ, ClassLoader chargeur) {
		String nom = cible.getClass().getName() + "." + champ;
		try {
			Field f = trouverChamp(cible.getClass(), champ);
			if (f == null) {
				log.warn("Nettoyage des fuites : {} introuvable (structure interne changée)", nom);
				return 0;
			}
			f.setAccessible(true);
			return vider(f.get(cible), chargeur, nom);
		}
		catch (Throwable e) {
			if (e instanceof VirtualMachineError fatale) {
				throw fatale;
			}
			log.warn("Nettoyage des fuites : {} non purgé : {}", nom, e.toString());
			return 0;
		}
	}

	private int vider(Object cache, ClassLoader chargeur, String nom) {
		if (cache instanceof Map<?, ?> m) {
			int avant = m.size();
			m.entrySet().removeIf(e -> designe(e.getKey(), chargeur) || designe(e.getValue(), chargeur));
			return avant - m.size();
		}
		if (cache instanceof ConcurrentLruCache<?, ?> lru) {
			int taille = lru.size();
			lru.clear();
			return taille;
		}
		if (cache != null) {
			log.warn("Nettoyage des fuites : {} est un {} inattendu (structure interne changée)", nom,
					cache.getClass().getName());
		}
		return 0;
	}

	/** Cache d'un composite de résolveurs d'arguments et caches propres de chacun de ses résolveurs. */
	private int purgerComposite(Object composite, ClassLoader chargeur) {
		if (!(composite instanceof HandlerMethodArgumentResolverComposite c)) {
			return 0;
		}
		int n = retirerEntreesDuChamp(c, "argumentResolverCache", chargeur);
		for (HandlerMethodArgumentResolver r : c.getResolvers()) {
			// @RequestParam, @PathVariable, @RequestHeader... : NamedValueInfo par MethodParameter
			if (r instanceof org.springframework.web.method.annotation.AbstractNamedValueMethodArgumentResolver) {
				n += retirerEntreesDuChamp(r, "namedValueInfoCache", chargeur);
			}
		}
		return n;
	}

	private Object champ(Object cible, String nom) {
		try {
			Field f = trouverChamp(cible.getClass(), nom);
			if (f == null) {
				log.warn("Nettoyage des fuites : {}.{} introuvable (structure interne changée)",
						cible.getClass().getName(), nom);
				return null;
			}
			f.setAccessible(true);
			return f.get(cible);
		}
		catch (Exception e) {
			log.warn("Nettoyage des fuites : {}.{} illisible : {}", cible.getClass().getName(), nom, e.toString());
			return null;
		}
	}

	private static Field trouverChamp(Class<?> type, String nom) {
		for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
			try {
				return c.getDeclaredField(nom);
			}
			catch (NoSuchFieldException ignore) {
				// superclasse suivante
			}
		}
		return null;
	}

	/**
	 * Une génération fermée doit avoir retiré sa SessionFactory du registre ; on retire en plus toute SessionFactory
	 * fermée qui y serait restée, et les correspondances nom -> uuid orphelines.
	 */
	private int purgerRegistreHibernate() throws ReflectiveOperationException {
		Class<?> c = Class.forName("org.hibernate.internal.SessionFactoryRegistry", false,
				NettoyeurFuites.class.getClassLoader());
		Object registre = c.getField("INSTANCE").get(null);
		Object usines = champ(registre, "sessionFactoryMap");
		Object noms = champ(registre, "nameUuidXref");
		int n = 0;
		if (usines instanceof Map<?, ?> m) {
			int avant = m.size();
			m.values().removeIf(sf -> sf instanceof SessionFactoryImplementor s && s.isClosed());
			n += avant - m.size();
			if (noms instanceof Map<?, ?> x) {
				x.values().removeIf(uuid -> !m.containsKey(uuid));
			}
		}
		return n;
	}

	private static final String JACKSON2 = "com.fasterxml.jackson.databind.";

	/** Classe d'une bibliothèque facultative ; {@code null} si elle n'est pas sur le classpath (rien à purger). */
	private static Class<?> classeFacultative(String nom) {
		try {
			return Class.forName(nom, false, NettoyeurFuites.class.getClassLoader());
		}
		catch (ClassNotFoundException absente) {
			return null;
		}
	}

	/** {@code TypeFactory.defaultInstance()} de Jackson 2 : instance statique partagée par tout le monde. */
	private void purgerJackson2Statique() throws ReflectiveOperationException {
		Class<?> typeFactory = classeFacultative(JACKSON2 + "type.TypeFactory");
		if (typeFactory == null) {
			return;
		}
		Object instance = typeFactory.getMethod("defaultInstance").invoke(null);
		typeFactory.getMethod("clearCache").invoke(instance);
	}

	/** Mappers Jackson 2 du cœur : TypeFactory, sérialiseurs, désérialiseurs et désérialiseurs racine. */
	private void purgerJackson2Beans() throws ReflectiveOperationException {
		Class<?> type = classeFacultative(JACKSON2 + "ObjectMapper");
		if (type == null) {
			return;
		}
		for (Object mapper : beans(type)) {
			viderMapperJackson2(mapper);
		}
	}

	/** Un {@code ObjectMapper} Jackson 2 : TypeFactory, sérialiseurs, désérialiseurs et désérialiseurs racine. */
	private void viderMapperJackson2(Object mapper) throws ReflectiveOperationException {
		Class<?> type = classeFacultative(JACKSON2 + "ObjectMapper");
		if (type == null || !type.isInstance(mapper)) {
			return;
		}
		Object typeFactory = type.getMethod("getTypeFactory").invoke(mapper);
		typeFactory.getClass().getMethod("clearCache").invoke(typeFactory);
		Object serialisation = type.getMethod("getSerializerProvider").invoke(mapper);
		serialisation.getClass().getMethod("flushCachedSerializers").invoke(serialisation);
		Object deserialisation = type.getMethod("getDeserializationContext").invoke(mapper);
		deserialisation.getClass().getMethod("flushCachedDeserializers").invoke(deserialisation);
		if (champ(mapper, "_rootDeserializers") instanceof Map<?, ?> racines) {
			racines.clear();
		}
	}

	private static boolean estMapperJackson2(Object o) {
		Class<?> type = classeFacultative(JACKSON2 + "ObjectMapper");
		return type != null && type.isInstance(o);
	}

	/** Remet à zéro la configuration statique de Flyway si elle porte le chargeur retiré (API publique). */
	private void purgerFlyway(ClassLoader chargeur) throws ReflectiveOperationException {
		Class<?> logFactory = classeFacultative("org.flywaydb.core.api.logging.LogFactory");
		if (logFactory == null) {
			return;
		}
		Field f = logFactory.getDeclaredField("configuration");
		f.setAccessible(true);
		Object configuration = f.get(null);
		if (configuration != null
				&& configuration.getClass().getMethod("getClassLoader").invoke(configuration) == chargeur) {
			logFactory.getMethod("setConfiguration", Class.forName("org.flywaydb.core.api.configuration.Configuration",
					false, logFactory.getClassLoader())).invoke(null, (Object) null);
		}
	}

	/**
	 * Mappers des convertisseurs HTTP : Jackson 3 et aussi Jackson 2 (convertisseurs YAML / CBOR / Smile encore en
	 * Jackson 2, trouvés par analyse de heap : leur {@code canWrite} remplit le cache de sérialiseurs avec les entités
	 * du plugin, même quand ils ne servent jamais la réponse).
	 */
	private void viderJackson(List<HttpMessageConverter<?>> convertisseurs) throws ReflectiveOperationException {
		Class<?> jackson2 = classeFacultative("org.springframework.http.converter.json.AbstractJackson2HttpMessageConverter");
		for (HttpMessageConverter<?> c : convertisseurs) {
			if (c instanceof AbstractJacksonHttpMessageConverter<?> j) {
				j.getMapper().clearCaches();
			}
			else if (jackson2 != null && jackson2.isInstance(c)) {
				viderMapperJackson2(jackson2.getMethod("getObjectMapper").invoke(c));
			}
		}
	}

	private <T> Collection<T> beans(Class<T> type) {
		try {
			return coeur.getBeansOfType(type).values();
		}
		catch (RuntimeException e) {
			log.warn("Nettoyage des fuites : beans {} illisibles : {}", type.getSimpleName(), e.toString());
			return List.of();
		}
	}

	private List<ExceptionHandlerExceptionResolver> resolveursDExceptions() {
		List<ExceptionHandlerExceptionResolver> resultat = new ArrayList<>();
		for (HandlerExceptionResolver r : beans(HandlerExceptionResolver.class)) {
			if (r instanceof ExceptionHandlerExceptionResolver e) {
				resultat.add(e);
			}
			else if (r instanceof HandlerExceptionResolverComposite composite) {
				for (HandlerExceptionResolver interne : composite.getExceptionResolvers()) {
					if (interne instanceof ExceptionHandlerExceptionResolver e) {
						resultat.add(e);
					}
				}
			}
		}
		return resultat;
	}

	// ---- Filet : parcours générique (prototype) ----

	private int parcoursGenerique(ClassLoader chargeur) {
		Set<Object> vus = Collections.newSetFromMap(new IdentityHashMap<>());
		List<Object> racines = new ArrayList<>();
		racines.addAll(beans(HandlerAdapter.class));
		racines.addAll(beans(HandlerExceptionResolver.class));
		// RouterFunctionMapping garde sa propre liste de convertisseurs (Jackson 2 YAML... : analyse de heap)
		racines.addAll(beans(HandlerMapping.class));
		racines.addAll(beans(ObjectMapper.class));
		int n = 0;
		for (Object r : racines) {
			n += parcourir(r, chargeur, 0, vus);
		}
		return n;
	}

	/** Parcourt les champs d'instance des objets Spring / Jackson / Hibernate et purge les Map et caches trouvés. */
	private int parcourir(Object cible, ClassLoader chargeur, int profondeur, Set<Object> vus) {
		if (cible == null || profondeur > PROFONDEUR_MAX || !vus.add(cible)) {
			return 0;
		}
		String type = cible.getClass().getName();
		if (!(type.startsWith("org.springframework.") || type.startsWith("tools.jackson.")
				|| type.startsWith("org.hibernate."))) {
			return 0;
		}
		int n = 0;
		for (Class<?> c = cible.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
			for (Field f : c.getDeclaredFields()) {
				if (Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive()) {
					continue;
				}
				try {
					f.setAccessible(true);
					n += parcourirValeur(f.get(cible), chargeur, profondeur, vus);
				}
				catch (RuntimeException | IllegalAccessException ignore) {
					// champ inaccessible (module fermé...) : le filet est au mieux, les purges nommées font foi
				}
			}
		}
		return n;
	}

	private int parcourirValeur(Object v, ClassLoader chargeur, int profondeur, Set<Object> vus) {
		if (v == null) {
			return 0;
		}
		if (v instanceof Map<?, ?> m) {
			if (!vus.add(m)) {
				return 0;
			}
			int avant = m.size();
			try {
				m.entrySet().removeIf(e -> designe(e.getKey(), chargeur) || designe(e.getValue(), chargeur));
			}
			catch (RuntimeException ignore) {
				// Map non modifiable
			}
			int n = avant - m.size();
			for (Object valeur : new ArrayList<>(m.values())) {
				n += parcourir(valeur, chargeur, profondeur + 1, vus);
			}
			return n;
		}
		if (v instanceof Collection<?> col) {
			if (!vus.add(col)) {
				return 0;
			}
			int n = 0;
			try {
				for (Object element : new ArrayList<>(col)) {
					n += parcourir(element, chargeur, profondeur + 1, vus);
				}
			}
			catch (RuntimeException ignore) {
				// collection modifiée pendant la copie
			}
			return n;
		}
		if (v instanceof ConcurrentLruCache<?, ?> lru) {
			int taille = lru.size();
			lru.clear();
			return taille;
		}
		if (v instanceof ObjectMapper mapper) {
			mapper.clearCaches();
		}
		else if (estMapperJackson2(v) && vus.add(v)) {
			try {
				viderMapperJackson2(v);
			}
			catch (ReflectiveOperationException | RuntimeException ignore) {
				// filet au mieux : les purges nommées font foi
			}
			return 0;
		}
		return parcourir(v, chargeur, profondeur + 1, vus);
	}

	/** {@code o} désigne-t-il une classe chargée par {@code chargeur} (ou une SessionFactory fermée) ? */
	static boolean designe(Object o, ClassLoader chargeur) {
		if (o == null) {
			return false;
		}
		if (o instanceof Class<?> k) {
			return k.getClassLoader() == chargeur;
		}
		if (o.getClass().getClassLoader() == chargeur) {
			return true;
		}
		if (o instanceof HandlerMethod hm) {
			return hm.getBeanType().getClassLoader() == chargeur;
		}
		if (o instanceof MethodParameter mp) {
			return mp.getDeclaringClass().getClassLoader() == chargeur
					|| mp.getParameterType().getClassLoader() == chargeur;
		}
		if (o instanceof ResolvableType rt) {
			Class<?> r = rt.resolve();
			return r != null && r.getClassLoader() == chargeur;
		}
		if (o instanceof Method m) {
			return m.getDeclaringClass().getClassLoader() == chargeur;
		}
		if (o instanceof SessionFactoryImplementor sf) {
			return sf.isClosed();
		}
		if (o instanceof tools.jackson.databind.JavaType jt) {
			return jt.getRawClass().getClassLoader() == chargeur;
		}
		return false;
	}

}

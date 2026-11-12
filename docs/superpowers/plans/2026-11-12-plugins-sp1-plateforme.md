# Plateforme de plugins (sous-projet 1) : plan d'implémentation

> **Pour les agents d'exécution :** SOUS-SKILL REQUISE : utiliser superpowers:subagent-driven-development (recommandé) ou superpowers:executing-plans. Les étapes utilisent des cases `- [ ]`.

**Objectif :** ajouter au cœur un chargeur de plugins à chaud (contextes Spring enfants, schéma et Flyway par plugin, mode maintenance, retour arrière, nettoyage des fuites, test de fuite automatique, vérification « à chaud ou mise à jour du cœur »), **désactivé par défaut**, sans extraire aucun code métier.

**Architecture :** nouveau module Modulith `features/plateforme`. Les types partagés avec les plugins vivent dans `src/sdk/java` (package `com.logicielpourtous.site.sdk`), compilés dans le cœur (build-helper) ET dans un petit projet Maven autonome `plugins-sdk/` que les plugins utilisent en scope `provided`. Chaque plugin = un jar avec `plugin.properties`, chargé par un `PluginClassLoader` enfant-d'abord, dans un `AnnotationConfigApplicationContext` dont le parent est le cœur, avec sa propre `EntityManagerFactory` (PAS multi-tenant), son Flyway (`classpath:db/plugin/<nom>`, table `flyway_schema_history_<nom>`) et son schéma `plugin_<nom>` pour ses nouvelles tables.

**Stack :** Spring Boot 4.0.2 (Spring 7.0.3, Hibernate 7.2.1, Flyway 11.14.1, Jackson 3), Java 25, PostgreSQL local, JUnit 5, AssertJ.

**Spec :** `docs/superpowers/specs/2026-11-12-plugins-design.md` (sections 4.3, 4.4, 4.5, 5 et 8). Code de référence jetable, à lire et à PORTER (pas à copier tel quel) : `C:\Users\nicolas\Desktop\springboot\plugin-spike\core\src\main\java\spike\core\` (`PluginManager`, `PluginClassLoader`, `PluginGeneration`, `ContractRegistry`, `PluginRoutingHandlerMapping`, `LeakCleaner`) et `...\plugin-spike\RESULTATS.md` (section 3, pièges).

## Contraintes globales
- Le cœur et ses tests existants ne changent pas de comportement : tout est derrière `app.plateforme.actif=true` (défaut `false`). Les beans de la plateforme portent `@ConditionalOnProperty(name = "app.plateforme.actif", havingValue = "true")`.
- Packages : `com.logicielpourtous.site.features.plateforme` (cœur), `com.logicielpourtous.site.sdk` (partagé). Noms en français, comme le reste du code.
- Hypothèse à confirmer avant exécution : en production, toutes les données sont dans le schéma `public` (le mécanisme de schéma par tenant existe dans `MultiTenantJpaConfiguration` mais le tenant par défaut est `public`). Les EMF de plugin ne sont **pas** multi-tenant et utilisent le `search_path` par défaut (`public`) ; les entités qui lisent une table existante déclarent `@Table(schema = "public")`. Vérification : `\dn` sur la base de production doit ne montrer que `public` (plus `plugin_*` plus tard).
- Plugins : Flyway par plugin (jamais dans l'historique du cœur) ; EMF avec `hibernate.hbm2ddl.auto=validate`, `hibernate.default_schema=plugin_<nom>`, nom d'unité de persistance unique par génération ; `@EnableTransactionManagement` et `@EnableMethodSecurity` déclarés dans le contexte enfant.
- Packages parent-first dans `PluginClassLoader` : `java.`, `javax.`, `jakarta.`, `org.springframework.`, `org.hibernate.`, `tools.jackson.`, `com.fasterxml.`, `org.slf4j.`, `org.flywaydb.`, `org.postgresql.`, `com.logicielpourtous.site.sdk.`.
- Drainage obligatoire avant fermeture d'un contexte, délai maximal 30 s (propriété `app.plateforme.delai-drainage-ms`, défaut 30000).
- Tests : un test à la fois (`mvn -B -q -Dtest=<Classe> test`), à cause du bruit « too many clients » des exécutions complètes. Les 8 échecs connus des tests de synchronisation (`comments.author_id`) sont hors sujet.
- Commits en français comme le reste de l'historique, terminés par `Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>`.

## Revue : points que les tests des tâches n'exercent pas (chacun a son test dans la tâche indiquée)
1. Jar sans `plugin.properties`, avec manifeste incomplet, ou fichier non-jar dans le répertoire : refus propre, jamais une exception qui arrête le démarrage du cœur (T2, T6).
2. Requête plus longue que le délai de drainage : fermeture forcée journalisée, jamais un blocage infini (T6).
3. Deux installations simultanées du même plugin : sérialisées, une seule génération active (T6).
4. Requête sur `/api/plugins/inconnu/...` ou sur un plugin retiré : 404 propre, pas 500 (T5).
5. Migrations SQL en minuscules, multi-lignes, avec commentaires ou plusieurs instructions : détectées comme destructives (T2).
6. Plateforme désactivée : aucun bean, aucune route, `SiteApplicationTests` inchangé (T8).

## Structure des fichiers
- Créer `plugins-sdk/pom.xml` ; `src/sdk/java/com/logicielpourtous/site/sdk/{ModeMiseAJour,ManifestePlugin,ContratIndisponibleException}.java`
- Modifier `pom.xml` (build-helper `add-source` sur `src/sdk/java`)
- Créer sous `src/main/java/com/logicielpourtous/site/features/plateforme/` : `package-info.java`, `LecteurManifeste`, `AnalyseurMigrations`, `VerificateurMiseAJour`, `PluginClassLoader`, `PluginGeneration`, `ContratRegistre`, `DemarreurContexte`, `RoutagePlugins`, `FiltreEnVol`, `PluginManager`, `NettoyeurFuites`, `PluginAdminController`, `PlateformeProperties`
- Modifier `config/SecurityConfig.java` (chaîne JWT Angular) ; `src/test/.../ModularityTests.java`
- Tests sous `src/test/java/com/logicielpourtous/site/features/plateforme/` ; aide de test `JarDePluginDeTest`.

---

### Tâche 1 : SDK partagé et câblage Maven

**Fichiers :**
- Créer : `plugins-sdk/pom.xml`, `src/sdk/java/com/logicielpourtous/site/sdk/ModeMiseAJour.java`, `ManifestePlugin.java`, `ContratIndisponibleException.java`
- Modifier : `pom.xml` (plugin `org.codehaus.mojo:build-helper-maven-plugin`, goal `add-source`, source `src/sdk/java`)
- Test : `src/test/java/com/logicielpourtous/site/features/plateforme/ManifestePluginTest.java`

**Interfaces :**
- Produit : `enum ModeMiseAJour { CHAUD, COEUR }` ; `record ManifestePlugin(String nom, String version, String coeurMin, String coeurMax, ModeMiseAJour miseAJour, String paquet, String configuration, List<String> dependances, List<String> tablesReferencees)` avec `static ManifestePlugin depuis(java.util.Properties p)` (lève `IllegalArgumentException` avec le nom de la clé manquante) ; `class ContratIndisponibleException extends RuntimeException` (constructeur `(String contrat)`).
- Clés de `plugin.properties` : `nom`, `version`, `coeur-min` (obligatoires), `coeur-max` (facultative), `mise-a-jour` (`chaud`|`coeur`, obligatoire), `paquet`, `configuration` (classe `@Configuration`, obligatoires), `dependances` et `tables-referencees` (listes séparées par des virgules, facultatives, vides par défaut).

- [ ] **Étape 1 : test qui échoue** `ManifestePluginTest` : `depuis_lit_toutes_les_cles` (propriétés complètes → champs et listes attendus, `miseAJour == CHAUD`) ; `depuis_refuse_une_cle_obligatoire_absente` (sans `version` → `IllegalArgumentException` dont le message contient `version`) ; `depuis_refuse_mise_a_jour_inconnue` (`mise-a-jour=x` → `IllegalArgumentException`).
- [ ] **Étape 2 :** lancer `mvn -B -q -Dtest=ManifestePluginTest test`. Attendu : échec de compilation (types absents).
- [ ] **Étape 3 :** créer les trois types dans `src/sdk/java` ; ajouter build-helper au `pom.xml`. `plugins-sdk/pom.xml` : projet autonome `com.logicielpourtous:site-plugins-sdk:0.0.1`, `<sourceDirectory>../src/sdk/java</sourceDirectory>`, Java 25, aucune dépendance.
- [ ] **Étape 4 :** relancer le test (attendu : 3 réussis) puis `mvn -B -q -f plugins-sdk/pom.xml install` (attendu : BUILD SUCCESS, jar créé).
- [ ] **Étape 5 :** vérifier que l'image n'est pas affectée : `mvn -B -q -DskipTests package` puis `ls target/site-*.jar` ne liste qu'un jar. Commit.

### Tâche 2 : lecture du manifeste, analyse des migrations, vérification

**Fichiers :** créer `LecteurManifeste`, `AnalyseurMigrations`, `VerificateurMiseAJour` ; tests `LecteurManifesteTest`, `AnalyseurMigrationsTest`, `VerificateurMiseAJourTest`.

**Interfaces :**
- Consomme : `ManifestePlugin`, `ModeMiseAJour` (T1).
- Produit : `LecteurManifeste.lire(Path jar) : ManifestePlugin` (lit `plugin.properties` à la racine du jar ; lève `IllegalArgumentException` si absent ou invalide, jamais d'autre exception) ; `LecteurManifeste.migrations(Path jar, String nomPlugin) : Map<String,String>` (nom de fichier → SQL, pour `db/plugin/<nom>/V*.sql`) ; `record Constat(String regle, String extrait)` ; `AnalyseurMigrations.analyser(String sql, Set<String> tablesReferencees) : List<Constat>` (statique) ; `record Verdict(boolean accepte, List<String> raisons)` ; `VerificateurMiseAJour.verifier(ManifestePlugin m, Map<String,String> migrations, Set<String> tablesReferencees, String versionCoeur) : Verdict` (statique).

Règles de `analyser` (sensibles = constat) : `ALTER TABLE ... RENAME` (table ou colonne), `ALTER TABLE ... DROP COLUMN`, `DROP TABLE`, `ALTER COLUMN ... TYPE`, `ADD COLUMN ... NOT NULL` sans `DEFAULT` sur une table qui n'est pas créée dans le même fichier, `ALTER COLUMN ... SET NOT NULL`, et tout `DROP`/`RENAME`/`ALTER ... TYPE` visant une table de `tablesReferencees` (nom simple ou `schema.table`, insensible à la casse). Ignorer les commentaires `--` et `/* */` ; plusieurs instructions par fichier ; insensible à la casse et aux retours à la ligne. Autorisés sans constat : `CREATE TABLE`, `CREATE INDEX`, `ADD COLUMN` nullable ou avec `DEFAULT`, `ADD CONSTRAINT`, `INSERT`.
Règles de `verifier` : refus si `versionCoeur` < `coeurMin` ou > `coeurMax` (comparaison numérique par segment) ; refus si `miseAJour == CHAUD` et au moins un constat (raison = règle + extrait) ; accepté sinon (`COEUR` accepte tout).

- [ ] **Étape 1 : tests qui échouent.** `AnalyseurMigrationsTest` (un test par ligne de règle, assertions sur `regle`) : `rename_colonne_en_minuscules_multiligne`, `drop_column`, `drop_table`, `type_change`, `add_column_not_null_sans_default_sur_table_existante` (constat) vs `..._table_creee_dans_le_meme_fichier` et `..._avec_default` (aucun constat), `commentaire_ne_declenche_pas` (`-- DROP TABLE x` seul → liste vide), `deux_instructions_dans_un_fichier_detecte_la_seconde`, `table_referencee_drop_detecte_avec_schema` (`DROP TABLE public.Customers` avec `Set.of("customers")`), `create_table_et_index_ok`. `VerificateurMiseAJourTest` : `chaud_avec_migration_destructive_refuse` (raisons non vides), `coeur_avec_migration_destructive_accepte`, `version_coeur_trop_ancienne_refusee` (`coeurMin=1.6.0`, coeur `1.5.9`), `coeur_max_depasse_refuse`. `LecteurManifesteTest` utilise `JarDePluginDeTest` (créé ici : `JarDePluginDeTest.creer(Path dossier, String nom, String version, Map<String,String> proprietes, Map<String,String> migrations) : Path` écrivant `plugin.properties` et `db/plugin/<nom>/V*.sql` dans un jar) : `jar_valide_lu`, `jar_sans_plugin_properties_refuse_proprement`, `fichier_qui_n_est_pas_un_jar_refuse_proprement`.
- [ ] **Étape 2 :** lancer chaque classe ; attendu : échec (types absents).
- [ ] **Étape 3 :** implémenter les trois classes. `analyser` : normaliser (retirer les commentaires, passer en minuscules, regrouper les espaces), découper sur `;`, appliquer des `Pattern` précompilés par règle.
- [ ] **Étape 4 :** relancer les trois classes ; attendu : tous réussis. Commit.

### Tâche 3 : chargeur de classes, génération, registre de contrats

**Fichiers :** créer `PluginClassLoader`, `PluginGeneration`, `ContratRegistre` ; tests `PluginClassLoaderTest`, `ContratRegistreTest`.

**Interfaces :**
- Consomme : `ContratIndisponibleException`.
- Produit : `PluginClassLoader(String etiquette, URL jar, ClassLoader parent)` (`extends URLClassLoader`, enfant-d'abord sauf préfixes parent-first des contraintes globales ; `close()` public) ; `PluginGeneration` avec champs `String nom, String version; Path jarCopie, source; PluginClassLoader loader; ConfigurableApplicationContext contexte; RequestMappingHandlerMapping mapping; AtomicInteger enVol; int seq` et `String etiquette()` (`nom@version#seq`) ; `ContratRegistre.declarer(Class<?> type)`, `<T> T obtenir(Class<T> type)` (proxy JDK stable ; chaque appel délègue au fournisseur courant ou lève `ContratIndisponibleException`, et incrémente/décrémente `enVol` de la génération fournisseur), `publier(Class<?> type, Object impl, PluginGeneration gen)`, `retirer(PluginGeneration gen)`, `Set<Class<?>> types()`.

- [ ] **Étape 1 : tests qui échouent.** `PluginClassLoaderTest` (jar de test avec une classe `x.Hello` compilée par `javax.tools` dans l'aide de test, étendre `JarDePluginDeTest` avec `classes`) : `charge_enfant_d_abord` (une classe présente dans le jar ET dans le parent vient du jar), `parent_d_abord_pour_les_prefixes_partages` (`java.lang.String`, `org.springframework.context.ApplicationContext` viennent du parent), `close_libere_le_fichier` (le jar copié peut être supprimé après `close()`, important sous Windows). `ContratRegistreTest` avec une interface de test `Salut` : `proxy_stable_apres_remplacement` (même proxy, nouveau fournisseur → nouvelle réponse), `aucun_fournisseur_leve_contrat_indisponible`, `retirer_rend_le_contrat_indisponible`, `compteur_en_vol_revient_a_zero_apres_appel_qui_leve`.
- [ ] **Étape 2 :** lancer ; attendu : échec.
- [ ] **Étape 3 :** porter `PluginClassLoader`, `PluginGeneration`, `ContractRegistry` du prototype (noms français, `enVol` mis à jour dans le proxy en `try/finally`).
- [ ] **Étape 4 :** relancer ; attendu : réussis. Commit.

### Tâche 4 : démarrage d'un contexte de plugin (Flyway, JPA, transactions)

**Fichiers :** créer `DemarreurContexte`, `PlateformeProperties` ; test `DemarreurContexteIT` (nom `*IT` mais lancé par `mvn -Dtest=DemarreurContexteIT test`, comme les autres tests de base du projet) ; étendre `JarDePluginDeTest` avec un plugin de test complet (entité `Note(id, texte)`, dépôt Spring Data, `@Configuration` avec `@EnableJpaRepositories`, `@EnableTransactionManagement`, migration `V1__note.sql` créant `plugin_testnotes.note`).

**Interfaces :**
- Consomme : `ManifestePlugin`, `PluginGeneration`, `PluginClassLoader`.
- Produit : `record ResultatMigration(int appliquees, String versionSchema)` ; `DemarreurContexte(DataSource ds, ApplicationContext coeur)` avec `ResultatMigration migrer(PluginGeneration gen, ManifestePlugin m)` (Flyway : `schemas("plugin_"+nom).createSchemas(true)`, `table("flyway_schema_history_"+nom)`, `locations("classpath:db/plugin/"+nom)`, classloader du plugin) et `ConfigurableApplicationContext demarrer(PluginGeneration gen, ManifestePlugin m)` (porte `startContext` du prototype : EMF avec `setPersistenceUnitName("plugin-"+gen.etiquette())`, `JpaTransactionManager`, TCCL positionné pendant `refresh()` puis restauré) ; `PlateformeProperties` (`@ConfigurationProperties("app.plateforme")` : `boolean actif=false`, `String repertoirePlugins="plugins"`, `long delaiDrainageMs=30000`, `String versionCoeur="0.0.0"`).

- [ ] **Étape 1 : test qui échoue.** `DemarreurContexteIT` (base PostgreSQL locale ; nettoyage en `@AfterEach` : `DROP SCHEMA plugin_testnotes CASCADE`) : `migre_dans_le_schema_du_plugin` (après `migrer`, la table `plugin_testnotes.note` existe, `flyway_schema_history_testnotes` existe dans ce schéma, `public` ne contient aucune table `note`) ; `demarre_un_contexte_qui_lit_et_ecrit` (le dépôt enregistre un `Note` puis le relit) ; `migration_cassee_ne_laisse_aucune_trace` (V1 correcte puis V2 invalide `ALTER TABLE inexistante...` : `migrer` lève une exception, la colonne et la ligne d'historique de V2 n'existent pas) ; `deux_generations_ont_des_unites_de_persistance_distinctes`.
- [ ] **Étape 2 :** lancer ; attendu : échec.
- [ ] **Étape 3 :** implémenter en portant le prototype. Ne PAS réutiliser `MultiTenantJpaConfiguration` : EMF dédiée.
- [ ] **Étape 4 :** relancer ; attendu : réussis. Commit.

### Tâche 5 : routage des plugins, mode maintenance, comptage des requêtes en vol, sécurité

**Fichiers :** créer `RoutagePlugins`, `FiltreEnVol` ; modifier `config/SecurityConfig.java` (dans `angularApiFilterChain`, ajouter `"/api/plateforme/**"` et `"/api/plugins/**"` à `securityMatcher` et les règles `.requestMatchers("/api/plateforme/**").hasRole("ADMIN")` puis `.requestMatchers("/api/plugins/**").authenticated()`, placées avant `/api/intra/**`) ; tests `RoutagePluginsTest`, `SecurityPlateformeTest`.

**Interfaces :**
- Consomme : `PluginGeneration`.
- Produit : `RoutagePlugins implements HandlerMapping, Ordered` (ordre juste avant `RequestMappingHandlerMapping`) avec `router(String nom, PluginGeneration gen)`, `retirerRoute(String nom, PluginGeneration gen)`, `maintenance(String nom, boolean actif)`, `boolean enMaintenance(String nom)` ; un plugin en maintenance répond `503` avec en-tête `Retry-After: 2`, un plugin inconnu `404` ; `FiltreEnVol extends OncePerRequestFilter` : incrémente `enVol` de la génération visée pendant toute la requête. Préfixe d'URL : `/api/plugins/<nom>/...` ; l'interface Angular d'un plugin sera servie par `/plugins/<nom>/ui/...` (hors périmètre de cette tâche, voir T8).

- [ ] **Étape 1 : tests qui échouent.** `RoutagePluginsTest` (MockMvc avec le plugin de test de T4 démarré) : `route_vers_le_plugin_actif` (GET `/api/plugins/testnotes/notes` → 200), `plugin_inconnu_donne_404` (et non 500), `maintenance_donne_503_avec_retry_after` (en-tête `Retry-After` = `2`) puis `fin_de_maintenance_redonne_200`, `bascule_vers_nouvelle_generation_sans_erreur`, `en_vol_compte_pendant_la_requete_et_revient_a_zero`. `SecurityPlateformeTest` (`@SpringBootTest` + MockMvc) : `plateforme_sans_jeton_refusee_401`, `plateforme_avec_role_esn_user_refusee_403`, `plateforme_admin_acceptee`, `plugins_sans_jeton_refuses_401`.
- [ ] **Étape 2 :** lancer ; attendu : échec.
- [ ] **Étape 3 :** porter `PluginRoutingHandlerMapping` (avec `createWithResolvedBean()` : le piège du nom de bean cherché dans le contexte du cœur) ; modifier `SecurityConfig`.
- [ ] **Étape 4 :** relancer ces deux classes ET `CsrfCibleTest` ; attendu : réussis. Commit.

### Tâche 6 : PluginManager (installation, mise à jour en maintenance, retour arrière, démarrage)

**Fichiers :** créer `PluginManager` ; tests `PluginManagerIT`.

**Interfaces :**
- Consomme : T2 (`LecteurManifeste`, `VerificateurMiseAJour`), T3, T4, T5.
- Produit : `enum Statut { OK, REFUSE, ECHEC, ANNULE }` ; `record ResultatInstallation(Statut statut, String plugin, String version, List<String> raisons, long interruptionMs)` ; `PluginManager.installer(Path jar) : ResultatInstallation` (`synchronized`) ; `desinstaller(String nom) : boolean` (le schéma est conservé) ; `Map<String,PluginGeneration> actifs()` ; `@EventListener(ApplicationReadyEvent.class) demarrer()` qui installe chaque `.jar` du répertoire configuré (dans l'ordre alphabétique ; un jar refusé est journalisé et ignoré, jamais bloquant ; les non-jar et le sous-dossier `.work` sont ignorés).
- Flux de `installer` : lire le manifeste (invalide → `REFUSE`) ; s'il existe une version active → `VerificateurMiseAJour` (refusé → `REFUSE`, version active intacte) ; sans version active → vérifier seulement `coeurMin/Max` ; copier le jar dans `.work/` ; si une version est active : `maintenance(nom,true)`, `retirer` le contrat, drainage (au plus `delaiDrainageMs`, au-delà : fermeture forcée journalisée), fermeture de l'ancienne génération (T7 nettoie) ; migrer puis démarrer la nouvelle ; en cas d'échec : redémarrer l'ancien jar (`ANNULE`, base cohérente) ; `maintenance(nom,false)`. `interruptionMs` = durée de la maintenance.

- [ ] **Étape 1 : tests qui échouent** (`PluginManagerIT`, plugin de test en versions v1, v2 (migration additive `ADD COLUMN titre text`), v3-casse (migration invalide), v3-coeur-requis (déclare `chaud` avec un `DROP COLUMN`)) : `installation_initiale_puis_requete_ok` ; `mise_a_jour_v1_vers_v2_sous_trafic` (un thread appelle l'API en boucle pendant la mise à jour : toute réponse est 200 ou 503 avec `Retry-After`, jamais 500 ; `interruptionMs` < 5000) ; `donnees_v1_lisibles_par_v2` ; `migration_cassee_annule_et_ancienne_version_sert` (`ANNULE`, l'API répond encore, historique Flyway inchangé) ; `declaration_chaud_contredite_refusee` (`REFUSE`, raisons non vides, v2 toujours active) ; `requete_plus_longue_que_le_drainage_est_forcee` (délai de drainage 300 ms, requête de 2 s → mise à jour terminée en < 2 s, avertissement journalisé) ; `deux_installations_simultanees_sont_serialisees` (2 threads, exactement une génération active à la fin) ; `repertoire_avec_fichier_non_jar_et_jar_invalide_ne_bloque_pas_le_demarrage`.
- [ ] **Étape 2 :** lancer ; attendu : échec.
- [ ] **Étape 3 :** porter `deployMaintenance`/`deploySwap` du prototype (seule la stratégie maintenance est portée ; pas de mode `core`, pas de swap atomique) ; installation initiale sans maintenance.
- [ ] **Étape 4 :** relancer ; attendu : réussis. Commit.

### Tâche 7 : nettoyage des fuites et test de fuite automatique

**Fichiers :** créer `NettoyeurFuites` ; tests `NettoyeurFuitesTest`, `FuiteMemoireIT` ; modifier `PluginManager` (appel de `nettoyer` à la fermeture et méthode de rapport).

**Interfaces :**
- Produit : `NettoyeurFuites.nettoyer(ClassLoader loader)` (purge par réflexion, **chaque cache dans son propre `try/catch` qui journalise un avertissement si la structure interne a changé**, sans jamais lever) ; `PluginManager.rapportFuites(boolean pressionSouple) : RapportFuites` avec `record RapportFuites(int retires, int toujoursVivants, List<String> etiquettesVivantes, long metaspaceMo)` (porter `leakReport` : `WeakReference` par loader retiré, pression mémoire pour effacer les `SoftReference`, puis GC).
- Caches à purger (RESULTATS.md section 3) : Spring Data (`ClassTypeInformation.cache`, `resolvableTypeCache`, `TypeDiscoverer.CACHE`), Hibernate (`SessionFactoryRegistry`), Jackson 3 (`SerializerCache`, `DeserializerCache`, `_rootDeserializers`, cache de `TypeFactory` du `JsonMapper` du cœur), Spring MVC (`argumentResolverCache`, caches `initBinder`/`modelAttribute` de `RequestMappingHandlerAdapter`, `exceptionHandlerCache` de `ExceptionHandlerExceptionResolver`).

- [ ] **Étape 1 : tests qui échouent.** `FuiteMemoireIT` (le test de CI exigé par le spec 4.4 et 8) : `vingt_mises_a_jour_aucune_fuite` (installer v1, puis 20 mises à jour alternées v2/v1 avec une requête GET entre chaque, y compris une requête qui fait lever une exception côté plugin pour remplir `exceptionHandlerCache` ; `rapportFuites(true).toujoursVivants == 0`) ; `sans_nettoyage_les_generations_fuient` (preuve que le test sait détecter : avec un `NettoyeurFuites` désactivé par propriété de test, `toujoursVivants > 0` après 5 mises à jour ; ce test est le garde-fou du garde-fou). `NettoyeurFuitesTest` : `structure_interne_absente_ne_leve_pas` (champ introuvable → avertissement, pas d'exception).
- [ ] **Étape 2 :** lancer ; attendu : échec (classe absente).
- [ ] **Étape 3 :** porter `LeakCleaner` et `leakReport`. Ajouter la propriété de test `app.plateforme.nettoyage-actif` (défaut `true`) uniquement pour le garde-fou.
- [ ] **Étape 4 :** relancer ; attendu : réussis (le test peut prendre ~1 min). Commit.

### Tâche 8 : administration, état, intégration au cœur

**Fichiers :** créer `PluginAdminController`, `package-info.java` du module `plateforme` ; modifier `ModularityTests`, `application.properties.example` ; test `PluginAdminControllerTest`, `PlateformeDesactiveeTest`.

**Interfaces :**
- Produit : `GET /api/plateforme/plugins` → liste `[{nom, version, etat: ACTIF|MAINTENANCE}]` ; `POST /api/plateforme/plugins/installer` avec corps JSON `{"fichier":"<nom.jar>"}` (fichier du répertoire configuré uniquement : le nom ne doit contenir ni `/`, ni `\`, ni `..`, sinon 400) → `ResultatInstallation` ; `GET /api/plateforme/fuites` → `RapportFuites` ; `DELETE /api/plateforme/plugins/{nom}`. Tous réservés à `ROLE_ADMIN` (T5). `package-info.java` : `@ApplicationModule(allowedDependencies = {"commun"})`. Propriétés documentées dans `application.properties.example` : `app.plateforme.actif=false`, `app.plateforme.repertoire-plugins`, `app.plateforme.delai-drainage-ms`, `app.plateforme.version-coeur`.

- [ ] **Étape 1 : tests qui échouent.** `PluginAdminControllerTest` : `installer_refuse_un_nom_avec_chemin` (`../x.jar`, `a/b.jar`, `a\b.jar` → 400), `installer_un_jar_valide_repond_ok`, `liste_montre_la_version_active`, `fuites_repond_un_rapport`. `PlateformeDesactiveeTest` (`@SpringBootTest` sans la propriété) : `aucun_bean_de_plateforme_quand_desactivee` (`context.getBeansOfType(PluginManager.class)` vide ; `GET /api/plateforme/plugins` → 404 ou 401, jamais 200). `ModularityTests` : ajouter `plateforme` à `MODULES_VERIFIES`.
- [ ] **Étape 2 :** lancer ; attendu : échec.
- [ ] **Étape 3 :** implémenter ; mettre `@ConditionalOnProperty` sur tous les beans de la plateforme ; vérifier que `SiteApplicationTests` et `ModularityTests` passent.
- [ ] **Étape 4 :** lancer `PluginAdminControllerTest`, `PlateformeDesactiveeTest`, `ModularityTests`, `SiteApplicationTests`, `CsrfCibleTest` un par un ; attendu : réussis. Commit.
- [ ] **Étape 5 :** documenter en tête de `RESULTATS`-équivalent dans `docs/` une note courte « lancer la plateforme en local » (activer la propriété, déposer un jar dans `plugins/`). Commit final.

---

## Auto-revue (faite)
- **Couverture du spec :** 4.3 (mécanisme, maintenance, retour arrière) → T4, T6 ; 4.4 (nettoyage, test de fuite) → T7 (le redémarrage de nuit et la surveillance du Metaspace sont au sous-projet 2, `rapportFuites` expose déjà `metaspaceMo`) ; 4.5 (classification, analyse des migrations, clés étrangères) → T2, T6 ; 5 (règles du prototype) → contraintes globales + T4, T5, T6 ; 8 (critères) → T6, T7. Signature et distribution : sous-projet 2, hors de ce plan.
- **Cohérence des types :** `ManifestePlugin`, `PluginGeneration`, `ResultatInstallation`, `RapportFuites` utilisés avec les mêmes noms de T1 à T8.
- **Lacune connue :** l'interface Angular d'un plugin (`/plugins/<nom>/ui/...`) n'est pas servie dans ce sous-projet ; elle est portée au sous-projet 3 avec le premier plugin réel.

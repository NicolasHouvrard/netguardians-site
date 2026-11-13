# Plugins SP2 — signature, distribution, surveillance — plan d'implémentation

> **Pour les exécutants :** utiliser superpowers:subagent-driven-development (recommandé) ou superpowers:executing-plans. Les étapes utilisent des cases `- [ ]`.

**Objectif :** un jar signé sur un autre poste, publié au Maître, tiré et vérifié par chaque serveur, installé sans commande manuelle ; refus de tout jar non signé ; surveillance du Metaspace avec redémarrage de nuit du Maître.

**Architecture :** la signature (Ed25519, JDK) vit dans le SDK partagé (compilé dans le cœur et dans `plugins-sdk`, avec une petite CLI). Le cœur vérifie dans `PluginManager`. Le Maître stocke les jars dans un dépôt-répertoire et les sert aux serveurs par des routes `/api/licence/plugins/**` authentifiées par la clé API de licence existante ; un planificateur « tireur » sur chaque serveur télécharge, vérifie, installe, lit `statut` et le rapporte.

**Pile :** Java 25, Spring Boot 4.0.2, Spring Modulith, Flyway (cœur, `src/main/resources/db/migration`, prochaine version V9), JUnit, scripts bash de `nas-package/` avec leurs tests `nas-package/tests/`.

**Spec :** `docs/superpowers/specs/2026-11-13-plugins-sp2-signature-distribution-design.md` (et `2026-11-12-plugins-design.md`). Le code de SP1 est dans `src/main/java/com/logicielpourtous/site/features/plateforme/`.

## Global Constraints
- Algorithme **Ed25519** via `java.security` ; **aucune dépendance Maven ajoutée**.
- Fichier de signature `<jar>.sig`, JSON `{"keyId","algo":"Ed25519","signature":<base64>}` ; la signature porte sur les octets exacts du jar.
- Clés publiques : **uniquement** la ressource `plateforme/cles-publiques.properties` du cœur ; aucune propriété ne permet d'en ajouter. Au moins deux clés attendues (avertissement au démarrage sinon).
- `app.plateforme.signature-obligatoire` : **vrai par défaut**. Faux = développement/tests, avec avertissement.
- Toute la plateforme reste conditionnelle à `app.plateforme.actif=true` ; ne rien changer au comportement du cœur quand elle est désactivée.
- **Décision utilisateur** : l'installation répond HTTP 200 même pour REFUSE/ECHEC/ANNULE ; **tout appelant lit et traite `statut`** (le tireur, le Maître qui stocke et affiche).
- Les routes serveur→Maître sont sous `/api/licence/plugins/**` : `licenceApiFilterChain` finit par `anyRequest().denyAll()`, donc chaque route est ajoutée explicitement avec `hasRole(LicenceApiKeyAuthFilter.ROLE)`.
- Modulith : le module `plateforme` ne dépend que de `commun` aujourd'hui ; toute dépendance ajoutée (ex. vers `licence`) est déclarée dans son `package-info.java`, et `licence` ne doit **jamais** dépendre de `plateforme` ; `ModularityTests` doit passer.
- Build : `mvn` = `~/.m2/wrapper/dists/apache-maven-3.9.9/3477a4f1/bin/mvn`, `JAVA_HOME=/c/Users/nicolas/scoop/apps/temurin25-jdk/current`, options `-B -q -Dskip.npm -Dskip.installnodenpm`. Une classe de test à la fois (`-Dtest=<Classe>`), résultats lus dans `target/surefire-reports`, pas dans le code de sortie. Tests existants lents (`*IT`, plusieurs minutes) : ne pas lancer la suite complète.
- Messages et commentaires en français, comme le reste du dépôt. Aucun secret (clé privée, clé API) dans le dépôt ni dans les journaux.

## Review Focus
- Un jar signé valide **puis modifié d'un octet** est refusé ; une signature valide d'une autre version/autre jar est refusée.
- Une `.sig` malformée (JSON cassé, base64 invalide, `algo` inconnu, champs manquants) ne lève jamais d'exception non contrôlée : `REFUSE`.
- Le Maître ne signe rien et n'accepte pas un jar mal signé ; republier la même version avec un autre contenu est refusé.
- Aucune traversée de chemin dans les routes de téléchargement (`nom`, `version`) ni dans la publication.
- Un tireur dont le Maître est injoignable, lent ou répond n'importe quoi ne bloque ni le démarrage ni les requêtes, et ne boucle pas.
- Une version refusée n'est pas retéléchargée toutes les 10 minutes pour toujours.
- Le repère de redémarrage ne déclenche **rien** hors de la fenêtre horaire ; le script ne coupe jamais l'ancienne instance si la nouvelle n'est pas saine.

---

### Tâche 1 : signature dans le SDK et outil en ligne de commande

**Fichiers :** créer `src/sdk/java/com/logicielpourtous/site/sdk/SignatureJar.java`, `ClePublique.java`, `OutilSignature.java` (classe avec `main`) ; tests `SignatureJarTest`, `OutilSignatureTest` ; modifier `plugins-sdk/pom.xml` (classe principale dans le manifeste du jar, sans dépendance).

**Interfaces :**
- Produit : `record ClePublique(String keyId, PublicKey cle)` ; `SignatureJar.signer(Path jar, PrivateKey cle, String keyId) : String` (le JSON de la `.sig`) ; `SignatureJar.verifier(Path jar, String jsonSig, List<ClePublique> cles) : Verdict` avec `record Verdict(boolean valide, String raison, String keyId)` (ne lève jamais : toute anomalie donne `valide=false` + raison) ; `SignatureJar.lireCles(Properties) : List<ClePublique>` ; `OutilSignature.main(String[])` : `generer-cles <dossier> <keyId>` (écrit `<keyId>.prive` et `<keyId>.public`), `signer <jar> <fichier-cle-privee> <keyId>` (écrit `<jar>.sig`), `verifier <jar> <fichier-cles-publiques.properties>` (code retour 0/1).

- [ ] **Étape 1 : tests qui échouent.** `SignatureJarTest` : `signature_valide_est_acceptee`, `un_octet_modifie_est_refuse`, `cle_inconnue_est_refusee`, `algo_inconnu_est_refuse`, `json_malforme_champs_manquants_ou_base64_invalide_sont_refuses_sans_exception` (une ligne par cas), `signature_d_un_autre_jar_est_refusee`. `OutilSignatureTest` : cycle complet générer → signer → vérifier dans un dossier temporaire, code retour 0 puis 1 après modification du jar ; la clé privée n'est jamais écrite sur la sortie standard.
- [ ] **Étape 2 :** lancer ; attendu : échec de compilation.
- [ ] **Étape 3 :** implémenter. JSON lu/écrit à la main (le SDK n'a aucune dépendance) avec un analyseur strict minimal des trois champs ; comparaison de `keyId` insensible à la casse interdite (exacte). Fichiers de clés : base64 PKCS#8 (privée) et X.509 (publique).
- [ ] **Étape 4 :** relancer ; attendu : réussis. Vérifier que `plugins-sdk` se construit encore (`mvn -f plugins-sdk/pom.xml package`). Commit.

### Tâche 2 : vérification de la signature dans le cœur

**Fichiers :** créer `plateforme/ClesPubliques.java`, `src/main/resources/plateforme/cles-publiques.properties` (une clé actuelle + une de secours **de développement générées pour ce dépôt, étiquetées `dev-` dans le keyId**, la vraie paire de production étant générée par l'utilisateur sur l'autre poste et remplaçant ces lignes ; documenter dans le fichier) ; modifier `PlateformeProperties` (`signatureObligatoire`, défaut true), `PluginManager`, `application.properties.example` ; tests `ClesPubliquesTest`, `PluginManagerSignatureIT` ; adapter `JarDePluginDeTest` (option de signer avec une paire de test) et les tests existants qui installent des jars non signés (`PluginManagerIT`, `RoutagePluginsTest`, `FuiteMemoireIT`, `PluginAdminControllerTest`, `DemarreurContexteIT`, `PluginManagerCablageTest`…) pour fixer `app.plateforme.signature-obligatoire=false` là où la signature n'est pas l'objet du test.

**Interfaces :**
- Consomme : `SignatureJar`, `ClePublique` (T1).
- Produit : `ClesPubliques(List<ClePublique>)` bean (lit la ressource) ; `PluginManager.installer(Path jar)` exige `<jar>.sig` adjacent quand `signatureObligatoire`, sinon `REFUSE` avec une raison explicite (`signature absente`, `signature invalide : <raison>`) ; le constructeur de test de `PluginManager` accepte une liste de clés injectée.

- [ ] **Étape 1 : tests qui échouent.** `PluginManagerSignatureIT` : `jar_signe_est_installe`, `jar_sans_sig_est_refuse`, `jar_modifie_apres_signature_est_refuse`, `cle_inconnue_est_refusee`, `refus_ne_laisse_aucune_copie_dans_work_ni_schema`, `signature_non_obligatoire_installe_un_jar_sans_sig_avec_avertissement`. `ClesPubliquesTest` : lecture de la ressource, avertissement si moins de deux clés, ressource absente = refus de démarrer la plateforme (échec explicite, pas de cœur sans clé qui accepte tout).
- [ ] **Étape 2 :** lancer ; attendu : échec.
- [ ] **Étape 3 :** implémenter ; la vérification se fait **avant** toute copie dans `.work` et avant l'ouverture du jar par un chargeur de classes. Adapter l'avertissement de démarrage « jars non signés » de SP1 : il n'apparaît plus que si `signature-obligatoire=false`.
- [ ] **Étape 4 :** lancer `ClesPubliquesTest`, `PluginManagerSignatureIT`, `PluginManagerIT`, `PluginManagerCablageTest`, `PluginAdminControllerTest` un par un ; attendu : réussis. Commit.

### Tâche 3 : refus de rétrogradation et démarrage sur la plus haute version signée

**Fichiers :** modifier `PluginManager`, `VerificateurMiseAJour` ; tests dans `PluginManagerIT`/`VerificateurMiseAJourTest`.

**Interfaces :** consomme T2. Produit : `installer` refuse (`REFUSE`, « rétrogradation refusée : v.X < v.active ») une version strictement inférieure à l'active ; à égalité, comportement actuel conservé (no-op ou réinstallation selon SP1). `demarrer` ne retient, pour chaque plugin, que les jars **à signature valide** avant de choisir la plus haute version.

- [ ] **Étape 1 : tests qui échouent.** `retrogradation_refusee` (1.2.0 active, 1.1.0 refusée, 1.2.0 toujours servie) ; `un_jar_plus_haut_mais_mal_signe_ne_gagne_pas_au_demarrage` (2.0.0 sans signature valide + 1.5.0 signé : 1.5.0 démarre) ; `jar_plus_haut_signe_gagne`.
- [ ] **Étape 2 :** lancer ; attendu : échec.
- [ ] **Étape 3 :** implémenter dans `demarrer`/`plusHautesVersions` et `installer`. Corriger aussi le mineur de SP1 : une version à plus de 18 chiffres ne doit pas arrêter tous les plugins (`comparer` protégé jar par jar).
- [ ] **Étape 4 :** relancer ; attendu : réussis. Commit.

### Tâche 4 : dépôt du Maître

**Fichiers :** créer `plateforme/DepotPlugins.java`, `EntreeCatalogue.java` (record), `DepotController.java` ; modifier `PlateformeProperties` (`depotMaitre` : répertoire, défaut `depot-plugins`) ; tests `DepotPluginsTest`, `DepotControllerTest`.

**Interfaces :**
- Produit : `DepotPlugins.publier(InputStream jar, InputStream sig) : EntreeCatalogue` (écrit dans un temporaire, vérifie la signature avec `ClesPubliques`, lit le manifeste avec `LecteurManifeste`, refuse si `<nom>-<version>.jar` existe avec un `sha256` différent, installe atomiquement) ; `catalogue() : List<EntreeCatalogue>` ; `ouvrir(nom, version, extension) : Path` (valide `nom` avec `ManifestePlugin.NOM_VALIDE`, `version` avec un motif semver strict ; aucune traversée de chemin) ; `record EntreeCatalogue(String nom, String version, String miseAJour, String coeurMin, String coeurMax, String sha256, long taille)`. `POST /api/plateforme/depot` (multipart `jar`, `sig`), `GET /api/plateforme/depot` ; ADMIN ; **beans actifs seulement si `app.plateforme.actif=true` et `app.role=MASTER`**.

- [ ] **Étape 1 : tests qui échouent.** `DepotPluginsTest` : `publication_valide_apparait_au_catalogue`, `jar_mal_signe_refuse_et_rien_ecrit`, `meme_version_contenu_different_refusee`, `meme_version_meme_contenu_idempotente`, `nom_ou_version_avec_chemin_refuse` (`../x`, `a/b`, `a\b`, version `1.0.0/../..`), `catalogue_trie_et_sha256_correct`. `DepotControllerTest` : 401 sans jeton, 403 non-ADMIN, 200 + entrée ADMIN, absent quand le rôle n'est pas MASTER.
- [ ] **Étape 2 :** lancer ; attendu : échec.
- [ ] **Étape 3 :** implémenter ; limite de taille du fichier téléversé (par défaut 100 Mo, propriété) ; écriture dans le dépôt par `Files.move` atomique.
- [ ] **Étape 4 :** relancer ; attendu : réussis. Commit.

### Tâche 5 : API pour les serveurs et état rapporté

**Fichiers :** créer `plateforme/DistributionController.java`, `EtatPluginServeur.java` (entité JPA), `EtatPluginServeurRepository.java`, `src/main/resources/db/migration/V9__plateforme_etat_serveur.sql`, `RapportEtatController` (ou méthode dans `DistributionController`) ; modifier `config/SecurityConfig.java` (3 lignes dans `licenceApiFilterChain`) ; tests `DistributionControllerTest`.

**Interfaces :**
- Consomme : `DepotPlugins` (T4). Le principal authentifié par `LicenceApiKeyAuthFilter` est l'id `Long` du `LicenceServeur` (pas de dépendance de code vers `licence`).
- Produit : `GET /api/licence/plugins/catalogue` → `List<EntreeCatalogue>` ; `GET /api/licence/plugins/{nom}/{version}/jar` et `/sig` (flux, `Content-Length`) ; `POST /api/licence/plugins/etat` corps `{nom, version, statut, raisons[]}` → 204 ; table `plateforme_etat_serveur(licence_serveur_id, nom, version, statut, raisons, rapporte_le)` clé `(licence_serveur_id, nom)` (dernier état) ; `GET /api/plateforme/serveurs-etat` (ADMIN, MASTER) listant les derniers états. Routes ajoutées avec `hasRole(LicenceApiKeyAuthFilter.ROLE)` ; elles n'existent que sur un Maître (plateforme active + `app.role=MASTER`).

- [ ] **Étape 1 : tests qui échouent.** `DistributionControllerTest` : sans clé 401 ; clé valide : catalogue, jar et sig téléchargés identiques au dépôt ; `404` pour une version absente ; `400` pour un nom/version à chemin ; `POST etat` enregistre puis remplace le dernier état du même serveur et plugin ; `statut` hors énumération → 400 ; `GET serveurs-etat` ADMIN seulement ; une clé API d'un autre module (`/api/sync`) n'ouvre rien.
- [ ] **Étape 2 :** lancer ; attendu : échec.
- [ ] **Étape 3 :** implémenter ; vérifier `ModularityTests` (aucune dépendance vers `licence`).
- [ ] **Étape 4 :** lancer `DistributionControllerTest`, `ModularityTests`, `CsrfCibleTest`, `SecurityPlateformeTest` un par un ; attendu : réussis. Commit.

### Tâche 6 : le tireur côté serveur

**Fichiers :** créer `plateforme/SourcePlugins.java` (interface), `SourceMaitreHttp.java`, `SourceDepotLocal.java`, `TireurPlugins.java` ; modifier `PlateformeProperties` (`masterUrl` repris de `app.licence.master-url`, `intervalleTirageMs` 600000), `package-info.java` (dépendance vers `licence` **seulement si nécessaire** pour lire la clé API ; sinon passer la clé par une interface `CleMaitre` implémentée côté `licence`… à trancher : la solution ne doit pas créer de cycle) ; tests `TireurPluginsTest` (source simulée), `SourceMaitreHttpTest` (serveur HTTP local du JDK).

**Interfaces :**
- Produit : `interface SourcePlugins { List<EntreeCatalogue> catalogue(); Path telecharger(EntreeCatalogue e, Path dossier); void rapporter(String nom, String version, ResultatInstallation.Statut statut, List<String> raisons); }` ; `TireurPlugins.tirer()` (planifié, `synchronized`, ne lève jamais) : pour chaque plugin du catalogue avec une version supérieure à l'active (compatible avec `coeurMin`/`coeurMax`), télécharge jar + sig, contrôle `sha256`, dépose `<nom>-<version>.jar` + `.sig` dans le répertoire des plugins, appelle `PluginManager.installer`, **lit `statut`**, rapporte ; garde en mémoire les couples (nom, version) refusés ou en échec pour ne pas les retenter tant qu'aucune version plus haute n'apparaît ; supprime les jars non actifs plus vieux que la version précédente. `SourceDepotLocal` (si `app.role=MASTER`) lit `DepotPlugins` sans HTTP et ne rapporte qu'au journal/à la table locale.

- [ ] **Étape 1 : tests qui échouent.** `TireurPluginsTest` : `nouvelle_version_signee_est_installee_et_rapportee_OK` ; `sha256_different_rejette_et_ne_depose_rien` ; `version_refusee_n_est_pas_retiree_au_tirage_suivant_mais_l_est_si_une_plus_haute_apparait` ; `statut_REFUSE_ANNULE_ECHEC_sont_rapportes_tels_quels` (garde-fou de la décision utilisateur : un REFUSE n'est jamais rapporté comme OK) ; `maitre_injoignable_ne_leve_pas_et_ne_modifie_rien` ; `reponse_catalogue_absurde_est_ignoree` ; `jars_anciens_supprimes_sauf_active_et_precedente`. `SourceMaitreHttpTest` : en-tête `X-Licence-Api-Key` envoyé, délais de connexion/lecture bornés, jamais la clé dans les journaux.
- [ ] **Étape 2 :** lancer ; attendu : échec.
- [ ] **Étape 3 :** implémenter ; délais HTTP explicites (connexion 5 s, lecture 60 s) ; le téléchargement se fait dans un dossier temporaire du répertoire des plugins (même volume), jamais directement dans le répertoire surveillé au démarrage avant vérification.
- [ ] **Étape 4 :** lancer `TireurPluginsTest`, `SourceMaitreHttpTest`, `ModularityTests` un par un ; attendu : réussis. Commit.

### Tâche 7 : fédération face à un plugin en maintenance (503 + Retry-After)

**Fichiers :** à déterminer d'après `features/sync/RestSyncClient.java` et `features/licence/LicenceClient.java` ; tests associés.

**Interfaces :** produit : tout appel sortant de fédération qui reçoit `503` avec `Retry-After` attend (borné, 30 s maximum) puis réessaie 2 fois ; sans `Retry-After`, comportement actuel inchangé.

- [ ] **Étape 1 :** vérifier d'abord si un tel traitement existe déjà (lecture du code) et le noter dans le rapport. Si oui, ajouter seulement le test qui le prouve et s'arrêter. Sinon, **écrire d'abord** `reessaie_apres_503_avec_retry_after`, `n_attend_pas_plus_de_30_secondes`, `503_sans_retry_after_garde_le_comportement_actuel`.
- [ ] **Étape 2 :** lancer ; attendu : échec (cas « absent »).
- [ ] **Étape 3 :** implémenter dans un utilitaire commun du module qui possède les clients (pas dans `plateforme`).
- [ ] **Étape 4 :** lancer les classes de test des clients concernés ; attendu : réussis. Commit.

### Tâche 8 : surveillance du Metaspace

**Fichiers :** créer `plateforme/SurveillanceMetaspace.java` ; modifier `PlateformeProperties` (`metaspaceSeuilMo` 512, `intervalleSurveillanceMs` 60000, `repertoireDonnees`) ; tests `SurveillanceMetaspaceTest`.

**Interfaces :** produit : `SurveillanceMetaspace.controler()` (planifié) lit l'utilisation via une `Supplier<Long>` injectable (bean par défaut = pool `Metaspace` de `ManagementFactory`) ; au-dessus du seuil, écrit une fois le fichier `<repertoireDonnees>/redemarrage-recommande` (JSON : `metaspaceMo`, `seuilMo`, `depuis`) et journalise en WARN ; sous le seuil, supprime le repère. Exposé aussi dans `GET /api/plateforme/plugins` ou une route sœur `GET /api/plateforme/metaspace` (ADMIN) : `{metaspaceMo, seuilMo, redemarrageRecommande}`.

- [ ] **Étape 1 : tests qui échouent.** `repere_ecrit_au_dessus_du_seuil_une_seule_fois`, `repere_supprime_quand_la_mesure_redescend`, `aucune_ecriture_sous_le_seuil`, `erreur_d_ecriture_ne_leve_pas` (répertoire en lecture seule), `route_metaspace_reservee_admin`.
- [ ] **Étape 2 :** lancer ; attendu : échec.
- [ ] **Étape 3 :** implémenter.
- [ ] **Étape 4 :** lancer ; attendu : réussis. Commit.

### Tâche 9 : redémarrage de nuit du Maître (NAS)

**Fichiers :** créer `nas-package/lib/redemarrage_nuit.sh`, `nas-package/systemd/site-redemarrage-nuit.service.template` et `.timer.template`, `nas-package/tests/test_redemarrage_nuit.sh` ; modifier `nas-package/tests/run_tests.sh`, `nas-package/README.md`, et le montage du répertoire de données dans `nas-package/lib/docker_bootstrap.sh` si le repère n'y est pas visible du NAS.

**Interfaces :** produit : `redemarrer_si_recommande` (bibliothèque à sourcer, comme `mise_a_jour.sh`) : s'il n'y a pas de repère, ne fait rien (code 0) ; hors de la fenêtre (`REDEMARRAGE_HEURE_DEBUT`/`FIN`, défaut 3/4, surchargeables) ne fait rien ; dans la fenêtre : détermine le tag de l'image en cours, appelle `mettre_a_jour_maitre <tag>` (blue-green existant) ; supprime le repère **seulement si le code retour est 0** ; si `mettre_a_jour_maitre` échoue, l'ancienne instance sert toujours et le repère reste. Un verrou évite deux exécutions simultanées. Mode de test via les fonctions `_exec_*` substituables comme dans `test_mise_a_jour.sh`.

- [ ] **Étape 1 : tests qui échouent.** `test_redemarrage_nuit.sh` : `pas_de_repere_ne_fait_rien`, `repere_hors_fenetre_ne_fait_rien`, `repere_dans_la_fenetre_appelle_mettre_a_jour_maitre_avec_le_tag_courant`, `echec_garde_le_repere`, `succes_supprime_le_repere`, `verrou_empeche_la_double_execution`, `tag_courant_introuvable_ne_fait_rien_et_avertit`.
- [ ] **Étape 2 :** lancer `bash nas-package/tests/test_redemarrage_nuit.sh` ; attendu : échec.
- [ ] **Étape 3 :** implémenter le script et les gabarits systemd (minuteur toutes les 10 minutes ; la fenêtre est décidée par le script).
- [ ] **Étape 4 :** relancer ce test puis `bash nas-package/tests/run_tests.sh` ; attendu : réussis (si l'environnement ne permet pas bash, le consigner et ne pas déclarer réussi). Commit.

### Tâche 10 : documentation et procédure de publication

**Fichiers :** créer `docs/plateforme-plugins-publication.md` ; modifier `docs/plateforme-plugins-local.md`, `application.properties.example`.

- [ ] **Étape 1 :** écrire la procédure pour quelqu'un qui n'a pas suivi le projet : générer la paire de clés sur l'autre poste (et où ranger la clé privée), remplacer les clés `dev-` du cœur par la clé publique de production (+ une de secours) et sortir une version du cœur, signer un jar, le publier au Maître (`curl` ou équivalent avec le jeton ADMIN), vérifier l'état rapporté (`GET /api/plateforme/serveurs-etat`), lire `statut`, que faire d'un REFUSE/ECHEC/ANNULE, rotation de clé, perte de clé privée.
- [ ] **Étape 2 :** documenter dans `docs/plateforme-plugins-local.md` : le mode `signature-obligatoire=false` (développement seulement), le volume persistant des plugins et du dépôt du Maître, le seuil Metaspace et le redémarrage de nuit (Maître seulement), la limite PARTNER/CLIENT, et que **rien n'est publié par GitHub/GHCR** pour les jars.
- [ ] **Étape 3 :** relire les deux documents contre le code (chemins, propriétés, routes, codes HTTP) ; corriger toute divergence. Commit final.

---

## Auto-revue (faite)
- **Couverture du spec :** 3 (signature) → T1, T2 ; 4 (versions, rétrogradation) → T3 ; 5 dépôt → T4, API et état → T5, tireur et décision `statut` → T5, T6 ; 6 Metaspace → T8, T9 ; fédération 503 (7 bis) → T7 ; critères 8 → T2, T3, T6, T8, T9, T10. Hors plan assumé : jars de référence dans l'image, redémarrage PARTNER/CLIENT, ordre signé de rétrogradation.
- **Cohérence des types :** `ClePublique`, `SignatureJar.Verdict`, `EntreeCatalogue`, `SourcePlugins`, `ResultatInstallation.Statut` utilisés avec les mêmes noms de T1 à T6.
- **Points à trancher par l'exécutant, à consigner dans le rapport :** la façon d'obtenir la clé API de licence dans `SourceMaitreHttp` sans cycle Modulith (T6) ; l'emplacement de l'utilitaire de réessai 503 (T7) ; le montage du répertoire de données pour le repère (T9).

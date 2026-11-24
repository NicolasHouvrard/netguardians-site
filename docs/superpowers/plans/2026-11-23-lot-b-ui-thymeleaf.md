# Lot B : interface Thymeleaf du plugin « partenaires » et test de bout en bout — Plan d'implémentation

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal :** Remplacer l'interface Angular abandonnée du plugin `partenaires` par des pages Thymeleaf servies sous `/p/partenaires/…`, retirer ses routes JSON devenues inutiles, et prouver tout le lot B par un test de bout en bout avec la vraie chaîne de sécurité.

**Architecture :** Le moteur du lot B (service de création de partenaire, contrat `GestionPartenairesContrat` réservé à l'admin du maître) ne change pas. Le plugin devient un `@Controller` + gabarits `plugins/partenaires/*.html` dans son jar, qui décorent la mise en page du socle ; le noyau les route (`/p/<nom>/**`, session, CSRF) et les rend. Aucun build front.

**Tech Stack :** Java 25, Spring Boot 4.0.2, Thymeleaf (mise en page `socle/layout`), contrat SDK `GestionPartenairesContrat`, tests JUnit/MockMvc/`@SpringBootTest`.

**Specs :** `docs/superpowers/specs/2026-11-20-lot-b-creation-partenaires-design.md` (lot B) et `docs/superpowers/specs/2026-11-22-socle-front-thymeleaf-htmx-design.md` (socle). Cette branche (`lot-b-ui`) repose sur `front-socle-htmx`.

## Global Constraints

- Plus **aucun** build front dans `plugins-partenaires` : ni Node, ni npm, ni `frontend-maven-plugin`, ni dossier `frontend/`, ni `ui/main.js`. `mvn -B -o -f plugins-partenaires/pom.xml package` doit réussir sans réseau ni npm.
- Le plugin ne dépend que du SDK (`provided`) et ne lit jamais l'identité ni les rôles : tout appel qui ne vient pas de l'admin du maître est refusé par le contrat (`SecurityException`) ; le plugin le traduit en **403** avec une page fixe.
- Pages sous `/p/partenaires/…` (routage et sécurité du socle : session, CSRF sur toute requête non-GET, compte à société à choisir bloqué) ; `@Controller` uniquement (aucun `@RestController` : plus de routes JSON `/api/plugins/partenaires`).
- Gabarits dans `src/main/resources/templates/plugins/partenaires/*.html`, décorant `socle/layout` ; tout texte dynamique par `th:text`/`th:value` (jamais `th:utext`) ; formulaires par `th:action` (jeton CSRF ajouté par Spring Security) ; aucun objet de classe de plugin dans la session ni en attribut flash ; noms d'attributs de modèle réservés du socle respectés (`apps`, `uiSlots`, `tuiles`, `titre`, `moduleActif`, `menuModule`, `fil` : on n'utilise que `titre` et `moduleActif`).
- Identifiant de partenaire dans l'adresse validé `^[0-9]{1,15}$` avant tout appel au contrat (sinon 404, contrat jamais appelé) ; le partenaire cible d'un client vient **uniquement** de l'adresse, jamais d'un champ de formulaire.
- Messages d'erreur : textes fixes en français, sans écho de l'exception ni de la saisie (les champs du formulaire peuvent réafficher la saisie de l'utilisateur par `th:value`).
- Manifeste : `entree.1=ADMIN|Partenaires|building|/liste|partenaires.gerer`, aucun `bloc.N`, aucun `ext.N` ; version `1.0.0`.
- Le dépôt GitHub est **public** : aucune faiblesse non corrigée dans un message de commit. Commits en français, ligne finale `Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>`. Rien n'est poussé sans demande explicite.

## Notes d'exécution (poste Windows, 8 Go de mémoire, sans `.mvn` dans le worktree)

- Maven : `~/.m2/wrapper/dists/apache-maven-3.9.9/3477a4f1/bin/mvn`, `JAVA_HOME=C:\Users\nicolas\scoop\apps\temurin25-jdk\current`, `MAVEN_OPTS=-Xmx768m`, options `-B -o -Dskip.npm=true -Dtest=<Classe> -DfailIfNoTests=false test` pour le noyau, `-f plugins-partenaires/pom.xml` pour le plugin ; **une classe de test par invocation**, chaque commande lourde en arrière-plan avec la sortie dans un fichier, jamais deux en parallèle.
- PostgreSQL local : `pg_ctl start -D C:\Users\nicolas\scoop\persist\postgresql\data` s'il refuse les connexions ; les tests suppriment leurs lignes, schémas `p_*` et tenants.
- Après Maven (noyau) : `git checkout -- src/main/resources/static/app` et supprimer les fichiers générés non suivis. Python n'est pas installé. Le SDK est déjà installé dans `~/.m2` (réinstaller avec `mvn -B -o -f plugins-sdk/pom.xml install -DskipTests` si le contrat `GestionPartenairesContrat` est introuvable).

## Review Focus

1. **Accès non autorisé** à chaque page et à chaque POST (anonyme, compte non admin du maître, POST sans jeton CSRF) : aucune écriture, aucune donnée affichée. → tâches 2 et 3.
2. **Texte hostile** (raison sociale, nom, e-mail contenant `<script>`, guillemets) : affiché comme texte partout (liste, formulaires réaffichés, messages). → tâche 3.
3. **Doublons et saisies invalides** (SIREN déjà connu, e-mail déjà utilisé, SIREN invalide, champ trop long, droit invalide, retrait du dernier droit du modèle) : message fixe, **rien n'est écrit**. → tâches 2 et 3.
4. **Identifiant de partenaire** non numérique, inconnu ou d'une société sans capacité partenaire : 404 ou 400 sans écriture, contrat non appelé pour le non numérique. → tâches 2 et 3.
5. **Modèle de droits** : un partenaire créé avant la modification garde ses droits ; un partenaire créé après reçoit le nouveau modèle. → tâche 3.
6. **Mise à jour à chaud et désinstallation** du plugin : tuile et pages disparaissent, plus de gabarit résolu. → tâche 3.

---

### Task 1 : Retirer l'interface Angular et les routes JSON du plugin

**Files:**
- Delete: `plugins-partenaires/frontend/` (tout), `plugins-partenaires/src/main/java/com/logicielpourtous/site/plugins/partenaires/PartenairesController.java`, `plugins-partenaires/src/test/java/com/logicielpourtous/site/plugins/partenaires/PartenairesControllerTest.java`, `plugins-partenaires/src/test/java/com/logicielpourtous/site/plugins/partenaires/FrontDansLeJarTest.java`
- Modify: `plugins-partenaires/pom.xml` (retirer `frontend-maven-plugin`, la copie vers `ui/`, la seconde exécution surefire de `FrontDansLeJarTest`, les dépendances devenues inutiles), `plugins-partenaires/.gitignore`, `plugins-partenaires/src/main/resources/plugin.properties` (icône `building`, chemin `/liste`)
- Test: `plugins-partenaires/src/test/java/com/logicielpourtous/site/plugins/partenaires/ManifestePartenairesTest.java` (mis à jour), `PluginSansFrontTest.java` (nouveau)

**Interfaces:**
- Produces: plugin dont le jar ne contient que `plugin.properties`, les classes du plugin et (tâche 2) `templates/plugins/partenaires/*.html` ; manifeste `entree.1=ADMIN|Partenaires|building|/liste|partenaires.gerer`. Le contrôleur de la tâche 2 doit exposer `GET /liste`.

- [ ] **Step 1: Écrire les tests en échec.** `ManifestePartenairesTest` : le manifeste lu par `ManifestePlugin.depuis` a exactement une entrée `ADMIN` d'icône `building`, de chemin `/liste` et de droit `partenaires.gerer`, aucun bloc, aucune extension. `PluginSansFrontTest` : `plugins-partenaires/pom.xml` ne contient pas `frontend-maven-plugin` ni `npm` ; `src/main/resources/` ne contient ni dossier `ui` ni fichier `.js` ; le répertoire `plugins-partenaires/frontend` n'existe pas ; aucune classe annotée `@RestController` dans `src/main/java` (parcours du code source par lecture de fichiers).
- [ ] **Step 2: Exécuter** `mvn -B -o -f plugins-partenaires/pom.xml test` (en arrière-plan) ; ÉCHEC attendu sur les deux classes.
- [ ] **Step 3: Implémenter** les suppressions et les modifications ci-dessus ; vérifier que `mvn -B -o -f plugins-partenaires/pom.xml package` réussit sans toucher npm.
- [ ] **Step 4: Exécuter** `mvn -B -o -f plugins-partenaires/pom.xml package` ; PASSE, jar créé, `unzip -l` ne montre ni `ui/` ni `node_modules`.
- [ ] **Step 5: Commit** `refactor(plugin-partenaires): retrait de l'interface Angular et des routes JSON`.

### Task 2 : Pages Thymeleaf du plugin

**Files:**
- Create: `plugins-partenaires/src/main/java/com/logicielpourtous/site/plugins/partenaires/PartenairesPagesController.java`
- Create: `plugins-partenaires/src/main/resources/templates/plugins/partenaires/liste.html`, `partenaire-form.html`, `client-form.html`, `modele.html`, `refuse.html`, `erreur.html`
- Test: `plugins-partenaires/src/test/java/com/logicielpourtous/site/plugins/partenaires/PartenairesPagesControllerTest.java` (MockMvc autonome, contrat simulé, sans rendu de gabarit), `GabaritsPartenairesTest.java`

**Interfaces:**
- Consumes: `GestionPartenairesContrat` (SDK) : `listerPartenaires() -> List<PartenaireResume>`, `creerPartenaire(CreationPartenaire) -> ResultatPartenaire` (issues `CREE`, `SIREN_DEJA_CONNU`, `EMAIL_DEJA_UTILISE`), `creerClientPourPartenaire(AnnuaireClientsContrat.CreationClient) -> AnnuaireClientsContrat.ResultatCreation` (issues `CREE`, `DEJA_CONNUE`, `EMAIL_DEJA_UTILISE`), `modeleDroits() -> Set<String>`, `remplacerModeleDroits(Set<String>)` ; `IllegalArgumentException` (demande invalide), `SecurityException` (appelant non autorisé).
- Produces: `@Controller PartenairesPagesController(GestionPartenairesContrat contrat)` ; routes (le noyau préfixe `/p/partenaires`) :
  - `GET /liste` → vue `plugins/partenaires/liste`, modèle `partenaires` (la liste du contrat), `titre`, `moduleActif="partenaires"`, paramètre facultatif `ok` ∈ {`partenaire`, `client`, `modele`} affiché comme message fixe de succès (toute autre valeur ignorée) ;
  - `GET /nouveau` → vue `partenaire-form` ; `POST /` (champs `siren`, `tva`, `raisonSociale`, `email`, `nom`) : `CREE` → redirection `/p/partenaires/liste?ok=partenaire` ; `SIREN_DEJA_CONNU` → 409, `EMAIL_DEJA_UTILISE` → 409 (vue `partenaire-form`, message fixe, saisie réaffichée) ; `IllegalArgumentException` → 400 (même vue, message fixe générique) ;
  - `GET /{partenaireId}/clients/nouveau` → vue `client-form` ; `POST /{partenaireId}/clients` (champs `particulier` (case), `siren`, `tva`, `email`, `nom`) → `creerClientPourPartenaire(new CreationClient(partenaireId, particulier, siren, tva, false, email, nom))` ; `CREE` → redirection `/p/partenaires/liste?ok=client` ; `DEJA_CONNUE` et `EMAIL_DEJA_UTILISE` → 409 avec les messages neutres du plugin clients (« société déjà connue, une demande d'accès a été envoyée à son manager », « cette adresse e-mail est déjà utilisée ») ; `IllegalArgumentException` → 400 ; identifiant non numérique → 404 sans appel au contrat ;
  - `GET /modele` → vue `modele`, modèle `droits` (liste triée) ; `POST /modele/ajouter` (champ `droit`) et `POST /modele/retirer` (champ `droit`) : calculent le nouvel ensemble à partir de `modeleDroits()` puis appellent `remplacerModeleDroits` ; refus (400, message fixe, aucun appel à `remplacerModeleDroits`) si le droit ne respecte pas `ManifestePlugin.DROIT_VALIDE` ou si le résultat serait vide ; succès → redirection `/p/partenaires/modele?ok=modele` (la page `modele` accepte aussi `ok=modele`) ;
  - `@ExceptionHandler(SecurityException.class)` → vue `refuse`, statut 403, texte fixe « Accès réservé à l'administrateur du serveur maître » ; `@ExceptionHandler(RuntimeException.class)` (autre que les précédentes) → vue `erreur`, statut 500, texte fixe sans détail.
  - Tous les gabarits : `layout:decorate="~{socle/layout}"`, contenu dans `layout:fragment="contenu"`, formulaires `th:action="@{/p/partenaires/…}"`, aucun `th:utext`.

- [ ] **Step 1: Écrire les tests en échec.** `PartenairesPagesControllerTest` (contrat simulé, `MockMvcBuilders.standaloneSetup`, vues résolues en noms bruts) : (a) `GET /liste` renvoie la vue et la liste du contrat ; `ok=client` donne le message de succès, `ok=<script>` aucun message ; (b) `POST /` avec issue `CREE` → redirection `/p/partenaires/liste?ok=partenaire` ; `SIREN_DEJA_CONNU`/`EMAIL_DEJA_UTILISE` → 409 et vue du formulaire avec message fixe et saisie dans le modèle ; `IllegalArgumentException("detail-interne")` → 400 sans `detail-interne` dans le modèle ; (c) pour chaque route, `SecurityException` du contrat → 403, vue `refuse`, corps sans détail ; `RuntimeException` → 500 vue `erreur` ; (d) `POST /{id}/clients` transmet **l'id du chemin** comme `partenaireSocieteId` même si le corps contient `partenaireSocieteId=999`, jamais un autre ; `particulier` absent → `false` ; messages 409 ; identifiants `abc`, `1;2`, `../x`, 16 chiffres → 404 et `verifyNoInteractions(contrat)` ; (e) modèle : ajouter `clients.exporter` appelle `remplacerModeleDroits` avec l'union ; retirer le dernier droit → 400 sans appel ; droit invalide (`Mauvais Droit`, vide, 61 caractères) → 400 sans appel ; retirer un droit absent → idempotent (appel avec le même ensemble ou aucun appel : choisir et tester). `GabaritsPartenairesTest` : chaque vue citée existe en `templates/plugins/partenaires/<vue>.html` dans les ressources, décore `socle/layout`, ne contient ni `th:utext` ni `<script`, et chaque `<form` en méthode POST utilise `th:action`.
- [ ] **Step 2: Exécuter** `mvn -B -o -f plugins-partenaires/pom.xml test` (en arrière-plan) ; ÉCHEC attendu : classe et gabarits absents.
- [ ] **Step 3: Implémenter** le contrôleur et les six gabarits (listes et formulaires Bootstrap avec les classes du socle ; la liste affiche identifiant, SIREN, raison sociale, état et un lien « Créer un client » par ligne vers `/p/partenaires/<id>/clients/nouveau`, plus les boutons « Créer un partenaire » et « Modèle de droits »).
- [ ] **Step 4: Exécuter** les deux classes puis `mvn -B -o -f plugins-partenaires/pom.xml package` ; PASSENT.
- [ ] **Step 5: Commit** `feat(plugin-partenaires): pages Thymeleaf de gestion des partenaires`.

### Task 3 : Test de bout en bout du lot B

**Files:**
- Create: `src/test/java/com/logicielpourtous/site/features/plateforme/PluginPartenairesIT.java`, `src/test/java/com/logicielpourtous/site/features/plateforme/JarPartenairesReel.java` (même rôle que `JarClientsReel` : localise `plugins-partenaires/target/partenaires-1.0.0.jar`, **échoue avec la commande de build** s'il est absent, puis le signe avec la clé de test comme `JarDePluginDeTest.signer`)

**Interfaces:**
- Consumes: le jar réel de la tâche 2 ; `PluginManager.installer/desinstaller` ; le modèle de `PluginSocleIT` (contexte complet, vraie chaîne de sécurité, `app.role=MASTER`, `app.plateforme.actif=true`, secret JWT de 64 caractères et `captcha.enabled=false` via `@DynamicPropertySource`, `@Primary ClesPubliques` de test, utilisateurs authentifiés par `user(...)`) ; services du noyau (`CreationPartenaireService` via le contrat, `DroitsService`, `ManagersService`, dépôts) pour les vérifications en base.

- [ ] **Step 1: Écrire le test.** Contexte complet ; jar installé dans `@BeforeEach`, désinstallé dans `@AfterEach` ; comptes : un admin du maître (rôle global `ROLE_ADMIN`), un utilisateur non admin (gestionnaire d'une société). Cas : (a) `/accueil` de l'admin du maître contient le lien `/p/partenaires/liste` (tuile `ADMIN`), celui du non admin non ; anonyme → 302 `/login` ; (b) accès : `GET /p/partenaires/liste` par le non admin → 403 avec le texte fixe et sans donnée ; `POST /p/partenaires/` par le non admin → 403 et aucune ligne créée ; anonyme → 302 ; `POST` sans jeton CSRF par l'admin → 403 ; (c) l'admin crée un partenaire par `POST /p/partenaires/` (SIREN valide neuf, e-mail neuf, jeton CSRF) → 302 `/p/partenaires/liste?ok=partenaire` ; la liste affiche sa raison sociale ; en base : société `PARTENAIRE` rattachée à un tenant propriétaire, schéma `p_*` existant, premier utilisateur manager (`ROLE_GESTIONNAIRE`) dans ce tenant, plafond et réglage du manager égaux au modèle, état `ACTIF` ; (d) doublons : même SIREN → 409, même e-mail → 409, SIREN invalide → 400, raison sociale de plus de 255 caractères → 400 ; **aucune** ligne, aucun tenant, aucun schéma en plus ; (e) texte hostile : raison sociale `<script>alert(1)</script>` et nom `"><img src=x onerror=1>` acceptés s'ils passent la validation : la page de liste et le formulaire réaffiché montrent le texte échappé (jamais la balise brute) ; (f) client pour ce partenaire : `POST /p/partenaires/{id}/clients` (société avec SIREN neuf, puis particulier) → 302 ; lien d'origine `PARTENAIRE` vers le partenaire cible, utilisateur du client dans le **tenant du partenaire** (pas celui de l'admin), ligne de journal `CLIENT_CREE_POUR_PARTENAIRE` ; id `abc` → 404 ; id d'une société sans capacité partenaire → 400 et rien d'écrit ; (g) boucle du lot B : le premier manager du partenaire créé en (c) a `DroitsService.autorise(userId, societeId, "clients.creer")` vrai, et `AnnuaireClientsContrat.creerClient(new CreationClient(societeId, false, <siren neuf>, null, false, <email neuf>, "Client test"))` réussit (`CREE`) — c'est le cas qu'aucun partenaire sans tenant ne pouvait satisfaire avant le lot B ; (h) modèle : `POST /p/partenaires/modele/ajouter` (`clients.exporter`) puis création d'un nouveau partenaire : il a ce droit, alors que le partenaire de (c) ne l'a pas ; retirer tous les droits un à un jusqu'au dernier : le dernier retrait est refusé (400) ; droit invalide refusé ; restaurer le modèle d'origine à la fin ; (i) mise à jour à chaud (une seconde version du jar avec un libellé de tuile différent, dérivée du jar réel) : la nouvelle version est servie, sans erreur de gabarit ; désinstallation : tuile absente, `/p/partenaires/liste` → 404, gabarits plus résolus. Nettoyer **tout** : utilisateurs, sociétés, appartenances, capacités, liens, plafonds, réglages, états, lignes de journal, tenants et schémas `p_*` créés, modèle de droits restauré.
- [ ] **Step 2: Construire le jar** (`mvn -B -o -f plugins-partenaires/pom.xml package`, en arrière-plan) puis exécuter `-Dtest=PluginPartenairesIT` (en arrière-plan) ; les cas déjà satisfaits passent d'emblée : prouver chacun en le rendant fautif un instant (puis annuler, ne jamais commiter une mutation) : (1) neutraliser la garde « admin du maître » dans `GestionPartenairesContratImpl.adminDuMaitre()` → (b) doit échouer ; (2) faire créer le partenaire sans tenant dans `CreationPartenaireService` (ou rendre la copie du modèle vide) → (c) ou (g) doit échouer ; (3) remplacer `th:text` par `th:utext` dans `liste.html` → (e) doit échouer ; (4) retirer `th:action` d'un formulaire POST → (b) ou (c) doit échouer (jeton CSRF absent). Consigner chaque mutation et son résultat dans le rapport.
- [ ] **Step 3: Corriger** ce que le test révèle (chaque correction de code de production dans son propre commit avec son test).
- [ ] **Step 4: Exécuter, chacune seule :** `PluginPartenairesIT`, `PluginSocleIT`, `PluginManagerIT`, `FuiteMemoireIT`, `GestionPartenairesContratTest`, `CreationPartenaireServiceTest`, `ContratsClientsTest`, `SecurityPlateformeTest`, `SocleSecuriteWebTest`, `ModularityTests`, `IdentiteIndependanceTest`, `InscriptionClientTest`, `ServeurAccueilServiceTest` ; puis `mvn -B -o -f plugins-partenaires/pom.xml test` ; PASSENT.
- [ ] **Step 5: Commit** `test(plateforme): vérification de bout en bout du plugin partenaires`.

---

## Auto-revue

- **Couverture de la spec du lot B :** pages de création de partenaire, de client pour un partenaire, de liste et d'édition du modèle de droits (T2) ; refus pour tout autre que l'admin du maître (T2, T3) ; tenant et schéma créés avec le partenaire, droits copiés du modèle, premier manager, état actif (T3 c) ; client dans le tenant du partenaire cible avec journal (T3 f) ; partenaire créé en mesure de créer des clients (T3 g) ; modèle non rétroactif (T3 h) ; tuile d'administration réservée à l'admin du maître (T3 a) ; plus d'interface Angular ni de build npm (T1).
- **Hors périmètre respecté :** inscription autonome des partenaires, états `LIMITE`/`BLOQUE`, écran fin des plafonds par partenaire, interface du plugin `clients` (projet 2 du socle).
- **Cohérence des noms :** `PartenairesPagesController`, routes `/liste`, `/nouveau`, `/{id}/clients/nouveau`, `/modele`, `/modele/ajouter`, `/modele/retirer`, vues `plugins/partenaires/*` définies en T2 et réutilisées à l'identique en T3 ; le chemin de manifeste `/liste` (T1) correspond à `GET /liste` (T2).

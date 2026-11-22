# Socle du front Thymeleaf + HTMX — Plan d'implémentation

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal :** Fournir le socle du front serveur (Thymeleaf + HTMX + Bootstrap 5.3 thémé) : mise en page unique façon Odoo, gabarits réutilisables, règles HTMX, accueil à tuiles, thème réglable par gabarit par l'admin du serveur, pages/gabarits livrés par les plugins sous `/p/<nom>/**` et emplacements d'extension (boutons, onglets) déclarés dans leurs manifestes.

**Architecture :** Un nouveau gabarit de mise en page `socle/layout` cohabite avec les cinq mises en page existantes (inchangées). Le thème est un jeu de variables CSS : valeurs par défaut dans l'application (fichier versionné), réglages du serveur en base, servi en `/css/theme.css`. Les plugins livrent des gabarits dans leur jar : leurs contrôleurs `@Controller` sont routés sous `/p/<nom>/**` (chaîne web à session, CSRF obligatoire) par le même `RoutagePlugins` que les routes JSON.

**Tech Stack :** Java 25, Spring Boot 4.0.2, Thymeleaf + layout dialect + Spring Security dialect, HTMX 1.9.10, Bootstrap 5.3.0 et Bootstrap Icons (fichiers locaux, aucun Node), PostgreSQL + Flyway.

**Spec :** `docs/superpowers/specs/2026-11-22-socle-front-thymeleaf-htmx-design.md`

## Global Constraints

- Aucun build front (npm, Node, Tailwind), aucun CDN, aucun Alpine.js : tous les fichiers (Bootstrap **5.3.0**, HTMX **1.9.10**, Bootstrap Icons, polices) sont servis depuis le projet, sous des chemins déjà ouverts par la sécurité web : `/css/**`, `/js/**`, `/images/**`.
- Les cinq mises en page existantes (`layout`, `intra-layout`, `admin-layout`, `espace-layout`, `boutique-layout`) et les 216 gabarits qui les utilisent ne sont **pas modifiés**.
- Valeurs de thème **validées strictement**, jamais de CSS libre : couleur `^#[0-9a-fA-F]{6}$` ; épaisseur de trait 0 à 4 px ; arrondi 0 à 24 px (tuiles 0 à 32) ; taille de texte 12 à 18 px ; police prise dans la liste des polices hébergées ; énumérations fermées ; clés prises dans une liste fermée.
- Chemins imposés : thème `/css/theme.css` (public) ; accueil `/accueil` (authentifié, règle explicite **avant** la règle `/{slug}` qui ouvre à tous toute adresse d'une seule section) ; apparence `/admin/apparence` (`hasRole("ADMIN")`, déjà couvert par `/admin/**`) ; pages de plugin `/p/<nom>/**` ; gabarits de plugin préfixés `plugins/<nom>/…`.
- CSRF : exigé sur toute requête non-GET de `/p/**` (règle de préfixe, sans liste) et de `/admin/apparence/**` et `/admin/socle/**` (ajoutés à `csrfCible()` dans `SecurityConfig`).
- Une seule migration Flyway, **V18** (V11 à V17 sont pris sur les branches des lots A et B) ; ne jamais modifier une migration appliquée (créer V19 si une colonne manque).
- Le dépôt GitHub est **public** : aucune faiblesse non corrigée dans un message de commit. Commits en français, ligne finale `Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>`. Rien n'est poussé sans demande explicite.

## Notes d'exécution (poste Windows, 8 Go de mémoire, sans `.mvn` dans le worktree)

- Maven : `~/.m2/wrapper/dists/apache-maven-3.9.9/3477a4f1/bin/mvn`, `JAVA_HOME=C:\Users\nicolas\scoop\apps\temurin25-jdk\current`, `MAVEN_OPTS=-Xmx768m`, options `-B -o -Dskip.npm=true -Dtest=<Classe> -DfailIfNoTests=false test`, **une classe de test par invocation**, chaque commande lourde **en arrière-plan avec la sortie dans un fichier**, jamais deux commandes lourdes en parallèle (la session a planté plusieurs fois par manque de mémoire).
- PostgreSQL local à démarrer à la main s'il refuse les connexions : `pg_ctl start -D C:\Users\nicolas\scoop\persist\postgresql\data`. Les tests qui créent des lignes les suppriment ; `FuiteMemoireIT` et `PluginManagerIT` restent verts.
- Après chaque exécution Maven : `git checkout -- src/main/resources/static/app` et supprimer les fichiers générés non suivis de ce dossier. Python n'est pas installé.
- Les fichiers tiers (Bootstrap, HTMX, icônes, polices) sont téléchargés **une fois** avec `curl` depuis la version publiée exacte, jamais via npm ; un `README` à côté des fichiers note version, URL d'origine, licence et somme SHA-256.

## Review Focus

1. **`/accueil` ouvert à tous par la règle `/{slug}`** : un anonyme doit être redirigé vers `/login`. → tâche 4.
2. **Injection de CSS par le thème** (`red;}body{display:none`, `url(javascript:…)`, `</style>`, valeurs énormes, clé inconnue, police hors liste), y compris par l'aperçu en direct : refus, rien d'écrit, le CSS généré ne contient que les variables autorisées. → tâches 5 et 6.
3. **CSRF** : POST/PUT/DELETE sans jeton refusé sur `/p/**`, `/admin/apparence/**`, `/admin/socle/**` ; avec jeton accepté ; le jeton est envoyé par HTMX automatiquement. → tâches 3, 6, 7.
4. **Session expirée pendant un appel HTMX** : l'utilisateur est renvoyé vers `/login` par l'en-tête `HX-Redirect`, jamais la page de connexion insérée dans un fragment (le code actuel compare `HX-Request` à `"htmx-request"` au lieu de `"true"`). → tâche 3.
5. **Un plugin ne masque ni n'atteint le noyau ou un autre plugin** : un nom de vue hors de `plugins/<nom>/` ou contenant `..` n'est jamais résolu depuis un plugin. → tâche 8.
6. **Mise à jour à chaud et désinstallation** : gabarit périmé jamais servi, fichiers statiques de l'ancienne version non servis, aucune fuite de chargeur de classes. → tâches 8 et 10.
7. **Tuiles et pages de plugin pour un utilisateur non autorisé** : la zone `ADMIN` n'apparaît qu'à l'admin du maître (lot B) ; une page `/p/<nom>/…` sans session est redirigée vers `/login`. → tâches 4, 7 et 10.
8. **Emplacements d'extension** : un élément dont le droit manque, un plugin retiré ou un identifiant d'entité non numérique (`1;2`, `../x`, vide, trop long) n'est jamais rendu ; le libellé d'un plugin est affiché en texte ; la route du plugin vérifie elle-même droits et appartenance (le filtre d'affichage n'est pas une protection). → tâches 9 et 10.

---

### Task 1 : Fichiers locaux et mise en page de base

**Files:**
- Create: `src/main/resources/static/css/vendor/bootstrap.min.css` (5.3.0), `static/js/vendor/bootstrap.bundle.min.js` (5.3.0), `static/js/vendor/htmx.min.js` (1.9.10), `static/css/vendor/bootstrap-icons.min.css` + `static/css/vendor/fonts/bootstrap-icons.woff2` (1.11.x), `static/css/vendor/README.md`
- Create: `src/main/resources/static/css/socle.css`, `static/js/socle.js`
- Create: `src/main/resources/templates/socle/layout.html`, `templates/socle/fragments/topbar.html`
- Test: `src/test/java/com/logicielpourtous/site/features/ui/SocleLayoutTest.java` (rendu Thymeleaf autonome)

**Interfaces:**
- Produces: la mise en page `socle/layout` s'utilise par `layout:decorate="~{socle/layout}"` avec le contenu dans `layout:fragment="contenu"`. Variables de modèle lues : `titre` (String), `tuiles` (`List<NavigationService.Tuile>` ou liste vide), `moduleActif` (String, facultatif). Elle charge, dans cet ordre : `/css/vendor/bootstrap.min.css`, `/css/vendor/bootstrap-icons.min.css`, `/css/socle.css`, `/css/theme.css` ; `/js/vendor/bootstrap.bundle.min.js`, `/js/vendor/htmx.min.js`, `/js/socle.js`. Elle contient `<meta name="_csrf" th:content="${_csrf.token}">` et `<meta name="_csrf_header" th:content="${_csrf.headerName}">` (jetons absents du modèle : balises omises sans erreur) et un conteneur `#toasts` pour les messages. `socle.css` n'utilise que des variables `--socle-*` (valeurs de repli dans le fichier, remplacées par `theme.css`) pour la barre supérieure (hauteur 46 px par défaut), la zone de panneau de contrôle et la zone de contenu.

- [ ] **Step 1: Écrire les tests en échec** (`SocleLayoutTest`, moteur Thymeleaf autonome avec `ClassLoaderTemplateResolver` sur `templates/`, `LayoutDialect`, contexte sans Spring Security) : (a) le rendu d'une page de test qui décore `socle/layout` contient les sept références de fichiers ci-dessus dans l'ordre ; (b) **aucune** URL absolue (`http://` ou `https://`) dans les attributs `href` et `src` ; (c) chaque fichier référencé existe dans le chemin de classes ; (d) avec un `_csrf` dans le contexte, les deux balises meta sont présentes avec les bonnes valeurs ; sans, aucune erreur ; (e) deux tuiles de test apparaissent comme liens dans la barre supérieure avec leur libellé, texte échappé : un libellé `<b>x</b>` est affiché tel quel ; (f) `titre` sert de `<title>`.
- [ ] **Step 2: Exécuter** `-Dtest=SocleLayoutTest` ; ÉCHEC attendu (gabarits absents).
- [ ] **Step 3: Télécharger** les fichiers tiers (curl, versions exactes, somme SHA-256 dans `README.md`), puis écrire `socle.css` (variables de repli, barre supérieure compacte, zone de contenu), `socle.js` (vide d'abord, complété à la tâche 3), `layout.html` et `fragments/topbar.html` (le sélecteur d'applications est un menu de tuiles ; le menu du module actif est une liste de liens passée par le modèle).
- [ ] **Step 4: Exécuter** `SocleLayoutTest` ; PASSE.
- [ ] **Step 5: Commit** `feat(front): mise en page du socle et fichiers locaux`.

### Task 2 : Gabarits réutilisables (panneau de contrôle, liste, fiche, tuiles)

**Files:**
- Create: `src/main/resources/templates/socle/fragments/control-panel.html`, `list-view.html`, `form-view.html`, `status-bar.html`, `tile-grid.html`
- Create: `src/main/java/com/logicielpourtous/site/features/ui/SocleStyleguideController.java`, `src/main/resources/templates/socle/styleguide.html`
- Modify: `src/main/resources/static/css/socle.css` (composants : liste, fiche, barre de statut, tuiles)
- Test: `src/test/java/com/logicielpourtous/site/features/ui/SocleFragmentsTest.java`

**Interfaces:**
- Produces: fragments appelés par `th:replace="~{socle/fragments/<nom> :: <nom>(…)}"` : `control-panel(titre, fil, actions, recherche)` (fil = liste de `{libelle, url}` ; recherche = `{url, cible}` ou nul : le champ porte `hx-get`, `hx-trigger="keyup changed delay:300ms"`, `hx-target`, `hx-push-url="false"`) ; `list-view(colonnes, lignes, urlLigne)` (colonnes = `{cle, libelle, tri}` ; cellules échappées) ; `status-bar(etats, courant)` (états = liste de `{code, libelle}` ; l'état courant est marqué `aria-current="step"`) ; `form-view(titre, statut, actions, contenu)` ; `tile-grid(tuiles)` (chaque tuile : icône, libellé, lien). Un contrôleur de démonstration réservé à l'admin sert `GET /admin/socle` (page complète) et `GET /admin/socle/liste?q=` (fragment de liste filtrée, renvoyé seul quand l'en-tête `HX-Request: true` est présent).

- [ ] **Step 1: Écrire les tests en échec** (`SocleFragmentsTest`, rendu autonome) : (a) la barre de statut marque exactement l'état courant et aucun autre ; (b) la vue liste échappe toute donnée (`<img src=x onerror=…>` reste du texte) ; (c) le champ de recherche porte les attributs `hx-*` ci-dessus ; (d) la grille de tuiles rend un lien par tuile et un message « Aucune application disponible » si la liste est vide ; (e) la vue fiche n'affiche un bouton d'action que si l'action est présente dans le modèle ; (f) test du contrôleur (`MockMvc` autonome) : requête avec `HX-Request: true` → uniquement le fragment de liste (pas de `<html`), sans l'en-tête → page complète.
- [ ] **Step 2: Exécuter** `SocleFragmentsTest` ; ÉCHEC attendu.
- [ ] **Step 3: Implémenter** les fragments, le CSS des composants et le contrôleur de démonstration (données factices en dur, aucune base).
- [ ] **Step 4: Exécuter** `SocleFragmentsTest` et `SocleLayoutTest` ; PASSENT.
- [ ] **Step 5: Commit** `feat(front): gabarits réutilisables du socle`.

### Task 3 : Règles HTMX, session expirée et CSRF

**Files:**
- Modify: `src/main/java/com/logicielpourtous/site/config/SecurityConfig.java` (point d'entrée d'authentification de la chaîne web ; `csrfCible()` : ajouter `/admin/socle/**`)
- Modify: `src/main/resources/static/js/socle.js`
- Test: `src/test/java/com/logicielpourtous/site/config/SocleSecuriteWebTest.java` (`@SpringBootTest` avec `MockMvc` et la vraie chaîne de sécurité, sur le modèle de `SecurityPlateformeTest`)

**Interfaces:**
- Produces: dans `socle.js` : (1) sur l'événement `htmx:configRequest`, l'en-tête indiqué par la balise `_csrf_header` reçoit la valeur de `_csrf` pour toute requête non-GET ; (2) sur `htmx:responseError` et `htmx:sendError`, un message est ajouté dans `#toasts` (texte générique, jamais le corps de la réponse) ; (3) une réponse portant l'en-tête `HX-Redirect` est suivie par HTMX (comportement natif). Côté serveur : une requête non authentifiée portant `HX-Request: true` reçoit **200** avec l'en-tête `HX-Redirect: /login` ; sans cet en-tête : redirection 302 vers `/login`, comme aujourd'hui.

- [ ] **Step 1: Écrire les tests en échec** (`SocleSecuriteWebTest`) : (a) `GET /admin/socle` anonyme avec `HX-Request: true` → 200 + `HX-Redirect: /login` et corps vide ; (b) même requête sans l'en-tête → 302 vers `/login` ; (c) `POST /admin/socle/liste` (route de démonstration ajoutée pour ce test si besoin) sans jeton CSRF avec un compte admin → 403 ; avec le jeton → pas 403 ; (d) un compte non admin reçoit 403 sur `/admin/socle`. Le test (a) doit échouer avant la correction parce que le code actuel compare l'en-tête à `"htmx-request"`.
- [ ] **Step 2: Exécuter** `-Dtest=SocleSecuriteWebTest` ; ÉCHEC attendu sur (a) et (c).
- [ ] **Step 3: Implémenter** la correction du point d'entrée (comparer `HX-Request` à `"true"`), l'ajout à `csrfCible()` et le script `socle.js`.
- [ ] **Step 4: Exécuter** `SocleSecuriteWebTest`, `SecurityPlateformeTest` ; PASSENT.
- [ ] **Step 5: Commit** `fix(securite): redirection HTMX en cas de session expirée, CSRF et messages d'erreur du socle`.

### Task 4 : Accueil à tuiles `/accueil`

**Files:**
- Create: `src/main/java/com/logicielpourtous/site/features/plateforme/AccueilController.java`, `src/main/resources/templates/socle/accueil.html`
- Modify: `src/main/java/com/logicielpourtous/site/config/SecurityConfig.java` (règle `.requestMatchers("/accueil").authenticated()` placée **avant** `/{slug:…}`)
- Test: `src/test/java/com/logicielpourtous/site/features/plateforme/AccueilControllerTest.java` (`@SpringBootTest` + `MockMvc` + vraie chaîne)

**Interfaces:**
- Consumes: `NavigationService.tuilesAutorisees(Long societeId)` → `List<Tuile>` (`plugin`, `libelle`, `icone`, `chemin`, `zone`, `version`) ; `AccueilController` reste dans le paquet `plateforme` (le module `plateforme` ne dépend que de `commun`).
- Produces: `GET /accueil` → page `socle/accueil` décorant `socle/layout`, avec la grille de tuiles ; le lien de chaque tuile est `/p/<plugin><chemin>` ; sans tuile : « Aucune application disponible » ; `GET /accueil` avec `HX-Request: true` renvoie seulement la grille. Un appel qui lève une exception à la lecture des tuiles affiche la page avec le message vide (échec fermé), pas une erreur 500.

- [ ] **Step 1: Écrire les tests en échec** : (a) **anonyme → 302 vers `/login`** (et non 200) ; (b) utilisateur authentifié avec une tuile PARTENAIRE : la grille contient le lien `/p/clients/clients` (plugin de test) ; (c) l'admin du maître (rôle `ROLE_ADMIN`, serveur MASTER) voit aussi la tuile `ADMIN` d'un plugin de test, un utilisateur ordinaire non ; (d) aucune tuile → message ; (e) le service de navigation lève → message, 200 ; (f) requête HTMX → fragment seul.
- [ ] **Step 2: Exécuter** `-Dtest=AccueilControllerTest` ; ÉCHEC attendu (le cas (a) échoue en 200 si la règle explicite manque : à vérifier avant d'implémenter la règle).
- [ ] **Step 3: Implémenter** le contrôleur, le gabarit et la règle de sécurité.
- [ ] **Step 4: Exécuter** `AccueilControllerTest` et `SecurityPlateformeTest` ; PASSENT.
- [ ] **Step 5: Commit** `feat(front): accueil à tuiles côté serveur`.

### Task 5 : Thème — réglages, validation et fichier `/css/theme.css`

**Files:**
- Create: `src/main/resources/db/migration/V18__theme_reglages.sql`
- Create: `src/main/resources/theme/theme-defaut.properties`
- Create (paquet `com.logicielpourtous.site.features.theme`) : `ThemeReglage.java`, `ThemeReglageRepository.java`, `ThemeJournal.java`, `ThemeJournalRepository.java`, `ThemeParDefautSource.java`, `ThemeParDefautFichier.java`, `ThemeService.java`, `ThemeValeurs.java`, `ThemeCssController.java`
- Create: polices hébergées `static/css/fonts/*.woff2` (au moins 3 polices à licence ouverte, fichiers de licence joints)
- Test: `src/test/java/.../features/theme/ThemeValeursTest.java`, `ThemeServiceTest.java`, `ThemeCssControllerTest.java`

**Interfaces:**
- Produces:
  - Table `theme_reglages(cle VARCHAR(60) PRIMARY KEY, valeur VARCHAR(40) NOT NULL, modifie_par BIGINT, modifie_a TIMESTAMPTZ NOT NULL DEFAULT now())` et `theme_journal(id BIGSERIAL PRIMARY KEY, cle VARCHAR(60) NOT NULL, ancienne_valeur VARCHAR(40), nouvelle_valeur VARCHAR(40), modifie_par BIGINT, cree_a TIMESTAMPTZ NOT NULL DEFAULT now())` ; pas de clé étrangère sur le journal ; idempotent.
  - Clés autorisées, groupées par gabarit (toutes en minuscules, variable CSS `--socle-<clé>`) : **général** `couleur-primaire`, `couleur-secondaire`, `couleur-fond`, `couleur-texte`, `couleur-bordure` (couleurs), `police` (identifiant de la liste), `taille-texte` (12–18), `epaisseur-trait` (0–4), `arrondi` (0–24) ; **barre supérieure** `topbar-fond`, `topbar-texte` (couleurs), `topbar-hauteur` (36–64) ; **tuiles** `tuile-fond` (couleur), `tuile-arrondi` (0–32), `tuile-taille-icone` (24–64) ; **liste** `liste-densite` (`compact`|`normal`|`aere`), `liste-lignes-alternees` (`oui`|`non`), `liste-entete-fond` (couleur) ; **fiche** `fiche-statut-style` (`chevron`|`pilule`), `fiche-statut-actif-fond` (couleur) ; **boutons** `bouton-arrondi` (0–24), `bouton-primaire-fond` (couleur). Une valeur par défaut peut être une **référence** `ref:<clé>` (par exemple `topbar-fond = ref:couleur-primaire`), rendue en `var(--socle-<clé>)` : c'est ainsi qu'un groupe « hérite du général ».
  - `ThemeParDefautSource.valeurs() -> Map<String,String>` (interface, pour une future source plugin) ; `ThemeParDefautFichier` la lit dans `theme/theme-defaut.properties` (toutes les clés ci-dessus y figurent, valeurs valides ; palette par défaut moderne choisie par le constructeur, polices : au moins l'identifiant `systeme` = pile système, plus les polices hébergées).
  - `ThemeValeurs.valider(String cle, String valeur) -> String` (valeur normalisée ; `IllegalArgumentException` générique sans écho de la valeur si clé inconnue ou valeur hors règle).
  - `ThemeService` : `Map<String,Etat> reglages()` (valeur effective, origine `HERITE`|`MODIFIE`), `void definir(Long adminId, String cle, String valeur)` (valide puis écrit et journalise, atomique), `void reinitialiser(Long adminId, String cle)`, `void toutReinitialiser(Long adminId)`, `String versionCss()` (identifiant dérivé du fichier par défaut et de la date du dernier réglage), `String css()` (bloc `:root{--socle-…:…;}` de toutes les clés, références rendues en `var(…)`).
  - `GET /css/theme.css` : `Content-Type: text/css;charset=UTF-8`, `ETag` = `versionCss()`, `Cache-Control: no-cache` (revalidation par ETag), `X-Content-Type-Options: nosniff` ; 304 si `If-None-Match` correspond.

- [ ] **Step 1: Écrire les tests en échec.** `ThemeValeursTest` : valeurs valides acceptées (`#1A2b3C`, `0`, `4`, `24`, `18`, `compact`, `systeme`) ; **refusées** : `red;}body{display:none`, `#12345`, `#1234567`, `url(javascript:alert(1))`, `</style>`, `5` pour `epaisseur-trait`, `25` pour `arrondi`, `19` pour `taille-texte`, `99999999999999999999`, une police absente de la liste, une clé inconnue, une valeur vide, une valeur de plus de 40 caractères ; le message d'erreur ne contient jamais la valeur reçue. `ThemeServiceTest` (base) : par défaut tout est `HERITE` ; `definir` écrit, journalise (clé, ancienne et nouvelle valeur, administrateur) et passe l'origine à `MODIFIE` ; une valeur invalide n'écrit ni réglage ni journal ; `reinitialiser` rend la valeur par défaut ; si la source par défaut change (source simulée), les clés `HERITE` suivent immédiatement et les clés `MODIFIE` restent ; une référence `ref:` est rendue `var(--socle-…)` ; le CSS généré ne contient que des variables `--socle-*` de la liste, et **aucune** valeur saisie ne peut y introduire `;`, `{`, `}`, `<` ou `url(` ; `versionCss()` change après chaque modification. `ThemeCssControllerTest` : 200 anonyme avec les en-têtes ci-dessus, 304 sur `If-None-Match`, ETag différent après `definir`. Nettoyer les lignes créées.
- [ ] **Step 2: Exécuter** les trois classes, chacune seule ; ÉCHEC attendu.
- [ ] **Step 3: Implémenter** la migration, les entités, les services et le contrôleur ; télécharger et joindre les polices avec leurs licences (somme SHA-256 notée dans `static/css/fonts/README.md`).
- [ ] **Step 4: Exécuter** les trois classes + `-Dtest=FlywayMigrationTest` ; PASSENT.
- [ ] **Step 5: Commit** `feat(front): réglages de thème validés et fichier de thème`.

### Task 6 : Écran « Apparence » par gabarit

**Files:**
- Create: `src/main/java/.../features/theme/ApparenceController.java`, `src/main/resources/templates/admin/apparence.html`, `templates/admin/apparence-groupe.html` (fragment par groupe), `templates/admin/apparence-apercu.html` (fragment d'aperçu)
- Modify: `src/main/java/com/logicielpourtous/site/config/SecurityConfig.java` (`csrfCible()` : ajouter `/admin/apparence/**`)
- Test: `src/test/java/.../features/theme/ApparenceControllerTest.java` (`@SpringBootTest` + `MockMvc` + vraie chaîne)

**Interfaces:**
- Consumes: `ThemeService` (tâche 5), fragments du socle (tâche 2).
- Produces: `GET /admin/apparence` (page complète : un onglet par groupe — Général, Barre supérieure, Tuiles, Liste, Fiche, Boutons —, chaque réglage avec son contrôle : sélecteur de couleur, curseur à bornes, liste de polices, liste fermée, case ; l'origine « hérité » ou « modifié » affichée ; bouton « Revenir à la valeur par défaut » par réglage et pour l'ensemble) ; `POST /admin/apparence/apercu` (valeurs en cours de saisie → fragment d'aperçu du groupe affiché avec des variables `--socle-*` **posées uniquement après validation par `ThemeValeurs`**, jamais le texte saisi tel quel ; valeur invalide → fragment d'erreur, aucun aperçu, aucune écriture) ; `POST /admin/apparence/{cle}` (enregistre) ; `POST /admin/apparence/{cle}/reinitialiser` ; `POST /admin/apparence/reinitialiser` (tout). Les identifiants d'administrateur journalisés viennent de la session, jamais de la requête.

- [ ] **Step 1: Écrire les tests en échec** : (a) anonyme → redirigé ; utilisateur non admin → 403 ; admin → 200 avec un onglet par groupe ; (b) chaque POST sans jeton CSRF → 403 ; avec jeton → traité ; (c) enregistrement valide : 200/redirection, valeur visible dans `GET /css/theme.css` et origine `MODIFIE` ; (d) valeur hostile (`red;}body{display:none`) : 400 avec message générique, **aucune** ligne de réglage ni de journal créée, `theme.css` inchangé ; (e) l'aperçu avec une valeur hostile ne renvoie aucun style et aucun écho de la valeur ; l'aperçu avec une valeur valide ne persiste rien ; (f) réinitialisation d'un réglage puis de tout : retour aux valeurs par défaut ; (g) le texte affiché (libellés, messages) est échappé.
- [ ] **Step 2: Exécuter** `-Dtest=ApparenceControllerTest` ; ÉCHEC attendu.
- [ ] **Step 3: Implémenter** le contrôleur et les gabarits (HTMX : aperçu à chaque changement de contrôle par `hx-post` avec `hx-trigger="change"`, enregistrement par bouton).
- [ ] **Step 4: Exécuter** `ApparenceControllerTest`, `ThemeServiceTest`, `SocleSecuriteWebTest` ; PASSENT.
- [ ] **Step 5: Commit** `feat(front): écran Apparence par gabarit pour l'admin du serveur`.

### Task 7 : Pages de plugin sous `/p/<nom>/**` (routage et sécurité)

**Files:**
- Modify: `src/main/java/com/logicielpourtous/site/features/plateforme/RoutagePlugins.java` (préfixe `/p/`, `nomDuPlugin`, mapping à deux préfixes, `getHandler`), `FiltreEnVol.java` (prend les deux préfixes via `nomDuPlugin`), `src/main/java/com/logicielpourtous/site/config/SecurityConfig.java` (règle `/p/**` authentifiée avant `anyRequest()` ; CSRF pour toute requête non-GET de `/p/**`)
- Test: `RoutagePluginsTest` et `PluginManagerIT` (étendus), `SocleSecuriteWebTest` (étendu)

**Interfaces:**
- Produces: dans `RoutagePlugins` : `static final String PREFIXE_PAGES = "/p/"` ; `nomDuPlugin(String chemin)` reconnaît `/api/plugins/<nom>/…` **et** `/p/<nom>/…` ; `creerMapping(gen)` enregistre **deux préfixes disjoints** sur le même mapping du plugin : `/api/plugins/<nom>` pour les classes `@RestController`, `/p/<nom>` pour les classes `@Controller` qui ne sont pas des `@RestController` ; la règle « seules les classes chargées par le chargeur de la génération sont des routes » est inchangée ; l'en-tête `X-Plugin`, la maintenance (503) et la génération annoncée par `FiltreEnVol` s'appliquent aux deux préfixes ; la réservation `/api/plugins/*/ui/**` est inchangée. Sécurité : toute requête vers `/p/**` exige une session authentifiée (redirection `/login`, ou `HX-Redirect` pour HTMX) et, pour les méthodes autres que GET/HEAD/TRACE/OPTIONS, un jeton CSRF.

- [ ] **Step 1: Écrire les tests en échec.** `RoutagePluginsTest` : `nomDuPlugin("/p/clients/liste")` → `clients`, `nomDuPlugin("/p")` et `nomDuPlugin("/p/")` → nul, les cas existants `/api/plugins/…` inchangés ; un contrôleur `@RestController` de plugin est joignable sous `/api/plugins/<nom>/…` et **pas** sous `/p/<nom>/…` ; un `@Controller` de plugin est joignable sous `/p/<nom>/…` et **pas** sous `/api/plugins/<nom>/…` ; un contrôleur du noyau déclaré dans le contexte du plugin n'est routé sous aucun des deux préfixes ; la maintenance renvoie 503 sous `/p/<nom>/…`. `PluginManagerIT` : un plugin de test avec un `@Controller` répond sous `/p/<nom>/…` (corps texte renvoyé par `@ResponseBody` pour ne pas dépendre des gabarits), disparaît après désinstallation (404), la mise à jour à chaud sert la nouvelle version. `SocleSecuriteWebTest` : `GET /p/x/y` anonyme → 302 vers `/login` ; `POST /p/x/y` authentifié sans jeton → 403, avec jeton → pas 403 (404 si aucun plugin).
- [ ] **Step 2: Exécuter** `-Dtest=RoutagePluginsTest` puis `PluginManagerIT` puis `SocleSecuriteWebTest` ; ÉCHEC attendu.
- [ ] **Step 3: Implémenter** le routage à deux préfixes (prédicats disjoints de `setPathPrefixes`), l'extension de `nomDuPlugin` et les règles de sécurité.
- [ ] **Step 4: Exécuter** les trois classes + `SecurityPlateformeTest` + `ModularityTests` ; PASSENT.
- [ ] **Step 5: Commit** `feat(plateforme): pages HTML des plugins sous /p/<nom> sur la chaîne à session`.

### Task 8 : Gabarits livrés par les plugins

**Files:**
- Create: `src/main/java/com/logicielpourtous/site/features/plateforme/ResolveurGabaritsPlugins.java`, `PluginStatiquesController.java`
- Modify: `src/main/java/com/logicielpourtous/site/config/ThymeleafConfig.java` (plusieurs résolveurs : ordre et motifs), `PluginManager.java` (invalider le cache de gabarits à l'installation, à la mise à jour et à la désinstallation), `RoutagePlugins.java` (réservation `/p/*/static/**`)
- Test: `ResolveurGabaritsPluginsTest`, `PluginStatiquesControllerTest`, `PluginManagerIT` (étendu), utilitaire `JarDePluginDeTest` (peut ajouter des gabarits et fichiers au jar de test)

**Interfaces:**
- Produces: un `ITemplateResolver` qui résout **uniquement** les noms de la forme `plugins/<nom>/<chemin>` : il cherche `templates/plugins/<nom>/<chemin>` dans le chargeur de la **génération active** de `<nom>` (`PluginManager.actifs()`) ; refus (non résolu) pour `..`, `\`, un chemin absolu, un nom de plugin inconnu ou non actif, une extension autre que `.html` ; il est ordonné **avant** le résolveur du noyau pour ces noms et ne résout jamais autre chose. Le cache de gabarits du moteur est vidé à chaque changement de génération. `GET /p/<nom>/static/<fichier>` sert les fichiers `static/<fichier>` du jar du plugin actif (types `css`, `js`, `svg`, `png`, `jpg`, `woff2` ; un seul segment ; en-têtes de type et `nosniff` ; session requise par la règle `/p/**`) ; un fichier absent ou un plugin non actif → 404 identique. Un gabarit de plugin peut `layout:decorate="~{socle/layout}"` et utiliser les fragments du noyau ; l'inverse n'existe pas.

- [ ] **Step 1: Écrire les tests en échec.** `ResolveurGabaritsPluginsTest` : un plugin actif de test avec `templates/plugins/demo/page.html` est résolu ; `plugins/demo/../../layout`, `plugins/demo/..\x`, `plugins/inconnu/page`, `plugins/demo/page.txt`, `layout`, `plugins/demo/` et `socle/layout` (nom du noyau) ne sont **pas** résolus par ce résolveur ; après désinstallation du plugin, plus de résolution ; après mise à jour à chaud (gabarit modifié), le nouveau contenu est rendu (pas l'ancien en cache). `PluginStatiquesControllerTest` : fichier servi avec le bon type ; `..`, extension non autorisée, plugin inconnu, version retirée → 404 identique. `PluginManagerIT` : un plugin dont le `@Controller` renvoie la vue `plugins/demo/page` est rendu avec la mise en page du noyau (`layout:decorate`), le texte du modèle est échappé ; mise à jour à chaud puis désinstallation vérifiées ; `FuiteMemoireIT` reste vert.
- [ ] **Step 2: Exécuter** les trois classes, chacune seule ; ÉCHEC attendu.
- [ ] **Step 3: Implémenter** le résolveur (`computeTemplateResource` sur `ClassLoaderTemplateResource` avec le chargeur de la génération), l'enregistrement de plusieurs résolveurs dans `ThymeleafConfig` (le résolveur existant garde son comportement pour tous les autres noms), l'invalidation du cache et le contrôleur de statiques.
- [ ] **Step 4: Exécuter** les classes ci-dessus + `PluginManagerIT`, `FuiteMemoireIT` ; PASSENT.
- [ ] **Step 5: Commit** `feat(plateforme): gabarits et fichiers statiques livrés par les plugins`.

### Task 9 : Emplacements d'extension (boutons et onglets déclarés par les plugins)

**Files:**
- Modify: `src/sdk/java/com/logicielpourtous/site/sdk/ManifestePlugin.java` (clé `ext.N`), créer `src/sdk/java/com/logicielpourtous/site/sdk/ExtensionUi.java`
- Create: `src/main/java/com/logicielpourtous/site/features/plateforme/EmplacementsService.java`, `EmplacementsAdvice.java`, `src/main/resources/templates/socle/fragments/slots.html`
- Modify: `src/main/resources/templates/socle/styleguide.html` et `SocleStyleguideController.java` (fiche de démonstration avec deux emplacements)
- Test: `ManifestePluginTest` (étendu, ou le test existant du manifeste), `EmplacementsServiceTest`, `SocleFragmentsTest` (étendu), `PluginManagerIT` (étendu)

**Interfaces:**
- Produces:
  - Manifeste : `ext.1`, `ext.2`, … au format `emplacement|libellé|chemin|droit|ordre` ; `emplacement` : `^[a-z][a-z0-9-]*(\.[a-z][a-z0-9-]*)+$` (ex. `clients.fiche.onglets`) ; `libellé` non vide ; `chemin` commence par `/`, ne contient qu'un seul marqueur `{id}` au plus, caractères limités à `[A-Za-z0-9/_\-.?=&{}]` ; `droit` : `ManifestePlugin.DROIT_VALIDE` ; `ordre` : entier 0 à 9999. Même numérotation contiguë stricte que `entree.N` et `bloc.N` ; un manifeste sans `ext.N` reste valide. `record ExtensionUi(String emplacement, String libelle, String chemin, String droit, int ordre)` dans `ManifestePlugin` : `List<ExtensionUi> extensions()`. Les constructeurs existants du manifeste restent utilisables (extensions vides).
  - `EmplacementsService.elements(String emplacement, Long societeId, String idEntite) -> List<ElementAffiche>` (`libelle`, `url`, `plugin`, `ordre`) : plugins **actifs** seulement ; filtre par `ControleDroits.autorise(userId, societeId, droit)` (identité de la session) ; tri par `ordre` puis plugin ; `url` = `/p/<plugin>` + `chemin` avec `{id}` remplacé par `idEntite` **si et seulement si** `idEntite` respecte `^[0-9]{1,15}$` (sinon l'élément est omis) ; échec fermé : toute exception à la lecture d'un manifeste ou d'un droit omet l'élément, jamais l'écran.
  - Assistant Thymeleaf : `@ControllerAdvice` `EmplacementsAdvice` exposant l'attribut de modèle `uiSlots` (objet avec `elements(emplacement, idEntite)`), évalué **à la demande** (aucun coût pour les écrans qui ne l'utilisent pas ; sans `app.plateforme.actif`, liste vide). Fragments `socle/fragments/slots.html :: boutons(emplacement, idEntite)` (chaque bouton : conteneur `hx-get="<url>" hx-trigger="load" hx-swap="outerHTML"` avec un libellé de repli) et `:: onglets(emplacement, idEntite)` (onglets Bootstrap `data-bs-toggle="tab"` ; chaque panneau se charge au premier clic : `hx-get="<url>" hx-trigger="click once" hx-target="#panneau-<n>"`, indicateur « Chargement… » ; libellés par `th:text`).

- [ ] **Step 1: Écrire les tests en échec.** Manifeste : `ext.1=clients.fiche.onglets|Historique|/historique?client={id}|historique.lire|20` accepté ; refusés : emplacement sans point, droit invalide, `ordre` négatif ou non numérique, chemin sans `/`, chemin avec deux `{id}`, caractères interdits (`<`, espace, `;`), numérotation non contiguë, 4 champs. `EmplacementsServiceTest` : deux plugins actifs de test contribuent au même emplacement : éléments triés par `ordre` ; un utilisateur sans le droit d'un élément ne le voit pas ; un plugin non actif ou désinstallé ne contribue plus ; mise à jour à chaud : les nouveaux éléments apparaissent ; `idEntite` = `12` → URL `/p/<plugin>/historique?client=12` ; `idEntite` = `1;2`, `../x`, `` (vide), `1234567890123456` → éléments omis ; une exception du contrôle de droits omet l'élément sans erreur. `SocleFragmentsTest` : un libellé hostile (`<b>x</b>`) est affiché en texte ; un onglet porte `hx-trigger="click once"` et le bouton `hx-trigger="load"` ; sans élément, rien n'est rendu. `PluginManagerIT` : un plugin de test avec `ext.1` et son `@Controller` renvoie le fragment sous `/p/<nom>/…` ; la page de démonstration l'inclut puis ne l'inclut plus après désinstallation.
- [ ] **Step 2: Exécuter** `ManifestePluginTest`, `EmplacementsServiceTest`, `SocleFragmentsTest`, chacun seul ; ÉCHEC attendu.
- [ ] **Step 3: Implémenter** la clé `ext.N` dans le manifeste du SDK (réinstaller le SDK dans `~/.m2` : `mvn -B -o -f plugins-sdk/pom.xml install -DskipTests`), le service, l'assistant et les fragments.
- [ ] **Step 4: Exécuter** les classes ci-dessus + `PluginManagerIT`, `ModularityTests`, `IdentiteIndependanceTest` ; PASSENT.
- [ ] **Step 5: Commit** `feat(plateforme): emplacements d'extension déclarés dans le manifeste des plugins`.

### Task 10 : Vérification de bout en bout du socle avec un plugin réel de test

**Files:**
- Create: `src/test/java/com/logicielpourtous/site/features/plateforme/PluginSocleIT.java`
- Modify (si utile): `JarDePluginDeTest.java`

**Interfaces:**
- Consumes: toutes les tâches précédentes ; plugin de test construit en mémoire avec : un manifeste (une entrée `PARTENAIRE`, une entrée `ADMIN`, une extension `ext.1` vers l'emplacement `demo.fiche.onglets`), un `@Controller` (`GET /` renvoyant `plugins/demo/page`, `POST /action` renvoyant un fragment), un `@RestController` JSON, un gabarit `plugins/demo/page.html` décorant `socle/layout`, un fichier `static/demo.css`.

- [ ] **Step 1: Écrire les tests en échec.** Contexte complet, vraie chaîne de sécurité, `app.plateforme.actif=true`, jetons et sessions simulés comme dans `PluginClientsIT` : (a) `/accueil` d'un utilisateur ayant le droit : lien `/p/demo/…` ; sans droit : message vide ; anonyme : `/login` ; tuile `ADMIN` seulement pour l'admin du maître ; (b) `GET /p/demo/` : page complète avec la mise en page du socle, thème et fichiers locaux, aucun domaine tiers ; avec `HX-Request: true` : fragment seul ; (c) `POST /p/demo/action` sans jeton → 403, avec jeton → 200 ; (d) l'API JSON `/api/plugins/demo/…` reste joignable avec jeton et refusée sans ; (e) `/p/demo/static/demo.css` : 200 avec session, `/p/demo/static/../x` et un plugin inconnu → 404 ; (f) mise à jour à chaud du plugin : le nouveau gabarit est servi, l'ancien disparaît ; (g) désinstallation : `/p/demo/…` → 404, la tuile disparaît, plus de gabarit résolu ; (h) l'admin modifie une couleur par `/admin/apparence` : la nouvelle valeur apparaît dans `/css/theme.css` et la page du plugin la charge ; (i) la page de démonstration du noyau (fiche à deux emplacements) affiche l'onglet du plugin pour un utilisateur ayant le droit et pas pour un autre, charge son contenu par `GET /p/demo/…` au clic, et ne l'affiche plus après la désinstallation. Nettoyer tout ce que le test crée (utilisateurs, sociétés, réglages de thème, journaux).
- [ ] **Step 2: Exécuter** `-Dtest=PluginSocleIT` ; les cas déjà satisfaits passent d'emblée : en vérifier chacun en le rendant fautif un instant (retirer la règle `/accueil`, le CSRF de `/p/**`, la réservation de statiques, l'invalidation du cache), puis annuler.
- [ ] **Step 3: Corriger** ce que le test révèle (chaque correction dans son propre commit avec son test).
- [ ] **Step 4: Exécuter, chacune seule** : `PluginSocleIT`, `PluginManagerIT`, `FuiteMemoireIT`, `SecurityPlateformeTest`, `SocleSecuriteWebTest`, `AccueilControllerTest`, `ApparenceControllerTest`, `ThemeServiceTest`, `RoutagePluginsTest`, `ModularityTests`, `IdentiteIndependanceTest` ; PASSENT.
- [ ] **Step 5: Commit** `test(front): vérification de bout en bout du socle avec un plugin`.

---

## Auto-revue

- **Couverture de la spec :** mise en page unique (T1) ; gabarits réutilisables (T2) ; fichiers locaux sans CDN, versions identiques aux mises en page existantes (T1) ; règles HTMX, CSRF, session expirée (T3) ; accueil à tuiles `/accueil` avec la règle avant `/{slug}` (T4) ; thème par défaut dans l'application + réglages en base hérités/modifiés + validation + journal + fichier servi avec cache (T5) ; écran d'apparence par gabarit avec aperçu et réinitialisation (T6) ; pages de plugin `/p/<nom>/**` sur la chaîne à session avec CSRF par défaut (T7) ; gabarits et statiques de plugin, préfixés, mise à jour à chaud (T8) ; emplacements d'extension déclarés dans le manifeste, filtrés par droit, chargés par HTMX (T9) ; bout en bout (T10). Chemin du thème `/css/theme.css` (correction de la spec, notée dans « Précisions »).
- **Hors périmètre respecté :** aucune migration d'écran existant, pas de chatter, kanban, calendrier ni mode sombre, aucune API mobile.
- **Cohérence des noms :** `ThemeService`, `ThemeValeurs`, `ThemeParDefautSource`, `ResolveurGabaritsPlugins`, `PluginStatiquesController`, `AccueilController`, `ApparenceController`, `SocleStyleguideController`, `EmplacementsService`, `EmplacementsAdvice`, `ExtensionUi` définis une fois et réutilisés à l'identique ; clés de thème listées une fois (tâche 5) et réutilisées par la tâche 6.
- **Dépendance externe :** les tâches 4 et 9 lisent la zone `ADMIN` ajoutée par le lot B (tâche 6 du lot B, branche `lot-b-partenaires`, déjà dans la base de cette branche).

# Plugin clients en Thymeleaf, arrivée sur /accueil et lien « Mon espace » — plan d'implémentation

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** après connexion, tout le monde arrive sur `/accueil` ; l'en-tête du site public pointe « Mon Espace » vers `/accueil` ; l'interface du plugin `clients` (lot A) passe d'Angular à des pages Thymeleaf (liste, création, fiche, « Entrer »).

**Architecture:** deux retouches du noyau (destination après connexion, lien d'en-tête) et un plugin `clients` 1.1.0 reconstruit sur le modèle exact du plugin `partenaires` : `@Controller` sous `/p/clients/…`, gabarits `plugins/clients/*` décorés par `socle/layout`, aucun front npm. Le contrat du noyau `AnnuaireClientsContrat` et ses contrôles ne changent pas.

**Tech Stack:** Spring Boot 4.0.2 (noyau), plugin Maven séparé (Spring MVC + Thymeleaf fournis par le noyau, `provided`), JUnit 5, Mockito, MockMvc autonome pour le plugin, `@SpringBootTest` pour le noyau.

**Spec:** `docs/superpowers/specs/2026-11-25-plugin-clients-thymeleaf-et-entete-design.md`

## Global Constraints

- Ne jamais pousser, fusionner ni tagger sans demande explicite de l'utilisateur ; ne jamais toucher au projet Firebase.
- Les clés privées de signature ne sont jamais dans le dépôt ni la session ; le plugin 1.1.0 est signé par l'utilisateur avec `outils/signer.ps1`.
- Dépôt GitHub PUBLIC : aucun message de commit, de doc ou de test ne décrit une faiblesse non corrigée.
- Messages de commit en français, terminés par `Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>`.
- Machine de 8 Go : une seule commande Maven lourde à la fois, une seule classe de test par exécution, `-B -o -Dskip.npm=true`, `MAVEN_OPTS=-Xmx768m`, `JAVA_HOME=C:\Users\nicolas\scoop\apps\temurin25-jdk\current`, mvn = `~/.m2/wrapper/dists/apache-maven-3.9.9/3477a4f1/bin/mvn`. Restaurer `src/main/resources/static/app` (`git checkout -- src/main/resources/static/app`) après Maven. Le plugin se construit avec `mvn -B -o -f plugins-clients/pom.xml package`.
- Les ITs du noyau qui chargent un jar réel exigent le jar prébuilt (`plugins-clients/target/clients-1.1.0.jar`).
- Texte affiché : toujours `th:text` (jamais `th:utext`), messages fixes, aucune exception ni saisie recopiée dans une réponse.
- Tout refus d'accès à un client (inconnu, d'un autre partenaire, droit manquant sur ce client) donne la MÊME réponse 404 (page fixe) ; droit manquant sur sa propre société : 403 page fixe sans donnée ; erreur inattendue : 500 page fixe, échec fermé.

## Review Focus

1. **Cloisonnement :** un utilisateur d'un autre partenaire demande la fiche ou « Entrer » d'un client qui n'est pas le sien : réponse (statut, vue, modèle) identique à celle d'un identifiant inconnu.
2. **Droits distincts :** `clients.lire` seul ne permet NI d'afficher le formulaire de création NI de créer (403, aucune écriture, contrat de création jamais appelé) ; `clients.entrer` est requis pour « Entrer ».
3. **Client particulier :** SIREN et TVA vides ne produisent pas une erreur 400 (régression déjà vue au lot B) ; un SIREN de plus de 14 caractères est tronqué par le champ et refusé côté serveur avec un message par champ.
4. **CSRF :** un POST (création, « Entrer ») sans jeton est refusé par la chaîne du noyau, sans aucune écriture.
5. **Identité dégradée :** identité sans société, userId nul ou contrat indisponible : 403 sans qu'aucune donnée de client ne soit lue.
6. **Texte hostile :** nom, e-mail et raison sociale contenant des balises sont affichés comme du texte (liste, fiche, formulaire ré-affiché).

---

### Task 1: Arrivée sur /accueil et lien « Mon Espace »

**Files:**
- Modify: `src/main/java/com/logicielpourtous/site/features/auth/CustomTenantSuccessHandler.java` (méthode `destinationApresConnexion`, ~l.137-170)
- Modify: `src/main/resources/templates/header/connected.html` (bouton « Mon Espace », `href="/espace"`)
- Modify (test): `src/test/java/com/logicielpourtous/site/features/auth/CustomTenantSuccessHandlerSelectionTest.java` (assertions `/admin/console` aux ~l.165 et ~l.184, et tout cas ESN `/intra` / autre `/espace`)
- Create (test): `src/test/java/com/logicielpourtous/site/features/auth/EnteteConnecteTest.java`

**Interfaces:**
- Consumes: rien d'une autre tâche.
- Produces: `destinationApresConnexion(HttpServletResponse, CustomUserDetails, boolean doitChangerMotDePasse, boolean depuisAngular)` garde sa signature ; retourne `/accueil` pour tout utilisateur connecté sauf `/change-password` (mot de passe à changer) et l'URL Angular avec jeton (connexion venant d'Angular).

- [ ] **Step 1: Tests qui échouent**
  Dans `CustomTenantSuccessHandlerSelectionTest`, changer les attentes existantes `/admin/console` en `/accueil` et ajouter les cas : utilisateur ESN → `/accueil` ; utilisateur ni admin ni ESN → `/accueil` ; `doitChangerMotDePasse=true` → `/change-password` (même pour un admin) ; `depuisAngular=true` → commence par l'URL Angular configurée et contient `?token=`. Test `admin_et_esn_recoivent_toujours_le_cookie_jwt_court` : le cookie `_intra_jwt` (max-age 300) est toujours posé pour un admin et un utilisateur ESN, jamais pour un autre.
  Créer `EnteteConnecteTest` : lit la ressource `templates/header/connected.html` et affirme que le lien « Mon Espace » (balise `<a` dont le texte contient « Mon Espace ») a `href="/accueil"` (et non `/espace`) ; ne pas interdire `/espace` ailleurs dans le fichier.
- [ ] **Step 2: Vérifier l'échec** — `mvn … -Dtest=CustomTenantSuccessHandlerSelectionTest test` puis `-Dtest=EnteteConnecteTest` : échecs sur `/accueil`. Expected: FAIL.
- [ ] **Step 3: Implémenter** la destination : après les deux premiers cas de `destinationApresConnexion` (Angular puis mot de passe), conserver la pose du cookie JWT pour admin/ESN, puis `return "/accueil";` (supprimer les trois branches `/admin/console`, `/intra`, `/espace`). Dans `header/connected.html`, remplacer `href="/espace"` par `href="/accueil"` (le libellé « Mon Espace » et le reste du fichier inchangés).
- [ ] **Step 4: Vérifier** — les deux classes de test, une par une. Expected: PASS. Puis `-Dtest=ChoixSocieteControllerTest` (le contrôleur appelle `destinationApresConnexion`) et `-Dtest=SocleSecuriteWebTest`. Expected: PASS ; si un test existant attend une autre destination, l'aligner sur `/accueil` (c'est la nouvelle règle) et le signaler dans le rapport.
- [ ] **Step 5: Commit** — `feat(auth): arrivee sur /accueil apres connexion et lien Mon Espace vers /accueil`.

---

### Task 2: Plugin clients en pages Thymeleaf (version 1.1.0)

**Files:**
- Create: `plugins-clients/src/main/java/com/logicielpourtous/site/plugins/clients/ClientsPagesController.java`
- Create: `plugins-clients/src/main/resources/templates/plugins/clients/{liste,client-form,fiche,entree,refuse,introuvable,erreur}.html`
- Modify: `plugins-clients/pom.xml` (version 1.1.0 ; retirer le bloc `frontend-maven-plugin`, le `maven-resources-plugin` de copie du front et l'exécution `FrontDansLeJarTest` ; aligner les dépendances sur `plugins-partenaires/pom.xml` : `jakarta.servlet-api` en `provided`, retirer `jackson-databind` et `json-path`, ajouter ce que le pom partenaires ajoute pour Thymeleaf/Mockito)
- Modify: `plugins-clients/src/main/resources/plugin.properties` (`version=1.1.0` ; `entree.1=PARTENAIRE|Clients|people|/liste|clients.lire` ; supprimer `bloc.1`)
- Delete: `plugins-clients/frontend/` (tout), `ClientsController.java`, `ClientsControllerTest.java`, `FrontDansLeJarTest.java`, `plugins-clients/.gitignore` si elle ne sert qu'au front
- Modify (test): `ManifesteClientsTest` (version 1.1.0, chemin `/liste`, 0 bloc)
- Create (test): `ClientsPagesControllerTest`, `GabaritsClientsTest`, `PluginSansFrontTest` (copier le principe des tests homonymes de `plugins-partenaires`)

**Interfaces:**
- Consumes: `IdentiteContrat.courante() -> IdentiteCourante(userId, roles, societeId, tenantId, roleServeur)` ; `AnnuaireClientsContrat` : `clientsDuPartenaire(Long) -> List<ClientResume(societeId, siren, tva, origine, nom)>`, `fiche(Long) -> Optional<FicheClient(ClientResume resume, Long partenaireSocieteId, List<String> capacites)>`, `autorise(Long userId, Long societeId, String droit) -> boolean`, `creerClient(CreationClient(partenaireSocieteId, particulier, siren, tva, etrangere, email, nom)) -> ResultatCreation(issue, societeId)` (issues `CREE`, `DEJA_CONNUE`, `EMAIL_DEJA_UTILISE` ; `IllegalArgumentException` si invalide), `entrerEspaceClient(Long userId, Long clientSocieteId) -> PorteeAcces(societeId, Set<String> droitsEffectifs)` (`SecurityException` en cas de refus). Droits : `clients.lire`, `clients.creer`, `clients.entrer`.
- Produces: routes (relatives ; le noyau les monte sous `/p/clients`) `GET /liste`, `GET /nouveau`, `POST /` (création), `GET /{societeId}/fiche`, `POST /{societeId}/entrer`; vues `plugins/clients/<nom>`; jar `plugins-clients/target/clients-1.1.0.jar` (consommé par la tâche 3).

- [ ] **Step 1: Tests qui échouent** — `ClientsPagesControllerTest` (MockMvc autonome, contrats Mockito, vue vide pour lire le nom de vue, comme `PartenairesPagesControllerTest`). Noms et assertions :
  - `liste_affiche_seulement_les_clients_de_la_societe_de_l_appelant` : vue `plugins/clients/liste`, le contrat est interrogé avec la société de l'identité, modèle `clients`.
  - `liste_sans_droit_lire_ou_sans_societe_ou_identite_indisponible_est_403_sans_lecture` : 403, `clientsDuPartenaire` jamais appelé.
  - `fiche_de_mon_client` : vue `plugins/clients/fiche`, modèle `fiche`.
  - `fiche_d_un_autre_partenaire_et_fiche_inconnue_donnent_la_meme_reponse` : statut 404 et vue `plugins/clients/introuvable` identiques pour les deux ; identifiant non numérique ou > 15 chiffres → même 404.
  - `formulaire_de_creation_exige_le_droit_creer` : `clients.lire` seul → 403 ; avec `clients.creer` → vue `plugins/clients/client-form`.
  - `creation_valide_redirige_vers_la_fiche_du_nouveau_client` : le contrat reçoit `partenaireSocieteId` de l'identité (jamais un paramètre de la requête, même si un paramètre `partenaireSocieteId` est envoyé) ; redirection `/p/clients/{id}/fiche?ok=cree`.
  - `creation_sans_droit_creer_est_403_et_ne_cree_rien` (`creerClient` jamais appelé).
  - `creation_particulier_avec_siren_et_tva_vides_est_acceptee` (SIREN/TVA vides transmis `null`).
  - `creation_siren_invalide_tva_incoherente_email_invalide_nom_vide_donnent_une_erreur_par_champ` (400, `erreursChamps` par champ, saisie ré-affichée, contrat jamais appelé).
  - `deja_connue_et_email_deja_utilise_donnent_409_avec_message_neutre` (messages fixes, sans identité d'une autre société).
  - `illegal_argument_du_contrat_donne_400_avec_message_borne_a_200_caracteres_ou_generique`.
  - `entrer_accorde_affiche_le_perimetre` : vue `plugins/clients/entree`, modèle société + droits triés ; appelé avec `userId` de l'identité et l'id du chemin seulement.
  - `entrer_refuse_donne_le_meme_404_qu_un_client_inconnu` (`SecurityException` → 404 `introuvable`) ; `entrer_sans_droit_entrer_est_403_sans_appel_du_contrat`.
  - `erreur_inattendue_donne_500_a_page_fixe_sans_message` (échec fermé).
  `GabaritsClientsTest` : rend chaque gabarit avec le moteur Thymeleaf autonome (comme `GabaritsPartenairesTest`) ; un nom `<script>x</script>` est échappé (`&lt;script&gt;`), aucun `th:utext` dans les gabarits (lecture de ressources). `PluginSansFrontTest` : aucune ressource `ui/` ni `frontend/` dans le jar/les ressources.
- [ ] **Step 2: Vérifier l'échec** — `mvn -B -o -f plugins-clients/pom.xml -Dtest=ClientsPagesControllerTest test`. Expected: FAIL (classe absente).
- [ ] **Step 3: Implémenter `ClientsPagesController`** sur le modèle de `PartenairesPagesController` (même organisation : constantes de messages, `@ExceptionHandler(SecurityException.class)` → 403 page `refuse`, `@ExceptionHandler(RuntimeException.class)` → 500 page `erreur` avec journalisation SEVERE de la cause côté serveur uniquement) : constructeur `(IdentiteContrat, AnnuaireClientsContrat)` ; helper privé `societeAutorisee(String droit) -> Optional<Long>` repris tel quel de l'ancien `ClientsController` (identité indisponible, `userId` ou `societeId` nuls ⇒ vide ; sinon `annuaire.autorise`). Reprendre à l'identique de `PartenairesPagesController` les vérificateurs privés `verifierSiren`, `verifierTva`, `verifierEmail`, `verifierTexte`, `luhn`, `messageNoyau`, `videEnNull` (messages et règles inchangés ; SIREN/TVA facultatifs si `particulier`). Identifiant du chemin validé par `^[0-9]{1,15}$` sinon 404 `introuvable`. Vue d'ensemble : `liste` (droit lire), `nouveau`/création (droit creer), `fiche` (droit lire, rattachement `partenaireSocieteId` = société de l'appelant sinon 404 identique), `entrer` (droit entrer). La page `entree` affiche le périmètre (société cliente, droits effectifs triés) et la phrase fixe « L'espace client n'est pas encore disponible : voici le périmètre qui vous est accordé. » sans lien.
  Gabarits : `layout:decorate="~{socle/layout}"`, `th:text` uniquement, formulaires `th:action="@{/p/clients/...}"`, champ SIREN `maxlength="14"`, cases `particulier` et `etrangere`, bouton « Entrer dans l'espace » en `<form method="post">` sur la fiche. Titres et fil d'Ariane via les attributs de modèle que `PartenairesPagesController.habiller` pose (même clés).
- [ ] **Step 4: Retirer le front** : supprimer `frontend/`, l'ancien contrôleur JSON et ses tests, nettoyer le pom (voir Files), mettre à jour le manifeste (voir Files) et `ManifesteClientsTest`. Avant de supprimer `bloc.1`, `grep -rn "blocs/resume" src plugins-*` : si un code du noyau autre que `NavigationService.blocs()` en dépend, STOP et le signaler.
- [ ] **Step 5: Vérifier** — une classe à la fois dans `plugins-clients` : `ClientsPagesControllerTest`, `GabaritsClientsTest`, `PluginSansFrontTest`, `ManifesteClientsTest`. Expected: PASS. Puis `mvn -B -o -f plugins-clients/pom.xml package` : jar `plugins-clients/target/clients-1.1.0.jar` produit, sans `ui/main.js` ni `node`.
- [ ] **Step 6: Commit** — `feat(plugin-clients): pages Thymeleaf (liste, creation, fiche, entrer), retrait du front Angular, version 1.1.0`.

---

### Task 3: Tests du noyau sur le jar réel clients 1.1.0

**Files:**
- Modify: `src/test/java/com/logicielpourtous/site/features/plateforme/JarClientsReel.java` (jar `clients-1.1.0.jar` ; `deriver(...)` : version dérivée sans le remplacement de `ui/main.js`, qui n'existe plus)
- Modify: `src/test/java/com/logicielpourtous/site/features/plateforme/PluginClientsCompatibiliteTest.java` (0 bloc, version 1.1.0, une entrée)
- Rewrite: `src/test/java/com/logicielpourtous/site/features/plateforme/PluginClientsIT.java` sur le modèle de `PluginPartenairesIT` (chaîne web réelle, session, CSRF)

**Interfaces:**
- Consumes: jar `plugins-clients/target/clients-1.1.0.jar` (tâche 2) ; les routes `/p/clients/…` ci-dessus ; les helpers de `PluginPartenairesIT` (profil de test, jeton/session, fabriques de sociétés, partenaires et droits) à imiter, pas à modifier.
- Produces: rien pour d'autres tâches.

- [ ] **Step 1: Tests qui échouent** — réécrire `PluginClientsIT` (une méthode par propriété, noms descriptifs) :
  - `la_tuile_clients_n_apparait_qu_avec_le_droit_lire` (`/accueil` : avec `clients.lire` la tuile `/p/clients/liste` est présente ; sans le droit, absente).
  - `liste_et_fiche_sont_cloisonnees_par_partenaire` (A ne voit que ses clients ; la fiche d'un client de B donne le même 404 qu'un identifiant inconnu).
  - `sans_session_les_pages_redirigent_vers_le_login_et_les_post_exigent_le_csrf` (aucune écriture sans jeton).
  - `creation_valide_puis_doublon_409_puis_donnees_invalides_400_sans_ecriture`.
  - `particulier_sans_siren_ni_tva_est_cree`.
  - `entrer_accorde_pour_son_client_et_refuse_en_404_identique_pour_un_autre` (aucun changement de session après « Entrer » : même `JSESSIONID`, `/accueil` inchangé).
  - `texte_hostile_dans_un_nom_est_affiche_echappe`.
  - `mise_a_jour_a_chaud_vers_1_1_1_garde_les_pages_et_la_desinstallation_retire_les_routes` (avec `deriver`).
  Les anciens cas JSON (`/api/plugins/clients/...`, `ui/…/main.js`) disparaissent. Adapter `PluginClientsCompatibiliteTest` et `JarClientsReel`.
- [ ] **Step 2: Vérifier l'échec** — construire d'abord le jar (`mvn -B -o -f plugins-clients/pom.xml package`), puis `mvn … -Dtest=PluginClientsCompatibiliteTest test` : doit déjà passer après adaptation ; `-Dtest=PluginClientsIT test` : FAIL tant que l'IT n'est pas complet. Expected: FAIL avant la réécriture complète.
- [ ] **Step 3: Implémenter** l'IT jusqu'au vert (aucune modification du code de production : si un comportement attendu manque, le signaler plutôt que de patcher le noyau).
- [ ] **Step 4: Régression** — une classe à la fois : `PluginPartenairesIT`, `PluginSocleIT`, `PluginsEcranIT`, `AccueilControllerTest`, `AppsAdviceTest`, `RoutagePluginsTest`, `TuileTest`, `ModularityTests`. Expected: PASS.
- [ ] **Step 5: Commit** — `test(plateforme): IT du plugin clients 1.1.0 sur la chaine web reelle`.

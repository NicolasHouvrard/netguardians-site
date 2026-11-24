# Écran de gestion des plugins du Maître — Plan d'implémentation

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans. Steps use checkbox (`- [ ]`) syntax.

**Goal :** Remplacer les commandes `curl` de publication et de suivi des plugins par un écran du noyau `/admin/plugins` (Thymeleaf + HTMX), réservé à l'admin du serveur Maître : publier un jar signé, voir le dépôt, les plugins installés, l'état rapporté par les serveurs annexes, et désinstaller un plugin du Maître. Ajouter un script `signer.ps1` pour la signature hors ligne.

**Architecture :** Écran du **noyau** (pas un plugin : un plugin qui gère les plugins se coupe la branche), comme `/admin/apparence`. Un `@Controller` session (chaîne web, CSRF actif) appelle directement les services existants : `DepotPlugins` (publier/catalogue), `PluginManager` (états/désinstaller) et la lecture d'état des serveurs de `DistributionController`. La **clé privée ne touche jamais le serveur ni le navigateur** : la signature reste hors ligne (`OutilSignature signer`), l'écran n'accepte qu'un jar et son `.sig`.

**Tech Stack :** Spring Boot 4, Thymeleaf (`socle/layout`), HTMX 1.9 déjà vendu, JUnit/MockMvc, `@SpringBootTest` pour l'IT.

**Specs :** conception des plugins `docs/superpowers/specs/2026-11-13-plugins-sp2-signature-distribution-design.md` ; procédure actuelle `docs/plateforme-plugins-publication.md` (à mettre à jour).

## Global Constraints

- Écran **uniquement sur le serveur Maître** (`@Conditional(SurServeurMaitre.class)`, `@ConditionalOnProperty app.plateforme.actif=true`) et réservé à `ROLE_ADMIN` (règle `/admin/**` déjà en place + `@PreAuthorize("hasRole('ADMIN')")` en défense en profondeur).
- Aucune clé privée, aucun secret de signature côté serveur ou navigateur ; l'écran ne signe rien. La signature est vérifiée par `DepotPlugins.publier` (inchangé).
- CSRF actif sur tous les POST (`th:action` ou en-tête HTMX du socle) ; aucun GET n'a d'effet de bord.
- Tout texte dynamique (noms de plugin, versions, `raisons` rapportées par les serveurs, messages d'erreur) par `th:text` : **jamais** `th:utext` ; les `raisons` viennent des serveurs annexes et sont non fiables.
- Messages d'erreur : textes fixes en français pour les cas connus (`DepotRefuse` : signature/manifeste invalide, version déjà publiée avec un contenu différent, trop volumineux) ; le message de `DepotRefuse` déjà nettoyé par le dépôt peut être affiché tel quel ; aucune trace d'exception, aucun chemin de fichier.
- Limite d'envoi : 10 Mo (limite multipart globale existante) ; un dépassement affiche un message clair (pas une page blanche) si l'application le permet, sinon le documenter.
- Désinstallation : POST uniquement, avec confirmation côté page (`hx-confirm`), nom de plugin validé `^[a-z0-9][a-z0-9-]{0,63}$` ; plugin inconnu → message, pas d'erreur 500. Le plugin désinstallé n'est pas réinstallé à la même version (comportement existant du tireur) : l'écran le dit.
- Dépôt GitHub **public** : aucune faiblesse non corrigée dans un message de commit. Commits en français, ligne finale `Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>`. Rien n'est poussé sans demande explicite.
- Machine 8 Go : Maven `-B -o -Dskip.npm=true -Dtest=<UNE classe>`, `MAVEN_OPTS=-Xmx768m`, en arrière-plan avec la sortie dans un fichier, une commande lourde à la fois ; restaurer `src/main/resources/static/app` après Maven.

## Review Focus

1. **Accès** : anonyme, non-admin et admin d'un serveur non-Maître n'ont ni la page, ni les POST (publication, désinstallation) ; POST sans jeton CSRF refusé. → tâche 1.
2. **Texte hostile** : nom de plugin, version, `raisons` de serveurs contenant `<script>` ou guillemets sont affichés échappés. → tâche 1.
3. **Jar ou signature invalides** : signature absente/fausse, jar sans manifeste, version déjà publiée avec contenu différent → message fixe, rien n'est écrit dans le dépôt. → tâche 1 (test d'intégration avec vrai `DepotPlugins`).
4. **Désinstallation** d'un plugin inconnu, au nom piégé (`../x`, majuscules, trop long) → refus sans effet ; un plugin en service est bien retiré (tuile disparue). → tâche 1.
5. **Republication identique** (idempotente) affichée comme déjà publiée, pas comme erreur. → tâche 1.

---

### Task 1 : Écran `/admin/plugins` (contrôleur, gabarit, tuile, tests)

**Files:**
- Create: `src/main/java/com/logicielpourtous/site/features/plateforme/PluginsEcranController.java`, `src/main/resources/templates/admin/plugins.html`
- Modify: `src/main/java/com/logicielpourtous/site/features/plateforme/AppsAdvice.java` (tuile « Plugins » comme « Apparence », visible seulement si `ROLE_ADMIN` ET serveur Maître), `DistributionController.java` ou un petit service partagé si la lecture de `serveurs-etat` doit être réutilisée sans HTTP (extraire sans changer le comportement de la route)
- Test: `PluginsEcranControllerTest.java` (MockMvc sur le contexte complet, services simulés avec `@MockitoBean` pour les cas d'accès et d'échappement), `PluginsEcranIT.java` (vrai `DepotPlugins`, jar signé avec la clé de test via `JarDePluginDeTest`, `@Primary ClesPubliques`, comme `PluginPartenairesIT`), `AppsAdviceTest.java` (étendu)

**Interfaces:**
- Consumes: `DepotPlugins.publier(InputStream jar, InputStream sig) -> EntreeCatalogue(nom, version, miseAJour, coeurMin, coeurMax, sha256, taille)` (lève `DepotPlugins.DepotRefuse` avec `motif()` ∈ INVALIDE/TROP_VOLUMINEUX/CONFLIT) ; `DepotPlugins.catalogue() -> List<EntreeCatalogue>` ; `PluginManager.etats() -> List<EtatPlugin(nom, version, etat)>` ; `PluginManager.desinstaller(String nom) -> boolean` ; lecture des états des serveurs (`EtatPluginServeurRepository`, voir `DistributionController` ligne ~70-100 pour la forme de `serveurs-etat` : `{licenceServeurId, nom, version, statut, raisons, rapporteLe, metaspace}` avec `limite` 1–2000, défaut 500).
- Produces: routes (session, CSRF) : `GET /admin/plugins` (page : trois sections « Dépôt du Maître », « Installés sur le Maître », « Serveurs annexes », plus formulaire d'envoi) ; `POST /admin/plugins/publier` (multipart `jar` et `sig` ; réponse HTMX fragment de résultat, ou redirection `/admin/plugins?ok=publie|identique` ; erreurs 400/409/413 avec message fixe) ; `POST /admin/plugins/{nom}/desinstaller` (204 → redirection `/admin/plugins?ok=desinstalle`, inconnu → message). Attribut de modèle `titre="Plugins"`, `moduleActif="plugins"`.

- [ ] **Step 1: Écrire les tests en échec.** `PluginsEcranControllerTest` : (a) anonyme → 302 `/login` ; ROLE_USER/GESTIONNAIRE → 403 pour GET et POST ; ROLE_ADMIN sur serveur non-Maître → la route n'existe pas (404) (contexte `app.role=PARTNER`, si faisable en un test dédié) ; POST sans CSRF → 403 ; (b) `GET /admin/plugins` admin → 200, contient les trois sections et le nom d'un plugin simulé ; un nom `<script>x</script>` et une `raison` `"><img src=x onerror=1>` apparaissent échappés (jamais la balise brute) ; (c) `POST publier` : `DepotRefuse(INVALIDE)` → 400 avec le message du refus et rien d'autre ; `CONFLIT` → 409 message fixe « version déjà publiée avec un contenu différent : publier un numéro plus haut » ; `TROP_VOLUMINEUX` → 413 ; une publication identique renvoie l'entrée connue → message « déjà publié (identique) » ; une partie manquante (`sig` absente) → 400 message fixe ; (d) `POST desinstaller` : nom valide connu → redirection ; inconnu (`desinstaller` renvoie false) → message sans 500 ; noms piégés `../x`, `Plugin`, 65 caractères, `a;b` → 400/404 sans appel à `desinstaller`. `PluginsEcranIT` : jar réel `partenaires` signé avec la clé de test (copie dans un dossier temporaire, comme `JarPartenairesReel`) publié par l'écran → présent dans `GET`, publication identique → « identique », jar modifié d'un octet avec l'ancienne signature → 400 et le catalogue inchangé, mauvaise signature de clé inconnue → 400, même version contenu différent → 409 ; installation puis désinstallation via l'écran retire la tuile ; l'écran n'expose aucun chemin de fichier dans ses messages. `AppsAdviceTest` : la tuile « Plugins » (icône `bi-puzzle`, `/admin/plugins`) pour `ROLE_ADMIN` sur le Maître seulement.
- [ ] **Step 2: Exécuter** chaque classe seule (en arrière-plan, sortie dans un fichier) ; ÉCHEC attendu.
- [ ] **Step 3: Implémenter** contrôleur, gabarit (décorant `socle/layout`, formulaire d'envoi `enctype="multipart/form-data"` avec `th:action`, sections en tableaux Bootstrap, bouton désinstaller en POST avec `hx-confirm`), tuile, et l'accès sans HTTP aux états des serveurs.
- [ ] **Step 4: Exécuter** les trois classes de test puis `ModularityTests`, `SocleSecuriteWebTest`, `AccueilControllerTest`, `PluginSocleIT` ; PASSENT.
- [ ] **Step 5: Commit** `feat(plateforme): écran d'administration des plugins du Maître`.

### Task 2 : Scripts de signature et mise à jour de la documentation

**Files:**
- Create: `outils/signer.ps1` (ou le dossier d'outils existant du dépôt s'il y en a un ; sinon `outils/`)
- Modify: `docs/plateforme-plugins-publication.md` (nouvelle section « Depuis l'écran `/admin/plugins` » ; les commandes `curl` restent documentées comme alternative)
- Test: aucun test automatique ; le script est vérifié par lecture et par exécution de `-WhatIf` si implémenté, sinon par exécution sur un jar temporaire avec une clé de test dans un dossier temporaire (jamais la clé de production).

**Interfaces:**
- Produces: `outils/signer.ps1 -Jar <chemin> -Cle <chemin .prive> -KeyId <id>` qui : trouve Java 25 (`$env:JAVA_HOME` sinon `java` du PATH, et **refuse** avec un message clair si la version est < 25), localise `plugins-sdk\target\site-plugins-sdk-0.0.1.jar` (message avec la commande de build s'il manque), exécute `OutilSignature signer`, puis `verifier` avec `src\main\resources\plateforme\cles-publiques.properties`, et affiche le chemin du `.sig`. Il n'affiche jamais le contenu de la clé et n'écrit aucune copie de la clé.

- [ ] **Step 1:** écrire le script ; **Step 2:** l'exécuter avec une paire de clés de test éphémère (générée dans un dossier temporaire via `OutilSignature generer-cles`) et un jar de test ; la signature doit être valide pour une clé de test mais le contrôle de la ressource des clés de production doit échouer proprement (message clair, code retour non nul) — ne jamais commiter de clé ; **Step 3:** documenter la procédure (publier depuis l'écran, désinstaller, limite 10 Mo, clé jamais envoyée) ; **Step 4: Commit** `docs(plateforme): signature par script et publication depuis l'écran`.

---

## Auto-revue

- **Couverture :** publier (T1), voir dépôt/installés/serveurs annexes (T1), désinstaller (T1), tuile (T1), signature par script (T2), clé privée jamais côté serveur (Global Constraints, T2).
- **Hors périmètre :** installation depuis un fichier local du serveur (`/plugins/installer`), diagnostic de fuites (`/fuites`), plugin « obligatoire » (concept futur), signature dans le navigateur (refusée par principe).
- **Cohérence :** routes et noms de la tâche 1 réutilisés dans les tests de la tâche 1 ; la documentation de la tâche 2 cite les mêmes routes.

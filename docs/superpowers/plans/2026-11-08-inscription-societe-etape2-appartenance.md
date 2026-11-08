# Inscription d'une société, étape 2 : appartenance multi-sociétés. Plan d'implémentation

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Une même personne (même e-mail) peut appartenir à plusieurs sociétés, choisir celle dans laquelle elle travaille à la connexion, et en changer ; ses droits sont alors ceux de la société active.

**Architecture:** Une table d'**appartenances** (personne, tenant, fiche client éventuelle, rôles) s'ajoute sans toucher à `users`. Le compte connecté (`CustomUserDetails`) porte la **société active** ; comme 96 des 161 appels à `getTenant()` passent par ce compte, la plupart suivent sans être modifiés. Tant qu'une personne n'a qu'une appartenance (tous les comptes d'aujourd'hui), le comportement actuel est **strictement inchangé**.

**Tech Stack:** Java, Spring Boot 4, Spring Security (session + JWT), JPA/Hibernate, Flyway, Thymeleaf, JUnit 5 (+ MockMvc).

**Spec:** `docs/superpowers/specs/2026-11-08-inscription-societe-design.md` (décision 8 ; section « 5 bis. Appartenance d'une personne à plusieurs sociétés » ; « Livraison », étape 2).

## Prérequis d'environnement (à faire une fois)

Mêmes que pour les étapes précédentes : `application.properties` et `application-local.properties` (ignorés par git) vers une base locale de test, `JAVA_HOME` sur le JDK 25. Commande : `mvn -B -Dskip.installnodenpm=true -Dskip.npm=true test -Dtest=<Classe>` (jamais sans ces deux options, jamais `git add -A`). Un test ne compte que si le rapport affiche `Tests run: N` avec N > 0 (la classe extérieure d'un `@Nested` affiche `0` : lire le total). Écrire les fichiers contenant des antislashs ou des guillemets imbriqués avec l'outil d'écriture, pas par un heredoc du shell. Travailler sur une branche dédiée (`appartenance-societes`).

## Global Constraints

- **Aucun changement de la table `users`.** Nouvelles tables : `appartenances(id, user_id, tenant_id, customer_id, cree_a)` et `appartenances_roles(appartenance_id, role_id)`, colonnes nommées explicitement en minuscules avec soulignés. Migration : `V5__appartenances.sql` (V4 existe).
- **Règle de compatibilité (décision de conception)** : une personne qui a **zéro ou une** appartenance est traitée exactement comme aujourd'hui (tenant, fiche client et rôles lus sur `User`). Les tenant, fiche client et rôles **de l'appartenance active** ne s'appliquent que pour une personne qui en a **au moins deux**. Raison : les écrans existants qui modifient les rôles écrivent dans `users_roles`, pas dans les appartenances ; les faire primer figerait les droits des comptes actuels.
- **Les droits viennent de l'appartenance active uniquement** (jamais de l'union des appartenances). Tant qu'aucune n'est choisie (personne à plusieurs sociétés), le compte n'a que l'autorité `ROLE_SELECTION_SOCIETE` et ne peut atteindre que le sélecteur, la déconnexion et les ressources statiques.
- **Sélecteur à chaque connexion** d'une personne à plusieurs sociétés (choix de conception), avec un lien « Changer de société » dans l'en-tête.
- Tout nouveau POST web est ajouté à `SecurityConfig.csrfCible()`. Messages en français. Un jeton ou un formulaire ne peut jamais désigner l'appartenance d'**une autre personne**.
- Hors de cette étape : l'invitation d'un e-mail déjà connu qui crée une 2e appartenance (étape 4), les écrans de gestion des rôles par société, la connexion déléguée au Maître.

## Review Focus

1. **Appartenance d'une autre personne** : un POST du sélecteur ou une revendication JWT désignant l'identifiant d'une appartenance qui n'est pas la vôtre est refusé et ne change rien (Tasks 3 et 4).
2. **Compte « en attente de choix »** : aucune URL, hormis le sélecteur, la déconnexion et les ressources statiques, n'est accessible (redirection vers le sélecteur) ; pas d'erreur 500 due à un tenant nul (Task 3).
3. **Droits stricts par société** : administrateur dans A et simple utilisateur dans B n'a plus `ROLE_ADMIN` après avoir basculé sur B (Task 2).
4. **Compte d'avant** : sans aucune ligne d'appartenance, ou avec une seule, la connexion et les droits sont identiques à aujourd'hui (Tasks 2 et 3).
5. **Le schéma du tenant suit la société active** : après le choix ou le changement, `CURRENT_TENANT_ID` (session) et le contexte de tenant correspondent à la société choisie (Task 3).

---

### Task 1: Modèle des appartenances et reprise de l'existant

**Files:**
- Create: `src/main/resources/db/migration/V5__appartenances.sql`
- Create: `src/main/java/com/logicielpourtous/site/features/user/Appartenance.java`
- Create: `src/main/java/com/logicielpourtous/site/features/user/AppartenanceRepository.java`
- Test: `src/test/java/com/logicielpourtous/site/features/user/AppartenanceTest.java`

**Interfaces:**
- Produces : `Appartenance` (entité `implements java.io.Serializable`, Lombok `@Getter @Setter`, table `appartenances` schéma `public`) : `Long id`, `Long userId`, `Tenant tenant` (ManyToOne, chargé immédiatement, non nul), `Long customerId` (nullable), `Set<Role> roles` (ManyToMany chargé immédiatement via `appartenances_roles`), `Instant creeA`. `AppartenanceRepository extends JpaRepository<Appartenance, Long>` avec `List<Appartenance> findByUserIdOrderById(Long userId)`.

- [ ] **Step 1: Write the failing test** `AppartenanceTest` (`@SpringBootTest @Transactional`) : `appartenance_conserveTenantFicheEtRoles` (créer un tenant, un `User`, un rôle existant ; enregistrer une appartenance avec `customerId=5` et un rôle ; relire après `em.clear()` : tenant, `customerId`, rôles et `creeA` non nul) ; `deuxAppartenancesDeLaMemePersonneSurDesSocietesDifferentesCoexistent` ; `laBaseRefuseLaMemeAppartenanceEnDouble` (insertion SQL de deux lignes `(user_id, tenant_id, customer_id)` identiques, y compris avec `customer_id` nul : `DuplicateKeyException`) ; `findByUserId_neRenvoieQueLesAppartenancesDeCettePersonne`.
- [ ] **Step 2: Run to verify it fails** : `-Dtest=AppartenanceTest` ; attendu : échec de compilation.
- [ ] **Step 3: Implement** la migration : les deux tables ; **index unique** sur `(user_id, tenant_id, COALESCE(customer_id, 0))` (un `customer_id` nul compte comme une valeur, sinon deux lignes sans fiche passeraient) ; puis la **reprise de l'existant**, idempotente : une appartenance par utilisateur ayant un `tenant_id` (reprenant `tenant_id` et `customer_id`), et la copie de ses lignes de `users_roles` vers `appartenances_roles`, avec `ON CONFLICT DO NOTHING`. Entité et dépôt selon les signatures.
- [ ] **Step 4: Run the tests to verify they pass** ; relancer `FlywayMigrationTest` et `ModularityTests`.
- [ ] **Step 5: Commit** : `git commit -m "feat(appartenance): modele des appartenances multi-societes (migration V5)"`.

### Task 2: Le compte connecté porte la société active

**Files:**
- Modify: `src/main/java/com/logicielpourtous/site/features/auth/CustomUserDetails.java`
- Modify: `src/main/java/com/logicielpourtous/site/features/auth/CustomUserDetailsService.java`
- Test: `src/test/java/com/logicielpourtous/site/features/auth/CustomUserDetailsAppartenanceTest.java`

**Interfaces:**
- Consumes: Task 1 (`Appartenance`, `AppartenanceRepository`).
- Produces : `CustomUserDetails` gagne le constructeur `CustomUserDetails(User user, List<Appartenance> appartenances, Long appartenanceActiveId)` ; le constructeur `CustomUserDetails(User user)` reste et vaut « aucune appartenance ». Nouvelles méthodes : `boolean plusieursSocietes()` (au moins 2 appartenances), `boolean selectionRequise()` (`plusieursSocietes()` sans appartenance active valide), `List<Appartenance> appartenances()`, `Long appartenanceActiveId()`, `CustomUserDetails avecAppartenanceActive(Long id)` (renvoie un nouveau compte ; si `id` n'est pas l'une de **ses** appartenances, lève `IllegalArgumentException`). Comportement de `getTenant()`, `getCustomerId()`, `getAuthorities()`, `hasEsnRole()` : si `plusieursSocietes()` et active valide → ceux de l'appartenance active (autorités construites comme aujourd'hui, avec le préfixe `ROLE_` et l'autorité synthétique `ROLE_ESN_USER`) ; si `selectionRequise()` → `getTenant()` nul, `getCustomerId()` nul, autorités = `ROLE_SELECTION_SOCIETE` seulement ; sinon comportement actuel. `CustomUserDetailsService` : `loadUserByUsername(String email)` charge les appartenances (sans active) ; nouvelle méthode `UserDetails charger(String email, Long appartenanceId)` (active si elle appartient bien à cette personne, sinon en attente de choix).

- [ ] **Step 1: Write the failing tests** `CustomUserDetailsAppartenanceTest` (sans Spring, objets en mémoire) : `sansAppartenance_ouAvecUneSeule_lecomportementEstCelDeLaFiche` (tenant, `customerId`, rôles de `User`, même avec une appartenance de contenu différent) ; `plusieursSocietes_sansChoix_selectionRequise_etAucunDroit` (autorités = exactement `ROLE_SELECTION_SOCIETE`, `getTenant()` nul, pas de `ROLE_ADMIN`) ; `plusieursSocietes_lesDroitsSontCeuxDeLaSocieteActive` (administrateur dans A, utilisateur dans B : actif A donne `ROLE_ADMIN`, actif B ne donne pas `ROLE_ADMIN`, donne `ROLE_ESN_USER` si un rôle de type ESN) ; `avecAppartenanceActive_refuseUneAppartenanceQuiNEstPasLaSienne` ; `plusieursSocietes_getTenantEtGetCustomerIdSuiventLaSocieteActive`. Un test `@SpringBootTest` : `charger_laissePersonneEnAttenteSiLIdentifiantNEstPasLeSien` (appartenance d'une autre personne passée à `charger`).
- [ ] **Step 2: Run to verify they fail** : échec de compilation.
- [ ] **Step 3: Implement** selon les signatures et la règle de compatibilité. Le compte doit rester sérialisable (il est conservé en session).
- [ ] **Step 4: Run the tests to verify they pass** ; relancer `CompteLicenceAdminControllerTest` et `CsrfCibleTest` (ils construisent des comptes de test).
- [ ] **Step 5: Commit** : `git commit -m "feat(appartenance): le compte connecte porte la societe active"`.

### Task 3: Sélecteur de société à la connexion web

**Files:**
- Create: `src/main/java/com/logicielpourtous/site/features/auth/ChoixSocieteController.java`
- Create: `src/main/java/com/logicielpourtous/site/features/auth/SelectionSocieteFilter.java`
- Create: `src/main/resources/templates/feature/auth/choisir-societe.html`
- Modify: `src/main/java/com/logicielpourtous/site/features/auth/CustomTenantSuccessHandler.java`
- Modify: `src/main/java/com/logicielpourtous/site/config/SecurityConfig.java` (filtre dans la chaîne web, règle d'accès, `csrfCible()`)
- Modify: `src/main/resources/templates/header/connected.html` (lien « Changer de société » si `plusieursSocietes()`)
- Test: `src/test/java/com/logicielpourtous/site/features/auth/ChoixSocieteControllerTest.java`

**Interfaces:**
- Consumes: Task 2 (`CustomUserDetails.selectionRequise()`, `appartenances()`, `avecAppartenanceActive`).
- Produces : `GET /choisir-societe` (liste les sociétés de la personne : nom du tenant, et nom de la fiche client si elle existe) ; `POST /choisir-societe` (paramètre `appartenanceId`) : retient la société, **remplace l'authentification de la session** par le compte actif, met à jour `CURRENT_TENANT_ID` et le contexte de tenant, redirige vers `/` ; un identifiant qui n'est pas l'une des appartenances **du compte connecté** donne une erreur 403, sans rien changer. `SelectionSocieteFilter` (dans la chaîne web) : si le compte connecté est `selectionRequise()` et que la requête ne vise ni `/choisir-societe`, ni `/logout`, ni les ressources statiques (`/css`, `/js`, `/images`, `/webjars`), ou `/error`, redirection vers `/choisir-societe`. `CustomTenantSuccessHandler` : si `selectionRequise()`, ne touche ni au schéma de tenant ni à la redirection par rôle et envoie vers `/choisir-societe` ; sinon utilise `getTenant()` **du compte** (et non plus celui de la fiche `User`) pour le schéma actif.

- [ ] **Step 1: Write the failing tests** `ChoixSocieteControllerTest` (`@SpringBootTest @AutoConfigureMockMvc @Transactional`, comptes construits en mémoire, `.with(user(...))`, `.with(csrf())` sur les POST) : `enAttenteDeChoix_toutesLesPagesRedirigentVersLeSelecteur` (une page de l'intranet, l'accueil et une API web : redirection 3xx vers `/choisir-societe`, aucune erreur 500) ; `enAttenteDeChoix_leSelecteurListeSesSocietes` (200, contient les deux noms) ; `enAttenteDeChoix_laDeconnexionResteAccessible` ; `choisir_unePourLaPersonne_activeLaSocieteEtMetAJourLeTenantDeSession` (attribut `CURRENT_TENANT_ID` = schéma de la société choisie ; la page suivante n'est plus redirigée ; l'autorité `ROLE_ADMIN` présente ou absente selon la société) ; `choisir_uneAppartenanceDUnAutre_estRefuseeEtNeChangeRien` (403 ; toujours en attente) ; `choisir_sansJeton_estRefuse` (403) ; `unCompteAvecUneSeuleSociete_neVoitJamaisLeSelecteur` (la page d'accueil ne redirige pas) ; `lienChangerDeSociete_n_apparaitQueSiPlusieursSocietes`.
- [ ] **Step 2: Run to verify they fail** : échec (routes absentes, pas de redirection).
- [ ] **Step 3: Implement** les classes et les modifications selon les signatures. Enregistrer l'authentification mise à jour dans la session (`HttpSessionSecurityContextRepository`) pour qu'elle survive à la requête suivante.
- [ ] **Step 4: Run the tests to verify they pass** ; puis `CsrfCibleTest`, `FederationAdminControllerTest`, `ModularityTests`, et la **suite complète** (attendu : seulement les 8 échecs de synchronisation déjà rouges sur `main`).
- [ ] **Step 5: Commit** : `git commit -m "feat(appartenance): selecteur de societe a la connexion web"`.

### Task 4: Société active dans l'API (JWT)

**Files:**
- Modify: `src/main/java/com/logicielpourtous/site/config/JwtTokenProvider.java`
- Modify: `src/main/java/com/logicielpourtous/site/config/JwtAuthenticationFilter.java`
- Modify: `src/main/java/com/logicielpourtous/site/features/api/angular/AuthApiController.java`
- Test: `src/test/java/com/logicielpourtous/site/features/api/angular/AuthApiAppartenanceTest.java`

**Interfaces:**
- Consumes: Task 2 (`CustomUserDetailsService.charger`, `CustomUserDetails`).
- Produces : `JwtTokenProvider.generate(UserDetails user, Long appartenanceId)` (ajoute la revendication `app`) et `Long extractAppartenanceId(String token)` (nul si absente) ; `generate(UserDetails)` inchangé. `JwtAuthenticationFilter` charge le compte par `charger(email, appartenanceId)` et ne positionne le tenant que si le compte n'est pas en attente de choix. `AuthApiController` : la réponse de `POST /api/auth/login`, pour une personne à plusieurs sociétés, contient `selectionRequise: true` et la liste `societes` (`id`, `libelle`) avec un jeton **sans** société ; nouveau `POST /api/auth/societe` (corps `{ "appartenanceId": n }`, jeton valide exigé) qui renvoie un nouveau jeton portant la société choisie, ou 403 si l'identifiant n'est pas l'une des appartenances de la personne.

- [ ] **Step 1: Write the failing tests** `AuthApiAppartenanceTest` : `jeton_conserveLaSocieteChoisie` (génération puis extraction) ; `unCompteAvecUneSeuleSociete_connexionInchangee` (pas de `selectionRequise`) ; `plusieursSocietes_laConnexionDemandeLeChoix_etLeJetonSansSocieteNOuvreRien` (un appel protégé par `ROLE_ADMIN` avec ce jeton : 403) ; `choisirUneSociete_renvoieUnJetonQuiOuvreLesDroitsDeCetteSociete` ; `choisirLaSocieteDUnAutre_est403` ; `uneRevendicationVersLAppartenanceDUnAutre_laisseLaPersonneEnAttente` (jeton falsifié : aucun droit).
- [ ] **Step 2: Run to verify they fail** : échec.
- [ ] **Step 3: Implement** selon les signatures ; autoriser `POST /api/auth/societe` à tout compte authentifié (y compris en attente de choix) dans la chaîne API concernée.
- [ ] **Step 4: Run the tests to verify they pass** ; puis la **suite complète** (attendu : seulement les 8 échecs de synchronisation déjà rouges sur `main`).
- [ ] **Step 5: Commit** : `git commit -m "feat(appartenance): societe active dans le jeton JWT"`.

---

## Self-review

- **Couverture de la spec (étape 2 et section 5 bis)** : séparation personne / appartenance et rôle propre à la société (Tasks 1 et 2) ; sélecteur à la connexion et changement de société (Task 3) ; société active lue partout via le compte connecté, notamment pour le schéma du tenant (Tasks 2 et 3) ; API (Task 4). Hors étape, par la spec : invitation d'un e-mail déjà connu (étape 4), connexion déléguée (conception ultérieure).
- **Cohérence des types** : `Appartenance`, `AppartenanceRepository.findByUserIdOrderById`, `CustomUserDetails.{plusieursSocietes, selectionRequise, appartenances, appartenanceActiveId, avecAppartenanceActive}`, `CustomUserDetailsService.charger` et `JwtTokenProvider.{generate(user, id), extractAppartenanceId}` portent les mêmes noms partout.
- **Non-effets assumés** : aujourd'hui tous les comptes ont une seule appartenance, donc **rien ne change pour eux**. Le sélecteur n'apparaît qu'après l'étape 4 (qui crée la 2e appartenance). Les écrans de modification des rôles continuent d'écrire dans `users_roles`, sans effet sur une personne à plusieurs sociétés : à traiter avec la gestion des rôles par société.
- **Risque principal** : ce chantier touche l'identité de toute connexion. Le comportement des comptes d'avant est donc protégé par la règle de compatibilité, et par un test dédié à chaque tâche.
- **Proportion** : quatre tâches pour une étape ; le plan fixe les signatures, les règles et les cas de test, pas le code.

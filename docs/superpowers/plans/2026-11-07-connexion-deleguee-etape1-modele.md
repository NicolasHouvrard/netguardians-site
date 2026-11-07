# Connexion déléguée, étape 1 : modèle et écran d'administration. Plan d'implémentation

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Donner au Maître le rattachement tenant → serveur d'accueil, la hiérarchie tenant client → tenant partenaire, l'URL publique d'un serveur et un « serveur par défaut » des nouveaux tenants, avec l'écran d'administration pour les renseigner. Aucun changement de la connexion.

**Architecture:** Une migration Flyway V3 ajoute trois colonnes et une table à une ligne. Un service (`ServeurAccueilService`) porte les règles (validation, serveur par défaut). Un contrôleur Thymeleaf réservé à l'administrateur, et seulement quand `app.role=MASTER`, suit le patron de `CompteLicenceAdminController`.

**Tech Stack:** Java (Spring Boot 4, JPA/Hibernate, Flyway, Thymeleaf), PostgreSQL, JUnit 5 + MockMvc (`@SpringBootTest`).

**Spec:** `docs/superpowers/specs/2026-11-07-connexion-deleguee-design.md` (section « Conception proposée », parties 1 et « Livraison », étape 1).

## Prérequis d'environnement (à faire une fois)

Les tests d'intégration ont besoin d'une base PostgreSQL et de deux fichiers **ignorés par git** (`src/main/resources/application.properties`, copie de `application.properties.example`, et `application-local.properties` avec `spring.datasource.url/username/password`). Base de test : `site_test_federation` sur le PostgreSQL local (utilisateur `postgres`, sans mot de passe). Commande : `mvn -B -Dfrontend.skip=true test -Dtest=<Classe>` avec `JAVA_HOME` sur le JDK 25. **Un test ne compte que si le rapport affiche `Tests run: N` avec N > 0.**

## Global Constraints

- Noms de colonnes des **tables existantes** : minuscules **sans soulignés** pour `licence_serveurs` (stratégie implicite, voir `V2__ordres_deploiement.sql`), donc `urlpublique`. Pour `tenants`, les colonnes sont nommées explicitement avec soulignés (`schema_name`, `is_esn`) : utiliser `serveur_licence_id` et `tenant_partenaire_id` avec `@Column(name=...)`.
- `spring.jpa.hibernate.ddl-auto=validate` : toute colonne d'entité doit exister dans une migration, sinon le démarrage échoue.
- Garde `requireMaster()` (réponse 404 si `app.role` n'est pas `MASTER`) et `@PreAuthorize("hasRole('ADMIN')")` sur tout nouvel écran, comme `CompteLicenceAdminController`.
- URL publique d'un serveur : forme `https://hôte[:port]`, sans chemin, requête, identifiants ni espace. Messages et libellés en français.
- Nouveau tenant : serveur d'accueil = serveur par défaut s'il existe, sinon `null`. Un tenant `null` signifie « rattaché au Maître lui-même ».
- Hiérarchie à un seul niveau : un tenant partenaire ne dépend lui-même d'aucun partenaire.

## Review Focus

1. Un tenant désigné comme son propre partenaire, ou deux tenants partenaires l'un de l'autre : refusé.
2. Un partenaire qui a lui-même un partenaire (chaîne à deux niveaux) : refusé.
3. Un serveur `REVOQUE` choisi comme serveur d'accueil ou serveur par défaut : refusé.
4. URL publique `http://…`, avec chemin (`https://a.b/x`), avec identifiants (`https://u:p@a.b`), avec espace ou vide : refusée ; une URL valide est normalisée sans `/` final.
5. L'écran sur un serveur qui n'est pas le Maître : 404 ; un compte non administrateur : refus.

---

### Task 1: Migration V3 et champs d'entités

**Files:**
- Create: `src/main/resources/db/migration/V3__federation_tenants.sql`
- Modify: `src/main/java/com/logicielpourtous/site/features/commun/Tenant.java`
- Modify: `src/main/java/com/logicielpourtous/site/features/licence/LicenceServeur.java`
- Create: `src/main/java/com/logicielpourtous/site/features/licence/ParametreFederation.java`
- Create: `src/main/java/com/logicielpourtous/site/features/licence/ParametreFederationRepository.java`
- Test: `src/test/java/com/logicielpourtous/site/features/licence/FederationModeleTest.java`

**Interfaces:**
- Produces: `Tenant.getServeurLicenceId(): Long`, `setServeurLicenceId(Long)`, `getTenantPartenaireId(): Long`, `setTenantPartenaireId(Long)` ; `LicenceServeur.getUrlPublique(): String`, `setUrlPublique(String)` ; entité `ParametreFederation` (`getId()`, `getServeurDefautId(): Long`, `setServeurDefautId(Long)`) et `ParametreFederationRepository extends JpaRepository<ParametreFederation, Long>`.

- [ ] **Step 1: Write the failing test** `FederationModeleTest` (`@SpringBootTest @Transactional`) : `tenant_conserveServeurEtPartenaire` (sauvegarde un tenant avec `serveurLicenceId=7` et `tenantPartenaireId=9`, relit par `findById`, vérifie les deux valeurs), `licenceServeur_conserveUrlPublique` (valeur `https://app.exemple.com`), `parametreFederation_existeAvecId1ParMigration` (`findById(1L)` présent, `getServeurDefautId()` nul).
- [ ] **Step 2: Run it to verify it fails** : `-Dtest=FederationModeleTest` ; attendu : échec de compilation (méthodes absentes).
- [ ] **Step 3: Implement** la migration (`ALTER TABLE tenants ADD COLUMN IF NOT EXISTS serveur_licence_id BIGINT, ADD COLUMN IF NOT EXISTS tenant_partenaire_id BIGINT` ; `ALTER TABLE licence_serveurs ADD COLUMN IF NOT EXISTS urlpublique VARCHAR(255)` ; `CREATE TABLE IF NOT EXISTS parametres_federation (id BIGINT PRIMARY KEY, serveur_defaut_id BIGINT)` ; `INSERT INTO parametres_federation (id) VALUES (1) ON CONFLICT DO NOTHING`), puis les champs d'entités avec getters et setters, en suivant les noms de colonnes des contraintes globales. `ParametreFederation` : table `parametres_federation`, schéma `public`, `id` assigné (pas de génération).
- [ ] **Step 4: Run the tests to verify they pass** : trois tests verts, et l'application démarre (`ddl-auto=validate` accepte le schéma).
- [ ] **Step 5: Commit** : `git add src docs && git commit -m "feat(federation): modele tenant-serveur et URL publique (migration V3)"`.

### Task 2: Service `ServeurAccueilService`

**Files:**
- Create: `src/main/java/com/logicielpourtous/site/features/licence/ServeurAccueilService.java`
- Modify: `src/main/java/com/logicielpourtous/site/features/commun/TenantManagementService.java:25` (créer le tenant avec le serveur par défaut)
- Test: `src/test/java/com/logicielpourtous/site/features/licence/ServeurAccueilServiceTest.java`

**Interfaces:**
- Consumes: Task 1 (champs d'entités, `ParametreFederationRepository`, `LicenceServeurRepository`, `TenantRepository`).
- Produces (`@Service @Transactional`) :
  - `Optional<Long> serveurParDefautId()`
  - `void definirServeurParDefaut(Long serveurId)` : `null` efface ; lève `IllegalArgumentException` si le serveur n'existe pas ou n'est pas `ACTIF`.
  - `void rattacherTenant(Long tenantId, Long serveurId, Long partenaireId)` : applique les règles de la section Global Constraints ; lève `IllegalArgumentException` (message français) sinon ; `null` pour retirer un rattachement.
  - `String normaliserUrlPublique(String url)` : renvoie l'URL normalisée ; lève `IllegalArgumentException` si invalide ; vide ou `null` renvoie `null` (effacement).

- [ ] **Step 1: Write the failing tests** `ServeurAccueilServiceTest` (`@SpringBootTest @Transactional`) : `serveurParDefaut_absentAuDepart`, `definirServeurParDefaut_refuseUnServeurRevoque` (et un identifiant inconnu), `rattacher_refuseLeTenantCommeSonProprePartenaire`, `rattacher_refuseUneChaineADeuxNiveaux` (le partenaire a déjà un partenaire), `rattacher_refuseDeuxPartenairesMutuels`, `rattacher_refuseUnServeurRevoque`, `rattacher_accepteEtConserveServeurEtPartenaire`, `normaliser_` pour les cas de Review Focus 4 (valide avec et sans `/` final et avec port ; `http://`, chemin, identifiants, espace, vide), `nouveauTenant_recoitLeServeurParDefaut` (via `TenantManagementService.createNewTenant` avec un schéma de test unique, puis nettoyer le schéma créé ; `@Transactional` ne peut pas annuler un `CREATE SCHEMA`, donc le test supprime le schéma en fin de test).
- [ ] **Step 2: Run to verify they fail** : échec de compilation (service absent).
- [ ] **Step 3: Implement** `ServeurAccueilService` avec les signatures ci-dessus ; la ligne `parametres_federation` d'id 1 est créée par la migration (la lire et la mettre à jour). Dans `TenantManagementService.createNewTenant`, après `setSchemaName`, appeler `tenant.setServeurLicenceId(serveurAccueilService.serveurParDefautId().orElse(null))` (injection `@Autowired`, comme les champs existants de la classe).
- [ ] **Step 4: Run the tests to verify they pass.**
- [ ] **Step 5: Commit** : `git commit -m "feat(federation): service de rattachement tenant-serveur et serveur par defaut"`.

### Task 3: Écran d'administration `/intra/licence/federation`

**Files:**
- Create: `src/main/java/com/logicielpourtous/site/features/licence/FederationAdminController.java`
- Create: `src/main/resources/templates/feature/licence/federation.html`
- Modify: `src/main/resources/templates/feature/licence/comptes.html` (lien vers la nouvelle page dans l'en-tête)
- Test: `src/test/java/com/logicielpourtous/site/features/licence/FederationAdminControllerTest.java`

**Interfaces:**
- Consumes: Task 2 (`ServeurAccueilService`), `TenantRepository.findAll()`, `LicenceServeurRepository.findAll()`.
- Produces : `@Controller @RequestMapping("/intra/licence/federation") @PreAuthorize("hasRole('ADMIN')")` :
  - `GET ""` → vue `feature/licence/federation` avec les attributs `tenants`, `serveurs`, `serveurDefautId`.
  - `POST "/tenants/{id}"` (paramètres facultatifs `serveurLicenceId`, `tenantPartenaireId`) → `redirect:/intra/licence/federation`.
  - `POST "/serveurs/{id}/url"` (paramètre `urlPublique`) → même redirection.
  - `POST "/defaut"` (paramètre facultatif `serveurId`) → même redirection.
  - Une `IllegalArgumentException` du service devient un message d'erreur (`errorMessage`) affiché sur la page, sans 500.

- [ ] **Step 1: Write the failing tests** `FederationAdminControllerTest` (`@SpringBootTest @AutoConfigureMockMvc @Transactional`, même helper `adminPrincipal(TenantRepository)` que `CompteLicenceAdminControllerTest`, à recopier) : `get_affichePageAvecTenantsEtServeurs`, `postTenant_rattacheEtRedirige`, `postTenant_proprePartenaire_afficheErreurSansRien_modifier`, `postUrl_valide_enregistreNormalisee` et `postUrl_http_refusee`, `postDefaut_definitLeServeurParDefaut`, `utilisateurNonAdmin_estRefuse` (rôle `USER`, statut 403), et une classe interne `@Nested @TestPropertySource(properties = "app.role=partner")` : `pageIntrouvableSiPasMaitre` (404).
- [ ] **Step 2: Run to verify they fail** : 404 sur toutes les routes (contrôleur absent).
- [ ] **Step 3: Implement** le contrôleur (garde `requireMaster()` identique à `CompteLicenceAdminController`) et le template Thymeleaf dans la mise en page `intra-layout`, avec : un tableau des tenants (nom, schéma, deux listes déroulantes serveur et partenaire, bouton Enregistrer), un tableau des serveurs (compte, rôle, adresse Headscale, champ URL publique, bouton), et un formulaire du serveur par défaut. Ajouter dans `comptes.html` un lien « Fédération » vers la page.
- [ ] **Step 4: Run the tests to verify they pass** ; puis relancer `CompteLicenceAdminControllerTest` et `GestionServeursControllerTest` pour vérifier l'absence de régression.
- [ ] **Step 5: Commit** : `git commit -m "feat(federation): ecran d'administration (rattachements, URL publique, serveur par defaut)"`.

---

## Self-review

- **Couverture de la spec (étape 1)** : modèle (Task 1), serveur par défaut et affectation des nouveaux tenants (Task 2), URL publique, hiérarchie et écran (Tasks 2 et 3). Hors de cette étape, par la spec : point d'entrée Maître, repli secondaire, redirection, visibilité (étapes 2 et 3).
- **Cohérence des types** : `rattacherTenant(Long, Long, Long)`, `definirServeurParDefaut(Long)`, `normaliserUrlPublique(String)` et `serveurParDefautId(): Optional<Long>` sont utilisés avec les mêmes signatures aux Tasks 2 et 3 ; les colonnes sont celles des Global Constraints.
- **Hors périmètre volontaire** : le tenant « public » créé à la main sur le Maître et sur le VPS garde `serveur_licence_id` vide (rattaché au Maître) ; l'administrateur règle le rattachement depuis le nouvel écran.
- **Proportion** : trois tâches pour une étape de la spec ; le code lui-même n'est pas dans le plan.

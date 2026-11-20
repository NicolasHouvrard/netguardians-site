# Lot B, sous-projet 1 : création de partenaires et de clients par le maître — Plan d'implémentation

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal :** Un service du noyau crée un partenaire complet (société, premier manager, tenant SQL, droits de départ) en une seule transaction ; un plugin « partenaires » (maître uniquement) permet à l'admin du maître de créer des partenaires, de créer des clients au nom d'un partenaire et d'éditer le modèle de droits par défaut.

**Architecture :** Le moteur reste dans le noyau (création du tenant et du schéma, des comptes, des droits, vérification « admin du maître ») et est publié aux plugins par un nouveau contrat SDK `GestionPartenairesContrat`. Le plugin `partenaires` n'a que les routes et l'interface (élément personnalisé Angular dans son jar, comme le plugin `clients`).

**Tech Stack :** Java 25, Spring Boot 4.0.2, JPA/PostgreSQL (DDL transactionnel), Flyway, SDK `plugins-sdk` 0.0.1, Angular 21.2 + `@angular/elements` pour le front du plugin.

**Spec :** `docs/superpowers/specs/2026-11-20-lot-b-creation-partenaires-design.md` (suite de `2026-11-18-cadre-plugins-gestion-clients-design.md`).

## Global Constraints

- Aucune migration appliquée n'est modifiée : tout changement de schéma = nouveau fichier. V11 à V16 sont pris ; ce plan utilise **V17** (une seule migration, tâche 2). Si V17 est déjà appliquée à la base de test et qu'une colonne manque, créer V18, jamais modifier V17.
- Le nom de schéma d'un tenant est **généré par le serveur** et validé par l'expression `^p_[0-9a-f]{12}$` avant toute instruction SQL ; jamais saisi.
- Le DDL de création du schéma passe par la **connexion de la transaction en cours** (`DataSourceUtils.getConnection(dataSource)`), pour être annulé avec elle.
- Le noyau vérifie lui-même « admin du maître » : `IdentiteContrat.courante().roles()` contient `ROLE_ADMIN` **et** `roleServeur()` vaut `MASTER`. Jamais d'un paramètre fourni par un plugin. Sinon `SecurityException`, rien d'écrit.
- Échec fermé partout : identité absente, rôle absent, partenaire cible invalide → refus sans écriture.
- Un plugin ne dépend que de `plugins-sdk` (jamais du code du noyau) ; `app.role=MASTER` n'est pas lu par le plugin.
- Le comportement existant de `TenantManagementService.createNewTenant(String, String)` et l'événement `TenantEnCreationEvenement` ne régressent pas (`ServeurAccueilServiceTest` doit rester vert).
- Commits : message en français, ligne finale `Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>`. Rien n'est poussé sans demande explicite. Le dépôt GitHub est **public** : aucune faiblesse non corrigée dans un message de commit ni une description de PR.

## Notes d'exécution (poste Windows, sans `.mvn` dans le worktree)

- Maven : `~/.m2/wrapper/dists/apache-maven-3.9.9/3477a4f1/bin/mvn` avec `JAVA_HOME=C:\Users\nicolas\scoop\apps\temurin25-jdk\current`, option `-B -o -Dskip.npm=true`, **une classe de test par invocation** (`-Dtest=Classe`).
- Les tests avec base exigent PostgreSQL local démarré : `pg_ctl start -D C:\Users\nicolas\scoop\persist\postgresql\data` (s'il refuse les connexions sur 127.0.0.1:5432).
- Les lancements longs (build npm du plugin ~5 min, `PluginManagerIT`) se font **en arrière-plan avec la sortie dans un fichier**, puis lecture de la fin du fichier.
- Après toute exécution Maven du noyau : `git checkout -- src/main/resources/static/app` et supprimer les fichiers générés non suivis de ce dossier avant de commiter.
- Python n'est pas installé : utiliser les outils d'édition, pas de script Python.
- Les tests ne doivent jamais utiliser ni afficher le mot de passe de la base ; les fichiers `application*.properties` locaux ne sont jamais commités.
- Plugin : après modification du SDK, l'installer dans `~/.m2` (`mvn -B -o -f plugins-sdk/pom.xml install -DskipTests`) avant de construire un plugin.

## Review Focus

1. **Appelant non autorisé** (partenaire ordinaire, admin d'un serveur qui n'est pas MASTER, identité absente) sur `creerPartenaire`, `creerClientPourPartenaire`, `listerPartenaires`, `modeleDroits`, `remplacerModeleDroits` : refus et aucune écriture. → tâches 5 et 8.
2. **Échec à mi-parcours** de `creerPartenaire` (par exemple e-mail trop long à l'insertion de l'utilisateur) : ni société, ni tenant, ni schéma ne subsistent. → tâches 1 et 4.
3. **Doublons** (SIREN déjà connu, e-mail déjà utilisé) : rien n'est écrit, aucune demande d'accès créée. → tâche 4.
4. **Partenaire cible invalide** pour un client créé par le maître (société inconnue, sans capacité PARTENAIRE, sans tenant propriétaire) : refus sans écriture. → tâche 5.
5. **Nom de schéma hostile** passé directement à la méthode de création du tenant : refusé avant toute instruction SQL. → tâche 1.
6. **Modification du modèle de droits** après la création d'un partenaire : ce partenaire garde ses droits. → tâche 4.
7. **Tuile d'administration** (zone `ADMIN`) jamais visible pour un non-admin du maître, ni via le paramètre `zone`. → tâche 6.

---

### Task 1 : Création d'un tenant et de son schéma dans la transaction en cours

**Files:**
- Modify: `src/main/java/com/logicielpourtous/site/features/commun/TenantManagementService.java`
- Test: `src/test/java/com/logicielpourtous/site/features/commun/TenantTransactionnelTest.java` (nouveau, `@SpringBootTest`, **non transactionnel**, sur le modèle de `src/test/java/com/logicielpourtous/site/features/clients/CreationClientTransactionTest.java`)

**Interfaces:**
- Consumes: `TenantRepository`, `ApplicationEventPublisher`, `DataSource` déjà présents dans la classe.
- Produces: `public Tenant creerTenantTransactionnel(String raisonSociale, String schemaName)` — `@Transactional` (propagation REQUIRED), valide `schemaName` contre `^p_[0-9a-f]{12}$` (sinon `IllegalArgumentException` **avant** toute écriture), sauvegarde le `Tenant` (nom, schéma, après publication de `TenantEnCreationEvenement`), crée le schéma et la table `contacts` sur la connexion de la transaction, renvoie le `Tenant` enregistré (avec son `id`). `public static String nouveauNomDeSchema()` — renvoie `"p_"` + 12 hexadécimaux aléatoires (`SecureRandom`). `createNewTenant` est **inchangé**.

- [ ] **Step 1: Écrire les tests en échec.** Dans `TenantTransactionnelTest` : (a) `creerTenantTransactionnelCreeLeTenantEtLeSchema` : le tenant existe (`tenants.findById`), `information_schema.schemata` contient le schéma, `information_schema.tables` contient `<schéma>.contacts` ; (b) `nomDeSchemaHostileEstRefuseSansRienEcrire` : pour `"p_abc; DROP SCHEMA public"` et `"public"` et `"P_ABCDEF012345"`, `IllegalArgumentException` et le nombre de lignes de `tenants` est inchangé ; (c) `echecApresLaCreationAnnuleLeSchemaEtLeTenant` : appeler la méthode depuis un `TransactionTemplate` puis lever `IllegalStateException` dans le callback ; après la sortie, le schéma n'existe pas et le tenant n'existe pas ; (d) `nouveauNomDeSchemaRespecteLeFormat` : 50 noms distincts, tous conformes à l'expression. Nettoyer les schémas et tenants créés en `@AfterEach` (`DROP SCHEMA ... CASCADE`, suppression des lignes de `tenants`).
- [ ] **Step 2: Exécuter** `-Dtest=TenantTransactionnelTest` ; ÉCHEC attendu : méthode absente.
- [ ] **Step 3: Implémenter** les deux méthodes dans `TenantManagementService` ; connexion via `DataSourceUtils.getConnection(dataSource)` et `DataSourceUtils.releaseConnection(...)` dans un `finally` (ne jamais fermer la connexion de la transaction) ; mêmes instructions DDL que `createNewTenant` pour la table `contacts` ; le nom validé est le seul fragment inséré dans le SQL.
- [ ] **Step 4: Exécuter** `TenantTransactionnelTest` puis `-Dtest=ServeurAccueilServiceTest` (non-régression de `createNewTenant`) ; PASSENT.
- [ ] **Step 5: Commit** `feat(commun): création transactionnelle d'un tenant et de son schéma`.

### Task 2 : Migration V17, modèle de droits par défaut et journal des actions du maître

**Files:**
- Create: `src/main/resources/db/migration/V17__partenaires_lot_b.sql`
- Create: `src/main/java/com/logicielpourtous/site/features/clients/ModeleDroitsPartenaire.java` (entité), `ModeleDroitsPartenaireRepository.java`, `ModeleDroitsPartenaireService.java`
- Create: `src/main/java/com/logicielpourtous/site/features/clients/JournalActionMaitre.java` (entité), `JournalActionMaitreRepository.java`, `JournalActionsMaitre.java` (service)
- Create: `src/main/java/com/logicielpourtous/site/features/clients/EtatPartenaire.java` (entité), `EtatPartenaireRepository.java`
- Test: `src/test/java/com/logicielpourtous/site/features/clients/ModeleDroitsPartenaireServiceTest.java`, `JournalActionsMaitreTest.java`, `V17MigrationIT.java` (sur le modèle de `V15MigrationIT`)

**Interfaces:**
- Produces (SQL, schéma `public`, instructions idempotentes `IF NOT EXISTS`, sans clé étrangère pour le journal) :
  - `modele_droits_partenaire(droit VARCHAR(60) PRIMARY KEY)` initialisée avec `clients.lire`, `clients.creer`, `clients.entrer` (`ON CONFLICT DO NOTHING`).
  - `journal_actions_maitre(id BIGSERIAL PRIMARY KEY, admin_user_id BIGINT NOT NULL, action VARCHAR(40) NOT NULL, partenaire_societe_id BIGINT, objet_societe_id BIGINT, cree_a TIMESTAMPTZ NOT NULL DEFAULT now())` + index sur `(admin_user_id, cree_a)` et `(partenaire_societe_id, cree_a)`.
  - `partenaires_etat(societe_id BIGINT PRIMARY KEY REFERENCES societes_identites(id) ON DELETE CASCADE, etat VARCHAR(20) NOT NULL, debut_validation TIMESTAMPTZ NOT NULL)` avec `CHECK (etat IN ('ACTIF','LIMITE','BLOQUE'))`.
- `ModeleDroitsPartenaireService`: `Set<String> droits()` (lecture) ; `void remplacer(Set<String> droits)` (`@Transactional` ; chaque droit valide `ManifestePlugin.DROIT_VALIDE` ou `IllegalArgumentException` ; ensemble vide refusé ; remplace tout ; aucune écriture si un droit est invalide).
- `JournalActionsMaitre`: `void enregistrer(Long adminUserId, String action, Long partenaireSocieteId, Long objetSocieteId)` en `Propagation.REQUIRES_NEW` (modèle : `JournalAccesPartenaire`) ; actions utilisées : `PARTENAIRE_CREE`, `CLIENT_CREE_POUR_PARTENAIRE`, `MODELE_DROITS_MODIFIE`.
- `EtatPartenaire` : entité avec `societeId`, `etat` (`String`), `debutValidation` (`Instant`) ; repository `JpaRepository<EtatPartenaire, Long>`.

- [ ] **Step 1: Écrire les tests en échec.** `V17MigrationIT` : les trois tables existent, le modèle contient les trois droits de départ, la contrainte `CHECK` refuse l'état `'AUTRE'`, la table `journal_actions_maitre` n'a aucune clé étrangère. `ModeleDroitsPartenaireServiceTest` : `droits()` renvoie les trois droits ; `remplacer(Set.of("clients.lire"))` ne laisse que celui-là (puis remettre les trois en `@AfterEach`) ; `remplacer(Set.of("clients.lire","Mauvais Droit"))` lève `IllegalArgumentException` et le modèle est inchangé ; `remplacer(Set.of())` lève `IllegalArgumentException`. `JournalActionsMaitreTest` : l'écriture est visible depuis une autre transaction **pendant** que la transaction appelante reste ouverte (même technique que `AccesEspaceClientServiceTest`, cas (a)).
- [ ] **Step 2: Exécuter** les trois classes ; ÉCHEC attendu.
- [ ] **Step 3: Implémenter** la migration, les entités (Lombok `@Getter @NoArgsConstructor` comme `JournalAcces`) et les services.
- [ ] **Step 4: Exécuter** les trois classes + `-Dtest=FlywayMigrationTest` ; PASSENT.
- [ ] **Step 5: Commit** `feat(clients): migration V17, modèle de droits par défaut et journal des actions du maître`.

### Task 3 : Contrat SDK `GestionPartenairesContrat`

**Files:**
- Create: `src/sdk/java/com/logicielpourtous/site/sdk/GestionPartenairesContrat.java`
- Test: `src/test/java/com/logicielpourtous/site/sdk/GestionPartenairesContratTest.java` (forme des enregistrements : valeurs par défaut, copies défensives)

**Interfaces:**
- Produces (`package com.logicielpourtous.site.sdk`, types du JDK uniquement) :
  ```
  public interface GestionPartenairesContrat {
      ResultatPartenaire creerPartenaire(CreationPartenaire demande);
      AnnuaireClientsContrat.ResultatCreation creerClientPourPartenaire(AnnuaireClientsContrat.CreationClient demande);
      List<PartenaireResume> listerPartenaires();
      Set<String> modeleDroits();
      void remplacerModeleDroits(Set<String> droits);
      record CreationPartenaire(String siren, String tva, String raisonSociale, String email, String nom) {}
      record ResultatPartenaire(String issue, Long societeId) {}   // issue : CREE | SIREN_DEJA_CONNU | EMAIL_DEJA_UTILISE
      record PartenaireResume(Long societeId, String siren, String raisonSociale, String etat) {}
  }
  ```
  Chaque méthode documente : « `SecurityException` si l'appelant n'est pas l'admin du serveur maître (vérifié par le noyau) » ; `IllegalArgumentException` pour une demande invalide ; `creerClientPourPartenaire` : `demande.partenaireSocieteId()` est le partenaire cible.
- Consumes: `AnnuaireClientsContrat.CreationClient` / `ResultatCreation` (lot A, inchangés).

- [ ] **Step 1: Écrire le test en échec** : `ResultatPartenaire` conserve ses valeurs ; `new CreationPartenaire(null,...)` est permis (validation côté noyau) ; l'interface est bien dans le module `plugins-sdk` (le test importe la classe depuis le chemin `src/sdk/java`).
- [ ] **Step 2: Exécuter** `-Dtest=GestionPartenairesContratTest` ; ÉCHEC (classe absente).
- [ ] **Step 3: Créer** l'interface et les trois enregistrements avec leur documentation ; vérifier que `plugins-sdk/pom.xml` inclut déjà le dossier `src/sdk/java` (sinon l'ajouter) puis installer le SDK dans `~/.m2`.
- [ ] **Step 4: Exécuter** le test ; PASSE.
- [ ] **Step 5: Commit** `feat(sdk): contrat de gestion des partenaires`.

### Task 4 : `CreationPartenaireService` — création complète d'un partenaire

**Files:**
- Create: `src/main/java/com/logicielpourtous/site/features/clients/CreationPartenaireService.java`
- Create: `src/main/java/com/logicielpourtous/site/features/clients/PremierManagerService.java` (extraction de `AnnuaireClientsContratImpl.creerUtilisateur`)
- Modify: `src/main/java/com/logicielpourtous/site/features/clients/AnnuaireClientsContratImpl.java` (utilise `PremierManagerService`, comportement identique)
- Test: `src/test/java/com/logicielpourtous/site/features/clients/CreationPartenaireServiceTest.java` (+ `CreationPartenaireTransactionTest.java` non transactionnel)

**Interfaces:**
- Consumes: `TenantManagementService.creerTenantTransactionnel` / `nouveauNomDeSchema` (tâche 1), `ModeleDroitsPartenaireService.droits()`, `EtatPartenaireRepository` (tâche 2), `RegistreSocietes.enregistrer(TypeSociete.PARTENAIRE, IdentiteSociete, Long)`, `EmailsUtilises.estUtilise(String)`, `CapaciteSocieteRepository`, `ManagersService.ajouterUtilisateur(Long, Long, Set<String>)`, `DroitsService.fixerPlafond` et `accorderAuRole(.., ManagersService.ROLE_MANAGER, ..)`, `NumerosSociete` (mêmes validations que `AnnuaireClientsContratImpl.identite`).
- Produces: `PremierManagerService.creer(String email, String nom, Long tenantId) -> User` (compte actif, mot de passe aléatoire inutilisable, `mustChangePassword`, rôle global `ROLE_GESTIONNAIRE` comme aujourd'hui, tenant lu par `tenants.findById` sans repli : `IllegalStateException` sinon). `CreationPartenaireService.creer(GestionPartenairesContrat.CreationPartenaire demande) -> GestionPartenairesContrat.ResultatPartenaire` — `@Transactional`, ordre : validations (SIREN valide et français, nom et e-mail non vides, e-mail bien formé) → `emailsUtilises.estUtilise` (sinon `EMAIL_DEJA_UTILISE`, rien écrit) → `creerTenantTransactionnel(raisonSociale, nouveauNomDeSchema())` → `registre.enregistrer(PARTENAIRE, identite, tenant.getId())` (sur `SocieteDejaConnueException` : retourner `SIREN_DEJA_CONNU` **sans** créer de demande d'accès, en s'assurant que la transaction annule le tenant et le schéma déjà créés : voir note) → capacité `PARTENAIRE` → `PremierManagerService.creer` → `managers.ajouterUtilisateur(userId, societeId, Set.of())` → `droits.fixerPlafond(societeId, modele)` et `droits.accorderAuRole(societeId, ROLE_MANAGER, modele)` (copie du modèle **à cet instant**) → ligne `partenaires_etat` (`ACTIF`, maintenant) → `CREE` avec l'id de la société.
  Note : `RegistreSocietes.enregistrer` a `noRollbackFor = SocieteDejaConnueException` ; pour ne pas laisser un tenant orphelin, vérifier le doublon **avant** de créer le tenant (`registre.trouver(identite).isPresent()` → `SIREN_DEJA_CONNU`) et garder le `catch` de la course comme filet (dans ce cas, lever une exception interne qui annule la transaction puis la convertir en `SIREN_DEJA_CONNU` au niveau supérieur sans réutiliser la transaction annulée).

- [ ] **Step 1: Écrire les tests en échec.** `CreationPartenaireServiceTest` : (a) `creerUnPartenaireEcritToutEnUneFois` : société `type=PARTENAIRE` avec `proprietaire_tenant_id` = tenant créé, schéma existant (`information_schema`), capacité `PARTENAIRE`, utilisateur dans ce tenant, manager de la société (`ManagersService`), `partenaires_etat` = `ACTIF` avec `debut_validation` proche de maintenant, plafond et réglage manager égaux au modèle ; (b) `sirenDejaConnuNEcritRien` : `SIREN_DEJA_CONNU`, aucune ligne de plus dans `tenants`, `societes_identites`, `users`, `demandes` d'accès, aucun schéma de plus ; (c) `emailDejaUtiliseNEcritRien` (même vérifications) ; (d) `modifierLeModeleApresCreationNeChangePasLesDroitsDuPartenaire` : après création, `ModeleDroitsPartenaireService.remplacer(Set.of("clients.lire"))` ; `DroitsService.autorise(userId, societeId, "clients.creer")` reste vrai (remettre le modèle d'origine en `@AfterEach`) ; (e) entrées invalides (SIREN invalide, étranger, nom vide, e-mail mal formé, `demande` nulle) : `IllegalArgumentException`, rien écrit. `CreationPartenaireTransactionTest` (non transactionnel) : un e-mail de plus de 255 caractères fait échouer l'insertion de l'utilisateur **après** la création du tenant et de la société : ni tenant, ni schéma, ni société, ni capacité ne subsistent. Nettoyer en `@AfterEach` (schémas, tenants, sociétés créés).
- [ ] **Step 2: Exécuter** les deux classes ; ÉCHEC attendu : classes absentes.
- [ ] **Step 3: Implémenter** `PremierManagerService` (déplacement de la méthode `creerUtilisateur`, `AnnuaireClientsContratImpl` appelle le service) puis `CreationPartenaireService`.
- [ ] **Step 4: Exécuter** les deux classes puis `-Dtest=ContratsClientsTest` et `-Dtest=CreationClientTransactionTest` (non-régression de l'extraction) ; PASSENT.
- [ ] **Step 5: Commit** `feat(clients): création complète d'un partenaire (société, manager, tenant, droits)`.

### Task 5 : `GestionPartenairesContratImpl` — admin du maître et création de clients pour un partenaire

**Files:**
- Create: `src/main/java/com/logicielpourtous/site/features/clients/GestionPartenairesContratImpl.java`
- Modify: `src/main/java/com/logicielpourtous/site/features/clients/AnnuaireClientsContratImpl.java` (extraire la création en `creerClientPour(Long partenaireSocieteId, CreationClient demande)` accessible au paquet, sans contrôle d'appelant ; `creerClient` l'appelle comme avant)
- Modify: `src/main/java/com/logicielpourtous/site/features/clients/PublicationContratsCoeur.java` (publier `GestionPartenairesContrat`)
- Test: `src/test/java/com/logicielpourtous/site/features/clients/GestionPartenairesContratTest.java`, `PublicationContratsCoeurTest.java` (étendu)

**Interfaces:**
- Consumes: `CreationPartenaireService.creer`, `JournalActionsMaitre.enregistrer`, `ModeleDroitsPartenaireService`, `IdentiteContrat.courante()`, `EtatPartenaireRepository`, `SocieteIdentiteRepository`, `CapaciteSocieteRepository`, `AnnuaireClientsContratImpl.creerClientPour`.
- Produces: bean `GestionPartenairesContratImpl implements GestionPartenairesContrat`. Garde commune `private IdentiteCourante adminDuMaitre()` : lit `identite.courante()` (toute `RuntimeException` ou valeur nulle → `SecurityException`), exige `userId != null`, `roles` contenant `ROLE_ADMIN` et `roleServeur` égal à `"MASTER"`, sinon `SecurityException("Réservé à l'administrateur du serveur maître.")`. Chaque méthode appelle la garde **en premier**. `creerPartenaire` : garde → `CreationPartenaireService.creer` → si `CREE`, journal `PARTENAIRE_CREE` (`partenaire_societe_id = objet_societe_id = id` créé). `creerClientPourPartenaire` : garde → la cible (`demande.partenaireSocieteId()`) doit exister, avoir la capacité `PARTENAIRE` et un tenant propriétaire valide (sinon `IllegalArgumentException`) → `creerClientPour(cible, demande)` → si `CREE`, journal `CLIENT_CREE_POUR_PARTENAIRE` (cible, client créé). `listerPartenaires` : garde → sociétés ayant la capacité `PARTENAIRE` avec raison sociale (nom du tenant propriétaire), SIREN, état (`partenaires_etat`, `ACTIF` par défaut si absent). `modeleDroits` / `remplacerModeleDroits` : garde → service du modèle ; le remplacement journalise `MODELE_DROITS_MODIFIE`.

- [ ] **Step 1: Écrire les tests en échec.** Simuler l'identité par un bean `IdentiteContrat` de test (ou `@MockitoBean`) renvoyant `new IdentiteCourante(userId, roles, null, null, roleServeur)`. (a) pour chacune des cinq méthodes : refus (`SecurityException`) avec `roles={"ROLE_GESTIONNAIRE"}`/`MASTER`, avec `roles={"ROLE_ADMIN"}`/`PARTNER`, avec `roles={"ROLE_ADMIN"}`/`null`, et quand `courante()` lève `IllegalStateException` ; **aucune ligne écrite** (compter `societes_identites`, `users`, `tenants`, `journal_actions_maitre`) ; (b) admin du maître : `creerPartenaire` crée et journalise `PARTENAIRE_CREE` avec l'`admin_user_id` de l'identité ; (c) `creerClientPourPartenaire` pour un partenaire créé par la tâche 4 : client créé, lien d'origine `PARTENAIRE` vers la **cible**, l'utilisateur du client a le **tenant de la cible** (pas le tenant de l'admin), journal `CLIENT_CREE_POUR_PARTENAIRE` ; (d) cible inconnue, cible sans capacité `PARTENAIRE`, cible sans tenant propriétaire : `IllegalArgumentException`, rien écrit ; (e) `listerPartenaires` contient le partenaire créé avec état `ACTIF` ; (f) `remplacerModeleDroits` journalise et un droit invalide ne journalise rien ; (g) `PublicationContratsCoeurTest` : le registre sait fournir `GestionPartenairesContrat`.
- [ ] **Step 2: Exécuter** `-Dtest=GestionPartenairesContratTest` ; ÉCHEC attendu.
- [ ] **Step 3: Implémenter** l'extraction `creerClientPour`, le bean et la publication.
- [ ] **Step 4: Exécuter** `GestionPartenairesContratTest`, `PublicationContratsCoeurTest`, `ContratsClientsTest`, `ModularityTests`, `IdentiteIndependanceTest` ; PASSENT.
- [ ] **Step 5: Commit** `feat(clients): contrat de gestion des partenaires réservé à l'admin du maître`.

### Task 6 : Navigation — tuiles de la zone `ADMIN` pour l'admin du maître

**Files:**
- Modify: `src/main/java/com/logicielpourtous/site/features/plateforme/NavigationService.java`
- Modify: `frontend/intra/src/app/core/navigation/navigation.service.ts` et `frontend/intra/src/app/features/accueil/accueil.component.ts` seulement si un type ou un filtre limite les zones à `CLIENT | PARTENAIRE` (vérifier ; sinon aucun changement front)
- Test: `src/test/java/com/logicielpourtous/site/features/plateforme/NavigationServiceTest.java` et `NavigationControllerTest.java` (étendus) ; specs front touchées

**Interfaces:**
- Consumes: `IdentiteContrat.courante()` (déjà injecté dans `NavigationService`).
- Produces: dans `tuilesAutorisees(societeId)` (appel sans zone), si l'identité est **admin du maître** (`ROLE_ADMIN` dans `roles` et `roleServeur` = `MASTER`), les entrées de zone `ADMIN` des plugins actifs sont ajoutées, **sans contrôle de droit sur une société** (même si `societeId` est nul) ; pour tout autre appelant, jamais de tuile `ADMIN` (comme aujourd'hui). Dans `tuiles(zone, societeId)` avec `zone = ADMIN` : liste vide sauf admin du maître. Les tuiles `ADMIN` portent `zone = ADMIN` et la version du plugin. Les autres zones sont inchangées.

- [ ] **Step 1: Écrire les tests en échec.** (a) admin du maître sans société : la tuile `ADMIN` d'un manifeste de test apparaît dans `tuilesAutorisees(null)` ; (b) mêmes entrées pour un identité `ROLE_ADMIN` sur un serveur `PARTNER` : absentes ; (c) pour un partenaire ordinaire avec droits : absentes, y compris par `tuiles(ADMIN, societeId)` ; (d) l'admin du maître garde aussi ses tuiles PARTENAIRE/CLIENT si sa société en a (non-régression) ; (e) contrôleur : `GET /api/navigation` sans zone pour l'admin du maître renvoie la tuile `ADMIN` avec `zone:"ADMIN"` ; (f) front (si modifié) : l'accueil rend une carte pour une tuile `ADMIN`.
- [ ] **Step 2: Exécuter** `-Dtest=NavigationServiceTest` et `NavigationControllerTest` ; ÉCHEC attendu sur (a) et (e).
- [ ] **Step 3: Implémenter** la méthode privée `adminDuMaitre(IdentiteCourante)` et son usage ; ne rien changer au contrôle des droits des autres zones.
- [ ] **Step 4: Exécuter** les deux classes, `SecurityPlateformeTest`, puis les specs front du dossier `core/plugins` et `features/accueil` ; PASSENT.
- [ ] **Step 5: Commit** `feat(plateforme): tuiles d'administration pour l'admin du serveur maître`.

### Task 7 : Plugin `partenaires` — module, manifeste et routes

**Files:**
- Create: `plugins-partenaires/pom.xml` (copie adaptée de `plugins-clients/pom.xml` : artefact `site-plugin-partenaires`, version `1.0.0`, mêmes dépendances `provided`, **sans** build front pour cette tâche), `plugins-partenaires/.gitignore`
- Create: `plugins-partenaires/src/main/resources/plugin.properties`, `.../PartenairesConfiguration.java`, `.../PartenairesController.java` dans `plugins-partenaires/src/main/java/com/logicielpourtous/site/plugins/partenaires/`
- Test: `plugins-partenaires/src/test/java/com/logicielpourtous/site/plugins/partenaires/PartenairesControllerTest.java`, `ManifestePartenairesTest.java` (modèles : `ClientsControllerTest`, `ManifesteClientsTest`)

**Interfaces:**
- Consumes: `GestionPartenairesContrat` et ses enregistrements (tâche 3), `ManifestePlugin.depuis`.
- Produces: `plugin.properties` : `nom=partenaires`, `version=1.0.0`, `mise-a-jour=chaud`, `coeur-min=0.0.0`, `paquet=com.logicielpourtous.site.plugins.partenaires`, `configuration=...PartenairesConfiguration`, `entree.1=ADMIN|Partenaires|apartment|/partenaires|partenaires.gerer`. Routes (le noyau ajoute `/api/plugins/partenaires`) :
  - `GET /liste` → 200 liste de `PartenaireResume` ;
  - `POST /` corps `CreationPartenaire` → 201 `{societeId}` pour `CREE` ; 409 `{issue, message}` pour `SIREN_DEJA_CONNU` (« Ce SIREN est déjà enregistré ») et `EMAIL_DEJA_UTILISE` (« Cette adresse e-mail est déjà utilisée ») ; 400 corps générique pour `IllegalArgumentException` ;
  - `POST /{partenaireId}/clients` corps `{particulier, siren, tva, email, nom}` → crée via `creerClientPourPartenaire(new CreationClient(partenaireId, ..., false, ...))` : 201 / 409 (messages neutres du plugin `clients`) / 400 ;
  - `GET /modele-droits` → 200 liste ; `PUT /modele-droits` corps `{droits:[...]}` → 204 ou 400.
  Toute `SecurityException` du contrat → **403 corps vide** ; tout autre `RuntimeException` → 500 corps vide. `@PathVariable("partenaireId")` avec nom explicite. L'id du partenaire cible vient du chemin ; aucune autre donnée d'identité n'est lue par le plugin.

- [ ] **Step 1: Écrire les tests en échec** (MockMvc autonome, contrat simulé) : manifeste valide via `ManifestePlugin.depuis` (entrée `ADMIN`, droit `partenaires.gerer`) ; pour chaque route, `SecurityException` du contrat → 403 corps vide ; `CREE` → 201 ; `SIREN_DEJA_CONNU` et `EMAIL_DEJA_UTILISE` → 409 avec le message ci-dessus ; `IllegalArgumentException` → 400 sans le message d'origine ; `POST /{id}/clients` transmet bien `partenaireId` du chemin comme `partenaireSocieteId` et ignore un `partenaireSocieteId` présent dans le corps ; `PUT /modele-droits` transmet l'ensemble reçu.
- [ ] **Step 2: Exécuter** `mvn -B -o -f plugins-partenaires/pom.xml test` ; ÉCHEC attendu.
- [ ] **Step 3: Implémenter** le module, le manifeste, la configuration et le contrôleur.
- [ ] **Step 4: Exécuter** `mvn -B -o -f plugins-partenaires/pom.xml package` ; PASSE.
- [ ] **Step 5: Commit** `feat(plugin-partenaires): routes de création de partenaires et de clients`.

### Task 8 : Plugin `partenaires` — interface (élément personnalisé) livrée dans le jar

**Files:**
- Create: `plugins-partenaires/frontend/` (projet Angular 21.2 séparé, `@angular/elements`, sortie unique `main.js`, **sur le modèle exact de `plugins-clients/frontend/`** : `package.json`, `angular.json`, `tsconfig*.json`, `scripts/version.mjs` à encodage injectif identique, `src/main.ts`, `src/app/providers.ts` avec `APP_ID` = `'partenaires'`)
- Create: composants `partenaires-liste`, `partenaire-creation`, `partenaire-client-creation`, `modele-droits` et `partenaires-api.ts`, `partenaires-app.component.ts` (aiguillage interne par la propriété `chemin` : `/partenaires`, `/partenaires/nouveau`, `/partenaires/<id>/clients/nouveau`, `/partenaires/modele`), avec leurs `*.spec.ts`
- Modify: `plugins-partenaires/pom.xml` (build du front avec Node 22.17.1 comme `plugins-clients/pom.xml`, copie de `main.js` vers `target/classes/ui/main.js`), `.gitignore`
- Test: `plugins-partenaires/src/test/java/com/logicielpourtous/site/plugins/partenaires/FrontDansLeJarTest.java` (version lue dans `plugin.properties`, nom d'élément encodé comme le noyau)

**Interfaces:**
- Consumes: routes de la tâche 7 ; propriétés d'élément `chemin` et `jeton` (contrat du chargeur de front du lot A) ; élément `partenaires-app-v<version encodée>` (règle d'encodage injective de `PluginLoaderService.nomElement`, copiée dans `scripts/version.mjs`).
- Produces: `ui/main.js` dans le jar ; écrans : liste des partenaires (SIREN, raison sociale, état) avec bouton « Créer un client » ; formulaire « Créer un partenaire » (SIREN, TVA facultative, raison sociale, e-mail, nom) ; formulaire « Créer un client pour ce partenaire » (société ou particulier, comme le plugin `clients`) ; éditeur du modèle de droits (liste de droits, ajout et retrait). Toute donnée serveur est affichée par interpolation (jamais `innerHTML`) ; le jeton ne va que dans l'en-tête `Authorization: Bearer` ; les identifiants du chemin sont validés `^[0-9]{1,15}$` avant tout appel ; 403 → état « Accès réservé à l'administrateur du serveur maître ».

- [ ] **Step 1: Écrire les tests en échec** (Vitest, comme `plugins-clients/frontend`) : liste affichée et état d'erreur sur 403 ; création de partenaire envoie les cinq champs et affiche les messages 409 verbatim ; création de client envoie `particulier` ou SIREN/TVA selon le choix, jamais un `partenaireSocieteId` dans le corps ; éditeur du modèle : retrait d'un droit puis enregistrement envoie l'ensemble restant ; identifiant de chemin non numérique : aucun appel réseau ; texte hostile `<b>` affiché tel quel ; `providers.spec.ts` : `APP_ID` = `partenaires` ; `FrontDansLeJarTest` : le jar contient `ui/main.js` qui déclare le nom d'élément attendu et ne contient aucun `node_modules`.
- [ ] **Step 2: Exécuter** `npm test` dans `plugins-partenaires/frontend` ; ÉCHEC attendu.
- [ ] **Step 3: Implémenter** le projet front et le branchement Maven (copie du schéma de `plugins-clients/pom.xml`).
- [ ] **Step 4: Exécuter** `npm test` puis `mvn -B -o -f plugins-partenaires/pom.xml package` (en arrière-plan, sortie dans un fichier) ; PASSENT, `FrontDansLeJarTest` vert.
- [ ] **Step 5: Commit** `feat(plugin-partenaires): interface livrée par le plugin` (aucun `node_modules`, `dist` ni `target` commité).

### Task 9 : Vérification de bout en bout — `PluginPartenairesIT`

**Files:**
- Create: `src/test/java/com/logicielpourtous/site/features/plateforme/PluginPartenairesIT.java` (sur le modèle de `PluginClientsIT` et `JarClientsReel`)
- Create (si utile): `src/test/java/com/logicielpourtous/site/features/plateforme/JarPartenairesReel.java` (même rôle que `JarClientsReel` : localise `plugins-partenaires/target/partenaires-1.0.0.jar` et **échoue avec la commande de build** s'il est absent, sans jamais ignorer le test)

**Interfaces:**
- Consumes: jars réels `plugins-partenaires` et `plugins-clients`, `PluginManager.installer` avec signature obligatoire (clé de test comme `PluginClientsIT`), `JwtTokenProvider` (secret de 64 caractères via `@DynamicPropertySource`, `app.role=MASTER`, `app.plateforme.actif=true`).

- [ ] **Step 1: Écrire les tests en échec.** (a) admin du maître (rôle `ROLE_ADMIN`, serveur MASTER) : `GET /api/navigation` contient la tuile `partenaires` de zone `ADMIN` ; un partenaire ordinaire ne la voit pas ; (b) l'admin crée un partenaire par `POST /api/plugins/partenaires` (201) : le schéma du tenant existe, la société a `proprietaire_tenant_id`, l'utilisateur est manager ; (c) **boucle complète** : ce premier manager (mot de passe posé directement en base dans le test, jeton émis par `JwtTokenProvider`) voit la tuile `clients`, crée un client par `POST /api/plugins/clients` (201) — cela prouve que la correction « refuser un partenaire sans tenant propriétaire » est satisfaite par la création de partenaire ; (d) l'admin crée un client pour ce partenaire par `POST /api/plugins/partenaires/{id}/clients` (201) : le lien vise le partenaire cible, l'utilisateur du client a le tenant du partenaire, une ligne `CLIENT_CREE_POUR_PARTENAIRE` est journalisée ; ce client apparaît dans `GET /api/plugins/clients/liste` du partenaire cible et pas dans celle d'un autre partenaire ; (e) un partenaire ordinaire appelant `POST /api/plugins/partenaires` ou `/{id}/clients` : 403 corps vide, aucune ligne écrite ; sans jeton : 401 ; (f) doublon de SIREN et d'e-mail : 409 avec les messages et aucun tenant, schéma ni société en plus ; (g) modèle de droits : `PUT` retire `clients.entrer` ; un partenaire créé **ensuite** n'a pas ce droit, un partenaire créé **avant** le garde ; (h) désinstallation du plugin `partenaires` : la tuile disparaît, ses routes répondent 404, le plugin `clients` continue de fonctionner. Nettoyer en `@AfterEach` (schémas `p_*` créés, tenants, sociétés, utilisateurs, journaux).
- [ ] **Step 2: Exécuter** `-Dtest=PluginPartenairesIT` (après `mvn -B -o -f plugins-partenaires/pom.xml package` et `mvn -B -o -f plugins-clients/pom.xml package`) ; les cas déjà satisfaits par les tâches précédentes passent d'emblée : vérifier chaque test en le rendant fautif un instant (par exemple retirer la garde « admin du maître » ou la vérification de tenant), puis annuler.
- [ ] **Step 3: Corriger** ce que le test révèle (chaque correction de code de production dans son propre commit avec son test).
- [ ] **Step 4: Exécuter, chacune seule :** `PluginPartenairesIT`, `PluginClientsIT`, `PluginManagerIT`, `SecurityPlateformeTest`, `NavigationServiceTest`, `ContratsClientsTest`, `CreationPartenaireServiceTest`, `GestionPartenairesContratTest`, `ModularityTests`, `IdentiteIndependanceTest`, `InscriptionClientTest`, `ServeurAccueilServiceTest`, `FuiteMemoireIT` ; PASSENT.
- [ ] **Step 5: Commit** `test(plateforme): vérification de bout en bout du plugin partenaires`.

---

## Auto-revue

- **Couverture de la spec :** tenant transactionnel (T1) ; modèle de droits, journal, état du partenaire (T2) ; contrat (T3) ; création complète d'un partenaire avec droits de départ copiés, état `ACTIF` et date de début de validation, sans application des 30 jours (T4) ; vérification « admin du maître » par l'identité, création de clients pour un partenaire cible dans le tenant de la cible, journal, liste, modèle (T5) ; tuile d'administration (T6, nécessaire pour que l'admin atteigne le plugin : écart de la spec, l'accès à l'écran n'y était pas décrit) ; plugin et interface (T7, T8) ; chargé sur le seul maître : par l'installation du jar sur ce serveur et par la garde du noyau, qui refuse tout autre serveur (T5, T9) ; bout en bout (T9).
- **Cohérence des types :** `CreationPartenaire`, `ResultatPartenaire`, `PartenaireResume`, `GestionPartenairesContrat` définis en T3 et utilisés à l'identique en T4, T5, T7 ; `creerTenantTransactionnel` et `nouveauNomDeSchema` (T1) utilisés en T4 ; `ModeleDroitsPartenaireService.droits()/remplacer`, `JournalActionsMaitre.enregistrer`, `EtatPartenaireRepository` (T2) utilisés en T4 et T5.
- **Hors périmètre respecté :** pas d'inscription autonome, pas d'application automatique des états `LIMITE` / `BLOQUE`, pas d'écran fin des plafonds par partenaire.

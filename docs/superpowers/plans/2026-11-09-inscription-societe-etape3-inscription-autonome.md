# Inscription d'une société, étape 3 : inscription autonome (client et partenaire) — Plan d'implémentation

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rendre SIREN/SIRET et TVA obligatoires à l'inscription d'un client (autonome) et d'un partenaire (ESN), enregistrer la société au registre unique, et traiter une société déjà connue sans jamais créer de doublon : message neutre, demande enregistrée, notification.

**Architecture:** Le formulaire `/register` envoie les numéros ; un service `InscriptionSocieteService` (module `auth`) les contrôle avec `IdentiteSociete.depuis(...)` (étape 1), consulte `RegistreSocietes.trouver(...)`, puis soit crée la société + premier utilisateur (+ ligne `Appartenance`, voir étape 2), soit enregistre une `DemandeSocieteConnue` et notifie le responsable. `AuthController` ne garde que la lecture du formulaire et le choix de la vue.

**Tech Stack:** Spring Boot 4, JPA, Flyway (V6), Thymeleaf, JUnit 5 + AssertJ, `@SpringBootTest` pour les cas base de données.

**Spec:** `docs/superpowers/specs/2026-11-08-inscription-societe-design.md` (décisions 1, 3, 4, 5, 9 ; sections 3 et 4).

## Global Constraints

- Numéros : normalisation et contrôles **uniquement** via `IdentiteSociete.depuis(sirenBrut, siretBrut, tvaBrute)` ; `IdentiteInvalideException` porte le champ fautif (`siren`, `siret`, `tva`).
- Unicité : seul `RegistreSocietes.enregistrer(TypeSociete, IdentiteSociete, Long proprietaireTenantId)` écrit au registre ; `SocieteDejaConnueException` n'annule pas la transaction ; `EnregistrementConcurrentException` signifie transaction avortée, à rejouer dans une nouvelle transaction.
- Société étrangère (TVA ≠ `FR`) : refusée à l'inscription d'un client ou d'un partenaire (seuls les fournisseurs, créés par le Maître à l'étape 4).
- Anti-buts du spec : ne jamais révéler qu'une société est cliente de tel partenaire ; ne jamais énumérer les SIREN existants par des réponses différentes ; message public **identique** que la société soit déjà inscrite ou suivie par un partenaire : « Cette société est déjà connue de la plateforme. Une demande a été transmise à son responsable. »
- Un compte e-mail déjà utilisé garde le refus actuel (« Cet email est déjà utilisé. »), inchangé.
- Nouveaux utilisateurs : une ligne `Appartenance` est créée en même temps que le compte (corrige pour ce chemin la lacune « rows non synchronisées » de la revue finale de l'étape 2).
- Tests : `mvn -B -Dskip.installnodenpm=true -Dskip.npm=true test -Dtest=<Classe>` ; 8 tests de synchronisation échouent déjà sur `main` (`comments.author_id`), ignorés. Commentaires et messages en français.

## Review Focus

1. **Doublon par variante de saisie** : SIRET au lieu du SIREN, espaces, points, TVA en minuscules → la même société est reconnue « connue », jamais créée une 2e fois.
2. **Deux inscriptions simultanées de la même société** : la 2e reçoit le message « déjà connue », pas une erreur 500 ni un doublon.
3. **Énumération** : le message, le code HTTP et la redirection sont identiques pour « déjà inscrite » et « suivie par un partenaire ».
4. **Société étrangère** et TVA incohérente avec le SIREN : refus clair avec le champ fautif, rien créé.
5. **Rien d'orphelin** : si l'étape de création échoue en cours de route (e-mail, compte), ni société ni ligne de registre ne subsiste (une seule transaction).
6. **Partenaire (ESN)** : une demande refusée par l'administrateur ne réserve pas la société au registre ; l'approbation enregistre le type `PARTENAIRE`.

---

### Task 1: Migration V6, demande « société connue », numéros sur la demande d'ESN

**Files:**
- Create: `src/main/resources/db/migration/V6__inscription_autonome.sql`
- Create: `src/main/java/com/logicielpourtous/site/features/auth/DemandeSocieteConnue.java`, `DemandeSocieteConnueRepository.java`
- Modify: `src/main/java/com/logicielpourtous/site/features/registration/SubscriptionRequest.java`
- Test: `src/test/java/com/logicielpourtous/site/features/auth/DemandeSocieteConnueTest.java`

**Interfaces:**
- Produces: table `demandes_societe_connue(id BIGSERIAL, societe_identite_id BIGINT NOT NULL REFERENCES societes_identites(id), email_demandeur VARCHAR(255) NOT NULL, nom_demandeur VARCHAR(255), cree_a TIMESTAMPTZ NOT NULL, notifiee BOOLEAN NOT NULL DEFAULT FALSE)` ; entité `DemandeSocieteConnue` (Lombok `@Getter @Setter`, `schema = "public"`) avec ces champs (`Long societeIdentiteId`, `String emailDemandeur`, `String nomDemandeur`, `Instant creeA`, `boolean notifiee`) ; `DemandeSocieteConnueRepository extends JpaRepository<DemandeSocieteConnue, Long>` ; colonnes `siren VARCHAR(9)` et `vat_number VARCHAR(20)` ajoutées à `subscription_requests` et champs `siren`, `vatNumber` sur `SubscriptionRequest`.

- [ ] **Step 1: Test** — `DemandeSocieteConnueTest` (`@SpringBootTest @Transactional`) : sauvegarde une `DemandeSocieteConnue` liée à une `SocieteIdentite` créée via `RegistreSocietes`, relit par id, vérifie `creeA` non nul et `notifiee == false` ; sauvegarde une `SubscriptionRequest` avec `siren="552032534"` et `vatNumber="FR27552032534"` et vérifie la relecture.
- [ ] **Step 2: Lancer, constater l'échec** (classe/colonnes absentes).
- [ ] **Step 3: Implémenter** la migration (toutes les colonnes des entités doivent y être : `ddl-auto` n'est pas fiable), les deux entités/dépôts et les champs de `SubscriptionRequest`.
- [ ] **Step 4: Lancer** `DemandeSocieteConnueTest`, `FlywayMigrationTest`, `ModularityTests` ; attendu : verts.
- [ ] **Step 5: Commit** `feat(inscription): V6 demandes de societe connue et numeros sur les demandes d'ESN`.

### Task 2: Service d'inscription d'un client

**Files:**
- Create: `src/main/java/com/logicielpourtous/site/features/auth/InscriptionSocieteService.java`
- Modify: `src/main/java/com/logicielpourtous/site/features/auth/AuthController.java` (méthode `registerClient` déléguée au service)
- Test: `src/test/java/com/logicielpourtous/site/features/auth/InscriptionClientTest.java`

**Interfaces:**
- Consumes: `IdentiteSociete.depuis`, `RegistreSocietes.trouver/enregistrer`, `PortalUserService.createExplicit`, `AppartenanceRepository` (étape 2), `DemandeSocieteConnueRepository` (Task 1).
- Produces: `sealed interface ResultatInscription { record Creee(User utilisateur, String jetonVerification) ; record DejaConnue() }` et `ResultatInscription InscriptionSocieteService.inscrireClient(DemandeClient d)` où `record DemandeClient(String nomSociete, String nomComplet, String email, String motDePasse, String siren, String siret, String tva)` ; lève `IdentiteInvalideException` (numéros) et `IllegalArgumentException("Cet email est déjà utilisé.")`. Méthode `@Transactional`, qui rejoue une fois en cas d'`EnregistrementConcurrentException` puis renvoie `DejaConnue`.

- [ ] **Step 1: Tests** (`@SpringBootTest`, base réelle) : (a) société libre : crée `Customer` avec `siren`/`siret`/`vatNumber` normalisés, une ligne `SocieteIdentite` de type `CLIENT` liée (`ficheType="customers"`, `ficheId`), un `Site` « Siège », un `Contact`, un utilisateur inactif `GESTIONNAIRE` avec jeton, une `Appartenance` pour cet utilisateur ; (b) même société avec SIRET puis TVA en minuscules et espaces → `DejaConnue`, aucun second `Customer`, une `DemandeSocieteConnue` enregistrée ; (c) TVA `DE123456789` → `IdentiteInvalideException`, rien créé ; (d) e-mail déjà utilisé → `IllegalArgumentException`, rien créé ; (e) échec simulé après l'enregistrement au registre (envoi du compte qui lève) → aucune ligne `societes_identites` ni `customers` ne subsiste.
- [ ] **Step 2: Lancer, constater l'échec.**
- [ ] **Step 3: Implémenter** `inscrireClient` en reprenant la séquence actuelle de `registerClient` (client, siège, contact, compte portail `GESTIONNAIRE` inactif avec jeton), en y ajoutant le contrôle d'identité avant toute écriture, l'enregistrement au registre (`TypeSociete.CLIENT`, propriétaire `null` = Maître, puis `ficheType/ficheId` renseignés), la ligne d'appartenance et, pour une société connue, la `DemandeSocieteConnue`. L'envoi du mail de vérification reste dans `AuthController` après le commit.
- [ ] **Step 4: Adapter `registerClient`** : paramètres `siren`, `siret`, `tva`, appel du service, réponses : `Creee` → redirection actuelle `/register/email-sent` ; `DejaConnue` → redirection `/register/societe-connue` ; `IdentiteInvalideException` → retour au formulaire avec l'erreur sur le champ fautif.
- [ ] **Step 5: Lancer** `InscriptionClientTest` + tests d'`AuthController` existants (grep `register`) + `ModularityTests`.
- [ ] **Step 6: Commit** `feat(inscription): inscription client avec identite obligatoire et registre unique`.

### Task 3: Formulaire, page neutre « société connue » et notification

**Files:**
- Modify: `src/main/resources/templates/feature/auth/register.html` (onglet client : champs `siren`, `siret`, `tva`, libellés « SIREN ou SIRET » et « N° de TVA intracommunautaire »)
- Create: `src/main/resources/templates/feature/auth/register-societe-connue.html`
- Modify: `AuthController` (`GET /register/societe-connue`), `src/main/java/com/logicielpourtous/site/features/auth/NotificationSocieteConnue.java` (création)
- Test: `src/test/java/com/logicielpourtous/site/features/auth/InscriptionFormulaireTest.java`

**Interfaces:**
- Consumes: `DemandeSocieteConnue` (Task 1), `ResultatInscription` (Task 2), `MailTemplateService`/`GoogleMailService`/`MicrosoftMailService` (envoi existant, voir `AuthController.sendVerificationEmail`).
- Produces: `NotificationSocieteConnue.notifier(DemandeSocieteConnue d)` : envoie un e-mail à l'administrateur de la société si elle a un compte (utilisateur `GESTIONNAIRE` du `Customer` lié par `ficheId`), sinon à l'administrateur du partenaire propriétaire (`proprietaireTenantId` ; `null` = e-mail de l'administrateur du Maître), puis `notifiee = true`. Échec d'envoi : journalisé, jamais propagé (la réponse publique ne dépend pas de l'envoi).

- [ ] **Step 1: Tests** (`MockMvc`) : `POST /register` client avec société libre → 3xx vers `/register/email-sent` ; même société rejouée avec un autre e-mail → 3xx vers `/register/societe-connue` ; la page contient exactement la phrase neutre et aucun nom de partenaire ; société connue **inscrite** et société connue **suivie par un partenaire** donnent la même redirection et le même corps ; numéros invalides → 200 sur le formulaire avec l'erreur du champ ; un test unitaire de `NotificationSocieteConnue` vérifie le destinataire (administrateur de la société, à défaut propriétaire, à défaut Maître) avec un envoi simulé qui échoue sans exception.
- [ ] **Step 2: Lancer, constater l'échec.**
- [ ] **Step 3: Implémenter** formulaire, page neutre, notification, et l'appel à `notifier` après le commit pour `DejaConnue`.
- [ ] **Step 4: Lancer** `InscriptionFormulaireTest`, `CsrfCibleTest` (le formulaire garde son jeton CSRF), `ModularityTests`.
- [ ] **Step 5: Commit** `feat(inscription): formulaire client avec identite, page neutre et notification`.

### Task 4: Inscription d'un partenaire (ESN)

**Files:**
- Modify: `AuthController` (`registerEsn`), `register.html` (onglet ESN), `src/main/java/com/logicielpourtous/site/features/registration/SubscriptionRequestController.java` (approbation)
- Test: `src/test/java/com/logicielpourtous/site/features/auth/InscriptionEsnTest.java`

**Interfaces:**
- Consumes: Tasks 1-3.
- Produces: `registerEsn(...)` accepte `siren`, `siret`, `tva`, contrôle `IdentiteSociete.depuis` et **refuse** une société déjà au registre (mêmes message et redirection neutres, `DemandeSocieteConnue` + notification) sans rien réserver ; sinon stocke `siren` et `vatNumber` normalisés sur la `SubscriptionRequest`. À l'approbation, `RegistreSocietes.enregistrer(TypeSociete.PARTENAIRE, identite, null)` est appelé dans la même transaction que la création du tenant ; si la société est entre-temps connue, l'approbation échoue avec un message clair pour l'administrateur et la demande reste `PENDING`.

- [ ] **Step 1: Tests** : demande ESN valide → `SubscriptionRequest` avec numéros normalisés, aucune ligne de registre ; société déjà au registre → refus neutre, aucune `SubscriptionRequest` ; approbation → ligne `PARTENAIRE` créée ; rejet → aucune ligne ; approbation alors que la société a été inscrite entre-temps → erreur claire, demande toujours `PENDING`.
- [ ] **Step 2: Lancer, constater l'échec.**
- [ ] **Step 3: Implémenter** les trois points ci-dessus (formulaire, `registerEsn`, approbation).
- [ ] **Step 4: Lancer** `InscriptionEsnTest`, tests existants de `SubscriptionRequestController` (grep), `ModularityTests`.
- [ ] **Step 5: Commit** `feat(inscription): inscription partenaire avec identite et registre a l'approbation`.

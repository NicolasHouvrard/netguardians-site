# Moteur de synchronisation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Construire la synchronisation bidirectionnelle de `Request` entre un serveur en rôle `client` et un serveur en rôle `partner`/`master`, validée sur 2 instances locales, avec enrôlement par code à usage unique et détection de conflit côté partenaire.

**Architecture:** Nouveau module `features/sync`, découplé de `request` par événements de domaine Spring Data (`AbstractAggregateRoot`) pour ne pas refactoriser les points d'écriture existants. Transport REST toujours initié par le secondaire (push de son outbox + sondage périodique pour la redescente). Enrôlement par code à usage unique délivrant une clé API gérée centralement par le maître.

**Tech Stack:** Spring Boot 4.0.2, Spring Data JPA (`AbstractAggregateRoot`, `@DomainEvents`), Spring Modulith (Event Publication Registry déjà en place), `RestClient` Spring, PostgreSQL.

**Spec:** docs/superpowers/specs/2026-10-27-moteur-synchronisation-design.md (et le design doc parent docs/superpowers/specs/2026-10-26-architecture-distribuee-multi-serveurs-design.md, et le design doc de la Fondation déjà livrée docs/superpowers/specs/2026-10-26-fondation-modulith-role-sync-design.md pour le contexte)

## Global Constraints

- N'effectuer AUCUNE modification dans les modules `features/*` autres que `commun`, `request`, et le nouveau `sync`.
- Aucune nouvelle entité de ce plan n'étend `Syncable` — seule `Request` (déjà `Syncable` depuis la Fondation) traverse le réseau. `SecondaryServer`, `SyncOutboxEntry`, `SyncConflict` sont des entités purement locales à chaque serveur.
- Les appels réseau de synchronisation sont TOUJOURS initiés par le serveur secondaire (client ou partenaire configuré comme tel) — jamais par le maître. Le maître n'authentifie jamais une connexion sortante vers un secondaire.
- Détection de conflit UNIQUEMENT côté partenaire/maître (autorité de résolution) — le secondaire applique toujours ce qu'il reçoit du maître sans détection de conflit de son côté.
- Les commentaires privés (`Comment.isPublic = false`) ne redescendent JAMAIS vers un client — tout le reste (statut, assignation, commentaires publics) redescend normalement.
- La clé API n'est jamais exposée par une route applicative après l'enrôlement initial — seule une empreinte est stockée côté maître.
- Payload réseau = toujours un DTO explicite (jamais l'entité JPA sérialisée brute). Les références à des entités non-`Syncable` (équipe, technicien assigné) sont aplaties en champs d'affichage texte, pas des FK.
- Environnement de build (déjà résolu pendant la Fondation, à réutiliser identique) :
```bash
cd "C:\Users\nicolas\Desktop\springboot\site"
export JAVA_HOME="/c/Users/nicolas/scoop/apps/temurin25-jdk/current"
export PATH="/tmp/claude-maven/apache-maven-3.9.9/bin:$PATH"
mvn test -Dtest=NomDuTest -Dspring.profiles.active=local -Dspring.jpa.defer-datasource-initialization=true
```
  PostgreSQL local démarré (scoop, localhost:5432, `nicolas`/`Salocin14!`, db `site_db`). Après CHAQUE `mvn compile`/`mvn test` : `git checkout -- src/main/resources/static/app/index.html` (bruit de build connu, jamais à committer).

## Review Focus

- La publication fiable de l'événement de domaine quand un des ~15 points d'écriture existants de `Request`/`Comment` (répartis dans `RequestController`, `RequestManagerController`, `PublicRequestController`, `TicketController`) sauvegarde — un oubli sur UN SEUL point d'écriture rendrait cette entrée invisible à la synchronisation, silencieusement.
- La boucle d'écho : un événement appliqué localement PARCE QU'IL VIENT DE LA SYNCHRONISATION ne doit jamais redéclencher sa propre republication vers son serveur d'origine — sinon aller-retour infini entre client et partenaire.
- Les commentaires privés ne doivent JAMAIS apparaître dans un payload envoyé à un client — c'est la seule règle de confidentialité de ce chantier, une erreur ici expose du contenu interne.
- Une clé API révoquée ou un code d'enrôlement déjà utilisé doit être refusé immédiatement (401/410), pas silencieusement accepté.
- Un conflit détecté côté partenaire doit créer une alerte ET ne PAS appliquer l'événement conflictuel — un bug facile à introduire en inversant l'ordre (appliquer puis détecter).

---

### Task 1 : Module `features/sync` — entités locales et persistance

**Files:**
- Create: `src/main/java/com/logicielpourtous/site/features/sync/SecondaryServer.java`
- Create: `src/main/java/com/logicielpourtous/site/features/sync/SecondaryServerRepository.java`
- Create: `src/main/java/com/logicielpourtous/site/features/sync/SyncOutboxEntry.java`
- Create: `src/main/java/com/logicielpourtous/site/features/sync/SyncOutboxEntryRepository.java`
- Create: `src/main/java/com/logicielpourtous/site/features/sync/SyncConflict.java`
- Create: `src/main/java/com/logicielpourtous/site/features/sync/SyncConflictRepository.java`
- Create: `src/main/java/com/logicielpourtous/site/features/sync/package-info.java`
- Test: `src/test/java/com/logicielpourtous/site/features/sync/SyncEntitiesPersistenceTest.java`

**Interfaces:**
- Produces : `SecondaryServer` (champs : `id`, `nom` texte, `role` (`ServerRole`, réutilise l'enum de `commun`), `enrollmentCode` texte nullable (effacé après usage), `enrollmentCodeExpiresAt` instant nullable, `apiKeyHash` texte nullable (rempli après enrôlement), `statut` enum `ACTIF`/`REVOQUE` (réservé extension abonnement future), `creeA`/`misAJourA`). `SyncOutboxEntry` (champs : `id`, `entityType` texte (`"Request"` pour l'instant), `entitySyncId` UUID (= `Request.syncId`), `payload` texte (JSON sérialisé), `statut` enum `EN_ATTENTE`/`ENVOYE`/`ECHEC`, `tentatives` int, `creeA`). `SyncConflict` (champs : `id`, `entitySyncId` UUID, `versionAttendue` long, `versionRecue` long, `payloadRecu` texte, `resolu` boolean, `creeA`).
- Consumes : `ServerRole` (déjà produit par la Fondation, dans `commun`).

- [ ] **Step 1 : Écrire le test de persistance**

```java
@DataJpaTest
class SyncEntitiesPersistenceTest {
    @Test
    void persistsSecondaryServerWithDefaults() {
        SecondaryServer s = new SecondaryServer();
        s.setNom("Client test");
        s.setRole(ServerRole.CLIENT);
        SecondaryServer saved = secondaryServerRepository.save(s);
        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getStatut()).isEqualTo(SecondaryServerStatut.ACTIF);
    }

    @Test
    void persistsOutboxEntryAndConflict() {
        // instancie SyncOutboxEntry et SyncConflict avec des valeurs minimales,
        // sauvegarde via leurs repositories, vérifie un id généré sur chacun
    }
}
```

- [ ] **Step 2 : Run test, vérifier l'échec**

Run: `mvn test -Dtest=SyncEntitiesPersistenceTest -Dspring.profiles.active=local -Dspring.jpa.defer-datasource-initialization=true`
Expected: FAIL — les classes n'existent pas encore.

- [ ] **Step 3 : Implémenter les 3 entités + repositories + `package-info.java`**

`package-info.java` déclare `@ApplicationModule(allowedDependencies = {"commun"})` (ce module ne lit/écrit rien d'autre pour l'instant — `request` viendra en Task 2). `statut` par défaut `ACTIF` à la construction.

- [ ] **Step 4 : Run test, vérifier le succès**

Run: même commande qu'au Step 2.
Expected: PASS.

- [ ] **Step 5 : Commit**

```bash
git add src/main/java/com/logicielpourtous/site/features/sync/ src/test/java/com/logicielpourtous/site/features/sync/
git commit -m "feat(sync): entites locales SecondaryServer/SyncOutboxEntry/SyncConflict"
```

---

### Task 2 : Événements de domaine fiables sur `Request`/`Comment`, sans refactoriser les points d'écriture

**Files:**
- Modify: `src/main/java/com/logicielpourtous/site/features/commun/Syncable.java`
- Create: `src/main/java/com/logicielpourtous/site/features/request/RequestChangedEvent.java`
- Create: `src/main/java/com/logicielpourtous/site/features/request/CommentAddedEvent.java`
- Modify: `src/main/java/com/logicielpourtous/site/features/request/Comment.java`
- Modify: `src/main/java/com/logicielpourtous/site/features/request/RequestController.java`
- Modify: `src/main/java/com/logicielpourtous/site/features/request/RequestManagerController.java`
- Modify: `src/main/java/com/logicielpourtous/site/features/request/PublicRequestController.java`
- Modify: `src/main/java/com/logicielpourtous/site/features/request/TicketController.java`
- Create: `src/main/java/com/logicielpourtous/site/features/sync/RequestSyncListener.java`
- Modify: `src/main/java/com/logicielpourtous/site/features/sync/package-info.java` (créé en Task 1 — ajoute `"request"` à `allowedDependencies`, qui n'avait que `"commun"`)
- Test: `src/test/java/com/logicielpourtous/site/features/sync/RequestSyncListenerTest.java`

**Interfaces:**
- Consumes : `SyncOutboxEntryRepository` (Task 1).
- Produces : `RequestChangedEvent(Request request)`, `CommentAddedEvent(Comment comment)` — records simples. `RequestSyncListener` écoute ces 2 types et crée une `SyncOutboxEntry` (sérialisation du payload DTO laissée à la Task 3, ici une sérialisation minimale suffit pour prouver le déclenchement).

- [ ] **Step 1 : Preuve empirique du mécanisme, sur UN SEUL point d'écriture d'abord**

Avant de toucher les ~15 points d'écriture, écris UN test qui prouve que le mécanisme fonctionne vraiment avec Spring Data : fais `Syncable` étendre `org.springframework.data.domain.AbstractAggregateRoot<Syncable>` (classe abstraite — vérifie que ça compile avec `Request extends Syncable` existant, aucun autre changement d'héritage). Ajoute une méthode `Request.registerStatusChange()` qui appelle `this.registerEvent(new RequestChangedEvent(this))`. Écris :

```java
@DataJpaTest
class RequestSyncListenerTest {
    @Test
    void savingRequestPublishesEventWhenRegisteredBeforeSave() {
        Request r = requestRepository.save(new Request(/* champs minimaux */));
        r.setStatus(RequestStatus.IN_PROGRESS);
        r.registerStatusChange();
        requestRepository.save(r);  // <-- l'événement doit se publier ICI, à ce save()
        // vérifie via un listener de test (@EventListener enregistré dans le test,
        // ou @RecordApplicationEvents de Spring Test) que RequestChangedEvent a été publié exactement 1 fois
    }
}
```

**Important** : l'appel à `registerEvent(...)` doit se faire AVANT l'appel à `save()` qui publie, dans le même thread d'exécution — PAS dans un callback JPA (`@PrePersist`/`@PreUpdate`), dont le timing par rapport au mécanisme de publication de Spring Data n'est pas garanti. Si ce test échoue avec cette approche, c'est un signal que l'hypothèse de conception est fausse — reviens vers une alternative (`@DomainEvents`/`@AfterDomainEventPublication` annotés manuellement sur `Request`, documentés dans la Javadoc Spring Data) avant de continuer, et documente ce que tu as trouvé dans ton rapport.

- [ ] **Step 2 : Run test, vérifier l'échec puis le succès après implémentation**

Run: `mvn test -Dtest=RequestSyncListenerTest -Dspring.profiles.active=local -Dspring.jpa.defer-datasource-initialization=true`
Expected: FAIL (mécanisme pas encore implémenté), puis PASS après le Step 1.

- [ ] **Step 3 : Généraliser à tous les points d'écriture existants**

Pour chaque appel existant à `requestRepository.save(request)` qui suit une mutation significative (changement de statut, assignation équipe/technicien/type) dans `RequestController`, `RequestManagerController`, `PublicRequestController`, `TicketController` : insère `request.registerEvent(new RequestChangedEvent(request));` immédiatement avant l'appel `.save()`. Pour chaque appel à `commentRepository.save(comment)` sur un commentaire nouvellement créé : fais `Comment` étendre `AbstractAggregateRoot<Comment>` aussi (indépendamment de `Syncable`, `Comment` n'a pas besoin de `syncId`) et insère `comment.registerEvent(new CommentAddedEvent(comment));` avant ce `.save()`. Liste exhaustive des call sites à toi de dresser par grep (`requestRepository.save(` et `commentRepository.save(` dans les 4 fichiers) — ne pas en manquer un (Review Focus).

- [ ] **Step 4 : `RequestSyncListener` dans `features/sync`**

`@Component`, `@EventListener` sur `RequestChangedEvent` ET `CommentAddedEvent`, crée une `SyncOutboxEntry` minimale (`entityType="Request"`, `entitySyncId` = le `syncId` du `Request` concerné — pour `CommentAddedEvent`, remonte au `Request` parent du commentaire). `features/sync/package-info.java` : ajoute `"request"` à `allowedDependencies` (déjà `"commun"` depuis la Task 1).

- [ ] **Step 5 : Test de bout en bout du déclenchement généralisé**

Écris au moins 2 scénarios (un via `RequestController`, un via `TicketController`) qui sauvegardent une `Request` modifiée via le chemin réel du contrôleur (pas un appel direct au repository dans le test) et vérifient qu'une `SyncOutboxEntry` a bien été créée.

Run: `mvn test -Dtest=RequestSyncListenerTest -Dspring.profiles.active=local -Dspring.jpa.defer-datasource-initialization=true`
Expected: PASS.

- [ ] **Step 6 : Commit**

```bash
git add src/main/java/com/logicielpourtous/site/features/commun/Syncable.java src/main/java/com/logicielpourtous/site/features/request/ src/main/java/com/logicielpourtous/site/features/sync/ src/test/java/com/logicielpourtous/site/features/sync/
git commit -m "feat(sync): evenements de domaine sur Request/Comment, alimentent l'outbox"
```

---

### Task 3 : Enrôlement + endpoints REST + authentification

**Files:**
- Create: `src/main/java/com/logicielpourtous/site/features/sync/EnrollmentController.java`
- Create: `src/main/java/com/logicielpourtous/site/features/sync/SyncEventsController.java`
- Create: `src/main/java/com/logicielpourtous/site/features/sync/SyncApiKeyAuthFilter.java`
- Create: `src/main/java/com/logicielpourtous/site/features/sync/RequestSyncPayload.java`
- Modify: `src/main/java/com/logicielpourtous/site/config/SecurityConfig.java`
- Test: `src/test/java/com/logicielpourtous/site/features/sync/EnrollmentControllerTest.java`
- Test: `src/test/java/com/logicielpourtous/site/features/sync/SyncEventsControllerTest.java`

**Interfaces:**
- Consumes : `SecondaryServer`/`SyncOutboxEntry`/`SyncConflict` (Task 1), `RequestChangedEvent`/`CommentAddedEvent` payload (Task 2).
- Produces : `POST /api/sync/enroll` (body `{enrollmentCode}`, réponse `{apiKey}` — valeur en clair, UNE SEULE FOIS) ; `POST /api/sync/events` (authentifié, body = liste de `RequestSyncPayload`, enregistre côté maître) ; `GET /api/sync/events?since=<instant>` (authentifié, renvoie les `RequestSyncPayload` en attente pour CE secondaire).

- [ ] **Step 1 : `RequestSyncPayload` (DTO)**

Champs : `syncId` (UUID), `version` (long), `status`, `type`, `priority`, `title`, `description`, `assignedTeamName` (texte, nullable), `assignedToName` (texte, nullable), `publicComments` (liste de `{content, createdAt}` — UNIQUEMENT `isPublic=true`). Pas de test dédié (type de données pur), mais utilisé par les tests des steps suivants.

- [ ] **Step 2 : Écrire le test d'enrôlement**

```java
@Test
void enrollWithValidCodeReturnsApiKeyOnce() {
    // crée un SecondaryServer avec un enrollmentCode connu et une expiration future
    // POST /api/sync/enroll {enrollmentCode} -> 200, body contient apiKey non vide
    // relecture du SecondaryServer en base : enrollmentCode est maintenant null (usage unique),
    // apiKeyHash est non-null et NE CORRESPOND PAS à la valeur en clair reçue (c'est bien un hash)
}

@Test
void enrollWithAlreadyUsedCodeIsRejected() {
    // 2e appel avec le même code (déjà consommé par le test précédent, ou simulé directement) -> 410 Gone ou 401
}
```

- [ ] **Step 3 : Run, FAIL, implémenter `EnrollmentController`, Run, PASS**

Hash de la clé générée : réutilise `features/commun/CryptoConfig`/un hash SHA-256 simple — à toi de choisir le plus cohérent avec l'existant du dépôt, documente ton choix dans le rapport.

Run: `mvn test -Dtest=EnrollmentControllerTest -Dspring.profiles.active=local -Dspring.jpa.defer-datasource-initialization=true`

- [ ] **Step 4 : Écrire les tests des endpoints push/pull**

```java
@Test
void pushEventsWithoutApiKeyIsRejected() {
    // POST /api/sync/events sans header X-Sync-Api-Key -> 401
}

@Test
void pushEventsWithRevokedKeyIsRejected() {
    // SecondaryServer avec statut=REVOQUE -> 401 même avec la bonne clé
}

@Test
void pullEventsReturnsOnlyEntriesForThatSecondaryServer() {
    // crée 2 SyncOutboxEntry, une destinée au secondaire testé, une à un autre
    // GET /api/sync/events avec la clé du 1er secondaire -> ne renvoie QUE la sienne
}
```

- [ ] **Step 5 : Run, FAIL, implémenter `SyncApiKeyAuthFilter` + `SyncEventsController`, Run, PASS**

`SyncApiKeyAuthFilter` : filtre Spring Security (ou intercepteur, cohérent avec `ModuleSecurityInterceptor` déjà existant dans `config/`) qui vérifie l'en-tête `X-Sync-Api-Key` contre l'empreinte stockée, et le `statut=ACTIF` du `SecondaryServer` correspondant. Ajoute les 2 routes `/api/sync/**` à `SecurityConfig.java` avec ce filtre (pas le mécanisme JWT/session existant — l'authentification de ces routes est entièrement portée par la clé API).

- [ ] **Step 6 : Commit**

```bash
git add src/main/java/com/logicielpourtous/site/features/sync/ src/main/java/com/logicielpourtous/site/config/SecurityConfig.java src/test/java/com/logicielpourtous/site/features/sync/
git commit -m "feat(sync): enrolement + endpoints REST push/pull + authentification par cle API"
```

---

### Task 4 : Job planifié côté secondaire (push + pull) et détection de conflit côté partenaire

**Files:**
- Create: `src/main/java/com/logicielpourtous/site/features/sync/SyncOutboxScheduler.java`
- Create: `src/main/java/com/logicielpourtous/site/features/sync/SyncClient.java`
- Modify: `src/main/java/com/logicielpourtous/site/features/sync/SyncEventsController.java`
- Test: `src/test/java/com/logicielpourtous/site/features/sync/SyncOutboxSchedulerTest.java`
- Test: `src/test/java/com/logicielpourtous/site/features/sync/ConflictDetectionTest.java`

**Interfaces:**
- Consumes : `SyncOutboxEntryRepository`, `SyncClient` interne à cette tâche, `RequestSyncPayload` (Task 3).
- Produces : `SyncOutboxScheduler` (`@Scheduled`, actif uniquement si `app.sync.master-url` est configuré — i.e. ce serveur EST un secondaire). `RequestSyncPayloadMapper` (mappe `Request` → `RequestSyncPayload`, filtre les commentaires). Applique la détection de conflit lors de la réception (côté maître, dans `SyncEventsController.pushEvents`, pas côté secondaire).

- [ ] **Step 1 : Écrire le test du filtrage des commentaires privés (Review Focus — priorité avant tout le reste de cette tâche)**

```java
@Test
void mapperNeverIncludesPrivateComments() {
    // Request avec 2 Comment : un isPublic=true ("Visible"), un isPublic=false ("Interne, jamais envoye")
    RequestSyncPayload payload = mapper.toPayload(request);
    assertThat(payload.publicComments())
        .extracting("content")
        .containsExactly("Visible")  // et surtout PAS "Interne, jamais envoye"
        .doesNotContain("Interne, jamais envoye");
}
```

Run: `mvn test -Dtest=RequestSyncPayloadMapperTest -Dspring.profiles.active=local -Dspring.jpa.defer-datasource-initialization=true` → FAIL (mapper inexistant), puis implémente `RequestSyncPayloadMapper` (filtre explicite sur `Comment.isPublic`), puis PASS.

- [ ] **Step 2 : Écrire le test de détection de conflit (le 2e point le plus important de cette tâche)**

```java
@Test
void receivingEventWithStaleVersionCreatesConflictInsteadOfApplying() {
    // Request en base, version=3, title="Original"
    // POST /api/sync/events avec un RequestSyncPayload {syncId, version=2, title="Modifie par le client"}
    //   (version=2 = périmée, la vraie version actuelle est 3)
    // -> la Request en base garde title="Original" (PAS appliqué)
    // -> une SyncConflict a été créée (entitySyncId correspondant, versionAttendue=3, versionRecue=2)
}

@Test
void receivingEventWithCurrentVersionAppliesNormally() {
    // meme scenario mais version=3 (a jour) -> Request mise a jour, PAS de SyncConflict créée
}
```

- [ ] **Step 3 : Run, FAIL, implémenter la détection dans `SyncEventsController.pushEvents`, Run, PASS**

Compare `payload.version` à l'entité `Request` actuelle (résolue par `syncId`) AVANT tout `save()`. Si différente : crée `SyncConflict`, ne sauvegarde pas la `Request`, retourne 200 quand même (le secondaire ne doit pas re-essayer indéfiniment un événement déjà livré — c'est un conflit à trancher côté maître, pas un échec réseau).

- [ ] **Step 4 : Écrire le test du scheduler (push)**

```java
@Test
void schedulerPushesPendingOutboxEntriesAndMarksThemSent() {
    // SyncOutboxEntry statut=EN_ATTENTE en base
    // mock/stub SyncClient pour simuler un appel REST réussi
    // déclenche manuellement la méthode @Scheduled
    // -> l'entrée passe à statut=ENVOYE
}

@Test
void schedulerMarksEchecOnNetworkFailureWithoutCrashing() {
    // SyncClient simule une exception réseau -> statut passe à ECHEC, tentatives incrémenté,
    // le job ne lève pas d'exception non gérée
}
```

- [ ] **Step 5 : Run, FAIL, implémenter `SyncClient` (RestClient Spring) + `SyncOutboxScheduler`, Run, PASS**

`SyncClient` lit `app.sync.master-url`/`app.sync.api-key` depuis la config (propriétés laissées au porteur du plan, cf. design doc). Backoff simple : incrémente `tentatives`, pas de nouvelle tentative avant un délai croissant basique (ex. `tentatives * 5` secondes) — pas de bibliothèque de retry externe.

- [ ] **Step 6 : Écrire le test du scheduler (pull) + prévention de l'écho**

```java
@Test
void pullAppliesReceivedPayloadAndDoesNotRequeueItForOutbox() {
    // simule un GET /api/sync/events retournant un RequestSyncPayload
    // -> la Request locale correspondante (par syncId) est mise a jour
    // -> AUCUNE nouvelle SyncOutboxEntry n'est créée pour ce changement
    //    (sinon boucle infinie client<->partenaire)
}
```

Prouve la prévention d'écho en vérifiant directement que le code qui applique un événement REÇU écrit via le repository SANS passer par les méthodes qui appellent `registerEvent(...)` (Task 2) — donc aucun événement de domaine n'est publié pour cette écriture, donc `RequestSyncListener` ne crée aucune nouvelle entrée outbox.

- [ ] **Step 7 : Run, FAIL, implémenter, Run, PASS puis commit**

```bash
git add src/main/java/com/logicielpourtous/site/features/sync/ src/test/java/com/logicielpourtous/site/features/sync/
git commit -m "feat(sync): job planifie push/pull cote secondaire + detection de conflit cote maitre"
```

---

### Task 5 : Validation de bout en bout sur 2 instances locales

**Files:**
- Create: `src/main/resources/application-sync-client.properties` (profil de test, port + `app.role=CLIENT` + `app.sync.master-url`)
- Create: `src/main/resources/application-sync-partner.properties` (profil de test, autre port + `app.role=PARTNER`)
- Test: `src/test/java/com/logicielpourtous/site/features/sync/EndToEndTwoInstancesTest.java`

**Interfaces:**
- Consumes : tout ce qui précède.
- Produces : preuve que le cycle complet fonctionne réellement sur 2 processus Spring Boot distincts (pas seulement des tests unitaires avec des mocks).

- [ ] **Step 1 : Décrire le scénario de validation manuelle**

Documente dans le rapport de cette tâche les commandes exactes pour lancer les 2 instances localement (2 ports, 2 profils, 2 schémas ou 2 bases PostgreSQL locales distinctes), le code d'enrôlement généré manuellement côté "partenaire", sa saisie côté "client".

- [ ] **Step 2 : Exécuter le scénario réel : création côté client → visible côté partenaire**

Crée une `Request` via l'API du serveur "client", attends le prochain cycle du scheduler (ou déclenche-le manuellement), vérifie par une requête directe sur la base "partenaire" que la `Request` existe avec le même `syncId`.

- [ ] **Step 3 : Exécuter le scénario réel : changement côté partenaire → visible côté client**

Modifie le statut de cette `Request` côté "partenaire" (via son API), attends/déclenche le sondage du client, vérifie côté "client" que le statut a changé.

- [ ] **Step 4 : Exécuter le scénario conflit réel**

Coupe la connectivité simulée (ou modifie directement les 2 côtés avant tout sondage), vérifie qu'une `SyncConflict` apparaît côté "partenaire" et que la donnée n'a pas été silencieusement écrasée.

- [ ] **Step 5 : Documenter le résultat et committer les fichiers de profil + le test (le test peut rester `@Disabled` par défaut s'il nécessite 2 process réels — documente pourquoi dans son commentaire de classe)**

```bash
git add src/main/resources/application-sync-client.properties src/main/resources/application-sync-partner.properties src/test/java/com/logicielpourtous/site/features/sync/EndToEndTwoInstancesTest.java
git commit -m "test(sync): validation bout-en-bout sur 2 instances locales"
```

---

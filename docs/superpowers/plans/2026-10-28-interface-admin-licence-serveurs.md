# Interface d'administration — licences et enrôlement automatique des serveurs — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Un serveur maître principal (MP) centralise des comptes/abonnements qui enrôlent automatiquement (login/mot de passe pour un partenaire, jeton par client généré par le partenaire) les serveurs partenaires et leurs clients, sans aucune manipulation manuelle de code — plus un écran de gestion des serveurs secondaires/conflits (I-4) sur chaque serveur maître-capable.

**Architecture:** Nouveau module Spring Modulith `licence`. Le MP garde les comptes/serveurs-licenciés/jetons dans des tables dédiées (pas de réutilisation des tables de `sync`, pour qu'une clé de licence ne puisse jamais s'authentifier sur `/api/sync/**` ou l'inverse). Chaque serveur non-MASTER porte un petit composant d'auto-enrôlement qui appelle le MP une seule fois (login/mot de passe ou jeton), stocke sa clé de licence localement, puis écrit sa config résolue et demande un redémarrage manuel. Le partenaire découvre ses nouveaux clients par un sondage périodique vers le MP (jamais l'inverse) et crée alors un VRAI `SecondaryServer` local — le mécanisme de synchronisation de données déjà livré n'est pas modifié.

**Tech Stack:** Spring Boot, Spring Modulith, Spring Security (filtres par en-tête, BCrypt), Thymeleaf (écrans admin `intra`), PostgreSQL, JUnit/MockMvc.

**Spec:** `docs/superpowers/specs/2026-10-28-interface-admin-licence-serveurs-design.md` (lire aussi `docs/superpowers/specs/2026-10-27-moteur-synchronisation-design.md` pour le contexte du moteur de synchronisation déjà livré, que ce plan ne modifie pas).

## Global Constraints

- Aucune modification du protocole de synchronisation de données déjà livré (push/pull, détection de conflit, filtrage des commentaires privés, plafond de tentatives, `RequestSyncApplier`, `SyncOutboxScheduler`) — ce plan ajoute une couche au-dessus, il ne touche à aucun fichier de `features/sync` qui porte ce protocole, sauf pour LIRE `SecondaryServer`/`SyncConflict` (listés explicitement tâche par tâche).
- `SecondaryServer`/`SyncOutboxEntry`/`SyncConflict` restent des entités purement locales à chaque serveur (contrainte déjà actée, non rouverte).
- Nouveau module Spring Modulith `licence`, `allowedDependencies = {"commun", "customers", "sync"}` — jamais l'inverse. `sync`/`customers`/`commun` ne doivent rien connaître de `licence`.
- **Déviation volontaire par rapport au câblage précis du design doc** : le design doc proposait que le MP traite chaque compte enrôlé comme un `SecondaryServer` de `sync`. Ce plan NE LE FAIT PAS : le MP garde ses propres tables (`CompteLicence`, `LicenceServeur`, `JetonClient`) et sa propre empreinte de clé, avec son propre filtre `/api/licence/**` — jamais la table `secondary_servers` ni `SyncApiKeyAuthFilter`. Raison : une clé de licence qui s'authentifierait par erreur sur `/api/sync/**` (ou l'inverse) donnerait accès aux données métier (`Request`/`Comment`) du MP avec de simples identifiants de revente. Dupliquer la petite classe utilitaire de génération/empreinte de clé (15 lignes, voir `SyncApiKeys`) coûte moins que ce risque. `licence` dépend bien de `sync` (lecture/écriture de `SecondaryServer`/`SyncConflict` pour l'écran I-4 et pour la matérialisation locale côté partenaire), seulement jamais pour la clé elle-même.
- Mot de passe de `CompteLicence` haché via le `PasswordEncoder` (BCrypt) déjà exposé par `SecurityConfig` — jamais stocké ni journalisé en clair.
- Un seul serveur `PARTNER` par `CompteLicence` (refusé sinon, erreur claire).
- "Déclenche un redémarrage" (étape 7/9 du design) = un message de log explicite demandant un redémarrage manuel, PAS une tentative de redémarrage programmatique de la JVM (environnement de déploiement réel des partenaires/clients inconnu à ce stade — ruling du porteur du plan, cohérent avec YAGNI).
- Environnement de build identique à celui déjà en place :
```bash
cd "C:\Users\nicolas\Desktop\springboot\site"
export JAVA_HOME="/c/Users/nicolas/scoop/apps/temurin25-jdk/current"
export PATH="/tmp/claude-maven/apache-maven-3.9.9/bin:$PATH"
mvn test -Dtest=NomDuTest -Dspring.profiles.active=local -Dspring.jpa.defer-datasource-initialization=true
```
  PostgreSQL local déjà démarré (`nicolas`/`Salocin14!`, `site_db`). Après CHAQUE `mvn compile`/`mvn test` : `git checkout -- src/main/resources/static/app/index.html`. Ne JAMAIS faire de `DROP DATABASE`/`CREATE DATABASE` sans consentement explicite de l'utilisateur dans la conversation.

## Review Focus

- Un quota de sièges dépassé doit être refusé avec une erreur claire au serveur qui tente de s'enrôler, jamais un faux succès ni un silence (anti-goal explicite du design).
- Un code d'enrôlement ou un jeton déjà consommé doit être refusé (comme l'existant `EnrollmentController` le fait déjà pour son propre code) — un rejeu ne doit jamais réussir une seconde fois.
- Un redémarrage, un retry réseau ou une réinstallation du même poste ne doit jamais consommer un second siège — l'identifiant d'installation stable doit être reconnu, pas seulement l'adresse déclarée.
- Une clé de licence présentée sur `/api/sync/**` doit être refusée (401), et symétriquement une clé `X-Sync-Api-Key` présentée sur `/api/licence/**` doit être refusée — les deux mécanismes ne doivent jamais s'authentifier l'un sur l'autre.
- Une révocation doit réellement couper un serveur déjà actif (pas seulement bloquer un futur enrôlement) au prochain sondage de son partenaire — pas seulement mettre à jour un champ que personne ne relit.

---

### Task 1 : Module `licence` + `CompteLicence` + écran de gestion des comptes (MP)

**Files:**
- Create: `src/main/java/com/logicielpourtous/site/features/licence/package-info.java`
- Create: `src/main/java/com/logicielpourtous/site/features/licence/CompteLicence.java`
- Create: `src/main/java/com/logicielpourtous/site/features/licence/CompteLicenceRepository.java`
- Create: `src/main/java/com/logicielpourtous/site/features/licence/CompteLicenceAdminController.java`
- Create: `src/main/resources/templates/feature/licence/comptes.html`
- Test: `src/test/java/com/logicielpourtous/site/features/licence/CompteLicenceAdminControllerTest.java`
- Modify: `src/test/java/com/logicielpourtous/site/ModularityTests.java` (ajoute `"licence"` à `MODULES_VERIFIES`)

**Interfaces:**
- Consumes: `ServerRole`/`ServerRoleProperties` (module `commun`, déjà existant) pour savoir si CETTE instance est `MASTER` ; `PasswordEncoder` (bean déjà exposé par `SecurityConfig`).
- Produces : `CompteLicence` — `id: Long`, `login: String` (unique), `motDePasseHash: String`, `typeAbonnement: TypeAbonnement` (enum, une valeur `ILLIMITE` pour l'instant), `frequence: Frequence` (enum `MENSUEL`/`ANNUEL`), `nombreServeursAutorises: int`, `actif: boolean` (défaut `true`), `creeA: Instant`. `CompteLicenceRepository.findByLogin(String): Optional<CompteLicence>`.

- [ ] **Step 1 : Créer le module et son `package-info.java`**

```java
@org.springframework.modulith.ApplicationModule(allowedDependencies = {"commun", "customers", "sync"})
package com.logicielpourtous.site.features.licence;
```

- [ ] **Step 2 : Écrire le test de l'écran (`MockMvc`, rôle admin, instance `MASTER`)**

```java
@Test
void createCompte_hashesPasswordAndPersists() { /* POST sur le formulaire de création, GET la liste, assert motDePasseHash != "secret-en-clair" saisi */ }

@Test
void listComptes_showsActifAndServeurCount() { /* ... */ }

@Test
void deactivateCompte_setsActifFalse() { /* ... */ }

@Test
void adminScreen_notAvailableWhenRoleIsNotMaster() { /* app.role=PARTNER dans ce test -> 404 ou redirect, à toi de choisir le mécanisme exact (ex. un @ConditionalOnProperty sur le @Controller, ou un garde explicite) */ }
```

- [ ] **Step 2b : Run, vérifier l'échec** (classe/route inexistantes).

- [ ] **Step 3 : Implémenter `CompteLicence`, `CompteLicenceRepository`, `CompteLicenceAdminController`**

Controller mappé sous `/intra/licence/comptes`, rôle `ROLE_ADMIN` (même garde que les autres écrans `intra` existants). Hache le mot de passe reçu du formulaire avec le `PasswordEncoder` injecté avant `save()`. La garde "disponible seulement si `app.role=MASTER`" : choix libre (le plus simple : lire `ServerRoleProperties` dans le controller et rediriger/404 sinon).

- [ ] **Step 4 : Template `comptes.html`** (layout `intra-layout`, cohérent avec les écrans admin existants du dépôt) — liste + formulaire de création + bouton activer/désactiver.

- [ ] **Step 5 : Run, vérifier le succès.**

- [ ] **Step 6 : Étendre `ModularityTests.MODULES_VERIFIES` avec `"licence"`, run, vérifier le succès** (aucun cycle attendu : `licence` ne dépend que de `commun`/`customers`/`sync`, dans ce sens uniquement).

- [ ] **Step 7 : Commit.**

```bash
git checkout -- src/main/resources/static/app/index.html
git add src/main/java/com/logicielpourtous/site/features/licence src/main/resources/templates/feature/licence src/test/java/com/logicielpourtous/site/features/licence src/test/java/com/logicielpourtous/site/ModularityTests.java
git commit -m "feat(licence): module licence, CompteLicence et ecran de gestion (MP)"
```

---

### Task 2 : `LicenceServeur` + enrôlement automatique d'un partenaire (login/mot de passe) + échange de clé

**Files:**
- Create: `src/main/java/com/logicielpourtous/site/features/licence/LicenceServeur.java`
- Create: `src/main/java/com/logicielpourtous/site/features/licence/LicenceServeurRepository.java`
- Create: `src/main/java/com/logicielpourtous/site/features/licence/LicenceCles.java` (générer/empreinte — duplique volontairement `SyncApiKeys`, voir Global Constraints)
- Create: `src/main/java/com/logicielpourtous/site/features/licence/LicenceEnrolementController.java`
- Create: `src/main/java/com/logicielpourtous/site/features/licence/LicenceApiKeyAuthFilter.java`
- Modify: `src/main/java/com/logicielpourtous/site/config/SecurityConfig.java` (nouvelle `@Bean @Order licenceApiFilterChain`, matcher `/api/licence/**`)
- Modify: `src/main/java/com/logicielpourtous/site/features/licence/CompteLicenceAdminController.java` (Task 1 — ajoute le détail d'un compte et ses `LicenceServeur`)
- Modify: `src/main/resources/templates/feature/licence/comptes.html` (Task 1 — ajoute la liste des `LicenceServeur`, adresse éditable, révocation)
- Test: `src/test/java/com/logicielpourtous/site/features/licence/LicenceEnrolementControllerTest.java`
- Test: `src/test/java/com/logicielpourtous/site/features/licence/LicenceApiKeyAuthFilterTest.java` (ou intégré au test ci-dessus)

**Interfaces:**
- Consumes : `CompteLicence`/`CompteLicenceRepository` (Task 1). `ServerRole` (`commun`).
- Produces : `LicenceServeur` — `id: Long`, `compteId: Long`, `role: ServerRole` (`PARTNER` ou `CLIENT`), `identifiantInstallationStable: String` (UUID, unique), `adresseDeclaree: String`, `customerId: Long` (nullable, rempli seulement pour `CLIENT`, voir Task 4), `statut: LicenceServeurStatut` (`ACTIF`/`REVOQUE`), `codeEnrolement: String` (nullable), `codeEnrolementExpireA: Instant`, `codeEnrolementUtilise: boolean`, `cleApiHash: String` (nullable), `creeA: Instant`. `LicenceCles.generate(): String` / `LicenceCles.hash(String): String` (même contrat que `SyncApiKeys`). `POST /api/licence/enregistrer` et `POST /api/licence/cle` (contrats ci-dessous, consommés par Task 3).

- [ ] **Step 1 : Tests de `POST /api/licence/enregistrer` (login/mot de passe, rôle `PARTNER`)**

```java
@Test
void enregistrer_newPartnerUnderQuota_createsLicenceServeurAndReturnsCode() { /* compte avec nombreServeursAutorises=5, 0 serveur -> 200, code non null, expireA futur */ }

@Test
void enregistrer_quotaReached_isRejectedClearly() { /* compte avec nombreServeursAutorises=1, 1 LicenceServeur ACTIF déjà -> pas 200, pas de 2e LicenceServeur créé */ }

@Test
void enregistrer_sameIdentifiantInstallationStableRetried_doesNotConsumeASecondSeat() { /* 2 appels successifs, même identifiantInstallationStable, compte à quota=1 -> 1 seul LicenceServeur au total, le 2e appel renvoie un code (le même ou un nouveau, au choix) sans erreur de quota */ }

@Test
void enregistrer_secondPartnerUnderSameCompte_isRejected() { /* 1er appel PARTNER OK, 2e appel PARTNER sous le même compte (identifiant d'installation DIFFERENT) -> rejeté */ }

@Test
void enregistrer_wrongPassword_isRejected() { /* identifiants invalides -> pas 200, rien créé */ }
```

- [ ] **Step 2 : Run, vérifier l'échec.**

- [ ] **Step 3 : Implémenter `LicenceCles`, `LicenceServeur`, `LicenceServeurRepository`, et `POST /api/licence/enregistrer` dans `LicenceEnrolementController`**

Logique : résoudre le `CompteLicence` par login+mot de passe (`PasswordEncoder.matches`), vérifier `actif`. Si un `LicenceServeur` existe déjà pour `(compteId, identifiantInstallationStable)` : le réutiliser (idempotence), sinon vérifier le quota (`count(statut=ACTIF) < nombreServeursAutorises`) et, si `roleVoulu=PARTNER`, qu'aucun autre `PARTNER` n'existe déjà sous ce compte. Créer/mettre à jour, générer `codeEnrolement` (`LicenceCles.generate()`, mais en clair ici — c'est un code à usage unique comme celui déjà existant de `sync`, pas une clé API) + `codeEnrolementExpireA` (même durée que l'existant `EnrollmentController`, ou une valeur à toi de choisir si tu documentes pourquoi), renvoyer `{codeEnrolement, codeEnrolementExpireA}`.

- [ ] **Step 4 : Tests de `POST /api/licence/cle` (échange code → clé API)**

```java
@Test
void cle_validCode_returnsApiKeyOnceAndMarksCodeUsed() { /* renvoie une cle en clair, codeEnrolementUtilise=true, cleApiHash renseigne (pas la cle en clair) */ }

@Test
void cle_alreadyUsedCode_isRejected() { /* rejoue le meme appel -> refuse, pas de 2e cle generee */ }

@Test
void cle_expiredCode_isRejected() { /* codeEnrolementExpireA dans le passe -> refuse */ }
```

- [ ] **Step 5 : Implémenter `POST /api/licence/cle`** dans le même controller — même logique que `EnrollmentController.enroll` (Task 3 du plan précédent), appliquée à `LicenceServeur`.

- [ ] **Step 6 : `LicenceApiKeyAuthFilter`** — même patron que `SyncApiKeyAuthFilter` (en-tête `X-Licence-Api-Key`, résout par `LicenceCles.hash`, principal = `LicenceServeur.id`, exige `statut=ACTIF`, autorité `ROLE_LICENCE_SECONDARY`).

- [ ] **Step 7 : `SecurityConfig` — nouvelle chaîne `/api/licence/**`** (même patron que `syncApiFilterChain`) : `POST /api/licence/enregistrer` et `POST /api/licence/cle` en `permitAll()` (la preuve est dans le corps de la requête, comme `/api/sync/enroll`), tout le reste de `/api/licence/**` exige `hasRole("LICENCE_SECONDARY")`, `anyRequest().denyAll()`.

- [ ] **Step 8 : Test croisé des filtres (Review Focus)**

```java
@Test
void syncApiKeyIsRejectedOnLicenceRoutes() { /* une vraie cle SyncApiKeys valide, presentee en X-Licence-Api-Key sur une route /api/licence/** protegee -> 401 */ }

@Test
void licenceApiKeyIsRejectedOnSyncRoutes() { /* l'inverse : une cle de licence valide, presentee en X-Sync-Api-Key sur /api/sync/events -> 401 */ }
```

- [ ] **Step 9 : Étendre l'écran `comptes.html` (Task 1) : liste des `LicenceServeur` d'un compte, modification de l'adresse déclarée, révocation d'un serveur précis**

```java
@Test
void compteDetail_listsItsLicenceServeurs() { /* ... */ }

@Test
void updateAdresseDeclaree_changesTheStoredValue() { /* corrige une detection automatique fausse, sans reinstallation */ }

@Test
void revokeOneLicenceServeur_setsStatutRevoque_accountKeepsOthersActive() { /* revoquer un seul serveur ne touche pas aux autres du meme compte */ }
```

Ajoute une page détail `/intra/licence/comptes/{id}` (ou étend `comptes.html`) listant les `LicenceServeur` du compte avec un champ éditable pour `adresseDeclaree` et un bouton révoquer par ligne. Run, vérifier l'échec puis le succès.

- [ ] **Step 10 : Run toute la classe de test, vérifier le succès.**

- [ ] **Step 11 : Commit.**

```bash
git checkout -- src/main/resources/static/app/index.html
git add src/main/java/com/logicielpourtous/site/features/licence src/main/java/com/logicielpourtous/site/config/SecurityConfig.java src/main/resources/templates/feature/licence src/test/java/com/logicielpourtous/site/features/licence
git commit -m "feat(licence): enrolement automatique d'un partenaire + echange de cle API dediee"
```

---

### Task 3 : Bootstrap côté serveur non-MASTER (appel auto au démarrage)

**Files:**
- Create: `src/main/java/com/logicielpourtous/site/features/licence/EtatLicenceLocale.java`
- Create: `src/main/java/com/logicielpourtous/site/features/licence/EtatLicenceLocaleRepository.java`
- Create: `src/main/java/com/logicielpourtous/site/features/licence/LicenceBootstrap.java`
- Create: `src/main/resources/application-licence.properties.example` (template, documente les propriétés attendues)
- Test: `src/test/java/com/logicielpourtous/site/features/licence/LicenceBootstrapTest.java`

**Interfaces:**
- Consumes : `POST /api/licence/enregistrer` + `POST /api/licence/cle` (Task 2, contrats déjà définis). `ServerRoleProperties` (`commun`).
- Produces : `EtatLicenceLocale` — entité locale à CE serveur (singleton logique, une seule ligne) : `identifiantInstallationStable: String` (généré au premier démarrage si absent), `cleApiLicence: String` (en clair, lue ensuite par d'autres composants — même patron que `app.sync.api-key` déjà existant), `adressePartenaireResolue: String` (nullable, rempli plus tard par Task 6 si `app.role=CLIENT`), `enregistrementTermine: boolean`.

- [ ] **Step 1 : Test du bootstrap, cas `PARTNER` (login/mot de passe)**

```java
@Test
void bootstrap_withLoginAndPassword_registersOnceAndStoresApiKey() {
    // mock du client HTTP vers /api/licence/enregistrer puis /api/licence/cle
    // assert EtatLicenceLocale.cleApiLicence non null apres bootstrap.lancer()
    // un 2e appel a bootstrap.lancer() ne refait PAS les appels HTTP (enregistrementTermine=true)
}

@Test
void bootstrap_generatesIdentifiantInstallationStableOnceAndKeepsItAcrossRestarts() { /* 2 instances successives de EtatLicenceLocale (simulant 2 demarrages) -> meme UUID */ }

@Test
void bootstrap_masterRole_doesNothing() { /* app.role=MASTER -> bootstrap.lancer() ne fait aucun appel HTTP */ }
```

- [ ] **Step 2 : Run, vérifier l'échec.**

- [ ] **Step 3 : Implémenter `EtatLicenceLocale`/Repository et `LicenceBootstrap`**

`LicenceBootstrap` : composant `@ConditionalOnProperty(name = "app.licence.master-url")` (même patron que `RestSyncClient`/`SyncOutboxScheduler` déjà existants — absent de config = rien ne se passe, cohérent avec "une instance `MASTER` n'a besoin d'aucune config `app.licence.*`"). Au démarrage (`@EventListener(ApplicationReadyEvent.class)` ou équivalent) : si `EtatLicenceLocale` absent, le créer avec un `identifiantInstallationStable` neuf (`UUID.randomUUID()`). Si `enregistrementTermine=false` : appelle `POST <app.licence.master-url>/api/licence/enregistrer` avec soit `{login: app.licence.login, motDePasse: app.licence.mot-de-passe}` soit `{jeton: app.licence.jeton}` (exactement un des deux configuré, pas les deux — documente l'erreur si aucun ou les deux), `adresseDeclaree` (auto-détectée — `InetAddress.getLocalHost()` ou équivalent, valeur de départ, modifiable ensuite côté admin MP), `identifiantInstallationStable`, `roleVoulu = app.role`. Puis `POST /api/licence/cle` avec le code reçu. Stocke la clé, marque `enregistrementTermine=true`, loggue en `INFO` : "Licence obtenue, redémarrez ce serveur pour l'appliquer." (pas de redémarrage programmatique, voir Global Constraints).

- [ ] **Step 4 : Run, vérifier le succès.**

- [ ] **Step 5 : `application-licence.properties.example`** — documente `app.licence.master-url`, `app.licence.login`/`app.licence.mot-de-passe` (partenaire), `app.licence.jeton` (client), avec des `CHANGEME`.

- [ ] **Step 6 : Commit.**

```bash
git checkout -- src/main/resources/static/app/index.html
git add src/main/java/com/logicielpourtous/site/features/licence/EtatLicenceLocale.java src/main/java/com/logicielpourtous/site/features/licence/EtatLicenceLocaleRepository.java src/main/java/com/logicielpourtous/site/features/licence/LicenceBootstrap.java src/main/resources/application-licence.properties.example src/test/java/com/logicielpourtous/site/features/licence/LicenceBootstrapTest.java
git commit -m "feat(licence): auto-enrolement au demarrage d'un serveur non-MASTER"
```

---

### Task 4 : Jeton par client (généré par le partenaire) + enrôlement d'un client

**Files:**
- Create: `src/main/java/com/logicielpourtous/site/features/licence/JetonClient.java`
- Create: `src/main/java/com/logicielpourtous/site/features/licence/JetonClientRepository.java`
- Modify: `src/main/java/com/logicielpourtous/site/features/licence/LicenceEnrolementController.java` (ajoute `POST /api/licence/jetons` + le chemin `jeton` de `POST /api/licence/enregistrer`)
- Test: `src/test/java/com/logicielpourtous/site/features/licence/JetonClientTest.java`

**Interfaces:**
- Consumes : `LicenceServeur`/`LicenceApiKeyAuthFilter` (Task 2, le principal authentifié = `LicenceServeur.id`, d'où on retrouve `compteId`).
- Produces : `JetonClient` — `id: Long`, `compteId: Long`, `customerId: Long`, `code: String`, `expireA: Instant`, `utilise: boolean`, `creeA: Instant`. `POST /api/licence/jetons` (protégé par `LicenceApiKeyAuthFilter`, appelant = un `LicenceServeur` de rôle `PARTNER`) `{customerId} -> {jeton, expireA}`.

- [ ] **Step 1 : Tests**

```java
@Test
void jetons_partnerGeneratesTokenForOwnCustomer_returnsToken() { /* appel authentifie par la cle de licence du partenaire -> JetonClient cree avec compteId = celui du partenaire */ }

@Test
void enregistrer_withValidJeton_createsClientLicenceServeurWithCustomerIdFromToken() { /* jeton consomme -> LicenceServeur role=CLIENT, customerId = celui du jeton, PAS celui (absent) du corps de la requete */ }

@Test
void enregistrer_withAlreadyUsedJeton_isRejected() { /* rejeu -> refuse, pas de 2e LicenceServeur */ }

@Test
void enregistrer_withExpiredJeton_isRejected() { /* ... */ }

@Test
void enregistrer_jetonFromDifferentCompteCannotBypassQuota() { /* le quota verifie est celui du compte DU JETON, pas un autre */ }
```

- [ ] **Step 2 : Run, vérifier l'échec.**

- [ ] **Step 3 : Implémenter `JetonClient`/Repository, `POST /api/licence/jetons`, et étendre `POST /api/licence/enregistrer`**

`POST /api/licence/jetons` : résout le `LicenceServeur` appelant (principal du filtre), vérifie `role=PARTNER`, crée `JetonClient(compteId, customerId, code=LicenceCles.generate(), expireA)`. `POST /api/licence/enregistrer` : si le corps porte `jeton` (plutôt que `login`/`motDePasse`), résout le `JetonClient` par `code`, vérifie non expiré/non utilisé et que le `CompteLicence` associé est `actif`, applique le MÊME contrôle de quota que Task 2 (sur `compteId` du jeton), crée le `LicenceServeur` avec `role=CLIENT` et `customerId` du jeton, marque le jeton `utilise=true`.

- [ ] **Step 4 : Run, vérifier le succès.**

- [ ] **Step 5 : Commit.**

```bash
git checkout -- src/main/resources/static/app/index.html
git add src/main/java/com/logicielpourtous/site/features/licence/JetonClient.java src/main/java/com/logicielpourtous/site/features/licence/JetonClientRepository.java src/main/java/com/logicielpourtous/site/features/licence/LicenceEnrolementController.java src/test/java/com/logicielpourtous/site/features/licence/JetonClientTest.java
git commit -m "feat(licence): jeton d'installation par client, rattachement automatique au bon customerId"
```

---

### Task 5 : Écran I-4 (gestion des `SecondaryServer`/`SyncConflict`) + génération du jeton côté partenaire

**Files:**
- Create: `src/main/java/com/logicielpourtous/site/features/licence/GestionServeursController.java`
- Create: `src/main/java/com/logicielpourtous/site/features/licence/LicenceClient.java` (petit client HTTP sortant, appelle le MP avec `EtatLicenceLocale.cleApiLicence` — distinct de `RestSyncClient`)
- Create: `src/main/resources/templates/feature/licence/serveurs.html`
- Create: `src/main/resources/templates/feature/licence/conflits.html`
- Modify: `src/main/java/com/logicielpourtous/site/features/sync/SecondaryServerRepository.java` (si une méthode de recherche manque — vérifie l'existant avant d'ajouter)
- Test: `src/test/java/com/logicielpourtous/site/features/licence/GestionServeursControllerTest.java`

**Interfaces:**
- Consumes : `SecondaryServer`/`SecondaryServerRepository`, `SyncConflict`/`SyncConflictRepository`, `RequestSyncApplier.appliquer(Request, RequestSyncPayload)` (tous déjà existants dans `sync`, lecture/écriture directe — `licence` en dépend, voir Global Constraints). `Customer`/`CustomerRepository` (`customers`, déjà existant) pour la liste déroulante du partenaire. `EtatLicenceLocale`/`POST /api/licence/jetons` (Task 3/4).
- Produces : écran `/intra/licence/serveurs` (liste `SecondaryServer`, actions révoquer + générer un code manuel — réutilise la génération déjà écrite en Task 2 côté `LicenceCles`/même idée pour un code `SecondaryServer.enrollmentCode` cette fois, PAS de nouvelle classe : génère directement une valeur aléatoire, le mécanisme de `SecondaryServer` n'a pas de générateur dédié aujourd'hui, c'est pour cela qu'il fallait du SQL manuel) ; écran `/intra/licence/conflits` (liste `SyncConflict`, action trancher) ; sur une instance `PARTNER` uniquement, bouton "Générer un jeton d'installation" par ligne de `Customer`.

- [ ] **Step 1 : Tests de l'écran serveurs**

```java
@Test
void listServeurs_showsStatutAndCustomerId() { /* ... */ }

@Test
void revokeServeur_setsStatutRevoque() { /* ... */ }

@Test
void generateManualCode_createsUsableEnrollmentCode() { /* le code genere peut ensuite etre consomme par le vrai POST /api/sync/enroll deja existant */ }
```

- [ ] **Step 2 : Run, vérifier l'échec. Implémenter.** (Révoquer/générer un code manuel passent par `SecondaryServerRepository` déjà existant ; si le code manuel doit être une valeur aléatoire haute entropie, réutilise `java.security.SecureRandom`/`UUID.randomUUID()` directement, pas de nouvelle classe partagée nécessaire pour ça seul.)

- [ ] **Step 3 : Tests de l'écran conflits**

```java
@Test
void listConflits_showsExpectedAndReceivedVersion() { /* ... */ }

@Test
void resolveConflit_applyingReceivedPayload_callsRequestSyncApplierAndMarksConflitResolved() { /* apres resolution, la Request locale reflete le payload du conflit, et le SyncConflict ne reapparait plus dans la liste "a trancher" */ }

@Test
void resolveConflit_keepingCurrentState_justMarksConflitResolvedWithoutChangingTheRequest() { /* ... */ }
```

Mon call (le design doc laisse ce mécanisme ouvert) : `SyncConflict` gagne un champ `resolu: boolean` (migration simple, pas de backfill nécessaire pour des lignes déjà existantes — `false` par défaut suffit). "Trancher" = 2 actions possibles : appliquer le payload reçu (réutilise `RequestSyncApplier.appliquer`) ou garder l'état actuel (marque juste `resolu=true`).

- [ ] **Step 4 : Run, vérifier l'échec. Implémenter** (ajoute `resolu` à `SyncConflict`, migration SQL si la table existe déjà en local — vérifie `sync-id-backfill-migration.sql` pour le patron déjà établi).

- [ ] **Step 5 : Test + implémentation de la génération de jeton côté partenaire**

```java
@Test
void generateJetonForCustomer_onPartnerInstance_callsMpAndDisplaysToken() { /* mock LicenceClient -> le jeton renvoye par le MP est bien celui affiche dans la reponse de l'ecran */ }

@Test
void generateJetonButton_notShownWhenRoleIsNotPartner() { /* ... */ }
```

`LicenceClient` : petit wrapper HTTP (`RestClient`/`WebClient`, au choix — regarde ce que `RestSyncClient` utilise déjà et reste cohérent) vers `<app.licence.master-url>/api/licence/jetons`, authentifié par `EtatLicenceLocale.cleApiLicence` en en-tête `X-Licence-Api-Key`.

- [ ] **Step 6 : Run toute la classe, vérifier le succès.**

- [ ] **Step 7 : Templates** `serveurs.html`/`conflits.html` (layout `intra-layout`, cohérents avec les écrans admin existants).

- [ ] **Step 8 : Commit.**

```bash
git checkout -- src/main/resources/static/app/index.html
git add src/main/java/com/logicielpourtous/site/features/licence src/main/java/com/logicielpourtous/site/features/sync/SyncConflict.java src/main/resources/templates/feature/licence src/main/resources/*.sql src/test/java/com/logicielpourtous/site/features/licence/GestionServeursControllerTest.java
git commit -m "feat(licence): ecran de gestion des serveurs secondaires et des conflits (I-4) + generation de jeton cote partenaire"
```

---

### Task 6 : Découverte côté partenaire (sondage) + matérialisation locale + révocation propagée

**Files:**
- Create: `src/main/java/com/logicielpourtous/site/features/licence/LicenceDecouverteScheduler.java`
- Modify: `src/main/java/com/logicielpourtous/site/features/licence/LicenceEnrolementController.java` (ajoute `GET /api/licence/nouveaux-serveurs` et `POST /api/licence/code-partenaire`, + renvoyer `adressePartenaireResolue` si connue dans la réponse de `enregistrer` pour un `CLIENT`)
- Modify: `src/main/java/com/logicielpourtous/site/features/licence/LicenceServeur.java` (ajoute `notifiePartenaire: boolean`)
- Test: `src/test/java/com/logicielpourtous/site/features/licence/LicenceDecouverteSchedulerTest.java`

**Interfaces:**
- Consumes : `LicenceServeur`/`LicenceServeurRepository` (Task 2/4), `LicenceClient` (Task 5), `SecondaryServerRepository` (`sync`, déjà existant, pour créer le `SecondaryServer` local).
- Produces : `GET /api/licence/nouveaux-serveurs` (protégé, appelant = `LicenceServeur` rôle `PARTNER`) `-> List<{identifiantInstallationStable, customerId, statut}>` (uniquement les `LicenceServeur` `CLIENT` sous ce compte où `notifiePartenaire=false` OU `statut=REVOQUE` pas encore propagé). `POST /api/licence/code-partenaire` `{identifiantInstallationStable, codeLocal}` → stocke ce code pour que le prochain appel du client en attente le reçoive, marque `notifiePartenaire=true`.

- [ ] **Step 1 : Tests du sondage de découverte**

```java
@Test
void decouverte_newClientLicenceServeur_createsLocalSecondaryServerWithCorrectCustomerId() { /* apres un cycle du scheduler, un SecondaryServer local existe, role=CLIENT, customerId = celui connu du LicenceServeur */ }

@Test
void decouverte_pushesLocalCodeBackToMp() { /* le code d'enrolement du SecondaryServer local cree est bien celui renvoye par POST /api/licence/code-partenaire */ }

@Test
void decouverte_revokedLicenceServeur_revokesLocalSecondaryServer() { /* un LicenceServeur marque REVOQUE cote MP -> le SecondaryServer local correspondant passe REVOQUE au cycle suivant */ }

@Test
void decouverte_onlyRunsOnPartnerRole() { /* app.role=CLIENT ou MASTER -> le scheduler ne fait rien */ }
```

- [ ] **Step 2 : Run, vérifier l'échec.**

- [ ] **Step 3 : Implémenter `GET /api/licence/nouveaux-serveurs`, `POST /api/licence/code-partenaire`, `LicenceDecouverteScheduler`**

Scheduler `@ConditionalOnProperty(app.licence.master-url)` + garde explicite `app.role=PARTNER` (même patron que `SyncOutboxScheduler`). Chaque cycle : appelle `GET .../nouveaux-serveurs`, pour chaque entrée crée/mets à jour localement un `SecondaryServer` (role=CLIENT, customerId connu, statut suivant celui reçu), génère un `enrollmentCode` local si c'est une création, le renvoie via `POST .../code-partenaire`.

- [ ] **Step 4 : Mettre à jour `POST /api/licence/enregistrer` (chemin `CLIENT`)** pour inclure, dans sa réponse, le code local déjà poussé par le partenaire si disponible (sinon `null`, le client réessaiera).

- [ ] **Step 5 : Run toute la classe, vérifier le succès.**

- [ ] **Step 6 : Mettre à jour `LicenceBootstrap` (Task 3) côté client** : si `roleVoulu=CLIENT` et la réponse ne porte pas encore d'adresse partenaire + code local, réessayer périodiquement (même sondage, idempotent) au lieu d'échouer — ajoute un test à `LicenceBootstrapTest` pour ce cas.

- [ ] **Step 7 : Commit.**

```bash
git checkout -- src/main/resources/static/app/index.html
git add src/main/java/com/logicielpourtous/site/features/licence src/test/java/com/logicielpourtous/site/features/licence
git commit -m "feat(licence): decouverte et materialisation locale cote partenaire, revocation propagee"
```

---

### Task 7 : Validation de bout en bout sur 3 instances locales (MP + partenaire + client)

**Files:**
- Create: `src/main/resources/application-licence-mp.properties`
- Create: `src/main/resources/application-licence-partenaire.properties`
- Create: `src/main/resources/application-licence-client.properties`
- Test: `src/test/java/com/logicielpourtous/site/features/licence/EndToEndLicenceTest.java`

**Interfaces:**
- Consumes : tout ce qui précède.
- Produces : preuve que le flux complet fonctionne sur 3 process Spring Boot distincts (pas seulement des tests unitaires avec des mocks).

- [ ] **Step 1 : Décrire le scénario de validation manuelle**

Documente dans le rapport de cette tâche les commandes exactes pour lancer les 3 instances localement (3 ports, 3 profils, 3 bases PostgreSQL locales DISTINCTES et NEUVES — ne réutilise pas `site_db`/`site_db_secondaire` déjà chargées de données du plan précédent), la création manuelle d'un `CompteLicence` sur le MP, l'enrôlement automatique du partenaire, la génération d'un jeton pour un `Customer` créé sur le partenaire, l'enrôlement automatique du client avec ce jeton.

- [ ] **Step 2 : Exécuter le scénario réel : le partenaire s'enrôle automatiquement**

Démarre le partenaire avec `app.licence.login`/`app.licence.mot-de-passe` configurés ; vérifie par requête SQL directe sur la base du MP qu'un `LicenceServeur` `ACTIF` existe pour ce compte, et sur la base du partenaire que sa clé de licence locale est bien enregistrée (`EtatLicenceLocale`). Redémarre le partenaire avec cette clé pour qu'il devienne actif comme maître pour ses futurs clients (cohérent avec le ruling "redémarrage manuel").

- [ ] **Step 3 : Exécuter le scénario réel : un client s'enrôle automatiquement via un jeton**

Crée un `Customer` sur le partenaire, génère un jeton depuis son écran, configure et démarre le client avec `app.licence.jeton`. Vérifie que le partenaire matérialise bien un `SecondaryServer` local rattaché au bon `customerId` (sondage), et que le client reçoit à son tour l'adresse du partenaire + un code local, s'enrôle automatiquement vis-à-vis du partenaire (réutilisant le `/api/sync/enroll` déjà existant), et qu'une `Request` créée côté client apparaît bien côté partenaire avec le bon `customerId` (réutilise le scénario déjà prouvé par la Task 5 du plan précédent, mais désormais sans AUCUNE étape manuelle de code).

- [ ] **Step 4 : Exécuter le scénario réel de révocation**

Révoque le compte (ou juste le `LicenceServeur` du client) depuis l'écran du MP, attends le prochain sondage du partenaire, vérifie que le `SecondaryServer` local correspondant passe `REVOQUE`.

- [ ] **Step 5 : Documenter le résultat et committer les fichiers de profil + le test** (le test peut rester `@Disabled` par défaut s'il nécessite 3 process réels — documente pourquoi dans son commentaire de classe).

```bash
git add src/main/resources/application-licence-mp.properties src/main/resources/application-licence-partenaire.properties src/main/resources/application-licence-client.properties src/test/java/com/logicielpourtous/site/features/licence/EndToEndLicenceTest.java
git commit -m "test(licence): validation bout-en-bout sur 3 instances locales (MP, partenaire, client)"
```

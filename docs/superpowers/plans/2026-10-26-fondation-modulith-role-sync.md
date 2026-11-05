# Fondation Modulith + rôle de serveur + clé de synchronisation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Poser les 3 fondations de l'architecture distribuée (frontières de module Spring Modulith, rôle de serveur configurable, clé de synchronisation) sur une tranche verticale réelle (`features/request` + `features/commun`), sans toucher aux 42 autres modules métier.

**Architecture:** Extraction minimale et sans risque du sous-système de routage de tickets hors de `features/request` (pour réduire son couplage externe), puis ajout de Spring Modulith avec vérification scopée à `commun`+`request` seulement, puis deux petites briques réutilisables (`ServerRole`, `Syncable`) dans `commun`, appliquées à `Request`.

**Tech Stack:** Spring Boot 4.0.2, Java 25, Maven, Spring Modulith, JPA/Hibernate, JUnit 5.

**Spec:** docs/superpowers/specs/2026-10-26-fondation-modulith-role-sync-design.md (et le document parent docs/superpowers/specs/2026-10-26-architecture-distribuee-multi-serveurs-design.md pour le contexte global)

## Global Constraints

- N'effectuer AUCUNE modification dans les 42 autres packages `features/*` — seuls `request`, `commun`, et le nouveau `ticketing` sont concernés.
- `Comment`, `TimeEntry`, `TicketAttachment` (+ repositories), `TimelineEvent`, `TicketController` RESTENT dans `features/request` — ne pas les déplacer (couplage bidirectionnel réel avec `Request`, décision actée : seul le routage est extrait dans ce plan).
- Dépendances externes réelles du module `request` à déclarer explicitement (pas des exclusions silencieuses) : `auth`, `customers`, `intervention`, `invoice`, `mail`, `order`, `subscription`, `team`, `user`.
- Dépendance externe réelle du module `commun` à déclarer explicitement : `auth`, `customers` (via `IntraCompanyController`).
- `app.role` : enum `ServerRole { MASTER, PARTNER, CLIENT, PUBLIC }`, défaut `MASTER`.
- Champ de synchronisation : nommé `syncId`, type `UUID`, généré automatiquement si absent à la création, jamais modifiable après persistance.
- `ApplicationModules.of(...)` doit être pointé sur une classe à l'intérieur du package `features` (pas sur `SiteApplication`), sinon Modulith détecterait `config`/`features`/`service` comme 3 "modules" au lieu de chaque sous-package de `features`.

## Review Focus

- Un reviewer doit vérifier qu'aucune classe restée dans `features/request` (notamment `TicketController`, `Comment`, `TimeEntry`) n'importe quoi que ce soit du nouveau `features/ticketing` — sinon le découpage recréerait artificiellement un couplage inverse que ce plan n'a pas pour but de créer.
- Le test `ApplicationModules.of(...).verify()` doit vraiment ÉCHOUER si on retire une déclaration de dépendance externe nécessaire (sinon le test ne prouve rien) — à vérifier en le cassant volontairement une fois avant de le committer.
- `syncId` ne doit JAMAIS être régénéré sur un `Request` déjà persisté (un `@PrePersist` mal placé en `@PreUpdate` écraserait silencieusement la valeur à chaque modification) — scénario à tester explicitement.
- Le rôle par défaut (`MASTER`) doit tenir même si `app.role` est absent de la config ET si la valeur fournie est invalide/mal orthographiée (fail-safe sur une valeur connue, pas une exception au démarrage qui empêcherait tout déploiement).
- Après le déplacement du routage, les routes HTTP existantes sous `/intra/tickets/routing` doivent rester identiques (même `@RequestMapping`, même comportement) — un test d'intégration doit le prouver, pas seulement une relecture visuelle du diff.

---

### Task 1 : Extraction du routage de tickets vers `features/ticketing`

**Files:**
- Create: `src/main/java/com/logicielpourtous/site/features/ticketing/TicketRoutingRule.java` (déplacé depuis `features/request/TicketRoutingRule.java`)
- Create: `src/main/java/com/logicielpourtous/site/features/ticketing/TicketRoutingService.java` (déplacé depuis `features/request/TicketRoutingService.java`)
- Create: `src/main/java/com/logicielpourtous/site/features/ticketing/TicketRoutingRuleController.java` (déplacé depuis `features/request/TicketRoutingRuleController.java`)
- Create: `src/main/java/com/logicielpourtous/site/features/ticketing/TicketRoutingRuleRepository.java` (déplacé depuis `features/request/TicketRoutingRuleRepository.java`)
- Delete: les 4 fichiers originaux dans `features/request/`
- Test: `src/test/java/com/logicielpourtous/site/features/ticketing/TicketRoutingRuleControllerTest.java`

**Interfaces:**
- Produces: `features/ticketing` existe, contient uniquement ces 4 fichiers, dépend seulement de `features/team` et `features/user` (confirmé par grep avant ce plan — aucune autre dépendance externe).
- Consumes: rien des tâches suivantes.

- [ ] **Step 1 : Déplacer les 4 fichiers**

Change uniquement la ligne `package com.logicielpourtous.site.features.request;` en `package com.logicielpourtous.site.features.ticketing;` dans chacun des 4 fichiers déplacés — aucun autre changement de contenu (les imports vers `team`/`user` restent valides tels quels, ce sont des packages externes à `request` comme à `ticketing`).

- [ ] **Step 2 : Vérifier qu'aucune classe restée dans `features/request` ne référence les 4 classes déplacées**

Run: `grep -rln "TicketRouting" src/main/java/com/logicielpourtous/site/features/request/`
Expected: aucune sortie (déjà vérifié avant ce plan — ce step reconfirme après le déplacement).

- [ ] **Step 3 : Compiler**

Run: `./mvnw compile`
Expected: BUILD SUCCESS, aucune erreur de résolution de package.

- [ ] **Step 4 : Écrire un test d'intégration prouvant que les routes `/intra/tickets/routing` fonctionnent toujours**

```java
package com.logicielpourtous.site.features.ticketing;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class TicketRoutingRuleControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void routingRulesEndpointStillRespondsAfterPackageMove() throws Exception {
        mockMvc.perform(get("/intra/tickets/routing"))
            .andExpect(status().isOk());
    }
}
```

Adapte l'assertion exacte (code de statut attendu, besoin éventuel d'authentification simulée) au comportement réel observé — regarde comment les tests existants du dépôt gèrent l'authentification de test s'il y en a, sinon commence par ce test minimal et ajuste selon ce que `TicketRoutingRuleController` exige réellement (garde de sécurité, rôle requis).

- [ ] **Step 5 : Lancer le test**

Run: `./mvnw test -Dtest=TicketRoutingRuleControllerTest`
Expected: PASS.

- [ ] **Step 6 : Commit**

```bash
git add src/main/java/com/logicielpourtous/site/features/ticketing/ src/main/java/com/logicielpourtous/site/features/request/TicketRoutingRule.java src/main/java/com/logicielpourtous/site/features/request/TicketRoutingService.java src/main/java/com/logicielpourtous/site/features/request/TicketRoutingRuleController.java src/main/java/com/logicielpourtous/site/features/request/TicketRoutingRuleRepository.java src/test/java/com/logicielpourtous/site/features/ticketing/
git commit -m "refactor(ticketing): extrait le routage de tickets de request vers ticketing"
```

---

### Task 2 : Spring Modulith — vérification des frontières de `commun` et `request`

**Files:**
- Modify: `pom.xml`
- Create: `src/test/java/com/logicielpourtous/site/ModularityTests.java`

**Interfaces:**
- Consumes: `features/ticketing` créé à la Task 1 (ne doit PAS être inclus dans la vérification Modulith de ce plan).
- Produces: un test `ModularityTests` qui PASSE, prouvant que `commun` et `request` ont des frontières explicites et des dépendances externes déclarées (pas implicites).

- [ ] **Step 1 : Déterminer la version de Spring Modulith compatible avec Spring Boot 4.0.2**

Consulte la documentation officielle Spring Modulith (docs.spring.io/spring-modulith) ou son BOM sur Maven Central pour la dernière version déclarant une compatibilité avec Spring Boot 4.x. **Ruling à faire toi-même si aucune version ne déclare officiellement Spring Boot 4.x** : utilise la dernière version disponible de Spring Modulith, teste empiriquement qu'elle fonctionne avec Spring Boot 4.0.2 (Step 4 de cette tâche sert justement de preuve), et documente ce choix dans le message de commit — ne rétrograde PAS Spring Boot pour ce motif.

- [ ] **Step 2 : Ajouter la dépendance Modulith à `pom.xml`**

Ajoute `spring-modulith-starter-core` (scope normal) et `spring-modulith-starter-test` (scope `test`), avec la version déterminée au Step 1. Si Spring Modulith publie un BOM (`spring-modulith-bom`), importe-le dans `<dependencyManagement>` plutôt que de fixer la version sur chaque dépendance individuellement — patron standard de l'écosystème Spring, cohérent avec `spring-boot-starter-parent` déjà utilisé comme parent.

- [ ] **Step 3 : Écrire `ModularityTests`**

```java
package com.logicielpourtous.site;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

class ModularityTests {

    @Test
    void verifiesModuleStructure() {
        ApplicationModules modules = ApplicationModules.of(SiteApplication.class);
        modules.verify();
    }
}
```

`ApplicationModules.of(SiteApplication.class)` scanne par défaut tous les sous-packages directs de `com.logicielpourtous.site` — ça inclurait `config`/`features`/`service` comme 3 "modules" (violant la contrainte globale). Utilise plutôt `ApplicationModules.of(SiteApplication.class, JavaPackage.of(...))` ou l'option de configuration Modulith appropriée pour pointer la racine de scan sur `com.logicielpourtous.site.features` — vérifie la bonne API exacte dans la doc Modulith de la version choisie au Step 1 (l'API exacte de scope de scan a changé entre versions majeures).

- [ ] **Step 4 : Lancer le test, observer l'échec attendu**

Run: `./mvnw test -Dtest=ModularityTests`
Expected: FAIL — violations listées pour `request` (dépendances non déclarées vers `auth`, `customers`, `intervention`, `invoice`, `mail`, `order`, `subscription`, `team`, `user`) et pour `commun` (dépendance non déclarée vers `auth`, `customers`), ET des violations pour les 42 autres packages de `features/` si la vérification n'est pas correctement scopée à `commun`+`request` seulement (si c'est le cas, corrige le scope avant de continuer).

- [ ] **Step 5 : Déclarer explicitement les dépendances externes**

Pour `features/request` et `features/commun`, ajoute un `package-info.java` avec l'annotation Modulith appropriée à la version choisie (`@ApplicationModule(allowedDependencies = {...})` ou équivalent — vérifie le nom exact de l'annotation/attribut dans la doc de la version choisie) listant respectivement : `request` → `auth`, `customers`, `intervention`, `invoice`, `mail`, `order`, `subscription`, `team`, `user` ; `commun` → `auth`, `customers`.

- [ ] **Step 6 : Relancer le test, vérifier qu'il passe**

Run: `./mvnw test -Dtest=ModularityTests`
Expected: PASS.

- [ ] **Step 7 : Vérifier que le test détecte vraiment une régression (Review Focus)**

Retire temporairement UNE dépendance déclarée (ex. `team` de la liste de `request`), relance le test, confirme qu'il échoue avec un message citant cette dépendance manquante, puis restaure la déclaration complète.

Run: `./mvnw test -Dtest=ModularityTests`
Expected: FAIL temporairement (dépendance retirée), puis PASS après restauration.

- [ ] **Step 8 : Commit**

```bash
git add pom.xml src/test/java/com/logicielpourtous/site/ModularityTests.java src/main/java/com/logicielpourtous/site/features/request/package-info.java src/main/java/com/logicielpourtous/site/features/commun/package-info.java
git commit -m "feat(modulith): verifie les frontieres des modules commun et request"
```

---

### Task 3 : Rôle de serveur configurable

**Files:**
- Create: `src/main/java/com/logicielpourtous/site/features/commun/ServerRole.java`
- Create: `src/main/java/com/logicielpourtous/site/features/commun/ServerRoleProperties.java`
- Test: `src/test/java/com/logicielpourtous/site/features/commun/ServerRolePropertiesTest.java`

**Interfaces:**
- Produces: `ServerRole` (enum à 4 valeurs : `MASTER`, `PARTNER`, `CLIENT`, `PUBLIC`) ; `ServerRoleProperties.getRole(): ServerRole`, lu depuis la propriété `app.role`, défaut `MASTER` si absente ou invalide.
- Consumes: rien.

- [ ] **Step 1 : Écrire le test de la valeur par défaut**

```java
package com.logicielpourtous.site.features.commun;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Autowired;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class ServerRolePropertiesTest {

    @Autowired
    private ServerRoleProperties serverRoleProperties;

    @Test
    void defaultsToMasterWhenNotConfigured() {
        assertThat(serverRoleProperties.getRole()).isEqualTo(ServerRole.MASTER);
    }
}
```

- [ ] **Step 2 : Lancer le test, vérifier qu'il échoue**

Run: `./mvnw test -Dtest=ServerRolePropertiesTest`
Expected: FAIL — `ServerRoleProperties`/`ServerRole` n'existent pas encore.

- [ ] **Step 3 : Implémenter `ServerRole` (enum) dans `features/commun/ServerRole.java`**

4 valeurs : `MASTER`, `PARTNER`, `CLIENT`, `PUBLIC`.

- [ ] **Step 4 : Implémenter `ServerRoleProperties` dans `features/commun/ServerRoleProperties.java`**

`@ConfigurationProperties(prefix = "app")` avec un champ `role` (type `ServerRole`, défaut `MASTER`) et un getter `getRole(): ServerRole`. Une valeur de configuration absente ou qui ne correspond à aucune des 4 constantes de l'enum doit retomber sur `MASTER` plutôt que de faire planter le démarrage de l'application (Review Focus) — gère la conversion toi-même (ex. valeur par défaut Java sur le champ + relecture explicite de la propriété brute en cas d'échec de binding) plutôt que de compter sur le binding Spring par défaut qui lèverait une exception sur une valeur invalide.

- [ ] **Step 5 : Lancer le test, vérifier qu'il passe**

Run: `./mvnw test -Dtest=ServerRolePropertiesTest`
Expected: PASS.

- [ ] **Step 6 : Écrire et lancer un 2e test pour une valeur invalide**

```java
    @Test
    void fallsBackToMasterWhenConfiguredValueIsInvalid() {
        // configure app.role=not-a-real-role via @TestPropertySource ou équivalent,
        // puis vérifie que getRole() retourne MASTER malgré tout
    }
```

Run: `./mvnw test -Dtest=ServerRolePropertiesTest`
Expected: PASS (les 2 tests).

- [ ] **Step 7 : Commit**

```bash
git add src/main/java/com/logicielpourtous/site/features/commun/ServerRole.java src/main/java/com/logicielpourtous/site/features/commun/ServerRoleProperties.java src/test/java/com/logicielpourtous/site/features/commun/ServerRolePropertiesTest.java
git commit -m "feat(commun): ajoute le role de serveur configurable (app.role, defaut MASTER)"
```

---

### Task 4 : Clé de synchronisation `syncId` et application à `Request`

**Files:**
- Create: `src/main/java/com/logicielpourtous/site/features/commun/Syncable.java`
- Modify: `src/main/java/com/logicielpourtous/site/features/request/Request.java`
- Test: `src/test/java/com/logicielpourtous/site/features/request/RequestSyncIdTest.java`

**Interfaces:**
- Consumes: rien des tâches précédentes (indépendant de Task 2/3, peut être fait en parallèle si exécuté en inline plutôt qu'en subagent-driven — mais ce plan le place en dernier par simplicité de séquencement).
- Produces: `Syncable` (classe de base JPA, `@MappedSuperclass`) avec un champ `syncId: UUID`, généré automatiquement si `null` au moment de la persistance (`@PrePersist`, PAS `@PreUpdate`), jamais régénéré après. `Request extends Syncable`.

- [ ] **Step 1 : Écrire le test prouvant la génération et la stabilité de `syncId`**

```java
package com.logicielpourtous.site.features.request;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
class RequestSyncIdTest {

    @Autowired
    private RequestRepository requestRepository;

    @Test
    void generatesAndKeepsSyncIdStableAcrossUpdates() {
        Request request = new Request();
        // renseigne les champs obligatoires minimaux de Request (vérifie lesquels
        // sont @Column(nullable=false) dans Request.java avant d'écrire ce test)
        Request saved = requestRepository.save(request);

        assertThat(saved.getSyncId()).isNotNull();
        UUID firstSyncId = saved.getSyncId();

        saved.setStatus(RequestStatus.values()[0]); // n'importe quelle modification
        Request updated = requestRepository.save(saved);

        assertThat(updated.getSyncId()).isEqualTo(firstSyncId);
    }
}
```

Adapte la construction de `Request` aux champs réellement obligatoires (relis `Request.java` avant d'écrire ce test — ce plan ne les énumère pas car ils ne concernent pas ce qui est modifié ici).

- [ ] **Step 2 : Lancer le test, vérifier qu'il échoue**

Run: `./mvnw test -Dtest=RequestSyncIdTest`
Expected: FAIL — `getSyncId()` n'existe pas encore sur `Request`.

- [ ] **Step 3 : Implémenter `Syncable` dans `features/commun/Syncable.java`**

`@MappedSuperclass`, champ `private UUID syncId` (colonne `sync_id`, nullable à la création mais jamais null après persistance), getter `getSyncId()`, méthode `@PrePersist` qui l'assigne via `UUID.randomUUID()` SEULEMENT si `syncId == null`. Pas de setter public (lecture seule depuis l'extérieur — seul le mécanisme interne l'assigne).

- [ ] **Step 4 : Faire hériter `Request` de `Syncable`**

`public class Request extends Syncable { ... }` dans `Request.java` — vérifie que `Request` n'étend déjà aucune autre classe (sinon Java mono-héritage bloque, et il faut alors composer `Syncable` en champ plutôt qu'en héritage ; ajuste `Syncable`/le test en conséquence si c'est le cas).

- [ ] **Step 5 : Lancer le test, vérifier qu'il passe**

Run: `./mvnw test -Dtest=RequestSyncIdTest`
Expected: PASS.

- [ ] **Step 6 : Commit**

```bash
git add src/main/java/com/logicielpourtous/site/features/commun/Syncable.java src/main/java/com/logicielpourtous/site/features/request/Request.java src/test/java/com/logicielpourtous/site/features/request/RequestSyncIdTest.java
git commit -m "feat(commun): ajoute Syncable (syncId) et l'applique a Request"
```

---

# Fondation : Modulith + rôle de serveur + clé de synchronisation — Design

## Intention

1er sous-projet de l'architecture distribuée décrite dans `docs/superpowers/specs/2026-10-26-architecture-distribuee-multi-serveurs-design.md` (document parent, à lire d'abord — toutes ses décisions s'appliquent ici). Pose les 3 fondations dont tous les sous-projets suivants (moteur de synchronisation, multi-tenance client, login à froid hors ligne, import Firebase) dépendent : frontières de module explicites (Spring Modulith), notion de rôle de serveur, et mécanisme de clé de synchronisation. Validé sur une tranche verticale concrète et réelle : le module `request` (la "demande" du flux décrit par l'utilisateur : client crée une demande → envoyée au partenaire → traitée → réponse).

## Ce qui existe déjà (vérifié — corrigé après relecture, inventaire initial incomplet)

- `features/request/` contient en réalité **21 fichiers**, deux sous-ensembles cohabitant dans le même package :
  - La demande client elle-même : `Request`, `RequestStatus`, `RequestType`, `RequestPriority`, `RequestService`, `RequestRepository`, `RequestController` (interne), `RequestManagerController`, `PublicRequestController` — le flux "demande client" décrit par l'utilisateur.
  - Un système de tickets support co-localisé mais distinct fonctionnellement : `TicketController`, `TicketRoutingRule`, `TicketRoutingService`, `TicketAttachment`(+repository), `TimelineEvent`, `Comment`(+repository), `TimeEntry`(+repository) — tous rattachés à `Request` par une FK (`@ManyToOne Request request`), mais avec des dépendances externes propres (`intervention`, `mail`, `invoice`, `order` en plus de `customers`/`subscription`/`team`/`user`/`auth` déjà visibles sur la demande elle-même). Décision utilisateur : ce sous-ensemble est extrait AVANT la validation Modulith (cf. Goals).
- `features/commun/` : infrastructure de multi-tenance partenaire déjà en place (`Tenant`, `TenantContext`, `SchemaTenantResolver`, `MultiTenantConnectionProviderImpl`, `TenantInterceptor`, `TenantManagementService`) + chiffrement déjà disponible (`CryptoConfig`, `EncryptionConverter` — réutilisable plus tard pour le login à froid hors ligne, hors scope ici). Contient aussi `IntraCompanyController`/`TenantRepository`, non liés aux préoccupations de tenance/chiffrement du reste du package — seule source de dépendance externe actuelle de `commun` (`auth`, `customers`). **Mon call (Minor), pas de refactoring ici** : accepté tel quel, déclaré explicitement comme dépendance externe du module `commun` plutôt que déplacé — enjeu bien moindre que le cas `request` (1 fichier, couplage modeste).
- Confirmé par le vérificateur : aucune dépendance croisée `commun`↔`request` aujourd'hui dans les deux sens — `Syncable` peut être ajouté dans `commun` et consommé par `request` sans créer de cycle.
- Toutes les entités existantes (`~44` modules sous `features/`) utilisent `@Id @GeneratedValue(IDENTITY)` — clé primaire `Long` locale au schéma, aucun champ de synchronisation global aujourd'hui.
- Aucune notion de rôle de serveur dans la configuration actuelle — un seul profil de déploiement.
- Spring Boot 4.0.2, Java, Maven (`pom.xml`).
- Point technique noté par le vérificateur, pour le porteur du plan : la classe `@SpringBootApplication` (`SiteApplication`) vit dans `com.logicielpourtous.site`, avec `config`/`features`/`service` en paquets frères. `ApplicationModules.of(...)` doit être pointé sur une classe À L'INTÉRIEUR de `features` (pas sur `SiteApplication`), sinon la détection par défaut de Modulith (un niveau) traiterait `config`/`features`/`service` comme 3 "modules" au lieu de chaque sous-paquet de `features` individuellement — ce qui annulerait silencieusement tout l'objectif de ce sous-projet.
- Toutes les entités existantes (`~44` modules sous `features/`) utilisent `@Id @GeneratedValue(IDENTITY)` — clé primaire `Long` locale au schéma, aucun champ de synchronisation global aujourd'hui.
- Aucune notion de rôle de serveur dans la configuration actuelle — un seul profil de déploiement.
- Spring Boot 4.0.2, Java, Maven (`pom.xml`).

## Goals

- **Extraire UNIQUEMENT le sous-système de routage de tickets** (`TicketRoutingRule`, `TicketRoutingService`, `TicketRoutingRuleController`, `TicketRoutingRuleRepository`) hors de `features/request` vers un nouveau package `features/ticketing` — décision utilisateur après investigation : `Comment`/`TimeEntry`/`TicketAttachment` sont réellement couplés dans LES DEUX SENS avec `Request` aujourd'hui (FK `ticketing→request` d'un côté, mais `Request.java` porte lui-même des champs `@OneToMany` vers `Comment`/`TimeEntry`, et `RequestController`/`RequestService`/`TicketController` les utilisent directement dans l'autre sens) — les déplacer créerait un cycle entre modules que Modulith refuserait de valider, et le dé-couplage complet (retirer les champs `@OneToMany` de `Request.java` + déplacer la logique d'ajout/affichage de commentaires actuellement dans `RequestController`/`RequestService`) est un vrai chantier de refactoring, remis à plus tard. Seul le routage (`TicketRoutingRule`/`Service`/`Controller`/`Repository`) est réellement indépendant aujourd'hui (dépend seulement de `team`/`user`, aucun lien avec `Request`/`Comment`/`TimeEntry`) — extraction sans risque.
- `Comment`, `TimeEntry`, `TicketAttachment` (+ repositories), `TimelineEvent`, `TicketController` **restent dans `features/request`** pour ce sous-projet — le module `request` vérifié par Modulith est donc plus large que l'idéal (voir contraintes ci-dessous pour ses dépendances externes réelles), mais sans toucher à l'affichage/ajout de commentaires côté client.
- Ajouter Spring Modulith au projet et vérifier les frontières de **2 modules seulement** pour l'instant : `commun` et `request` (après l'extraction du routage ci-dessus) — pas une migration des 44 modules existants (cf. doc parent : "migration progressive module par module"). `ticketing` (routage seul) n'est pas vérifié par Modulith dans ce sous-projet (pourra l'être plus tard, avec le reste du ticketing quand son découplage sera fait).
- Introduire un **rôle de serveur** configurable (`app.role` = `master`/`partner`/`client`/`public`), lisible depuis n'importe quel bean du projet, avec `master` comme valeur par défaut (le déploiement NAS actuel).
- Introduire un mécanisme réutilisable de **clé de synchronisation** (`uuid` généré automatiquement) et l'appliquer à `Request` — la seule entité concernée par ce sous-projet.

## Non-goals

- N'implémente aucun transport réseau réel entre serveurs (sous-projet "moteur de synchronisation", suivant) — ce sous-projet pose juste les fondations lues/utilisées par ce transport plus tard.
- Ne retouche aucun des 42 autres modules `features/*`.
- N'implémente pas encore de restriction de modules par rôle (ex. "le rôle client ne voit pas tel module") — juste la capacité de LIRE le rôle actif ; l'application de restrictions arrive avec le moteur de synchronisation ou plus tard.
- Ne touche pas à la multi-tenance (ni partenaire existante, ni client à venir).

## Contraintes

- Spring Boot et Maven déjà en place — ajouter les dépendances Modulith (`spring-modulith-starter-core`, `spring-modulith-starter-test`) à `pom.xml`, versions compatibles Spring Boot 4.0.2 (le porteur du plan vérifie la version Modulith correspondante sur son BOM officiel).
- Le test de vérification Modulith (`ApplicationModules.of(...).verify()`) doit réussir en ne couvrant QUE `commun` et `request` — exclure explicitement les autres packages de `features/` de la vérification (sinon le test échoue immédiatement sur des violations préexistantes hors scope).
- **Dépendances externes réelles du module `request` (confirmées par grep, à déclarer explicitement — `allowedDependencies` ou équivalent, pas des exclusions silencieuses) :** `auth`, `customers`, `intervention`, `invoice`, `mail`, `order`, `subscription`, `team`, `user` — 9 modules, pas 4-5. C'est le périmètre réel une fois `Comment`/`TimeEntry`/`TicketAttachment`/`TicketController` conservés dans `request` (cf. Goals).
- `features/ticketing` (routage seul pour ce sous-projet) dépend seulement de `team`/`user` — pas vérifié par Modulith ici, mais à garder cohérent pour la suite.
- `features/commun` a une dépendance externe propre à `auth`/`customers` (via `IntraCompanyController`) — acceptée telle quelle (cf. Ce qui existe déjà), à déclarer explicitement elle aussi.
- `app.role` : valeurs possibles `MASTER`, `PARTNER`, `CLIENT`, `PUBLIC` (enum), lu depuis `application.yml`/variable d'environnement, défaut `MASTER`.
- Le champ de clé de synchronisation s'appelle `syncId` (type `UUID`), généré automatiquement à la création si absent (`@PrePersist` ou équivalent) — jamais null après persistance, jamais modifiable après création.

## Composants

0. **Extraction du routage de tickets uniquement** : déplacement de `TicketRoutingRule`/`TicketRoutingService`/`TicketRoutingRuleController`/`TicketRoutingRuleRepository` de `features/request` vers `features/ticketing`, mise à jour des imports/routes. `Comment`/`TimeEntry`/`TicketAttachment`/`TimelineEvent`/`TicketController` restent dans `features/request`.
1. **Dépendance + test Modulith** (`pom.xml`, nouveau test `ModularityTests`, pointé sur une classe à l'intérieur de `features`, pas sur `SiteApplication`).
2. **`ServerRole`** (enum) + **`ServerRoleProperties`** (lit `app.role`) dans `features/commun/`.
3. **`Syncable`** (classe de base/mapped-superclass ou interface + aspect) portant `syncId`, dans `features/commun/`.
4. **`Request`** modifiée pour hériter/porter `syncId` via `Syncable`.

## Ce qui reste au porteur du plan

- Répartition exacte en tâches (l'extraction ticketing — Composant 0 — doit précéder la validation Modulith — Composant 1).
- Choix précis du mécanisme Modulith pour délimiter `commun`/`request` sans toucher aux 42 autres packages (package-info.java avec annotation, ou configuration explicite du scope de `ApplicationModules.of(...)`).
- Mécanisme exact de génération de `syncId` (callback JPA `@PrePersist` vs `@EntityListeners` vs valeur par défaut Java au niveau du champ) — choisir le plus cohérent avec le style déjà utilisé dans `features/commun`/`features/request`.
- Tests prouvant : le rôle par défaut est bien `MASTER` sans configuration explicite ; `syncId` est bien généré et stable (jamais régénéré) sur une entité `Request` persistée puis relue ; les routes de routage de tickets (`TicketRoutingRuleController`) continuent de fonctionner après déplacement vers `features/ticketing` ; `commun`/`request` passent `ApplicationModules.of(...).verify()` avec les dépendances externes réelles explicitement déclarées (9 modules pour `request`, `auth`/`customers` pour `commun`).

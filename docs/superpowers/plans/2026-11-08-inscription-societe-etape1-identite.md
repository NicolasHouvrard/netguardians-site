# Inscription d'une société, étape 1 : identité et registre unique. Plan d'implémentation

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Contrôler les numéros d'identité d'une société (SIREN, SIRET, TVA), garantir qu'une société n'existe qu'une fois sur la plateforme grâce à un registre unique, et produire un rapport des doublons déjà présents. Aucun changement du formulaire d'inscription (c'est l'étape 3).

**Architecture:** Un nouveau module Modulith indépendant, `features/identite`, sans dépendance vers les autres modules. Il contient des fonctions pures de validation, une table-registre (`societes_identites`) protégée par des index uniques, un service d'enregistrement, et un rapport qui lit les tables des autres modules **en SQL** (pas par leurs classes Java) pour respecter les frontières vérifiées par `ModularityTests`.

**Tech Stack:** Java, Spring Boot 4, JPA/Hibernate, Flyway, Thymeleaf, PostgreSQL, JUnit 5 (+ `@SpringBootTest`, MockMvc).

**Spec:** `docs/superpowers/specs/2026-11-08-inscription-societe-design.md` (sections 1, 2 et « Livraison », étape 1).

## Prérequis d'environnement (à faire une fois)

Mêmes que pour les chantiers précédents : fichiers **ignorés par git** `src/main/resources/application.properties` (copie de `application.properties.example`) et `application-local.properties` (`spring.datasource.url/username/password`) vers une base locale de test, `JAVA_HOME` sur le JDK 25. Commande : `mvn -B -Dskip.installnodenpm=true -Dskip.npm=true test -Dtest=<Classe>` (sans ces deux options, Maven reconstruit tout l'Angular, 17 minutes, et réécrit des fichiers suivis par git ; **ne jamais `git add -A`**). Un test ne compte que si le rapport affiche `Tests run: N` avec N > 0 (le rapport d'une classe contenant un `@Nested` affiche `0` pour la classe extérieure : lire la ligne de total). Travailler sur une branche dédiée (`identite-societe`).

## Global Constraints

- Nouveau module `com.logicielpourtous.site.features.identite`, **sans** dépendance vers un autre module (`@ApplicationModule` sans `allowedDependencies`) ; le rapport lit les autres tables par `JdbcTemplate`. `ModularityTests` doit rester vert.
- Nommage des colonnes : explicite, minuscules **avec soulignés** (`@Column(name=...)`), comme `tenants`. **Colonnes lues dans les tables existantes (noms exacts de `V1__baseline.sql`)** : `customers(id, companyname, siren, siret, vat_number)`, `tenants(id, name, siren, vat_number)`, `partner_esns(id, company_name, siret, vat_number)`.
- Migration suivante : `V4__registre_societes.sql` (V3 existe). `ddl-auto=validate` : toute colonne d'entité doit exister dans la migration.
- Normalisation : on retire espaces, points et tirets ; la TVA est mise en **majuscules**. Un SIRET (14 chiffres) fournit son SIREN (9 premiers).
- SIREN et SIRET : clé de **Luhn**. **Exception La Poste** (SIREN `356000000`) : un SIRET est accepté si la **somme de ses chiffres est un multiple de 5**, même sans Luhn.
- TVA française : `FR` + clé à 2 chiffres + SIREN, `clé = (12 + 3 × (SIREN mod 97)) mod 97` (sur deux chiffres, avec zéro initial) ; elle doit être **cohérente avec le SIREN**. TVA étrangère : préfixe pays de l'Union européenne (`AT BE BG CY CZ DE DK EE EL ES FI HR HU IE IT LT LU LV MT NL PL PT RO SE SI SK`, plus `XI`) suivi de 2 à 12 caractères `A-Z0-9` ; **format seulement**.
- Une société étrangère (TVA non française) ne peut être que de type **FOURNISSEUR** ; son SIREN doit être absent.
- Messages d'erreur en français ; aucun appel à un service externe (INSEE, VIES).

## Review Focus

1. **Variantes de saisie** : `552 032 534`, `552.032.534`, un SIRET à la place du SIREN, TVA en minuscules ou avec espaces : toutes se ramènent à la même identité et sont détectées comme doublon (Task 2).
2. **TVA française avec la bonne forme mais une mauvaise clé**, ou une TVA qui ne correspond pas au SIREN fourni : refusée (Task 1).
3. **SIRET dont les 9 premiers chiffres ne sont pas le SIREN saisi** : refusé (Task 1).
4. **Société étrangère enregistrée comme client ou partenaire**, ou avec un SIREN français : refusée (Tasks 1 et 2).
5. **Filet de sécurité de la base** : si le contrôle applicatif est contourné, l'index unique refuse quand même le doublon (Task 2).

---

### Task 1: Contrôle des numéros et identité normalisée

**Files:**
- Create: `src/main/java/com/logicielpourtous/site/features/identite/package-info.java`
- Create: `src/main/java/com/logicielpourtous/site/features/identite/NumerosSociete.java`
- Create: `src/main/java/com/logicielpourtous/site/features/identite/IdentiteSociete.java`
- Create: `src/main/java/com/logicielpourtous/site/features/identite/IdentiteInvalideException.java`
- Test: `src/test/java/com/logicielpourtous/site/features/identite/NumerosSocieteTest.java`
- Test: `src/test/java/com/logicielpourtous/site/features/identite/IdentiteSocieteTest.java`

**Interfaces:**
- Produces (`NumerosSociete`, classe utilitaire finale, méthodes statiques pures) :
  - `String chiffres(String brut)` : retire espaces, points, tirets ; `null` si le résultat est vide ou si `brut` est `null`.
  - `String tva(String brut)` : idem, en majuscules.
  - `boolean sirenValide(String s)` ; `boolean siretValide(String s)` (avec exception La Poste) ; `String sirenDe(String siret)` ; `String tvaFrancaise(String siren)` ; `boolean tvaEtrangereValide(String tva)`.
- Produces (`IdentiteSociete`, `record IdentiteSociete(String siren, String tva, boolean etrangere)`) : `static IdentiteSociete depuis(String siren, String siret, String tva)` ; `siren` vaut `null` pour une société étrangère ; lève `IdentiteInvalideException` (sous-classe de `IllegalArgumentException`, porte `String champ()` valant `"siren"`, `"siret"` ou `"tva"`, et un message français).

Valeurs de test vérifiées (clés calculées) : SIREN `404833048` → TVA `FR83404833048` ; SIREN `732829320` → `FR44732829320` ; SIREN `552032534` → `FR27552032534` ; SIRET valides `40483304800022`, `40483304800006` (de `404833048`) et `55203253400018` (de `552032534`) ; SIREN invalide `123456789` ; La Poste : SIRET `35600000000001` accepté (exception), `35600000000000` refusé ; TVA étrangère valide `DE123456789`.

- [ ] **Step 1: Write the failing tests.** `NumerosSocieteTest` (sans Spring) : `sirenValide` vrai pour `404833048`, faux pour `123456789`, `40483304`, `4048330480`, `abcdefghi` et `null` ; `siretValide` vrai pour `40483304800022`, faux pour `40483304800023` (Luhn) et pour 13 chiffres ; exception La Poste : `siretValide("35600000000001")` vrai, `siretValide("35600000000000")` faux ; `chiffres(" 552.032-534 ")` vaut `552032534` et `chiffres("  ")` vaut `null` ; `tva(" fr 27 552.032.534 ")` vaut `FR27552032534` ; `tvaFrancaise("404833048")` vaut `FR83404833048` ; `tvaEtrangereValide("DE123456789")` vrai, `tvaEtrangereValide("US123456789")` faux, `tvaEtrangereValide("DE1")` faux. `IdentiteSocieteTest` (sans Spring) : `depuis("404 833 048", null, "fr83404833048")` donne `("404833048", "FR83404833048", false)` ; `depuis(null, "40483304800022", "FR83404833048")` donne le même SIREN ; échecs avec le bon `champ()` : TVA absente (`tva`), TVA `FR84404833048` mauvaise clé (`tva`), TVA française avec un autre SIREN que celui fourni (`tva`), SIREN invalide avec TVA française (`siren`), SIRET dont les 9 premiers chiffres diffèrent du SIREN fourni (`siret`), TVA française sans SIREN ni SIRET (`siren`), TVA étrangère `DE123456789` sans SIREN valide donne `(null, "DE123456789", true)`, TVA étrangère **avec** un SIREN (`siren`), TVA d'un pays hors Union (`tva`).
- [ ] **Step 2: Run to verify they fail** : `-Dtest=NumerosSocieteTest,IdentiteSocieteTest` ; attendu : échec de compilation (classes absentes).
- [ ] **Step 3: Implement** les trois classes selon les signatures et les contraintes globales, et `package-info.java` avec `@ApplicationModule` sans dépendance (même forme que `features/commun/package-info.java`). L'algorithme de Luhn est standard (doubler un chiffre sur deux en partant de la droite, soustraire 9 au-delà de 9, somme multiple de 10).
- [ ] **Step 4: Run the tests to verify they pass**, puis `-Dtest=ModularityTests` (nouveau module reconnu, aucune violation).
- [ ] **Step 5: Commit** : `git add src docs && git commit -m "feat(identite): controle des numeros SIREN, SIRET et TVA"`.

### Task 2: Registre unique des sociétés

**Files:**
- Create: `src/main/resources/db/migration/V4__registre_societes.sql`
- Create: `src/main/java/com/logicielpourtous/site/features/identite/TypeSociete.java`
- Create: `src/main/java/com/logicielpourtous/site/features/identite/SocieteIdentite.java`
- Create: `src/main/java/com/logicielpourtous/site/features/identite/SocieteIdentiteRepository.java`
- Create: `src/main/java/com/logicielpourtous/site/features/identite/SocieteDejaConnueException.java`
- Create: `src/main/java/com/logicielpourtous/site/features/identite/RegistreSocietes.java`
- Test: `src/test/java/com/logicielpourtous/site/features/identite/RegistreSocietesTest.java`

**Interfaces:**
- Consumes: Task 1 (`IdentiteSociete`, `NumerosSociete`).
- Produces : `enum TypeSociete { CLIENT, PARTENAIRE, FOURNISSEUR }` ; entité `SocieteIdentite` (table `societes_identites`, colonnes `id`, `siren` nullable, `tva` non nulle, `type`, `proprietaire_tenant_id` nullable (`null` = le Maître), `fiche_type` et `fiche_id` nullables, `cree_a`) ; `SocieteIdentiteRepository extends JpaRepository<SocieteIdentite, Long>` avec `Optional<SocieteIdentite> findBySiren(String)` et `Optional<SocieteIdentite> findByTva(String)` ; `SocieteDejaConnueException extends RuntimeException` ; `RegistreSocietes` (`@Service @Transactional`) : `Optional<SocieteIdentite> trouver(IdentiteSociete identite)` (par SIREN s'il existe, sinon par TVA) et `SocieteIdentite enregistrer(TypeSociete type, IdentiteSociete identite, Long proprietaireTenantId)`.

- [ ] **Step 1: Write the failing tests** `RegistreSocietesTest` (`@SpringBootTest @Transactional`) : `enregistrer_conserveLaSocieteEtLeProprietaire` (relue après `em.clear()`) ; `enregistrer_refuseLeDoublonParSirenSousToutesLesFormes` : enregistrer `552 032 534`/`FR27552032534`, puis refuser (`SocieteDejaConnueException`) la même société saisie comme `552.032.534`, puis par son SIRET `55203253400018`, puis avec la TVA en minuscules ; `enregistrer_refuseUneSocieteEtrangereDejaConnueParSaTva` (`DE123456789`) ; `enregistrer_refuseUneSocieteEtrangereCommeClientOuPartenaire` (`IllegalArgumentException` pour `CLIENT` et `PARTENAIRE`, accepté pour `FOURNISSEUR`) ; `trouver_renvoieVideSiInconnue` ; `labaseRefuseUnDoublonMemeSansLeControleApplicatif` : insertion SQL directe de deux lignes de même SIREN par `JdbcTemplate`, la seconde lève `DuplicateKeyException`, idem pour la TVA ; `leSirenPeutEtreAbsentPourPlusieursEtrangers` (deux fournisseurs étrangers aux TVA différentes coexistent : l'index sur le SIREN ignore les valeurs nulles).
- [ ] **Step 2: Run to verify they fail** : échec de compilation.
- [ ] **Step 3: Implement** la migration (table, **index unique partiel** sur `siren` `WHERE siren IS NOT NULL`, index unique sur `tva`), l'entité (colonnes explicites, `@PrePersist` pour `cree_a`), le repository, l'exception (elle porte la fiche existante, **sans** l'exposer dans son message public), et `RegistreSocietes` : `enregistrer` vérifie la règle « étrangère donc FOURNISSEUR », refuse via `trouver`, puis enregistre avec `saveAndFlush` en traduisant `DataIntegrityViolationException` en `SocieteDejaConnueException`.
- [ ] **Step 4: Run the tests to verify they pass** ; relancer `FlywayMigrationTest` et `ModularityTests`.
- [ ] **Step 5: Commit** : `git commit -m "feat(identite): registre unique des societes (migration V4)"`.

### Task 3: Rapport des doublons existants

**Files:**
- Create: `src/main/java/com/logicielpourtous/site/features/identite/RapportDoublonsService.java`
- Create: `src/main/java/com/logicielpourtous/site/features/identite/GroupeDoublon.java`
- Test: `src/test/java/com/logicielpourtous/site/features/identite/RapportDoublonsServiceTest.java`

**Interfaces:**
- Consumes: Task 1 (`NumerosSociete.chiffres`, `NumerosSociete.sirenDe`, `NumerosSociete.tva`).
- Produces : `record GroupeDoublon(String nature, String cle, List<LigneSource> lignes)` avec `record LigneSource(String table, Long id, String nom)` ; `RapportDoublonsService` (`@Service`, constructeur avec `JdbcTemplate`) : `List<GroupeDoublon> rapport()`. Règle : pour chaque ligne de `customers`, `tenants` et `partner_esns`, le SIREN normalisé est celui de la colonne `siren`, sinon celui déduit de `siret` ; un groupe `SIREN` réunit les lignes (de **n'importe quelles** tables) qui partagent un SIREN, s'il y en a au moins 2 ; un groupe `TVA` réunit celles qui partagent une TVA normalisée **parmi les lignes sans SIREN** ; les lignes sans aucun identifiant sont ignorées. Aucun écrit en base.

- [ ] **Step 1: Write the failing tests** `RapportDoublonsServiceTest` (`@SpringBootTest @Transactional`, insertions par `JdbcTemplate` avec les seules colonnes obligatoires : `customers(active, companyname, …)`, `tenants(name, schema_name, …)`, `partner_esns(active, company_name, …)` — vérifier les colonnes `NOT NULL` dans `V1__baseline.sql`) : `deuxClientsDeMemeSiren_formeUnGroupe` (une saisie `552 032 534` et l'autre `552032534`) ; `unClientEtUnTenantDeMemeSiren_formentUnGroupeInterTables` ; `unSiretEtUnSirenDeLaMemeSociete_formentUnGroupe` ; `deuxEtrangersDeMemeTva_formentUnGroupeTva` ; `uneSocieteSansIdentifiant_estIgnoree` ; `aucunDoublon_rapportVide` (en comparant seulement les groupes créés par le test, la base pouvant contenir d'autres lignes).
- [ ] **Step 2: Run to verify they fail** : échec de compilation.
- [ ] **Step 3: Implement** `rapport()` : lire les trois tables par SQL, normaliser en Java avec `NumerosSociete`, grouper, ne garder que les groupes de 2 lignes ou plus, trier par clé.
- [ ] **Step 4: Run the tests to verify they pass** ; relancer `ModularityTests`.
- [ ] **Step 5: Commit** : `git commit -m "feat(identite): rapport des doublons de societes"`.

### Task 4: Page d'administration du rapport

**Files:**
- Create: `src/main/java/com/logicielpourtous/site/features/identite/DoublonsSocietesController.java`
- Create: `src/main/resources/templates/feature/identite/doublons.html`
- Modify: `src/main/resources/templates/feature/licence/federation.html` (lien dans l'en-tête)
- Test: `src/test/java/com/logicielpourtous/site/features/identite/DoublonsSocietesControllerTest.java`

**Interfaces:**
- Consumes: Task 3 (`RapportDoublonsService.rapport()`, `GroupeDoublon`).
- Produces : `@Controller @RequestMapping("/intra/societes/doublons") @PreAuthorize("hasRole('ADMIN')")` ; `GET ""` renvoie la vue `feature/identite/doublons` avec l'attribut `groupes`. **Réservé au Maître** (404 si `app.role` n'est pas `MASTER`, comme `CompteLicenceAdminController`).

- [ ] **Step 1: Write the failing tests** `DoublonsSocietesControllerTest` (`@SpringBootTest @AutoConfigureMockMvc @Transactional`, même helper `adminPrincipal` que `FederationAdminControllerTest`, à recopier) : `get_afficheLesGroupesDeDoublons` (deux clients de même SIREN, la page contient le SIREN et les deux noms) ; `get_sansDoublon_afficheUnMessageVide` ; `utilisateurNonAdmin_estRefuse` (403) ; classe `@Nested` avec `@TestPropertySource(properties = "app.role=partner")` : `pageIntrouvableSiCeServeurNEstPasLeMaitre` (404).
- [ ] **Step 2: Run to verify they fail** : 404 sur la route.
- [ ] **Step 3: Implement** le contrôleur (garde `requireMaster()` identique à celle de `CompteLicenceAdminController`) et la page Thymeleaf dans la mise en page `intra-layout` : un tableau par groupe (nature, clé, puis table, identifiant, nom de chaque ligne), le message « Aucun doublon » sinon, et la mention « Aucune fusion automatique : corrigez ou supprimez les fiches à la main ». Ajouter le lien dans `federation.html`.
- [ ] **Step 4: Run the tests to verify they pass** ; puis la **suite complète** : les seuls échecs attendus sont les 8 tests de synchronisation déjà rouges sur `main` (`comments.author_id`).
- [ ] **Step 5: Commit** : `git commit -m "feat(identite): page d'administration des doublons de societes"`.

---

## Self-review

- **Couverture de la spec (étape 1)** : contrôle des numéros, exception La Poste, cohérence TVA/SIREN, TVA étrangère en format seulement (Task 1) ; registre unique, index uniques, type société et propriétaire, étrangère uniquement fournisseur (Task 2) ; rapport des doublons sans fusion automatique (Tasks 3 et 4). Hors de cette étape, par la spec : appartenance multi-sociétés (étape 2), formulaires d'inscription (3), invitation (4), transfert (5).
- **Cohérence des types** : `IdentiteSociete.depuis`, `NumerosSociete.*`, `RegistreSocietes.enregistrer/trouver`, `GroupeDoublon`/`LigneSource` et `RapportDoublonsService.rapport()` portent les mêmes noms et signatures partout.
- **Non-effets assumés** : à la fin de l'étape 1, le registre est **vide** et rien ne l'alimente ; les formulaires d'inscription ne changent pas. Seul le rapport est visible. L'ajout des contraintes sur les tables existantes (`customers`, `tenants`) n'est **pas** fait ici : il suivra, après lecture du rapport, quand les inscriptions alimenteront le registre.
- **Proportion** : quatre tâches pour une étape de la spec ; le code n'est pas dans le plan, seulement les signatures, les valeurs vérifiées et les assertions.

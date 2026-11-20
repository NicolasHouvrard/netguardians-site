# Cadre des plugins et gestion des clients — lot A — plan d'implémentation

> **Pour les exécutants :** sous-compétence obligatoire : superpowers:subagent-driven-development (recommandé) ou superpowers:executing-plans. Les étapes utilisent des cases `- [ ]`.

**Objectif :** sur un seul serveur, livrer le cadre (entrées de tuiles et blocs de fiche déclarés par les plugins, contrat d'identité, droits à trois couches) et le plugin « clients » (liste, fiche, création, accès à l'espace client).

**Architecture :** le noyau (`features/identite`, `features/user`, nouveau `features/clients`) possède société, capacités, lien client→partenaire, accès et droits ; le plugin `clients` (nouveau module Maven `plugins-clients/`) possède les écrans et les parcours et n'accède au noyau que par des contrats du SDK. L'existant est **étendu** (décision du propriétaire, 2026-11-18) : `societes_identites` devient la Société, `appartenances` l'accès délégué, le flux `demandes_societe_connue` est réutilisé pour les doublons. `Customer` n'est pas utilisé par le nouveau code.

**Pile :** Spring Boot 4.0.2, Java 25, PostgreSQL, Flyway, JUnit 5/AssertJ, Angular (`frontend/intra`, et projet Angular séparé du plugin), SDK `src/sdk/java` (module `plugins-sdk`).

**Spec :** `docs/superpowers/specs/2026-11-18-cadre-plugins-gestion-clients-design.md` (§4 cadre, §5 modèle, §6 parcours, §8 choix du constructeur).

## Contraintes globales

- Java 25 : exécuter Maven avec `C:\Users\nicolas\scoop\apps\temurin25-jdk\current\bin\java.exe` (le `java` du PATH est Java 8). Lancer **une classe de test à la fois** (`-Dtest=Classe`), sinon Postgres « too many clients ». 8 échecs de tests de synchronisation préexistants : les ignorer.
- Ne jamais pousser, fusionner, taguer ni déployer sans demande explicite. Messages de commit terminés par `Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>`.
- Contrats du SDK : types JDK et records uniquement ; aucun type de plugin dans un contrat. Le noyau ne dépend jamais d'un plugin.
- Un plugin vit sous `com.logicielpourtous.site.plugins.<plugin>` ; routes `/api/plugins/<nom>/...` ; son code est en scope `provided` (Spring/Hibernate/Jackson imposés par le noyau).
- Nouvelles tables du noyau : migration Flyway `V11__...` dans `src/main/resources/db/migration` (la dernière actuelle est `V10`).
- **Décisions du propriétaire (2026-11-18, après relecture du plan) :** le rôle manager client est `ROLE_GESTIONNAIRE` ; les **particuliers** (sans SIREN ni TVA) sont des clients à part entière, mais **l'e-mail d'un particulier ne peut jamais être celui d'un utilisateur existant** ; le front d'un plugin doit pouvoir être **séparé du front du noyau** (livré par le plugin) ; le comptable externe viendra plus tard.
- **Hors lot A :** inscription seul d'un client ou d'un particulier sur le site public (lot B : le lot A fournit le modèle et la création par le partenaire), cycle de vie du partenaire (documents, états), annuaire du Maître et gestion inter-serveurs, migration de données, comptable externe (partenaire de type comptable), abstraction des sources d'identité (LDAP/Google/Microsoft : le lot A garde l'authentification actuelle), schéma PostgreSQL par client (le lot A ne touche pas à `Tenant`), prérequis techniques SP3 (fuite d'application complète, `TenantContext`, `*IT` en CI).
- **Numérotation des migrations :** tâche 2 = `V11`, tâches 4 et 5 = `V12` (une seule migration pour les deux), tâche 11 = `V13`. Aucune n'est livrée avant l'exécution du plan ; si une migration plus récente existe au démarrage, prendre le numéro libre suivant et le noter dans le ledger.

## Review Focus (modes de défaillance que les tests de tâche n'exercent pas seuls)

1. Un partenaire qui obtient plus que son plafond en passant par « accéder à l'espace du client » ou par un lien de cet espace.
2. Un utilisateur qui retire le dernier manager d'un client, ou le premier utilisateur créé non manager.
3. Un plugin dont la mise à jour à chaud change ses entrées de tuiles : la navigation doit refléter la nouvelle version sans redémarrage et ne jamais montrer un plugin en maintenance.
4. Un client rattaché à aucun partenaire (aucun partenaire par défaut configuré) : échec explicite, jamais un client orphelin.
5. Fuite entre sociétés : un manager ou un partenaire qui lit la fiche ou les demandes d'une société qui n'est pas la sienne.

---

### Tâche 1 : entrées de tuiles et blocs de fiche dans le manifeste (SDK)

**Fichiers :**
- Créer : `src/sdk/java/com/logicielpourtous/site/sdk/ZoneNavigation.java`, `EntreeNavigation.java`, `BlocFiche.java`
- Modifier : `src/sdk/java/com/logicielpourtous/site/sdk/ManifestePlugin.java` ; ses appelants (grep `new ManifestePlugin(`, y compris `src/test/.../JarDePluginDeTest.java`)
- Test : `src/test/java/com/logicielpourtous/site/features/plateforme/ManifestePluginTest.java` (existant, à étendre)

**Interfaces :**
- Produit : `enum ZoneNavigation { PUBLIQUE, CLIENT, PARTENAIRE, ADMIN }` ; `record EntreeNavigation(ZoneNavigation zone, String libelle, String icone, String chemin, String droit)` ; `record BlocFiche(String id, String libelle, String chemin, String droit)` ; `ManifestePlugin` gagne les composants `List<EntreeNavigation> entrees` et `List<BlocFiche> blocs`, **en fin de liste**.
- Format `plugin.properties` : `entree.<n>=<ZONE>|<libellé>|<icône>|<chemin>|<droit>` et `bloc.<n>=<id>|<libellé>|<chemin>|<droit>` (n = 1, 2, …). Absence = liste vide (les plugins existants restent valides).

- [ ] **Étape 1 : tests en échec** dans `ManifestePluginTest` : (a) deux `entree.N` et un `bloc.1` sont lus dans l'ordre ; (b) zone inconnue → `IllegalArgumentException` nommant la zone ; (c) `chemin` ne commençant pas par `/` → rejeté ; (d) `droit` hors `^[a-z][a-z0-9_.]{0,59}$` → rejeté ; (e) manifeste sans entrée → listes vides ; (f) `id` de bloc hors `NOM_VALIDE` → rejeté.
- [ ] **Étape 2 : exécuter** `-Dtest=ManifestePluginTest` ; attendu : ÉCHEC (méthodes/composants absents).
- [ ] **Étape 3 :** implémenter les trois types et l'analyse dans `ManifestePlugin.depuis` ; mettre à jour tous les `new ManifestePlugin(...)` avec `List.of(), List.of()`.
- [ ] **Étape 4 :** exécuter `ManifestePluginTest`, `LecteurManifesteTest`, `PluginManagerCablageTest` ; attendu : tout passe.
- [ ] **Étape 5 :** commit `feat(sdk): entrées de tuiles et blocs de fiche dans le manifeste`.

### Tâche 2 : capacités de la société et lien client→partenaire (migration V11)

**Fichiers :**
- Créer : `src/main/resources/db/migration/V11__capacites_clients.sql` ; `features/clients/CapaciteSociete.java`, `CapaciteSocieteRepository.java`, `LienClient.java`, `LienClientRepository.java`, `TypeCapacite.java`
- Test : `src/test/java/com/logicielpourtous/site/features/clients/V11MigrationIT.java`

**Interfaces :**
- Produit : `enum TypeCapacite { CLIENT, PARTENAIRE, FOURNISSEUR }` ; tables `societe_capacites(societe_id → societes_identites, capacite, cree_a, UNIQUE(societe_id, capacite))` et `liens_clients(client_societe_id UNIQUE → societes_identites, partenaire_societe_id → societes_identites, origine ('PARTENAIRE'|'INSCRIPTION'), depuis, changements_annee_courante INT, annee_reference INT)` ; `LienClientRepository.findByClientSocieteId(Long)`, `findByPartenaireSocieteId(Long)` ; `CapaciteSocieteRepository.findBySocieteId(Long)`, `existsBySocieteIdAndCapacite(Long, TypeCapacite)`.
- La migration copie `societes_identites.type` vers `societe_capacites` (un PARTENAIRE reçoit aussi la capacité CLIENT, car un partenaire est un client avec des fonctionnalités en plus), puis rend la colonne `type` nullable (elle n'est plus écrite par le nouveau code).

- [ ] **Étape 1 : test en échec** (`V11MigrationIT`, patron des IT existants `*IT`) : après migration, une société de type PARTENAIRE a les capacités PARTENAIRE et CLIENT ; une de type FOURNISSEUR n'a que FOURNISSEUR ; insérer deux fois la même capacité viole l'unicité ; `liens_clients` refuse un `client_societe_id` en double.
- [ ] **Étape 2 :** exécuter `-Dtest=V11MigrationIT` ; attendu : ÉCHEC.
- [ ] **Étape 3 :** écrire la migration et les entités JPA (schéma `public`, colonnes ci-dessus).
- [ ] **Étape 4 :** exécuter `V11MigrationIT` et `SocieteIdentiteRepositoryTest`/`RegistreSocietesTest` (grep sous `src/test/.../identite`) ; attendu : passent.
- [ ] **Étape 5 :** commit `feat(clients): capacités de société et lien client-partenaire (V11)`.

### Tâche 2 bis : clients particuliers dans le registre

**Fichiers :**
- Modifier : `V11__capacites_clients.sql` (créée à la tâche 2, non livrée) : `ALTER TABLE societes_identites ADD COLUMN particulier BOOLEAN NOT NULL DEFAULT FALSE`, `ALTER COLUMN tva DROP NOT NULL`, `CHECK (particulier OR tva IS NOT NULL)`, et `CHECK (NOT particulier OR (siren IS NULL AND tva IS NULL))`
- Modifier : `features/identite/SocieteIdentite.java` (champ `particulier`), `RegistreSocietes.java`, `features/identite/NumerosSociete.java` si besoin
- Test : `src/test/java/.../features/identite/RegistreSocietesParticulierTest.java`

**Interfaces :**
- Produit : `SocieteIdentite RegistreSocietes.enregistrerParticulier(String emailPremierUtilisateur, Long proprietaireTenantId)` : crée une société `particulier = true`, capacité CLIENT, sans SIREN ni TVA. Lève `EmailDejaUtiliseException` (nouvelle, dans `identite`) si l'e-mail (comparé en minuscules, espaces retirés) est celui d'un utilisateur existant (`UserRepository`) ; **aucune écriture** dans ce cas. L'`IdentiteSociete` (record) n'est pas modifié : un particulier n'en a pas.

- [ ] **Étape 1 : tests en échec** : (a) création d'un particulier : société sans SIREN/TVA, `particulier = true`, capacité CLIENT ; (b) e-mail identique à celui d'un utilisateur existant, y compris avec une casse ou des espaces différents → `EmailDejaUtiliseException` et aucune ligne créée ; (c) la contrainte `CHECK` refuse en base une société non particulière sans TVA ; (d) les sociétés existantes (SIREN/TVA) se comportent comme avant (`RegistreSocietesTest` existant inchangé).
- [ ] **Étape 2 :** exécuter `-Dtest=RegistreSocietesParticulierTest` ; ÉCHEC.
- [ ] **Étape 3 :** implémenter ; l'unicité de l'e-mail utilisateur est déjà garantie en base par `users.email UNIQUE`.
- [ ] **Étape 4 :** exécuter `RegistreSocietesParticulierTest`, `RegistreSocietesTest`, `V11MigrationIT` ; PASSENT.
- [ ] **Étape 5 :** commit `feat(identite): clients particuliers dans le registre`.

### Tâche 3 : partenaire par défaut et rattachement d'un client

**Fichiers :**
- Créer : `features/clients/PartenaireReferentService.java`, `PartenaireDefautAbsentException.java`
- Modifier : `src/main/resources/application.properties` (clé `app.clients.partenaire-defaut-societe-id`, vide par défaut)
- Test : `src/test/java/.../features/clients/PartenaireReferentServiceTest.java`

**Interfaces :**
- Consomme : repositories de la tâche 2, `RegistreSocietes.enregistrer(TypeSociete, IdentiteSociete, Long)`.
- Produit : `LienClient rattacher(Long clientSocieteId, Long partenaireSocieteId, String origine)` ; `Long partenaireDefaut()` (lève `PartenaireDefautAbsentException` si la propriété est vide ou ne désigne pas une société ayant la capacité PARTENAIRE) ; `LienClient rattacherAuDefaut(Long clientSocieteId)`.

- [ ] **Étape 1 : tests en échec** : (a) `rattacher` crée le lien et ajoute la capacité CLIENT à la société si absente ; (b) un partenaire ne peut pas être son propre référent (`IllegalArgumentException`) ; (c) rattacher un client déjà rattaché → `IllegalStateException` (le changement passe par la tâche 4) ; (d) `rattacherAuDefaut` sans propriété configurée → `PartenaireDefautAbsentException` et **aucun lien créé** ; (e) propriété désignant une société sans capacité PARTENAIRE → même exception.
- [ ] **Étape 2 :** exécuter `-Dtest=PartenaireReferentServiceTest` ; ÉCHEC.
- [ ] **Étape 3 :** implémenter (transactionnel ; vérifier la capacité du partenaire avant d'écrire).
- [ ] **Étape 4 :** exécuter la classe ; PASSE.
- [ ] **Étape 5 :** commit `feat(clients): partenaire référent et partenaire par défaut`.

### Tâche 4 : changement de partenaire (une fois par an, sinon validation de l'admin)

**Fichiers :**
- Créer : `src/main/resources/db/migration/V12__changement_partenaire_et_droits.sql` (partagée avec la tâche 5) pour la table `demandes_changement_partenaire(id, client_societe_id, nouveau_partenaire_societe_id, motif, cree_a, statut 'EN_ATTENTE'|'ACCEPTEE'|'REFUSEE')`
- Créer : `features/clients/ChangementPartenaireService.java`, `DemandeChangementPartenaire.java` (+ repository)
- Test : `src/test/java/.../features/clients/ChangementPartenaireServiceTest.java`

**Interfaces :**
- Produit : `ResultatChangement demander(Long clientSocieteId, Long nouveauPartenaireSocieteId, String motif)` avec `record ResultatChangement(boolean applique, Long demandeId)` ; `void trancher(Long demandeId, boolean accepter)` (réservé à l'admin par l'appelant).
- Règle : au plus **1 changement appliqué par année civile** (`annee_reference`/`changements_annee_courante` de `liens_clients`) ; au-delà, une demande `EN_ATTENTE` est créée et le lien n'est pas modifié ; `trancher(…, true)` applique le changement. Dans tous les cas l'ancien partenaire est signalé par un événement `ChangementPartenaireEvenement(clientSocieteId, ancienPartenaireId, nouveauPartenaireId)` (publié ; l'envoi des notifications est hors lot).

- [ ] **Étape 1 : tests en échec** : (a) 1er changement de l'année : appliqué, événement publié ; (b) 2e changement la même année : non appliqué, demande `EN_ATTENTE` ; (c) 2e accepté par `trancher` : appliqué ; (d) refusé : lien inchangé ; (e) 1er changement de l'année suivante (horloge injectée) : appliqué ; (f) nouveau partenaire sans capacité PARTENAIRE → rejeté.
- [ ] **Étape 2 :** exécuter `-Dtest=ChangementPartenaireServiceTest` ; ÉCHEC.
- [ ] **Étape 3 :** implémenter avec une `java.time.Clock` injectée.
- [ ] **Étape 4 :** exécuter la classe ; PASSE.
- [ ] **Étape 5 :** commit `feat(clients): changement de partenaire annuel avec validation admin`.

### Tâche 5 : droits à trois couches (plafond, réglages du manager, utilisateur)

**Fichiers :**
- Modifier : `V12__changement_partenaire_et_droits.sql` (créée à la tâche 4) en y ajoutant : `plafonds_client(client_societe_id, droit, PRIMARY KEY(client_societe_id, droit))` = droits que le partenaire autorise ; `reglages_manager(client_societe_id, droit, role_nom, PRIMARY KEY(…))` = droits que le manager accorde à un rôle dans la société ; `features/clients/DroitsService.java`, `PlafondClient.java`, `ReglageManager.java` (+ repositories)
- Test : `src/test/java/.../features/clients/DroitsServiceTest.java`

**Interfaces :**
- Produit : `void fixerPlafond(Long clientSocieteId, Set<String> droits)` ; `void accorderAuRole(Long clientSocieteId, String roleNom, Set<String> droits)` (lève `DepassePlafondException` si un droit n'est pas dans le plafond) ; `boolean autorise(Long userId, Long societeId, String droit)` (vrai seulement si le droit est dans le plafond ET accordé à l'un des rôles de l'appartenance de l'utilisateur dans cette société ; faux sans appartenance).
- Les droits sont des chaînes de la forme du manifeste (`clients.lire`).

- [ ] **Étape 1 : tests en échec** : (a) accorder un droit hors plafond → `DepassePlafondException`, rien d'écrit ; (b) baisser le plafond retire de fait le droit à `autorise` (le réglage du manager reste stocké mais est inopérant) ; (c) utilisateur sans appartenance dans la société → faux ; (d) utilisateur avec deux rôles, un seul accordé → vrai ; (e) appartenance à la société A ne donne rien sur la société B.
- [ ] **Étape 2 :** exécuter `-Dtest=DroitsServiceTest` ; ÉCHEC.
- [ ] **Étape 3 :** implémenter (lecture des rôles via `Appartenance` existante, par `userId` et `tenant`/société).
- [ ] **Étape 4 :** exécuter la classe ; PASSE.
- [ ] **Étape 5 :** commit `feat(clients): droits à trois couches`.

### Tâche 6 : règles des managers (au moins un, premier créé = manager)

**Fichiers :**
- Créer : `features/clients/ManagersService.java`
- Modifier : le service qui crée une appartenance (grep `new Appartenance(` / `AppartenanceRepository.save`)
- Test : `src/test/java/.../features/clients/ManagersServiceTest.java`

**Interfaces :**
- Le rôle manager client est `ROLE_GESTIONNAIRE` (décision du propriétaire) ; vérifier qu'il existe dans la table `roles` (sinon l'ajouter par la migration V12) et utiliser cette constante partout.
- Produit : `Appartenance ajouterUtilisateur(Long userId, Long societeId, Set<String> roles)` (le premier utilisateur d'une société reçoit d'office le rôle manager) ; `void retirerRoleManager(Long userId, Long societeId)` et `void retirerUtilisateur(Long userId, Long societeId)` (lèvent `DernierManagerException` s'ils laisseraient la société sans manager, ou si la société n'a qu'un seul utilisateur).

- [ ] **Étape 1 : tests en échec** : (a) premier utilisateur → manager même si aucun rôle n'est demandé ; (b) deuxième utilisateur → pas manager ; (c) retirer le rôle manager du seul manager → `DernierManagerException` ; (d) avec deux managers, retirer l'un passe ; (e) société à un seul utilisateur : modifier son statut de manager → refusé ; (f) retirer le seul manager par suppression de l'utilisateur → refusé.
- [ ] **Étape 2 :** exécuter `-Dtest=ManagersServiceTest` ; ÉCHEC.
- [ ] **Étape 3 :** implémenter ; faire passer les points de création d'appartenance existants par `ajouterUtilisateur`.
- [ ] **Étape 4 :** exécuter la classe + les tests d'appartenance/inscription existants (grep `Appartenance` sous `src/test`) ; PASSENT.
- [ ] **Étape 5 :** commit `feat(clients): règles des managers`.

### Tâche 7 : contrat d'identité et contrat « annuaire clients » pour les plugins

**Fichiers :**
- Créer (SDK) : `src/sdk/java/com/logicielpourtous/site/sdk/IdentiteContrat.java`, `AnnuaireClientsContrat.java`
- Créer (noyau) : `features/clients/IdentiteContratImpl.java`, `AnnuaireClientsContratImpl.java`
- Modifier : `features/plateforme/ContratRegistre.java` seulement si aucun moyen existant ne publie un contrat fourni par le **noyau** (lire `ContratRegistre.publier/activer` et `ContratDeTest` ; sinon ajouter `publierCoeur(Class<?>, Object)` sans génération)
- Test : `src/test/java/.../features/clients/ContratsClientsTest.java`

**Interfaces :**
- `IdentiteContrat { IdentiteCourante courante(); }` avec `record IdentiteCourante(Long userId, Set<String> roles, Long societeId, Long tenantId, String roleServeur)` (JDK seulement).
- `AnnuaireClientsContrat` : `List<ClientResume> clientsDuPartenaire(Long partenaireSocieteId)` ; `Optional<FicheClient> fiche(Long clientSocieteId)` ; `boolean autorise(Long userId, Long societeId, String droit)` ; records `ClientResume(Long societeId, String siren, String tva, String origine)` et `FicheClient(ClientResume resume, Long partenaireSocieteId, List<String> capacites)`.
- Le noyau implémente ; le plugin n'a que l'interface.

- [ ] **Étape 1 : tests en échec** : (a) `courante()` reflète l'utilisateur authentifié (utiliser `SecurityContextHolder` de test) ; hors authentification → exception explicite ; (b) `clientsDuPartenaire` ne renvoie que les clients liés à ce partenaire ; (c) `fiche` d'une société sans lien client → `Optional.empty()` ; (d) `autorise` délègue à `DroitsService`.
- [ ] **Étape 2 :** exécuter `-Dtest=ContratsClientsTest` ; ÉCHEC.
- [ ] **Étape 3 :** implémenter ; vérifier qu'aucun type de plugin n'apparaît (le registre le refuse déjà).
- [ ] **Étape 4 :** exécuter `ContratsClientsTest` et `ContratRegistreTest` ; PASSENT.
- [ ] **Étape 5 :** commit `feat(plateforme): contrats d'identité et d'annuaire clients`.

### Tâche 8 : API de navigation (tuiles filtrées par les droits)

**Fichiers :**
- Créer : `features/plateforme/NavigationController.java`, `NavigationService.java`
- Modifier : `config/SecurityConfig.java` (ajouter `/api/navigation` à la chaîne qui couvre `/api/plugins/**`, accès `authentifieHorsSelection()`)
- Test : `src/test/java/.../features/plateforme/NavigationServiceTest.java`, `NavigationControllerTest.java`

**Interfaces :**
- Consomme : `PluginManager.actifs()` (renvoie `Map<String, PluginGeneration>`) ; lire `PluginGeneration` pour obtenir le `ManifestePlugin` (ajouter un accesseur `manifeste()` s'il manque) ; `DroitsService.autorise`.
- Produit : `GET /api/navigation?zone=PARTENAIRE&societeId=<id>` → `[{plugin, libelle, icone, chemin, zone}]` triés par plugin ; `GET /api/navigation/blocs?societeId=<id>` → blocs de fiche autorisés. Un plugin en maintenance ou non actif n'apparaît pas. Une entrée dont le droit n'est pas accordé n'apparaît pas ; un utilisateur sans droit sur la société reçoit une liste vide, pas une erreur.

- [ ] **Étape 1 : tests en échec** : (a) deux plugins actifs, un utilisateur n'ayant le droit que du premier → une seule tuile ; (b) filtre par zone ; (c) plugin absent de `actifs()` → pas de tuile ; (d) `societeId` d'une autre société que celles de l'utilisateur → liste vide ; (e) contrôleur : 401 sans jeton.
- [ ] **Étape 2 :** exécuter `-Dtest=NavigationServiceTest` puis `NavigationControllerTest` ; ÉCHEC.
- [ ] **Étape 3 :** implémenter ; le service relit `actifs()` à chaque appel (jamais de cache), pour suivre les mises à jour à chaud.
- [ ] **Étape 4 :** exécuter les deux classes + `SecurityPlateformeTest` ; PASSENT.
- [ ] **Étape 5 :** commit `feat(plateforme): API de navigation par tuiles`.

### Tâche 9 : plugin « clients » — module, liste et fiche

**Fichiers :**
- Créer : `plugins-clients/pom.xml` (modèle : `plugins-sdk/pom.xml`, dépendances `provided` : SDK, spring-web, spring-security), `plugins-clients/src/main/resources/plugin.properties`, `plugins-clients/src/main/java/com/logicielpourtous/site/plugins/clients/ClientsConfiguration.java` (`@Configuration @ComponentScan`), `ClientsController.java`
- Test : `plugins-clients/src/test/java/.../ClientsControllerTest.java` (MockMvc autonome, contrats simulés)

**Interfaces :**
- Manifeste : `nom=clients`, `version=1.0.0`, `mise-a-jour=chaud`, `coeur-min=<version du noyau en cours>`, `paquet=com.logicielpourtous.site.plugins.clients`, `configuration=…ClientsConfiguration`, `entree.1=PARTENAIRE|Clients|people|/clients|clients.lire`, `entree.2=CLIENT|Mon entreprise|building|/entreprise|entreprise.lire`, `bloc.1=resume|Résumé|/blocs/resume|clients.lire`.
- `GET /api/plugins/clients/liste` → clients du partenaire courant (via `AnnuaireClientsContrat.clientsDuPartenaire(identite.societeId())`) ; `GET /api/plugins/clients/{societeId}/fiche` → fiche ; **403** si `autorise(userId, societeId, "clients.lire")` est faux ; **404** si la société n'est pas cliente de ce partenaire.

- [ ] **Étape 1 : tests en échec** : (a) liste = uniquement les clients du partenaire courant ; (b) fiche d'un client d'un autre partenaire → 404 ; (c) droit refusé → 403 ; (d) fiche d'un client sans lien → 404.
- [ ] **Étape 2 :** exécuter les tests du module ; ÉCHEC.
- [ ] **Étape 3 :** implémenter ; ne jamais renvoyer de donnée d'une société non vérifiée par le contrat.
- [ ] **Étape 4 :** exécuter les tests du module ; PASSENT. Construire le jar et vérifier que `LecteurManifeste.lire` lit les entrées (test dans `plugins-clients`).
- [ ] **Étape 5 :** commit `feat(plugin-clients): liste et fiche des clients`.

### Tâche 10 : plugin « clients » — création d'un client par le partenaire et doublon

**Fichiers :**
- Modifier : `AnnuaireClientsContrat` (SDK) : `ResultatCreation creerClient(CreationClient demande)` avec `record CreationClient(Long partenaireSocieteId, boolean particulier, String siren, String tva, boolean etrangere, String email, String nom)` et `record ResultatCreation(String issue, Long societeId)` où `issue ∈ {CREE, DEJA_CONNUE, EMAIL_DEJA_UTILISE}`. Pour un particulier (`particulier = true`, `siren` et `tva` nuls) : `RegistreSocietes.enregistrerParticulier` ; `EmailDejaUtiliseException` → `EMAIL_DEJA_UTILISE` sans aucune écriture ni demande d'accès ; l'implémentation noyau `AnnuaireClientsContratImpl.creerClient` utilise `RegistreSocietes.enregistrer`, `PartenaireReferentService.rattacher`, `ManagersService.ajouterUtilisateur` ; sur `SocieteDejaConnueException` elle enregistre la `DemandeSocieteConnue` (flux existant, voir `InscriptionSocieteService`) **sans** rattacher ni créer l'utilisateur, et renvoie `DEJA_CONNUE`.
- Modifier : `ClientsController` : `POST /api/plugins/clients` (droit `clients.creer`) ; réponse 201 (`CREE`) ou 409 avec message neutre (`DEJA_CONNUE` : « société déjà connue, une demande d'accès a été envoyée à son manager » ; `EMAIL_DEJA_UTILISE` : « cette adresse e-mail est déjà utilisée »).
- Test : `ContratsClientsTest` (noyau) et `ClientsControllerTest` (plugin) étendus

- [ ] **Étape 1 : tests en échec** : (a0) création d'un particulier : société `particulier`, lien vers le partenaire, premier utilisateur manager ; e-mail d'un utilisateur existant → `EMAIL_DEJA_UTILISE`, rien d'écrit ; (a) création : société + capacité CLIENT + lien vers le partenaire (origine `PARTENAIRE`) + premier utilisateur manager ; (b) SIREN déjà connu : aucune écriture de lien ni d'utilisateur, une demande non traitée créée ; (c) échec à mi-parcours (utilisateur invalide) : tout est annulé (société comprise) ; (d) contrôleur : 201 / 409 / 403 sans le droit.
- [ ] **Étape 2 :** exécuter les deux classes ; ÉCHEC.
- [ ] **Étape 3 :** implémenter, en une seule transaction.
- [ ] **Étape 4 :** exécuter `ContratsClientsTest`, `ClientsControllerTest`, tests d'`InscriptionSocieteService` existants ; PASSENT.
- [ ] **Étape 5 :** commit `feat(plugin-clients): création d'un client et doublon de SIREN`.

### Tâche 11 : accès du partenaire à l'espace d'un client, avec traçabilité

**Fichiers :**
- Créer : `V13__journal_acces_partenaire.sql` : `journal_acces_partenaire(id, partenaire_user_id, client_societe_id, action, cree_a)` ; `features/clients/AccesEspaceClientService.java`
- Modifier : `AnnuaireClientsContrat` : `PorteeAcces entrerEspaceClient(Long userId, Long clientSocieteId)` avec `record PorteeAcces(Long societeId, Set<String> droitsEffectifs)` ; `ClientsController` : `POST /api/plugins/clients/{societeId}/entrer`
- Test : `AccesEspaceClientServiceTest`

**Interfaces :** `droitsEffectifs` = droits du rôle manager de la société **intersectés avec le plafond** de la société. L'identité reste celle du partenaire (aucun changement de `userId`).

- [ ] **Étape 1 : tests en échec** : (a) partenaire référent : entrée autorisée, droits effectifs = manager ∩ plafond, une ligne de journal créée ; (b) partenaire non référent de ce client → `AccesRefuseException`, rien journalisé comme succès ; (c) un droit retiré du plafond disparaît de `droitsEffectifs` ; (d) l'utilisateur manager du client lui-même n'obtient pas d'élévation par ce chemin.
- [ ] **Étape 2 :** exécuter `-Dtest=AccesEspaceClientServiceTest` ; ÉCHEC.
- [ ] **Étape 3 :** implémenter ; journal en `REQUIRES_NEW` pour garder la trace même si l'action échoue ensuite.
- [ ] **Étape 4 :** exécuter la classe ; PASSE.
- [ ] **Étape 5 :** commit `feat(clients): accès du partenaire à l'espace client, journalisé`.

### Tâche 12 : accueil à tuiles et chargeur de front de plugin (noyau, Angular)

**Fichiers :**
- Créer : `frontend/intra/src/app/features/accueil/accueil.component.ts` (+ `.html`, `.scss`), `core/navigation/navigation.service.ts`, `core/plugins/plugin-host.component.ts` (charge le front d'un plugin), `core/plugins/plugin-loader.service.ts`
- Modifier : `frontend/intra/src/app/app.routes.ts` (routes `accueil` et `plugins/:plugin/**` → `PluginHostComponent`), `core/auth/home-redirect.guard.ts` (redirection par défaut vers `accueil`)
- Modifier (noyau Java) : `features/plateforme/RoutagePlugins.java` ou nouveau `PluginUiController` : `GET /api/plugins/{nom}/ui/{fichier}` sert un fichier statique du jar du plugin sous `ui/` (types `js`, `css`, `json` seulement ; refuse `..` ; en-tête `Cache-Control: public, max-age=31536000, immutable` car l'URL porte la version : `GET /api/plugins/{nom}/ui/{version}/{fichier}`)
- Test : `*.spec.ts` à côté de chaque composant/service ; `src/test/java/.../features/plateforme/PluginUiControllerTest.java`

**Interfaces :**
- `NavigationService.tuiles(zone: string, societeId?: number): Observable<Tuile[]>` (GET `/api/navigation`) ; `Tuile { plugin; libelle; icone; chemin; zone; version }` (**ajouter `version` à la réponse de la tâche 8** : version du manifeste du plugin actif).
- **Mécanisme retenu (choix du constructeur) : éléments personnalisés (Web Components).** Un plugin publie `ui/<version>/main.js` qui déclare un élément `<nom-du-plugin-app>` ; `PluginLoaderService.charger(plugin, version)` injecte une seule fois `<script type="module" src="/api/plugins/<plugin>/ui/<version>/main.js">` ; `PluginHostComponent` crée l'élément, lui passe le `chemin` courant et le jeton d'authentification via propriétés (`chemin`, `jeton`), et le recrée quand la `version` d'une tuile change (un module déjà chargé ne se décharge pas : la nouvelle version utilise un nom d'élément suffixé par la version, `<nom>-app-v<version-sans-points>`, que le plugin déclare dans son `main.js`).
- Un lien de l'espace client ne doit jamais pointer sur une route de zone PARTENAIRE (test).

- [ ] **Étape 1 : tests en échec** (Karma/Jest selon le projet : voir `package.json`) : (a) le service appelle `/api/navigation` avec la zone ; (b) l'accueil rend une carte par tuile et aucune tuile → « aucune application disponible » ; (c) `PluginLoaderService` n'injecte pas deux fois le même script ; une nouvelle version injecte un nouveau script ; (d) `PluginHostComponent` crée l'élément du nom attendu et lui transmet `chemin` ; (e) en espace client, aucun lien généré vers une route partenaire ; (f) Java : `PluginUiControllerTest` : fichier existant servi avec le bon type, `..` et extension non admise → 400/404, plugin inconnu ou version qui n'est pas celle du plugin actif → 404.
- [ ] **Étape 2 :** exécuter les specs et `-Dtest=PluginUiControllerTest` ; ÉCHEC.
- [ ] **Étape 3 :** implémenter (composants autonomes, `loadComponent`, pattern des routes existantes ; la sécurité de `/api/plugins/*/ui/**` suit celle de `/api/plugins/**`).
- [ ] **Étape 4 :** exécuter les specs, `PluginUiControllerTest`, `ng build` ; PASSENT.
- [ ] **Étape 5 :** commit `feat(frontend): accueil à tuiles et chargeur de front de plugin`.

### Tâche 13 : front du plugin « clients » livré dans son propre jar

**Fichiers :**
- Créer : `plugins-clients/frontend/` (petit projet Angular **séparé**, avec `@angular/elements`, produisant un seul `main.js`), composants `clients-liste`, `clients-fiche`, `clients-creation` ; copie du `main.js` construit vers `plugins-clients/src/main/resources/ui/<version>/main.js` par le build Maven du module
- Modifier : `plugins-clients/pom.xml` (étape de build du front, ressources dans le jar)
- Test : specs du projet `plugins-clients/frontend` ; `plugins-clients/src/test/java/.../FrontDansLeJarTest.java`

**Interfaces :** élément `clients-app-v<version-sans-points>` (propriétés `chemin`, `jeton`) ; il appelle `/api/plugins/clients/...` avec `Authorization: Bearer <jeton>` ; routes internes : `/clients` (liste), `/clients/<id>` (fiche), `/clients/nouveau` (création, avec le choix société / particulier, les messages neutres de la tâche 10). Le front du plugin ne dépend d'aucun fichier du front du noyau.

- [ ] **Étape 0 : essai chronométré (au plus une demi-journée)** : un élément personnalisé minimal construit à part, chargé par `PluginHostComponent` depuis un jar installé, affiche « bonjour ». S'il échoue pour une raison de fond (versions Angular, zone.js en double, styles), noter la cause dans le ledger et **arrêter la tâche** pour décision du propriétaire ; ne pas basculer silencieusement sur un front compilé dans le noyau.
- [ ] **Étape 1 : tests en échec** : (a) la liste affiche les clients et un état d'erreur sur 403 ; (b) la fiche affiche les blocs ; (c) la création envoie `particulier` ou SIREN/TVA selon le choix et affiche les messages neutres sur 409 ; (d) Java : le jar construit contient `ui/<version>/main.js`.
- [ ] **Étape 2 :** exécuter les specs du projet front du plugin et `FrontDansLeJarTest` ; ÉCHEC.
- [ ] **Étape 3 :** implémenter.
- [ ] **Étape 4 :** exécuter les specs, le build Maven du module ; PASSENT.
- [ ] **Étape 5 :** commit `feat(plugin-clients): front livré par le plugin`.

### Tâche 14 : installation du jar `clients` à chaud et vérification de bout en bout

**Fichiers :**
- Créer : `src/test/java/.../features/plateforme/PluginClientsIT.java`
- Modifier : `FuiteMemoireIT.java` seulement s'il doit aussi tourner avec le jar `clients` (sinon laisser)

- [ ] **Étape 1 : test en échec** : construire le jar `plugins-clients`, l'installer via `PluginManager.installer`, vérifier : `GET /api/navigation` contient les tuiles `clients` ; `GET /api/plugins/clients/liste` répond ; réinstaller une version 1.0.1 avec une entrée de tuile en plus et constater qu'elle apparaît **sans redémarrage** ; `GET /api/plugins/clients/ui/1.0.0/main.js` répond, et la version 1.0.1 expose `ui/1.0.1/main.js` alors que l'ancienne URL ne répond plus ; désinstaller et constater que les tuiles disparaissent.
- [ ] **Étape 2 :** exécuter `-Dtest=PluginClientsIT` ; ÉCHEC.
- [ ] **Étape 3 :** corriger ce que l'IT révèle (chargement des contrats du noyau dans le contexte enfant, sécurité de la route).
- [ ] **Étape 4 :** exécuter `PluginClientsIT`, `PluginManagerIT`, `FuiteMemoireIT` ; PASSENT (un test à la fois).
- [ ] **Étape 5 :** commit `test(plateforme): vérification de bout en bout du plugin clients`.

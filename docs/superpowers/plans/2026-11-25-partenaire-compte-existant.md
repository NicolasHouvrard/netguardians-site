# Partenaire créé avec un compte existant — plan d'implémentation

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans. Steps use checkbox (`- [ ]`) syntax.

**Goal:** permettre à l'administrateur du Maître de créer un partenaire en rattachant un compte existant comme premier manager (case explicite).

**Architecture:** nouveau booléen dans `CreationPartenaire` (contrat SDK, ancien constructeur conservé) ; `CreationPartenaireService.creer` réutilise le compte existant au lieu de le créer ; le plugin `partenaires` expose la case.

**Tech Stack:** Spring Boot 4.0.2, JUnit 5, Mockito, MockMvc autonome (plugin), `@SpringBootTest` (IT du noyau).

**Spec:** `docs/superpowers/specs/2026-11-25-partenaire-compte-existant-design.md`

## Global Constraints
- Ne jamais pousser, fusionner ni tagger sans demande explicite ; ne jamais toucher à Firebase ; clés privées jamais dans la session (le plugin 1.1.0 est signé par le propriétaire avec `outils/signer.ps1`).
- Dépôt PUBLIC : aucun commit/doc ne décrit une faiblesse non corrigée. Commits en français, terminés par `Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>`.
- Machine de 8 Go : UNE commande Maven lourde à la fois, UNE classe de test par exécution, `-B -o -Dskip.npm=true`, `MAVEN_OPTS=-Xmx768m`, `JAVA_HOME=C:\Users\nicolas\scoop\apps\temurin25-jdk\current`, mvn = `~/.m2/wrapper/dists/apache-maven-3.9.9/3477a4f1/bin/mvn` ; plugin : `mvn -B -o -f plugins-partenaires/pom.xml …` ; restaurer `src/main/resources/static/app` après Maven ; le plugin compile contre le SDK installé localement (`site-plugins-sdk` 0.0.1 dans `~/.m2`) : après toute modification du SDK, le réinstaller (voir la commande utilisée aux lots précédents, dossier `plugins-sdk/`) ; ITs lourds en tâche de fond avec lecture du CONTENU de sortie.
- Toute erreur de refus n'écrit RIEN en base ; texte affiché par `th:text` uniquement.

## Review Focus
1. Sans la case, une adresse connue reste refusée (`EMAIL_DEJA_UTILISE`) et rien n'est écrit.
2. Compte inactif, ou adresse « utilisée » sans compte utilisateur : refus, rien n'est écrit (aucun tenant, schéma ni société créés).
3. Après rattachement : rôles globaux, tenant d'origine, nom, mot de passe du compte inchangés ; exactement une appartenance à la nouvelle société, avec le rôle manager, dans le tenant du partenaire.
4. Compatibilité binaire : l'ancien constructeur `CreationPartenaire(String, String, String, String, String)` existe encore et donne `rattacherCompteExistant=false` ; le jar `partenaires-1.0.0` fonctionne toujours contre le nouveau noyau.
5. Appelant non administrateur du Maître : `SecurityException` inchangée, aucune lecture d'utilisateur.
6. Course/doublon : SIREN déjà connu pendant le rattachement → pas de rattachement, tout annulé.

### Task 1: Noyau et contrat

**Files:**
- Modify: `src/sdk/java/com/logicielpourtous/site/sdk/GestionPartenairesContrat.java` (record `CreationPartenaire`)
- Modify: `src/main/java/com/logicielpourtous/site/features/clients/CreationPartenaireService.java` (`creer`)
- Modify ou créer : un petit utilitaire de recherche du compte existant par e-mail normalisé (dans `PremierManagerService` ou à côté)
- Test: `CreationPartenaireServiceTest` (existant ou à créer à côté des tests de `features/clients`), `GestionPartenairesContratImplTest` si présent

**Interfaces:**
- Produces: `record CreationPartenaire(String siren, String tva, String raisonSociale, String email, String nom, boolean rattacherCompteExistant)` avec constructeur à 5 paramètres conservé (`this(…, false)`) ; issues de `ResultatPartenaire` inchangées (`CREE`, `SIREN_DEJA_CONNU`, `EMAIL_DEJA_UTILISE`).
- Consumes: `ManagersService.ajouterUtilisateur(Long userId, Long societeId, Set<String> roles)`, `UserRepository` (recherche par e-mail, état actif), `EmailsUtilises.estUtilise(String)`.

- [ ] **Step 1: Tests qui échouent** (noms) : `sans_la_case_une_adresse_connue_reste_refusee_sans_ecriture` ; `avec_la_case_et_une_adresse_connue_le_compte_est_rattache_sans_creer_de_compte` (aucun nouvel utilisateur, une appartenance manager dans le tenant du partenaire, rôles globaux/tenant/nom/mot de passe du compte inchangés) ; `avec_la_case_et_une_adresse_inconnue_un_compte_est_cree_comme_avant` ; `compte_inactif_avec_la_case_est_refuse_sans_ecriture` ; `adresse_utilisee_sans_compte_utilisateur_est_refusee_sans_ecriture` ; `l_ancien_constructeur_a_cinq_parametres_existe_et_vaut_false` (réflexion sur le record) ; `siren_deja_connu_avec_la_case_est_refuse_et_rien_n_est_ecrit`.
- [ ] **Step 2: Échec** — une classe à la fois. Expected: FAIL.
- [ ] **Step 3: Implémenter** — record + constructeur conservé ; dans `creer`, après les contrôles métier AVANT la première écriture : si l'e-mail est utilisé et `rattacherCompteExistant`, rechercher le compte (e-mail normalisé) ; compte absent ou inactif → `EMAIL_DEJA_UTILISE` ; sinon continuer (tenant, société, capacité) puis, au lieu de `premierManager.creer`, utiliser l'utilisateur trouvé pour `managers.ajouterUtilisateur(...)`. Le reste (modèle de droits, état) est inchangé. Le SIREN connu reste testé avant toute écriture.
- [ ] **Step 4: Vérifier** — classes du Step 1 puis `GestionPartenairesContratImplTest`/`CreationPartenaire*` voisines, une à la fois ; réinstaller le SDK. Expected: PASS.
- [ ] **Step 5: Commit** — `feat(partenaires): creation d'un partenaire avec un compte existant comme premier manager (noyau et contrat)`.

### Task 2: Plugin partenaires 1.1.0 (case et message)

**Files:**
- Modify: `plugins-partenaires/src/main/java/com/logicielpourtous/site/plugins/partenaires/PartenairesPagesController.java` (création partenaire : paramètre `rattacherCompteExistant`, message `EMAIL_DEJA_UTILISE`)
- Modify: `plugins-partenaires/src/main/resources/templates/plugins/partenaires/partenaire-form.html` (case)
- Modify: `plugins-partenaires/pom.xml` et `plugin.properties` (version 1.1.0)
- Test: `PartenairesPagesControllerTest`, `GabaritsPartenairesTest`, `ManifestePartenairesTest` (version 1.1.0) ; `PluginPartenairesIT` (cas du compte existant)

**Interfaces:**
- Consumes: Task 1 (`CreationPartenaire(..., boolean rattacherCompteExistant)`) et SDK réinstallé.
- Produces: jar `plugins-partenaires/target/partenaires-1.1.0.jar` ; paramètre de formulaire `rattacherCompteExistant` (case, absent = false).

- [ ] **Step 1: Tests qui échouent** — `la_case_cochee_transmet_true_au_contrat_et_decochee_false` ; `message_d_adresse_deja_utilisee_dit_de_cocher_la_case` (409, texte fixe) ; le formulaire contient la case décochée par défaut et le ré-affichage garde son état ; le manifeste est en 1.1.0 ; IT : `partenaire_cree_avec_le_compte_existant_de_l_admin` (case cochée → redirection vers la liste, une appartenance manager du compte admin à la nouvelle société, aucun nouvel utilisateur, rôles/tenant du compte inchangés) et `adresse_existante_sans_la_case_donne_409_et_rien_n_est_ecrit`.
- [ ] **Step 2: Échec** — `mvn -B -o -f plugins-partenaires/pom.xml -Dtest=… test`. Expected: FAIL.
- [ ] **Step 3: Implémenter** — paramètre `rattacherCompteExistant` (booléen) transmis au contrat ; texte fixe du 409 : « Cette adresse e-mail est déjà utilisée : cochez « Rattacher le compte existant » pour en faire le manager de ce partenaire. » ; case `th:checked` selon la saisie ; version 1.1.0 (pom et manifeste).
- [ ] **Step 4: Vérifier** — classes du plugin une à une, puis `mvn -B -o -f plugins-partenaires/pom.xml package` (jar 1.1.0), puis `PluginPartenairesIT`, `PluginClientsIT`, `PluginSocleIT` (une classe à la fois). Expected: PASS ; si un test existant dépend du nom `partenaires-1.0.0.jar`, l'aligner et le signaler.
- [ ] **Step 5: Commit** — `feat(plugin-partenaires): case « rattacher le compte existant » et version 1.1.0`.

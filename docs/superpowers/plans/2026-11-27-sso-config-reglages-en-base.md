# Réglages du compte unique en base et écran d'administration — plan d'implémentation

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans. Steps use checkbox (`- [ ]`) syntax.

**Goal:** stocker les réglages du compte unique en base (priorité sur l'environnement), les régler dans `/admin/sso-config`, et générer le secret du client et son empreinte Authelia.

**Architecture:** un contrat `ConfigurationCompteUnique` (module `commun`) implémenté par `auth` (base d'abord, environnement ensuite) ; les six lecteurs actuels de l'environnement passent par lui ; l'écran d'administration écrit en base.

**Tech Stack:** Spring Boot 4.0.2, JPA + Flyway (V20), Thymeleaf, `MailEncryptionConverter` pour le chiffrement, JUnit 5, MockMvc / `@SpringBootTest`.

**Spec:** `docs/superpowers/specs/2026-11-27-sso-config-reglages-en-base-design.md`

## Global Constraints
- Ne jamais pousser, fusionner ni tagger sans demande explicite ; jamais d'installation sur un serveur par l'assistant ; jamais toucher à Firebase ; aucun secret, aucune vraie adresse privée dans le dépôt, les tests, les journaux ; ne jamais afficher un mot de passe.
- Dépôt PUBLIC : aucune doc ni commit ne décrit une faiblesse non corrigée. Commits en français, terminés par `Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>`.
- INTERDIT d'arrêter des processus `java.exe` ou toute autre commande de masse sur les processus de la machine. Machine de 8 Go : UNE commande Maven à la fois, UNE classe de test par exécution, `-B -o -Dskip.npm=true`, `MAVEN_OPTS=-Xmx768m`, `JAVA_HOME=C:\Users\nicolas\scoop\apps\temurin25-jdk\current`, mvn = `~/.m2/wrapper/dists/apache-maven-3.9.9/3477a4f1/bin/mvn` ; lancer Maven en tâche de fond et LIRE le contenu de la sortie (`Tests run:`, `BUILD`) : le code de sortie du shell ne prouve rien ; restaurer `src/main/resources/static/app` après Maven ; PostgreSQL local : `pg_ctl start -D C:\Users\nicolas\scoop\persist\postgresql\data`, base `site_test_federation`.
- Sans aucun réglage (base vide ET environnement vide) : AUCUN changement de comportement. La page `/login/secours` reste intacte. `ModularityTests` doit rester vert.
- Migration : nouveau fichier `V20__reglages_compte_unique.sql` (V19 est la dernière).
- Texte affiché : `th:text` uniquement ; le secret n'est jamais rendu, journalisé, ni renvoyé.

## Review Focus
1. **Secret :** jamais dans un journal, un message d'erreur, une réponse HTTP, un gabarit, un `toString`, une exception ; chiffré en base ; écriture seule (le formulaire ne le contient jamais).
2. **Priorité :** la base prime sur l'environnement ; base vide et environnement renseigné = comportement actuel ; base vide et environnement vide = non configuré (bouton absent, `/login` inchangé, aucun appel réseau).
3. **Application à chaud :** un enregistrement modifie le comportement sans redémarrage (registre, décodeur de jetons, déconnexion) ; une valeur invalide est refusée avant écriture.
4. **Accès :** seul ROLE_ADMIN ; CSRF sur tous les POST ; un serveur annexe ne modifie que ses propres réglages.
5. **Bouton de test :** https seulement, délais courts, aucune redirection suivie hors https, jamais le corps de la réponse renvoyé ; ne sert pas à sonder des adresses internes (refuse les adresses locales/privées si c'est simple, sinon le documenter).
6. **Empreinte :** format `$pbkdf2-sha512$310000$<sel>$<hash>` (encodage Authelia), sel aléatoire, comparaison de l'empreinte recalculée avec l'implémentation PBKDF2 standard du JDK ; documenter la vérification par la commande d'Authelia.
7. **Verrouillage :** impossible d'enregistrer une configuration qui rend `/login` inutilisable sans issue (la page de secours reste) ; message clair en cas de panne d'Authelia.

---

### Task 1: Stockage, contrat et résolution base/environnement

**Files:**
- Create: `src/main/resources/db/migration/V20__reglages_compte_unique.sql`
- Create: `src/main/java/com/logicielpourtous/site/features/commun/ConfigurationCompteUnique.java` (contrat)
- Create: `src/main/java/com/logicielpourtous/site/features/auth/ReglagesCompteUnique.java` (entité), `ReglagesCompteUniqueRepository.java`, `ConfigurationCompteUniqueImpl.java`
- Test: `ConfigurationCompteUniqueImplTest`, test JPA/Flyway de la migration (`@SpringBootTest` ou test existant de schéma)

**Interfaces:**
- Produces: `interface ConfigurationCompteUnique { boolean configure(); String issuerUri(); String clientId(); String clientSecret(); String urlPublique(); String retourDeconnexion(); String maitreUrlPublique(); long version(); }` (valeurs « » si absentes ; `retourDeconnexion()` = valeur explicite, sinon `urlPublique` + `/login?logout` si `urlPublique` valide, sinon « » ; `configure()` = émetteur, identifiant et secret tous renseignés) ; `ReglagesCompteUnique` (ligne unique) ; méthode d'enregistrement `enregistrer(ReglagesModifies, String auteur)` qui valide, chiffre le secret, incrémente `version()`.
- Consumes: variables d'environnement actuelles, avec les mêmes noms (`spring.security.oauth2.client.registration.authelia.client-id`, `…client-secret`, `spring.security.oauth2.client.provider.authelia.issuer-uri`, `app.auth.authelia.maitre-url-publique`, `app.auth.authelia.retour-deconnexion` si présente sur la branche), `MailEncryptionConverter` (ou le mécanisme de chiffrement équivalent).

- [ ] **Step 1: Tests qui échouent** : `la_base_prime_sur_l_environnement` ; `base_vide_et_environnement_renseigne_donne_les_valeurs_de_l_environnement` ; `base_vide_et_environnement_vide_n_est_pas_configure` ; `le_secret_est_chiffre_en_base_et_dechiffre_a_la_lecture` ; `retour_deconnexion_est_deduit_de_l_adresse_publique` ; `un_retour_explicite_valide_prime_sur_la_deduction` ; `une_adresse_http_est_refusee_a_l_enregistrement` ; `une_adresse_hors_domaine_parent_du_portail_est_refusee` ; `la_version_change_apres_un_enregistrement` ; `le_secret_n_apparait_dans_aucun_toString_ni_message_d_erreur`.
- [ ] **Step 2: Échec** (une classe à la fois). Expected: FAIL.
- [ ] **Step 3: Implémenter** migration, entité, dépôt, contrat, implémentation (priorité base > environnement, mise en cache avec invalidation par `version()`).
- [ ] **Step 4: Vérifier** : la classe de test, `ModularityTests`. Expected: PASS.
- [ ] **Step 5: Commit** — `feat(sso): reglages du compte unique en base (contrat, stockage chiffre, priorite sur l'environnement)`.

### Task 2: Les lecteurs passent par le contrat (application à chaud)

**Files:**
- Modify: `SocialOAuth2Config.java` (`autheliaConfigure`, `AutheliaRegistration`), `AuthController.java` (bouton, lien « mot de passe oublié »), `DeconnexionController.java`, `AutheliaLogoutSuccessHandler.java`, `SecurityConfig.java` (~l.395), `AiguillageService.java`, `ValidateurJetonAuthelia.java`
- Test: tests existants de ces classes (à adapter, pas à affaiblir), nouveaux cas d'application à chaud

**Interfaces:**
- Consumes: Task 1 (`ConfigurationCompteUnique`).
- Produces: aucun nouveau contrat ; comportement identique avec des valeurs venant de la base ; reconstruction paresseuse du registre et du décodeur quand `version()` change.

- [ ] **Step 1: Tests qui échouent** : `le_registre_authelia_se_reconstruit_apres_un_changement_de_reglages` ; `le_bouton_compte_unique_suit_les_reglages_en_base` ; `le_lien_mot_de_passe_oublie_suit_l_emetteur_en_base` ; `la_deconnexion_utilise_le_retour_deduit_de_l_adresse_publique` ; `le_validateur_de_jetons_se_reconstruit_si_l_emetteur_change` ; `l_aiguillage_utilise_l_adresse_publique_du_maitre_en_base` ; `sans_reglages_le_comportement_est_inchange` (les tests existants de la branche passent sans modification d'assertion).
- [ ] **Step 2: Échec** (une classe à la fois). Expected: FAIL.
- [ ] **Step 3: Implémenter** : remplacer chaque lecture d'environnement par le contrat ; aucune découverte réseau au chargement de `/login` ni à l'enregistrement.
- [ ] **Step 4: Vérifier**, une classe à la fois : classes modifiées, `SocialOAuth2ConfigAutheliaTest`, `AutheliaAuthorizationRequestResolverTest`, `ConnexionAutheliaBoutonTest`, `ConnexionAutheliaInjoignableTest`, `ConnexionParDefautAutheliaTest`, `DeconnexionAutheliaWebTest`, `AutheliaLogoutSuccessHandlerTest`, `AiguillageControllerTest` (licence), `ValidateurJetonAutheliaTest`, `SocleSecuriteWebTest`, `ModularityTests`. Expected: PASS.
- [ ] **Step 5: Commit** — `refactor(sso): le compte unique lit ses reglages dans le contrat (base d'abord, environnement ensuite)`.

### Task 3: Écran d'administration, test de connexion et génération du secret

**Files:**
- Modify: `src/main/java/com/logicielpourtous/site/features/sso/SsoStatusController.java` (ou nouveau contrôleur `SsoReglagesController`), `src/main/resources/templates/admin/sso-config.html`
- Create: `EmpreinteAuthelia.java` (génération du secret et de l'empreinte PBKDF2-SHA512), `TestConnexionAuthelia.java` (vérification de l'émetteur)
- Test: `EmpreinteAutheliaTest`, `SsoReglagesControllerTest`, `TestConnexionAutheliaTest`

**Interfaces:**
- Consumes: Task 1 (`enregistrer`, contrat), Task 2 (consommateurs).
- Produces: `EmpreinteAuthelia.generer() -> ClientGenere(String secretClair, String empreinte)` ; routes `GET /admin/sso-config` (affiche l'état et le formulaire), `POST /admin/sso-config/reglages`, `POST /admin/sso-config/tester`, `POST /admin/sso-config/generer-secret` (affiche UNE fois le bloc à coller dans Authelia, jamais le secret clair).

- [ ] **Step 1: Tests qui échouent** : `l_empreinte_a_le_format_pbkdf2_sha512_310000_sel_hash` ; `l_empreinte_se_verifie_avec_le_secret_par_l_implementation_standard` ; `deux_generations_donnent_des_secrets_et_des_sels_differents` ; `le_secret_clair_n_est_jamais_dans_la_reponse_ni_le_journal` ; `le_bloc_authelia_contient_client_id_empreinte_et_redirect_uri_calculee` ; `le_formulaire_refuse_http_et_les_domaines_hors_portail` ; `le_formulaire_n_affiche_jamais_le_secret_enregistre` ; `seul_un_administrateur_accede_a_l_ecran_et_le_csrf_est_exige` ; `un_secret_vide_dans_le_formulaire_conserve_le_secret_existant` ; `le_test_de_connexion_refuse_http_et_borne_les_delais` ; `une_modification_est_journalisee_sans_valeur_secrete`.
- [ ] **Step 2: Échec** (une classe à la fois). Expected: FAIL.
- [ ] **Step 3: Implémenter** l'écran, la génération (octets aléatoires sûrs, alphabet RFC 3986), le contrôle de l'émetteur et la journalisation.
- [ ] **Step 4: Vérifier**, une classe à la fois : les trois nouvelles classes, `SocleSecuriteWebTest`, `ModularityTests`, et un test de rendu de la page. Expected: PASS.
- [ ] **Step 5: Commit** — `feat(sso): ecran de reglages du compte unique, test de connexion et generation du secret client`.

### Task 4: Documentation

**Files:**
- Modify: `docs/connexion-authelia.md`, `docs/exemples/authelia/proprietes-site.exemple.md`, `src/main/resources/application.properties.example`

- [ ] **Step 1: Rédiger** : les réglages se font dans `/admin/sso-config` (les variables d'environnement restent des valeurs par défaut) ; la génération du secret et du bloc pour Authelia ; la commande de vérification de l'empreinte (syntaxe à vérifier avec la version d'Authelia) ; ce qu'il faut faire si une valeur erronée empêche la connexion (page `/login/secours`) ; l'étape 2 annoncée (distribution depuis le Maître).
- [ ] **Step 2: Relire** : cohérence avec le code livré (noms exacts, chemins), aucun secret, aucune faiblesse non corrigée décrite.
- [ ] **Step 3: Commit** — `docs(sso): reglages du compte unique dans l'ecran d'administration`.

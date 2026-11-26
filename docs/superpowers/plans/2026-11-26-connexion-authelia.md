# Connexion unique par Authelia et LDAP (projet A) — plan d'implémentation

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans. Steps use checkbox (`- [ ]`) syntax.

**Goal:** permettre la connexion par Authelia (OpenID Connect, LDAP du NAS) sur tous les serveurs du site, avec aiguillage vers le serveur de la société de l'utilisateur ; livrer la documentation et les exemples d'infrastructure.

**Architecture:** le client OpenID Connect existant (`oauth2Login`) reçoit un fournisseur `authelia` piloté par propriétés ; le rattachement reste « compte local EXISTANT par e-mail vérifié » ; le Maître expose `POST /api/licence/aiguillage` qui valide le jeton d'identité d'Authelia et répond la liste (société, adresse publique du serveur d'accueil) ; un serveur sans le compte local appelle le Maître et redirige ou propose un choix.

**Tech Stack:** Spring Boot 4.0.2, Spring Security oauth2-client + oauth2-jose (Nimbus), Flyway (V19), Thymeleaf, JUnit 5, `@SpringBootTest` / MockMvc, JWKS de test généré en mémoire.

**Spec:** `docs/superpowers/specs/2026-11-26-connexion-authelia-design.md`

## Global Constraints
- Ne jamais pousser, fusionner ni tagger sans demande explicite ; jamais d'installation sur le VPS ni le NAS par l'assistant ; jamais toucher à Firebase ; aucun secret (client_secret, clés) ni mot de passe dans le dépôt, les tests ou les logs ; ne jamais afficher un mot de passe de base de données.
- Dépôt PUBLIC : aucune doc ni commit ne décrit une faiblesse non corrigée.
- Commits en français, terminés par `Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>`.
- Machine de 8 Go : UNE commande Maven lourde à la fois, UNE classe de test par exécution, `-B -o -Dskip.npm=true`, `MAVEN_OPTS=-Xmx768m`, `JAVA_HOME=C:\Users\nicolas\scoop\apps\temurin25-jdk\current`, mvn = `~/.m2/wrapper/dists/apache-maven-3.9.9/3477a4f1/bin/mvn` ; restaurer `src/main/resources/static/app` après Maven ; ITs lourds en tâche de fond avec lecture du CONTENU de sortie ; PostgreSQL local : `pg_ctl start -D C:\Users\nicolas\scoop\persist\postgresql\data`, base `site_test_federation`.
- Sans configuration Authelia (propriétés absentes), AUCUN changement de comportement : la connexion locale et Google/Microsoft restent identiques.
- Aucun compte n'est jamais créé à partir d'une identité externe ; refus générique (même message) pour e-mail absent, non vérifié, compte inconnu ou inactif.
- Une migration de base = nouveau fichier Flyway `V19__…` (V18 est le dernier).

## Review Focus
1. **Prise de compte par e-mail :** `email_verified` absent ou faux, e-mail différent par la casse, deux comptes dont l'e-mail ne diffère que par la casse : refus, jamais un compte au hasard.
2. **Aiguillage :** jeton d'identité invalide (signature, émetteur, destinataire d'un AUTRE serveur, expiré, `email_verified` faux, `nonce`/`alg none`) → réponse vide identique ; clé de licence révoquée → 401 ; adresse de redirection toujours issue de `LicenceServeur.urlPublique` (jamais de la requête) ; serveur révoqué ou sans adresse omis.
3. **Pas d'énumération :** compte inconnu, inactif ou sans société accessible donne la même réponse vide ; pas de différence de statut ni de contenu.
4. **Redirection ouverte :** aucune adresse de redirection n'est lue dans un paramètre ; le choix d'un serveur se fait par identifiant, validé contre la liste renvoyée par le Maître.
5. **Administrateur non enfermé :** le formulaire de connexion local reste disponible ; Authelia indisponible ne bloque pas la page de connexion locale.
6. **Aucun secret journalisé :** jetons, secrets de client et clés de licence absents des logs et des messages d'erreur.

---

### Task 1: Client OpenID Connect « authelia » dans le site

**Files:**
- Modify: `src/main/java/com/logicielpourtous/site/features/auth/OidcSocialLoginUserService.java`
- Modify: `src/main/java/com/logicielpourtous/site/features/auth/AuthController.java` (attribut de modèle `autheliaEnabled`, comme `googleEnabled` ~l.291)
- Modify: `src/main/resources/templates/feature/auth/login.html` (bouton, comme Google ~l.68)
- Modify: `src/main/resources/application.properties` (exemple commenté des propriétés `spring.security.oauth2.client.registration.authelia.*` / `provider.authelia.issuer-uri`, valeurs vides par défaut via variables d'environnement)
- Test: `OidcSocialLoginUserServiceTest` (existant ou à créer à côté de `features/auth`), `AuthControllerTest`/gabarit de connexion si présent

**Interfaces:**
- Produces: enregistrement Spring `authelia` actif seulement si ses propriétés sont renseignées ; `OidcSocialLoginUserService.loadUser` : refuse (`OAuth2AuthenticationException`, message générique « Connexion impossible avec ce compte. ») si e-mail absent, `email_verified` ≠ true, compte inconnu, inactif ou ambigu (deux comptes dont l'e-mail diffère par la casse) ; sinon `CustomOidcUser(oidcUser, user, emailEnMinuscules)` comme aujourd'hui.
- Consumes: `UserRepository` (recherche par e-mail sans casse ; si le dépôt n'a qu'une recherche insensible à la casse unique, ajouter une variante qui renvoie une liste pour détecter l'ambiguïté).

- [ ] **Step 1: Tests qui échouent** : `refuse_si_email_non_verifie` ; `refuse_si_email_absent` ; `rattache_par_email_sans_casse_au_compte_existant` ; `refuse_et_ne_cree_rien_pour_un_compte_inconnu` ; `refuse_un_compte_inactif` ; `refuse_si_deux_comptes_ne_different_que_par_la_casse` ; `le_message_d_erreur_est_identique_dans_tous_les_refus_et_ne_nomme_pas_google` ; `le_bouton_authelia_n_apparait_que_si_le_fournisseur_est_configure`.
- [ ] **Step 2: Échec** (une classe à la fois). Expected: FAIL.
- [ ] **Step 3: Implémenter** : la vérification `email_verified` s'applique à tous les fournisseurs OpenID Connect ; messages génériques ; attribut `autheliaEnabled` via la même méthode que Google (`isProviderConfigured("authelia")`) ; bouton « Se connecter avec le compte unique » vers `/oauth2/authorization/authelia`.
- [ ] **Step 4: Vérifier** : `OidcSocialLoginUserServiceTest`, la classe de test de connexion, `SocleSecuriteWebTest`. Expected: PASS.
- [ ] **Step 5: Commit** — `feat(auth): connexion par le compte unique (fournisseur OpenID Connect authelia), refus generiques`.

### Task 2: Aiguillage côté Maître (`POST /api/licence/aiguillage`)

**Files:**
- Create: `src/main/resources/db/migration/V19__licence_serveur_oidc_client.sql` (colonne `oidc_client_id` nullable, unique quand renseignée, sur la table des serveurs de licence)
- Modify: `LicenceServeur.java` (champ `oidcClientId`), `FederationAdminController.java` + `templates/feature/licence/federation.html` (champ pour le renseigner, validé non vide/longueur/caractères), `SecurityConfig.java` (route `POST /api/licence/aiguillage` réservée à `ROLE_LICENCE_SECONDARY`)
- Create: `src/main/java/com/logicielpourtous/site/features/licence/AiguillageService.java`, `AiguillageController.java`, `ValidateurJetonAuthelia.java` (Nimbus `JwtDecoder` construit depuis l'émetteur/JWKS configurés)
- Modify: `src/main/resources/application.properties` (`app.auth.authelia.issuer-uri`, `app.auth.authelia.maitre-url-publique`, vides par défaut)
- Test: `AiguillageServiceTest`, `AiguillageControllerTest`, `ValidateurJetonAutheliaTest` (JWKS de test en mémoire)

**Interfaces:**
- Produces: requête `{ "idToken": "<JWT>" }` + en-tête `X-Licence-Api-Key` ; réponse `200 { "serveurs": [ { "societeId": Long|null, "nomSociete": String, "urlPublique": String } ] }` (liste éventuellement vide, JAMAIS d'autre information) ; `AiguillageService.aiguiller(Long licenceServeurId, String idToken) -> List<Destination(societeId, nomSociete, urlPublique)>` ; `ValidateurJetonAuthelia.valider(String idToken, String clientIdAttendu) -> Optional<String>` (e-mail en minuscules si signature, émetteur, destinataire = `clientIdAttendu`, validité et `email_verified=true` sont corrects ; vide sinon).
- Consumes: `LicenceServeurRepository`, `Tenant.serveurLicenceId`, `LicenceServeur.urlPublique`, `Appartenance` (tenant, société) du compte trouvé par e-mail ; l'adresse du Maître vient de la propriété `maitre-url-publique` pour les tenants sans serveur d'accueil.

- [ ] **Step 1: Tests qui échouent** : validateur : `jeton_valide_donne_l_email`, `signature_invalide`, `emetteur_inconnu`, `destinataire_d_un_autre_serveur`, `expire`, `email_non_verifie`, `algorithme_none_refuse` ; service : `liste_les_societes_hors_serveur_appelant`, `un_tenant_sans_serveur_d_accueil_pointe_vers_le_maitre`, `serveur_revoque_ou_sans_adresse_est_omis`, `compte_inconnu_inactif_ou_sans_societe_donne_une_liste_vide`, `l_adresse_vient_uniquement_de_la_base` ; contrôleur : `cle_licence_absente_ou_revoquee_donne_401`, `jeton_invalide_donne_200_et_liste_vide`, `aucune_donnee_dans_la_reponse_hors_societe_nom_et_adresse`, `un_serveur_sans_oidc_client_id_est_refuse_comme_jeton_invalide`.
- [ ] **Step 2: Échec** (une classe à la fois). Expected: FAIL.
- [ ] **Step 3: Implémenter** conformément à la conception (décisions complémentaires 2 et 3) ; le serveur appelant est identifié par la clé de licence (principal = id du `LicenceServeur`) et son `oidcClientId` sert de destinataire attendu ; le JWKS est lu depuis l'émetteur configuré (adresse de découverte), avec mise en cache courte. Migration `V19` sans effet de bord sur les données existantes.
- [ ] **Step 4: Vérifier** : les trois classes, puis `FederationAdminControllerTest`/tests voisins, `SocleSecuriteWebTest`, un test Flyway/démarrage (`ModularityTests`). Expected: PASS.
- [ ] **Step 5: Commit** — `feat(licence): aiguillage des utilisateurs authentifies vers le serveur de leur societe (Maitre)`.

### Task 3: Aiguillage côté serveur secondaire (compte absent localement)

**Files:**
- Modify: `OidcSocialLoginUserService.java` (cas « compte inconnu » sur un serveur secondaire : conserver le jeton d'identité en session et lever une exception dédiée `AiguillageRequis`), `SecurityConfig.java` (gestionnaire d'échec OpenID Connect qui redirige vers `/connexion/aiguillage` dans ce cas), `LicenceClient.java` (méthode d'appel de `POST /api/licence/aiguillage`)
- Create: `src/main/java/com/logicielpourtous/site/features/auth/AiguillageController.java`, `templates/feature/auth/choisir-serveur.html`
- Test: `AiguillageControllerTest`, `OidcAiguillageTest`

**Interfaces:**
- Consumes: Task 1 (fournisseur `authelia`), Task 2 (`POST /api/licence/aiguillage`, même contrat), `LicenceClient` (clé de licence, adresse du Maître).
- Produces: `GET /connexion/aiguillage` : lit le jeton de la session (une seule fois : il est retiré), appelle le Maître ; liste de 1 → redirection `302` vers `urlPublique + "/oauth2/authorization/authelia"` ; liste de plusieurs → page `choisir-serveur` (POST `/connexion/aiguillage` avec l'index validé contre la liste conservée en session, jeton CSRF) ; liste vide ou Maître injoignable → retour `/login` avec un message neutre. Sur le Maître lui-même (`app.role=MASTER`), le cas « compte inconnu » reste un refus générique (pas d'aiguillage).

- [ ] **Step 1: Tests qui échouent** : `compte_inconnu_sur_un_secondaire_conserve_le_jeton_et_redirige_vers_l_aiguillage` ; `un_seul_serveur_redirige_vers_son_adresse_publique_connexion_authelia` ; `plusieurs_serveurs_affichent_le_choix` ; `le_choix_est_valide_contre_la_liste_pas_contre_une_adresse_fournie` ; `un_index_hors_liste_est_refuse` ; `liste_vide_ou_maitre_injoignable_renvoie_au_login_avec_message_neutre` ; `le_jeton_est_retire_de_la_session_apres_usage` ; `le_maitre_ne_s_aiguille_pas_lui_meme` ; `aucune_adresse_de_redirection_n_est_lue_depuis_les_parametres`.
- [ ] **Step 2: Échec** (une classe à la fois). Expected: FAIL.
- [ ] **Step 3: Implémenter** conformément à la conception.
- [ ] **Step 4: Vérifier** : les deux classes, `OidcSocialLoginUserServiceTest`, `SocleSecuriteWebTest`, `AccueilControllerTest`. Expected: PASS.
- [ ] **Step 5: Commit** — `feat(auth): aiguillage vers le serveur de l'utilisateur apres connexion par le compte unique`.

### Task 4: Documentation et exemples d'infrastructure

**Files:**
- Create: `docs/connexion-authelia.md` (procédure complète), `docs/exemples/authelia/compose.exemple.yml`, `docs/exemples/authelia/configuration.exemple.yml`, `docs/exemples/authelia/nginx-auth.exemple.conf`, `docs/exemples/authelia/proprietes-site.exemple.md`
- Test: aucun test Java ; contrôle par relecture, aucune valeur secrète ni vraie adresse privée

**Interfaces:**
- Consumes: noms de propriétés des tâches 1 à 3 (relire le code livré pour les citer exactement).
- Produces: procédure en français pour le propriétaire : (1) LDAP Synology : activer le paquet, créer l'unité d'organisation et les utilisateurs (e-mail obligatoire et unique, droits d'écriture réservés à l'administrateur), accès limité au réseau privé ; (2) Authelia sur le VPS : conteneur, fichier de configuration (version 4.39 ou plus), source LDAP, politique de double facteur pour les administrateurs, un client OpenID Connect par serveur (identifiant, secret généré et haché, adresses de retour `https://<serveur>/login/oauth2/code/authelia`, consentement préconfiguré) ; (3) Nginx du VPS : hôte `auth.logicielpourtous.com`, certificat, en-têtes ; (4) propriétés du site par serveur (variables d'environnement) et `oidcClientId` renseigné dans la page « Fédération » ; (5) compte administrateur local de secours (création, mot de passe fort, rôle, quand l'utiliser) ; (6) migration des comptes (principe : export sans mot de passe, réinitialisation par e-mail) ; (7) retour arrière : retirer les propriétés Authelia rétablit la connexion locale seule.

- [ ] **Step 1: Rédiger** les cinq fichiers à partir du code livré ; valeurs d'exemple factices (`CHANGER_MOI`), aucune adresse réelle sauf `auth.logicielpourtous.com`.
- [ ] **Step 2: Relire** : aucune faiblesse non corrigée décrite ; chaque propriété citée existe dans le code ; la procédure fonctionne dans l'ordre.
- [ ] **Step 3: Commit** — `docs(auth): procedure et exemples pour la connexion par Authelia et le LDAP`.

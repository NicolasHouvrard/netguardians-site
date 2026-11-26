# Connexion unique par Authelia et LDAP, avec aiguillage vers le serveur de l'utilisateur (projet A)

Date : 2026-11-26. Remplace, pour la partie « annuaire et OTP », le « second temps » de `2026-11-07-connexion-deleguee-design.md`.
Projets suivants, à concevoir séparément : **B** déménagement d'un tenant entre serveurs ; **C** création d'un partenaire directement chez le
bon serveur. Ce document ne couvre que **A**.

## Intention
Un utilisateur a UN identifiant et UN mot de passe pour tous les serveurs (Maître/NAS, VPS, serveurs de partenaires et de clients). Il se connecte par
le VPS, point d'entrée public, puis arrive sur le serveur de sa société, sans retaper son mot de passe. Les mots de passe ne passent plus dans le code
du site : ils sont saisis sur un portail d'authentification dédié (Authelia), adossé au LDAP du NAS.

## Décisions du propriétaire (reprises telles quelles)
1. Le LDAP du NAS n'est joignable que par le réseau privé Tailscale/Headscale.
2. Chaque partenaire/client a son propre tenant, déménageable entre serveurs (projet B, hors de ce document).
3. Connexion par le VPS, puis redirection vers le serveur de l'utilisateur.
4. **Approche 1 retenue : Authelia comme fournisseur OpenID Connect sur le VPS**, LDAP du NAS en arrière-plan ; chaque serveur du site est un client.
5. **Le rattachement tenant → serveur reste dans la base du Maître** (page « Fédération » existante : `Tenant.serveurLicenceId`, `LicenceServeur.urlPublique`). Le
   LDAP ne contient PAS le serveur d'accueil : une personne peut avoir plusieurs sociétés (donc plusieurs serveurs) et le déménagement ne doit modifier qu'une ligne.
6. « On bascule tous les comptes sur le LDAP ensuite » : migration progressive, après la mise en place.

## Buts, non-buts, anti-buts
- **Buts** : mot de passe saisi une seule fois, ailleurs que dans le site ; ouverture de session partagée (SSO) entre serveurs ; double facteur disponible ;
  aiguillage automatique vers le serveur de la société choisie ; aucune création de compte à la volée.
- **Non-buts** : déménagement des données d'un tenant (projet B) ; création de partenaire chez un serveur donné (projet C) ; refonte des anciennes
  mises en page ; connexion mobile/API JWT (inchangée) ; connexion Angular existante (inchangée).
- **Anti-buts** : un serveur qui crée un compte local à partir d'une identité externe (on ne rattache qu'à un compte existant, comme l'actuelle connexion
  Google) ; un mot de passe stocké ou journalisé par le site ; une réponse qui permet de savoir si un e-mail existe ; l'administrateur du Maître enfermé
  hors de l'administration par un changement de connexion.

## Contraintes existantes (vérifiées dans le code)
- `SecurityConfig` a déjà `.oauth2Login(...)` avec `SocialLoginUserService` / `OidcSocialLoginUserService` : l'e-mail de l'identité est cherché dans `users`
  (`findByEmail`), refus si inconnu ou compte non activé ; aucune création de compte. Le texte d'erreur parle de Google et la vérification
  `email_verified` n'existe pas. Le starter `spring-boot-starter-security-oauth2-client` est présent.
- La connexion locale (formulaire, mot de passe haché, `CustomTenantSuccessHandler`, choix de société, sélecteur d'appartenance) reste le chemin de repli
  et celui des administrateurs locaux.
- Les routes `/api/licence/**` sont protégées par la clé API de licence du serveur appelant ; les serveurs se parlent par le réseau privé.
- Un compte peut avoir plusieurs appartenances (tenant, société, rôles) ; la société choisie détermine le tenant effectif.

## Conception

### 1. Infrastructure (hors code du site, documentée dans le dépôt)
- LDAP du NAS (paquet Synology) : utilisateurs avec identifiant, e-mail, nom ; accessible seulement depuis le réseau privé.
- Authelia sur le VPS (conteneur), joint au LDAP par le réseau privé, exposé sur un nom public dédié (ex. `auth.<domaine>`) derrière le Nginx du VPS ;
  fournisseur OpenID Connect avec UN client par serveur du site (identifiant et secret propres à chaque serveur, adresses de retour fixes).
- Double facteur (TOTP) activable par politique Authelia.
- Livrable dans le dépôt : exemples de configuration (compose, configuration Authelia, vhost Nginx), sans secret, et une procédure d'installation.

### 2. Client OpenID Connect dans le site (tous les serveurs)
- Enregistrement `authelia` piloté par des propriétés (absent si non configuré : aucun changement de comportement) ; adresse de l'émetteur, identifiant
  et secret par serveur.
- Bouton « Se connecter avec le compte unique » sur la page de connexion ; le formulaire local reste (administrateurs locaux, repli si Authelia est indisponible).
- Rattachement par e-mail à un compte local EXISTANT uniquement, comme aujourd'hui ; messages d'erreur génériques (ne nomment plus Google) ; refus si l'identité
  ne porte pas un e-mail vérifié (`email_verified`) ; comparaison sans casse ; refus si le compte est inactif.
- Après rattachement, suite inchangée : choix de société si plusieurs appartenances, destination `/accueil`.

### 3. Aiguillage vers le serveur de l'utilisateur
- Cas « le compte existe sur ce serveur » : connexion locale comme au point 2.
- Cas « le compte n'existe pas ici » (typiquement le VPS pour un utilisateur qui habite ailleurs) : après authentification par Authelia, le serveur
  demande au Maître, par le réseau privé et avec sa clé API de licence, où habite cette identité : `POST /api/licence/aiguillage` avec l'e-mail
  authentifié ; réponse = liste de couples (société, adresse publique du serveur d'accueil) pour les appartenances de ce compte, sans rien d'autre.
  - un seul serveur : redirection vers son adresse publique ; une session Authelia existe déjà, donc le serveur cible ouvre la session sans mot de passe ;
  - plusieurs serveurs : page de choix listant les sociétés ; liste vide : message neutre « aucun accès » (l'identité est déjà authentifiée, il n'y a donc pas de
    sondage d'adresses possible par un tiers).
- Le Maître ne répond à `/api/licence/aiguillage` qu'à un serveur de licence actif authentifié par sa clé ; réponse identique en cas d'inconnu ou de compte inactif.
- Les adresses de redirection sont celles enregistrées dans `LicenceServeur.urlPublique` (jamais une valeur fournie par la requête).

### 4. Migration des comptes (phase ultérieure, mais prévue)
- Outil d'export des comptes vers le LDAP (identifiant = e-mail), sans copier les mots de passe hachés ; chaque personne définit son mot de passe par un
  lien de réinitialisation envoyé par e-mail. Les administrateurs gardent leur compte local tant que le double facteur n'est pas en place.

## Approches considérées
1. **Authelia fournisseur OpenID Connect (retenue)** : mots de passe hors du site, SSO natif, double facteur, client déjà supporté par le code.
2. Connexion maison par le VPS (LDAP direct + jeton signé) : écartée, le site manipulerait les mots de passe et le double facteur serait à écrire.
3. Authelia seulement au niveau de Nginx : écartée, ne connaît ni tenants ni rôles.

## Risques à relire
- Le VPS reste le point exposé : durcissement d'Authelia (sauvegarde de la configuration, secrets, limitation d'essais) et du portail.
- Rattachement par e-mail : l'e-mail du LDAP fait foi ; qui peut modifier l'e-mail d'un utilisateur dans le LDAP peut prendre le compte correspondant. Les écritures LDAP
  sont donc réservées à l'administration, et l'e-mail ne doit pas être modifiable par l'utilisateur.
- Disponibilité : si Authelia ou le LDAP est injoignable, seuls les comptes locaux peuvent se connecter (message clair, jamais de connexion sans authentification).
- Un administrateur du Maître ne doit pas être enfermé dehors : son compte local et son appartenance restent.

## Ce qui est laissé au constructeur
Noms des propriétés, structure des classes et des tests, format exact de la page de choix de serveur, détails de la configuration Authelia (selon la version retenue).

## Questions à confirmer par le propriétaire avant le plan
- Le paquet LDAP du NAS (Synology Directory Server / LDAP Server) est-il déjà installé, avec quels utilisateurs ?
- Nom public du portail (ex. `auth.logicielpourtous.com`) et accord pour installer Authelia sur le VPS (hors du dépôt, à faire avec le propriétaire).
- Les administrateurs restent-ils en compte local tant que le double facteur n'est pas en place (proposé), ou passent-ils aussi au LDAP dans la première version ?

## Décisions complémentaires (mes choix, issues de la relecture du constructeur ; à corriger si besoin)
1. **Ouverture de session sur le serveur cible.** Chaque serveur est son propre client OpenID Connect d'Authelia. L'aiguillage redirige le navigateur vers l'adresse
   publique du serveur cible, sur le démarrage de sa connexion OpenID Connect (`/oauth2/authorization/authelia`). Comme la session Authelia est déjà ouverte,
   Authelia répond sans redemander le mot de passe (consentement préconfiguré pour ces clients). Le VPS n'a pas besoin d'être client des autres serveurs. La politique de
   double facteur se règle par client dans Authelia.
2. **Identité de rattachement.** Le claim `email` fait foi (attribut `mail` du LDAP), comparé sans casse, jamais `preferred_username`. L'e-mail est unique dans `users` ; une
   personne à plusieurs appartenances a UN seul e-mail, donc une seule identité.
3. **Contrat de `/api/licence/aiguillage`.** Le serveur appelant joint le jeton d'identité (ID token) obtenu d'Authelia ; le Maître le valide (signature par les clés publiques
   d'Authelia, émetteur, destinataire = identifiant de client du serveur appelant, validité, `email_verified`) avant toute réponse : un serveur ne peut donc interroger que les
   identités qu'il vient réellement d'authentifier. Sont exclues de la réponse les sociétés dont le tenant est hébergé par le serveur appelant lui-même (il les connaît). Un tenant sans
   serveur d'accueil (`serveurLicenceId` nul) est hébergé par le Maître : l'adresse publique du Maître est un réglage du Maître. Un serveur d'accueil sans `urlPublique` ou révoqué est
   omis de la réponse. Compte inconnu, inactif ou sans société accessible : même réponse vide.
4. **Le Maître est aussi un client** (identifiants propres), joignable publiquement par le VPS. Dans cette première version, les comptes qui portent `ROLE_ADMIN` NE peuvent PAS se
   connecter par Authelia (refus générique, comme la décision du 7 novembre) : les administrateurs restent en compte local.
5. **Authelia 4.39 ou plus récent**, clients confidentiels, secrets stockés hachés dans la configuration d'Authelia.
6. **Déconnexion.** Première version : déconnexion locale du serveur seulement ; la session Authelia reste ouverte (documenté). La déconnexion unique (`end_session_endpoint`) est une
   suite possible.

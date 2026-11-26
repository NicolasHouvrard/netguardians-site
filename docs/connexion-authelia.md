# Connexion unique par Authelia et LDAP : procédure d'installation

Objectif : une personne a UN identifiant et UN mot de passe pour tous les serveurs du site. Elle se connecte sur le
portail `https://auth.logicielpourtous.com` (Authelia, sur le VPS, adossé au LDAP du NAS), puis arrive sur le serveur de
sa société sans retaper son mot de passe. Les mots de passe ne sont jamais saisis dans le site lui-même.

Architecture :

- LDAP Synology (NAS, `100.64.0.2`) : annuaire des utilisateurs, joignable seulement par le réseau privé Tailscale/Headscale.
- Authelia (VPS, `100.64.0.3`) : portail et fournisseur OpenID Connect, derrière le Nginx du VPS.
- Chaque serveur du site (Maître `https://master.logicielpourtous.com`, VPS, serveurs de partenaires et de clients) est un client
  OpenID Connect d'Authelia, avec son propre identifiant et son propre secret.
- Le rattachement société -> serveur reste dans la base du Maître (page « Fédération »).

Exemples de fichiers : `docs/exemples/authelia/` (`compose.exemple.yml`, `configuration.exemple.yml`, `nginx-auth.exemple.conf`,
`proprietes-site.exemple.md`). Les valeurs `CHANGER_MOI` sont à remplacer ; aucun secret ne doit être ajouté au dépôt.

Ordre conseillé : 1, 2, 3, 4, 5, puis la vérification (point 9). Ne migrez les comptes (point 6) qu'une fois la vérification réussie.

---

## 1. LDAP Synology (sur le NAS)

1. Dans le Centre de paquets, ouvrir « Synology Directory Server » / « LDAP Server » (paquet déjà installé) et l'activer. Noter le
   **DN de base** affiché (par exemple `dc=CHANGER_MOI,dc=local`).
2. Dans le paquet, créer l'unité d'organisation ou le conteneur des utilisateurs (par défaut `cn=users`) et un groupe
   `administrateurs` pour les administrateurs du site.
3. Créer un compte de service `authelia` : c'est lui qu'Authelia utilise pour chercher les utilisateurs. Lui donner un mot de passe fort et
   unique. **Deux options pour les mots de passe des utilisateurs** :
   - **(a) retenue par défaut dans l'exemple (recommandée tant que l'outil de migration n'existe pas)** : la réinitialisation de mot de passe
     par Authelia est désactivée (`password_reset.disable: true`) et le compte de service reste en **lecture seule**. Les mots de passe sont définis par
     l'administration directement dans le LDAP. Le lien de réinitialisation par e-mail du site ne s'applique pas aux comptes du LDAP ;
   - **(b)** activer la réinitialisation par Authelia (`disable: false`) ; elle s'exécute avec le compte de service, qui doit alors avoir une écriture
     **limitée à l'attribut `userPassword`** (liste de contrôle d'accès du LDAP Synology/OpenLDAP), et **jamais** à `mail` ni aux autres attributs :
     qui peut modifier l'e-mail d'un utilisateur peut prendre le compte du site correspondant.
4. Créer les utilisateurs. Pour chacun : identifiant (l'e-mail est un bon choix), nom, et surtout l'attribut **`mail` obligatoire et unique**.
   Le site rattache la personne à son compte existant par cet e-mail.
5. **Réserver l'écriture à l'administration.** Celui qui peut modifier l'e-mail d'un utilisateur dans le LDAP peut prendre le compte
   du site correspondant : seuls les administrateurs du NAS doivent pouvoir modifier l'annuaire, et les utilisateurs ne doivent pas pouvoir
   changer leur propre e-mail.
6. **Limiter l'accès au réseau privé** : n'écouter (ou n'autoriser dans le pare-feu du NAS) que l'interface Tailscale/Headscale ; aucune
   redirection de port depuis Internet. Activer LDAPS (port 636) et exporter le certificat si Authelia doit le valider.
7. Tester depuis le VPS (réseau privé actif) : une recherche LDAP avec le compte `authelia` doit retrouver un utilisateur et son `mail`.

## 2. Authelia sur le VPS (4.39 ou plus récent)

Fichiers de départ : `compose.exemple.yml` et `configuration.exemple.yml`.

1. Créer un dossier, par exemple `/opt/authelia/`, avec `config/` et `secrets/` (droits restreints : `chmod 700 secrets`).
2. Générer les secrets, un fichier chacun dans `secrets/` (droits `0400`), jamais dans le dépôt :
   `jwt_reset_password`, `session`, `storage_encryption_key`, `oidc_hmac` (chaînes aléatoires longues, par exemple
   `openssl rand -hex 64`), `ldap_password` (mot de passe du compte `authelia`), `smtp_password`, et la clé privée de signature
   `oidc_jwks_rsa.pem` (`openssl genrsa -out oidc_jwks_rsa.pem 4096`).
3. Copier `configuration.exemple.yml` vers `config/configuration.yml` et renseigner : DN de base LDAP, adresse `ldaps://100.64.0.2:636`
   (adresse privée du NAS), nom du groupe des administrateurs, SMTP. L'exemple utilise l'e-mail (`mail`) comme identifiant de connexion
   (`attributes.username`, repris dans `users_filter`) et désactive la réinitialisation par Authelia (option (a) du point 1). Les règles `access_control` ne
   servent pas ici : l'accès des clients est réglé par `authorization_policies`. Le cookie de session est limité au portail `auth.logicielpourtous.com`
   (à vérifier avec la documentation d'Authelia de votre version, 4.39). « Se souvenir de moi » est désactivé (`remember_me: -1`) : la déconnexion
   n'étant que locale (point 7), une session longue sur un poste partagé resterait ouverte ; on peut accepter une durée courte si le confort prime,
   au prix de ce risque.
4. **Un client confidentiel par serveur.** Pour chaque serveur (Maître, VPS, puis chaque serveur ajouté) :
   - choisir un `client_id` propre (`site-maitre`, `site-vps`, ...) ;
   - générer un secret aléatoire long, le noter pour le serveur concerné, et en calculer l'**empreinte** :
     `docker run --rm authelia/authelia:4.39 authelia crypto hash generate pbkdf2 --variant sha512 --random --random.length 72 --random.charset rfc3986`
     (la commande affiche le secret en clair et son empreinte : l'empreinte `$pbkdf2-sha512$...` va dans `client_secret` du fichier
     de configuration ; le secret en clair va uniquement dans les propriétés du serveur concerné, point 4) ;
   - `redirect_uris` : exactement `https://<adresse-publique-du-serveur>/login/oauth2/code/authelia` (pour le Maître :
     `https://master.logicielpourtous.com/login/oauth2/code/authelia`) ;
   - `scopes` : `openid`, `email`, `profile` ;
   - PKCE exigé : `require_pkce: true` et `pkce_challenge_method: 'S256'` ;
   - consentement préconfiguré : `consent_mode: 'implicit'` (clients de confiance, pas d'écran de consentement) ;
   - la politique de claims de l'exemple place `email` et `email_verified` dans le jeton d'identité ; **`email_verified` doit valoir vrai** pour
     un utilisateur dont l'e-mail est dans l'annuaire (sinon le site refuse la connexion). Si votre version d'Authelia ne le fournit pas
     ainsi, ajustez la configuration des claims avant de continuer.
5. **Double facteur obligatoire pour les administrateurs** : la politique d'autorisation `site` de l'exemple exige `two_factor` pour le groupe LDAP
   des administrateurs et `one_factor` pour les autres. Chaque administrateur enregistre son application TOTP à sa première connexion.
6. Lancer : `docker compose up -d`, puis `docker compose logs authelia` : aucune erreur de configuration ni de connexion LDAP.
7. Bonne pratique : **sauvegarder `config/`** (configuration et base SQLite) et un exemplaire chiffré des secrets, hors du VPS ; garder la
   limitation d'essais (`regulation`) activée ; ne jamais exposer le port 9091 autrement que par le Nginx (l'exemple l'attache à `127.0.0.1`).

## 3. Nginx du VPS

Fichier de départ : `nginx-auth.exemple.conf`.

1. Obtenir un certificat pour `auth.logicielpourtous.com` (par exemple avec certbot) et pour `master.logicielpourtous.com`.
2. Installer le bloc du portail `auth.logicielpourtous.com` : proxy vers `127.0.0.1:9091`, redirection HTTP -> HTTPS.
3. **En-têtes transmis** : `Host`, `X-Forwarded-Proto`, `X-Forwarded-Host`, `X-Forwarded-For`. Le site construit l'adresse de retour OpenID Connect
   à partir de la requête (`server.forward-headers-strategy=native`) : sans ces en-têtes, il annoncerait `http://` ou un mauvais hôte et
   Authelia refuserait le retour. Cela vaut **aussi pour `master.logicielpourtous.com`** (bloc fourni, avec le port réel du Maître) et pour
   chaque autre serveur du site placé derrière un Nginx.
4. Tailles et délais : `client_max_body_size` (1 Mo suffit pour le portail ; 50 Mo pour les serveurs du site, comme
   `spring.servlet.multipart.max-request-size`), `proxy_connect_timeout` court, `proxy_read_timeout` adapté.
5. `nginx -t`, puis recharger Nginx. Contrôle : `https://auth.logicielpourtous.com/.well-known/openid-configuration` doit répondre en JSON,
   avec `"issuer": "https://auth.logicielpourtous.com"`.

## 4. Propriétés du site, serveur par serveur

Détail complet : `docs/exemples/authelia/proprietes-site.exemple.md`. Valeurs en variables d'environnement du serveur (les secrets ne sont jamais écrits dans
un fichier du dépôt). **Deux noms équivalents existent** : (1) `AUTHELIA_CLIENT_ID`, `AUTHELIA_CLIENT_SECRET`, `AUTHELIA_ISSUER_URI`,
`MAITRE_URL_PUBLIQUE`, valables si vous **décommentez** les lignes correspondantes de `application.properties.example` (copiées dans votre
`application.properties`) ; (2) `SPRING_SECURITY_OAUTH2_CLIENT_*` et `APP_AUTH_AUTHELIA_*`, qui fonctionnent sans modifier le fichier. Dans le
conteneur, utilisez **une seule** de ces deux formes (la forme (1), lignes décommentées, est la plus lisible) et ne les mélangez pas :

**Chaque serveur (Maître et secondaires)** :

- `spring.security.oauth2.client.registration.authelia.client-id` : le `client_id` de CE serveur ;
- `spring.security.oauth2.client.registration.authelia.client-secret` : le secret en clair de CE serveur ;
- `spring.security.oauth2.client.provider.authelia.issuer-uri` : `https://auth.logicielpourtous.com`.

Les trois sont nécessaires : tant qu'une seule est vide, la connexion par Authelia est absente et le site se comporte comme avant.
(Variables équivalentes : `SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_AUTHELIA_CLIENT_ID`, `..._CLIENT_SECRET`,
`SPRING_SECURITY_OAUTH2_CLIENT_PROVIDER_AUTHELIA_ISSUER_URI`.) Garder `server.forward-headers-strategy=native`.

**Authelia doit être configuré sur CHAQUE serveur cible** (Maître, VPS, serveurs de partenaires et de clients) : un serveur vers lequel l'aiguillage renvoie
un utilisateur mais dont les propriétés Authelia sont absentes affiche « momentanément indisponible ».

**Sur le Maître en plus** :

- `app.auth.authelia.issuer-uri` : `https://auth.logicielpourtous.com` (le Maître vérifie ainsi les jetons d'identité des serveurs secondaires) ;
- `app.auth.authelia.maitre-url-publique` : `https://master.logicielpourtous.com` (adresse où sont renvoyées les sociétés hébergées par le
  Maître lui-même).

**Page « Fédération » du Maître (`/intra/licence/federation`)**, pour chaque serveur secondaire enrôlé :

- renseigner l'**URL publique** (`https://<adresse-publique-du-serveur>`), c'est là que l'aiguillage renvoie l'utilisateur ;
- renseigner l'**identifiant client OpenID Connect** : le même `client_id` que dans Authelia pour ce serveur (champ `oidc_client_id`). Le
  Maître n'accepte les jetons d'un serveur que si leur destinataire est cet identifiant.

Redémarrer chaque serveur après modification des propriétés.

## 5. Compte administrateur local de secours (Maître)

Les administrateurs passent au LDAP (avec double facteur). Pour ne jamais être enfermé dehors si Authelia, le LDAP ou le VPS est en panne,
**un compte administrateur local reste sur le Maître** :

1. Créer (ou conserver) un compte local avec le rôle `ROLE_ADMIN` sur le Maître, avec une adresse e-mail dédiée (distincte de celle des
   administrateurs du LDAP).
2. Lui donner un mot de passe fort et unique (gestionnaire de mots de passe, au moins 20 caractères), conservé hors ligne.
3. Usage rare : uniquement en cas de panne de la connexion unique. La page de connexion locale (formulaire) reste toujours disponible.
4. Le tester régulièrement (par exemple chaque trimestre et après chaque mise à jour du site) : connexion par le formulaire, accès à
   l'administration.

## 6. Migration des comptes existants vers le LDAP

Principe (outil d'export à venir) :

- export des comptes **sans mot de passe** ; l'identifiant LDAP est l'e-mail ;
- mot de passe : avec l'option (a) du point 1 (par défaut), l'administration le définit dans le LDAP et le transmet par un canal sûr ; avec l'option (b),
  chaque personne le définit via le lien de réinitialisation d'Authelia envoyé par e-mail ;
- tant qu'un compte n'est pas dans le LDAP, la connexion locale continue de fonctionner comme avant.

En attendant l'outil, on peut créer les premiers utilisateurs à la main dans le LDAP (point 1), avec le même e-mail que leur compte du site.

## 7. Comportements à connaître

- **Rattachement par e-mail** : la connexion par Authelia ouvre le compte du site **déjà existant** portant le même e-mail (sans tenir compte des
  majuscules). Aucun compte n'est créé à partir d'une identité externe. Message identique dans tous les refus : « Connexion impossible avec ce compte. »
- **E-mail vérifié obligatoire** pour tous les fournisseurs OpenID Connect (Authelia, Google). Il n'y a pas de connexion Microsoft par cette voie.
- **Déconnexion** : locale seulement dans cette première version. Se déconnecter d'un serveur ne ferme pas la session Authelia, qui
  reste ouverte jusqu'à son expiration : sur un poste partagé, fermer aussi le navigateur.
- **Authelia ou LDAP indisponible** : seule la connexion locale fonctionne. Si le site n'a pas encore pu joindre Authelia, le bouton renvoie à la page
  de connexion avec le message « momentanément indisponible ». Une fois la découverte d'Authelia mise en cache (après une première connexion réussie),
  une panne ultérieure mène le bouton vers une erreur de passerelle du Nginx (502) : seul le formulaire de connexion local reste utilisable.
- **Comptes non synchronisés entre serveurs** : si le compte d'une personne n'existe pas sur le serveur cible, le navigateur peut tourner en boucle de
  redirection (symptôme `ERR_TOO_MANY_REDIRECTS`). Cause : compte absent sur le serveur cible ; remède : créer ou déménager le compte (point 6).
- **Plusieurs sociétés / serveurs** : une personne appartenant à plusieurs sociétés voit une page de choix ; une seule destination
  entraîne la redirection directe vers `/oauth2/authorization/authelia` du serveur concerné (route `/connexion/aiguillage` côté serveur
  secondaire ; `POST /api/licence/aiguillage` côté Maître, réservé aux serveurs de licence actifs authentifiés par leur clé).
- **Double facteur** : exigé par Authelia pour le groupe des administrateurs ; la politique se règle par groupe et par client dans la
  configuration d'Authelia.

## 8. Retour arrière

Retirer (ou vider) les trois propriétés `spring.security.oauth2.client.*.authelia.*` d'un serveur, et `app.auth.authelia.issuer-uri` sur le
Maître, puis redémarrer : le bouton disparaît, l'aiguillage est désactivé, et la connexion locale seule (plus Google si configuré)
fonctionne exactement comme avant. Rien n'est à défaire dans la base. Côté infrastructure, arrêter Authelia (`docker compose down`)
ne casse pas la connexion locale.

## 9. Vérification pas à pas

Commencer avec UN utilisateur et UN serveur (le Maître), avant d'étendre.

1. Dans le LDAP : un utilisateur de test avec `mail` identique à celui d'un compte existant du site (non administrateur pour ce premier essai).
2. Authelia : `https://auth.logicielpourtous.com` s'affiche en HTTPS ; l'utilisateur de test s'y connecte (et enregistre son TOTP s'il est administrateur).
3. Sur le Maître, ajouter les trois propriétés et `app.auth.authelia.*`, redémarrer. La page de connexion affiche le bouton
   « Se connecter avec le compte unique ».
4. Cliquer : redirection vers `auth.logicielpourtous.com`, saisie de l'identifiant et du mot de passe, puis retour sur
   `https://master.logicielpourtous.com/login/oauth2/code/authelia` et arrivée dans le site (choix de société si plusieurs appartenances).
5. Essais de refus attendus : un utilisateur LDAP sans compte du site, ou dont l'e-mail n'existe pas, voit « Connexion impossible avec ce compte. » ;
   aucun compte n'est créé.
6. Arrêter Authelia un instant : si une connexion par Authelia a déjà réussi depuis le démarrage du site, le bouton mène à une erreur de
   passerelle du Nginx (502), pas au message « momentanément indisponible » (qui n'apparaît que si le site n'a pas encore joint Authelia). Seul le
   formulaire de connexion local reste utilisable. Redémarrer Authelia.
7. Étendre : ajouter le client du VPS (Authelia, propriétés du VPS, puis URL publique et identifiant client dans la page « Fédération »).
   Un utilisateur dont la société est hébergée ailleurs, qui se connecte via le VPS, doit être renvoyé vers le bon serveur sans retaper son
   mot de passe.
8. Tester le compte administrateur local de secours (point 5) avant de passer les administrateurs au LDAP.

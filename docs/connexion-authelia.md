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

Ordre conseillé : 1, 2, 3, 4, 5, puis les tests de base (point 9) et la vérification (point 10). Ne migrez les comptes (point 6) qu'une fois la vérification réussie.

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
4. Créer les utilisateurs. Pour chacun : identifiant (`uid`), nom, et surtout l'attribut **`mail` obligatoire et unique**. Authelia accepte à la connexion
   l'e-mail OU l'`uid`. Le site rattache la personne à son compte existant par l'e-mail. Ajouter les administrateurs au groupe `administrateurs`
   (le LDAP Synology liste les membres d'un groupe par `memberUid`, c'est-à-dire par `uid`) : c'est ce groupe qui déclenche le double facteur obligatoire.
5. **Réserver l'écriture à l'administration.** Celui qui peut modifier l'e-mail d'un utilisateur dans le LDAP peut prendre le compte
   du site correspondant : seuls les administrateurs du NAS doivent pouvoir modifier l'annuaire, et les utilisateurs ne doivent pas pouvoir
   changer leur propre e-mail.
6. **Limiter l'accès au réseau privé** : n'écouter (ou n'autoriser dans le pare-feu du NAS) que l'interface Tailscale/Headscale ; aucune
   redirection de port depuis Internet. Activer LDAPS (port 636) et exporter le certificat si Authelia doit le valider.
7. Tester depuis le VPS (réseau privé actif) : une recherche LDAP avec le compte `authelia` doit retrouver un utilisateur et son `mail`.

## 2. Authelia sur le VPS (4.39 ou plus récent)

Fichiers de départ : `compose.exemple.yml` et `configuration.exemple.yml`.

1. Créer un dossier, par exemple `/opt/authelia/`, avec `config/` et `secrets/` (droits restreints : `chmod 700 secrets`).
2. Générer les secrets, un fichier chacun dans `secrets/` (droits `0400`), jamais dans le dépôt. Avec l'image d'Authelia (un appel par secret) :
   `docker run --rm authelia/authelia:4.39.28 authelia crypto rand --length 64 --charset alphanumeric` pour `jwt_reset_password` (à fournir même si la
   réinitialisation est désactivée), `session`, `storage_encryption_key`, `oidc_hmac` ; `ldap_password` est le mot de passe du compte `authelia` et
   `smtp_password` celui du SMTP. La clé privée de signature OpenID Connect :
   `docker run --rm -v "$PWD/secrets:/out" authelia/authelia:4.39.28 authelia crypto pair rsa generate --bits 4096 --directory /out`
   puis renommer `secrets/private.pem` en `secrets/oidc_jwks_rsa.pem` (et supprimer `public.pem`, inutile). (`openssl rand -hex 64` et
   `openssl genrsa -out oidc_jwks_rsa.pem 4096` conviennent aussi.) Le tag `4.39.28` était la dernière 4.39 connue à la rédaction : vérifiez-le avec
   `docker pull authelia/authelia:4.39.28`, sinon prenez la dernière 4.39.x.
3. Copier `configuration.exemple.yml` vers `config/configuration.yml` et renseigner : DN de base LDAP (remplacer `CHANGER_MOI` par le vôtre), adresse
   `ldaps://100.64.0.2:636` (adresse privée du NAS), nom du groupe des administrateurs, SMTP. Points à connaître :
   - **Identifiant et groupes** : `attributes.username` vaut `uid`, la connexion accepte l'e-mail ou l'`uid` (`users_filter`), et les groupes sont trouvés par
     `memberUid` (`groups_filter`). Il ne faut pas mettre `mail` comme `username` : `memberUid` contient l'`uid`, le groupe des administrateurs ne serait
     alors jamais trouvé et le double facteur ne leur serait **jamais exigé**, sans erreur visible. Vérifier les classes d'objets et les attributs réels
     avec un `ldapsearch` sur le NAS.
   - **Certificat du LDAP** : le certificat du NAS doit couvrir l'adresse utilisée (ou poser `tls.server_name`). `tls.certificate_chain` sert à présenter un
     certificat *client*, pas à faire confiance à une autorité ; pour une autorité privée, voir `certificates_directory` dans la documentation d'Authelia 4.39 (à vérifier).
     Éviter `skip_verify: true`.
   - **Mots de passe** : `password_reset.disable` et `password_change.disable` valent `true` (option (a) du point 1).
   - **Sessions** : le cookie est limité au portail `auth.logicielpourtous.com` (à vérifier avec la documentation d'Authelia de votre version, 4.39) ; il n'y a
     pas de `default_redirection_url` (inutile ici, et elle doit appartenir au domaine du cookie). « Se souvenir de moi » est désactivé (`remember_me: -1`) : la
     déconnexion n'étant que locale (point 7), une session longue sur un poste partagé resterait ouverte ; on peut accepter une durée courte si le
     confort prime, au prix de ce risque.
   - Les règles `access_control` ne servent pas ici : l'accès des clients est réglé par `authorization_policies`.
   - **Notifications** : l'enrôlement du double facteur (TOTP) demande un code envoyé par le notifier (e-mail de l'utilisateur). Pour les premiers tests sans SMTP,
     utiliser `notifier.filesystem` (`filename: '/config/notification.txt'`, page de documentation `.../configuration/notifications/file/`) et lire le code dans ce fichier ;
     un seul fournisseur à la fois. Revenir à SMTP ensuite.
4. **Un client confidentiel par serveur.** Pour chaque serveur (Maître, VPS, puis chaque serveur ajouté) :
   - choisir un `client_id` propre (`site-maitre`, `site-vps`, ...) ;
   - générer un secret aléatoire long, le noter pour le serveur concerné, et en calculer l'**empreinte** :
     `docker run --rm authelia/authelia:4.39.28 authelia crypto hash generate pbkdf2 --variant sha512 --random --random.length 72 --random.charset rfc3986`
     (la commande affiche le secret en clair et son empreinte : l'empreinte `$pbkdf2-sha512$...` va dans `client_secret` du fichier
     de configuration ; le secret en clair va uniquement dans les propriétés du serveur concerné, point 4) ;
   - `redirect_uris` : exactement `https://<adresse-publique-du-serveur>/login/oauth2/code/authelia` (pour le Maître :
     `https://master.logicielpourtous.com/login/oauth2/code/authelia`) ;
   - `scopes` : `openid`, `email`, `profile` ;
   - PKCE exigé : `require_pkce: true` et `pkce_challenge_method: 'S256'`. Le site envoie PKCE `S256` pour le fournisseur `authelia` (résolveur de requêtes d'autorisation dédié) ;
     contrôlez-le au premier essai : si Authelia refuse l'autorisation pour une raison de PKCE, la requête n'envoie pas `code_challenge` ;
   - consentement préconfiguré : `consent_mode: 'implicit'` (clients de confiance, pas d'écran de consentement) ;
   - par défaut le jeton d'identité ne contient pas l'e-mail (Authelia le sert par `userinfo`) : la politique `claims_policies.site.id_token` de l'exemple y place
     `email`, `email_verified`, `name` et `preferred_username`, et chaque client la référence (`claims_policy: 'site'`) ;
   - **`email_verified` est toujours à vrai côté Authelia**, sans vérification réelle de l'adresse, quel que soit l'utilisateur. La vérification d'e-mail du site ne
     protège donc pas ici : **la confiance repose entièrement sur le contrôle des écritures dans l'annuaire** (point 1 : écriture réservée à l'administration,
     `mail` non modifiable par l'utilisateur, pas d'écriture du compte de service sur `mail`).
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

## 5 bis. Pages de connexion

Quand le fournisseur `authelia` est configuré (les trois propriétés du point 4), la page `/login` propose le compte unique par défaut :

- un grand bouton « Se connecter » (vers `/oauth2/authorization/authelia`), le texte « Vous utilisez le compte unique de Logiciel pour tous. » et un lien
  « Mot de passe oublié ? » vers le portail Authelia. L'adresse du lien est l'`issuer-uri` de la configuration (jamais celle de la requête) ; si elle
  n'est pas en `https`, le lien n'est pas affiché. Les boutons Google/Microsoft restent affichés s'ils sont configurés, sous le bouton principal ;
- le formulaire local (identifiant, mot de passe, « se souvenir de moi », captcha) n'est **pas rendu** sur `/login` ;
- pas de redirection automatique vers Authelia (évite les boucles et la reconnexion immédiate après une déconnexion).

**Adresse de secours `/login/secours`** (publique) : elle affiche le formulaire local, avec l'avertissement « Connexion de secours réservée aux comptes
locaux ». C'est le recours du compte administrateur local du point 5 (panne d'Authelia, du LDAP ou du VPS). Le formulaire poste sur `POST /login` avec
un champ caché `secours=1` : un échec (identifiants ou captcha) revient sur `/login/secours?error`, sinon sur `/login?error`. Quand Authelia est
indisponible, `/login?error=authelia` affiche « Le compte unique est momentanément indisponible » avec un lien discret « Connexion de secours ».

Sans configuration Authelia, rien ne change : `/login` garde le formulaire local.

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
- **Déconnexion** : le site ferme sa propre session, puis un utilisateur connecté par le compte unique arrive sur la page `/deconnecte`
  (« Vous êtes déconnecté du site. »). Cette page propose, avec un avertissement pour les postes partagés, le lien « Fermer aussi la session du
  compte unique » qui ouvre la page de déconnexion du portail (`<issuer-uri>/logout`) dans un **nouvel onglet**. L'adresse de ce lien vient
  uniquement de la configuration (`spring.security.oauth2.client.provider.authelia.issuer-uri`, en https obligatoire), jamais de la requête ; elle
  n'est affichée que pour une session venue d'Authelia. Les comptes locaux, Google et Microsoft se déconnectent comme avant (retour à
  `/login?logout`), de même si le portail n'est pas configuré ou pas en https. La session Authelia reste donc ouverte tant que l'utilisateur ne
  clique pas sur ce lien (ou jusqu'à son expiration). La déconnexion unique complète, sans clic supplémentaire, viendra avec Authelia 4.40
  (déconnexion OpenID Connect, non publiée à ce jour).
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

## 9. Tests de base avant de brancher le site

À faire avant d'ajouter la moindre propriété Authelia au site (ordre conseillé : après les points 1 à 3, avant le point 10).

1. **Réseau privé, depuis le VPS** : `docker run --rm busybox nc -zv 100.64.0.2 636` doit répondre « open ». Refaire le test **depuis le conteneur
   Authelia** une fois démarré (`docker compose exec authelia ...` si l'image contient l'outil, sinon un conteneur de test sur le même réseau Docker) : un conteneur peut ne pas
   atteindre l'interface Tailscale de l'hôte si le routage ou la traduction d'adresses n'est pas en place.
2. **Validation de la configuration** : `docker compose run --rm authelia authelia validate-config --config /config/configuration.yml` ne doit signaler aucune erreur
   (la commande ne teste pas la connexion LDAP ni SMTP ; confirmez-la avec `authelia validate-config --help` si elle diffère dans votre version).
3. **Démarrage et journaux** : `docker compose up -d`, puis `docker compose logs authelia` : pas d'erreur de configuration, ni de connexion au LDAP ou au SMTP.
4. **Navigateur** : ouvrir `https://auth.logicielpourtous.com`, se connecter avec l'utilisateur de test (e-mail ou `uid`). Pour un administrateur, enregistrer le TOTP (le code
   d'enrôlement arrive par le notifier). Contrôler qu'un membre du groupe `administrateurs` se voit bien demander le second facteur.
5. `https://auth.logicielpourtous.com/.well-known/openid-configuration` répond en JSON avec le bon `issuer` (point 3).


## 10. Vérification pas à pas

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

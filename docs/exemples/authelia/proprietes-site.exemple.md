# Propriétés du site pour la connexion unique (exemples, sans secret)

Chaque valeur peut être donnée dans `application.properties` OU en variable d'environnement (forme recommandée pour
les secrets : ils ne sont jamais écrits dans un fichier du dépôt).

## Serveur secondaire (VPS, serveurs de partenaires ou de clients)

| Propriété | Variable d'environnement équivalente | Exemple |
|---|---|---|
| `spring.security.oauth2.client.registration.authelia.client-id` | `SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_AUTHELIA_CLIENT_ID` | `site-vps` |
| `spring.security.oauth2.client.registration.authelia.client-secret` | `SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_AUTHELIA_CLIENT_SECRET` | `CHANGER_MOI` (le secret en clair, dont Authelia garde l'empreinte) |
| `spring.security.oauth2.client.provider.authelia.issuer-uri` | `SPRING_SECURITY_OAUTH2_CLIENT_PROVIDER_AUTHELIA_ISSUER_URI` | `https://auth.logicielpourtous.com` |
| `spring.security.oauth2.client.registration.authelia.scope` (facultative) | `SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_AUTHELIA_SCOPE` | `email,profile` (défaut ; `openid` est toujours ajouté) |
| `server.forward-headers-strategy` | `SERVER_FORWARD_HEADERS_STRATEGY` | `native` (déjà dans `application.properties.example`) |

Le bouton « Se connecter avec le compte unique » n'apparaît que si les TROIS premières valeurs sont renseignées.
Adresse de retour à déclarer dans Authelia : `https://<adresse-publique-du-serveur>/login/oauth2/code/authelia`.

## Maître (NAS)

Les trois propriétés ci-dessus (le Maître est aussi un client, avec ses propres identifiants, par exemple `site-maitre`), plus :

| Propriété | Variable d'environnement équivalente | Exemple |
|---|---|---|
| `app.auth.authelia.issuer-uri` | `APP_AUTH_AUTHELIA_ISSUER_URI` | `https://auth.logicielpourtous.com` (même valeur que l'émetteur ci-dessus ; vide = aiguillage désactivé) |
| `app.auth.authelia.maitre-url-publique` | `APP_AUTH_AUTHELIA_MAITRE_URL_PUBLIQUE` | `https://master.logicielpourtous.com` (format `https://hôte[:port]`, sans chemin) |

## Page « Fédération » du Maître (`/intra/licence/federation`)

Pour chaque serveur enrôlé (tableau « Serveurs enrôlés ») :

- colonne « URL publique » : `https://<adresse-publique-du-serveur>` ; c'est là que l'utilisateur est renvoyé par l'aiguillage ;
- colonne « Identifiant client OpenID Connect » : le `client_id` déclaré pour CE serveur dans Authelia (par exemple `site-vps`) ;
  1 à 100 caractères parmi lettres, chiffres, `.`, `_`, `-`, unique par serveur. Sans lui, l'aiguillage ignore ce serveur.

## Réglages dans l'écran d'administration (`/admin/sso-config`)

Les valeurs ci-dessus sont des valeurs **par défaut**. Un administrateur (`ROLE_ADMIN`) du serveur concerné peut les régler dans
`/admin/sso-config` : ce qui est enregistré en base **prime** et s'applique sans redémarrage ; un champ vide laisse la variable d'environnement
reprendre la main.

| Champ de l'écran | Valeur par défaut (propriété) | Exemple |
|---|---|---|
| Adresse d'Authelia | `spring.security.oauth2.client.provider.authelia.issuer-uri` | `https://auth.logicielpourtous.com` |
| Identifiant du client | `spring.security.oauth2.client.registration.authelia.client-id` | `site-vps` |
| Secret du client (écriture seule, jamais affiché) | `spring.security.oauth2.client.registration.authelia.client-secret` | généré par le bouton « Générer un secret client » |
| Adresse publique de ce serveur | aucune (base seulement) | `https://app.logicielpourtous.com` |
| Adresse de retour après déconnexion (facultative) | `app.auth.authelia.retour-deconnexion` | vide = `<adresse publique>/login?logout` |
| Adresse publique du Maître (Maître seulement) | `app.auth.authelia.maitre-url-publique` | `https://master.logicielpourtous.com` |

Procédure complète (génération du secret, bloc `clients:` d'Authelia, empreinte, redémarrage, test) et dépannage par `/login/secours` :
`docs/connexion-authelia.md`, point 4 bis. Générer un secret remplace immédiatement le secret actif.

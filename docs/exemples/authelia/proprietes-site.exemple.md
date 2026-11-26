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

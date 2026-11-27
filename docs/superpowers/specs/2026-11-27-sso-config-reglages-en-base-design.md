# Réglages du compte unique en base et écran d'administration (étape 1)

Date : 2026-11-27. Suite de la connexion unique par Authelia (v1.18 à v1.21). Demande du propriétaire : « stocker les réglages en base
plutôt que dans des fichiers par serveur », « générer plus facilement le mot de passe et l'empreinte d'Authelia ».
L'étape 2 (distribution des réglages non secrets depuis la page « Fédération » du Maître vers les serveurs distants) est HORS de ce document.

## Intention
Un administrateur règle le compte unique d'un serveur (adresse d'Authelia, identifiant et secret du client, adresse publique du serveur) dans
l'écran `/admin/sso-config`, sans modifier de fichier ni recréer de conteneur. L'écran génère aussi le secret du client et son empreinte
Authelia, et affiche le bloc de configuration à coller dans Authelia.

## Décisions du propriétaire
1. Réglages en base (à la place des variables d'environnement) ; un écran dans `/admin/sso-config`.
2. Générer facilement le mot de passe et l'empreinte (`$pbkdf2-sha512$…`) d'un client Authelia.
3. La distribution depuis le Maître (étape 2) viendra ensuite ; le secret restera local à chaque serveur.

## Buts, non-buts, anti-buts
- Buts : plus de fichier de réglages à éditer pour le compte unique ; changement sans redémarrage ; secret chiffré en base, jamais réaffiché ;
  l'adresse de retour de la déconnexion est déduite de l'adresse publique du serveur ; valeurs contrôlées (https, domaine cohérent).
- Non-buts : distribution depuis le Maître (étape 2) ; modification de la configuration d'Authelia lui-même (fichier du VPS) ; création de comptes LDAP.
- Anti-buts : un secret affiché, journalisé ou renvoyé dans une réponse ; un administrateur enfermé dehors par une valeur erronée (la page
  `/login/secours` et les variables d'environnement restent) ; un serveur annexe qui règle un autre serveur.

## Contraintes existantes (vérifiées dans le code)
- Les réglages sont lus aujourd'hui dans l'environnement à six endroits : `SocialOAuth2Config` (`autheliaConfigure`, `AutheliaRegistration`),
  `AuthController` (bouton, lien « mot de passe oublié »), `DeconnexionController`, `AutheliaLogoutSuccessHandler` (et `SecurityConfig` ligne ~395),
  `AiguillageService` (adresse publique du Maître), `ValidateurJetonAuthelia` (émetteur).
- `/admin/sso-config` existe (`SsoStatusController`) mais en lecture seule (Google, Microsoft).
- Un convertisseur de chiffrement de champ existe (`MailEncryptionConverter`, clé de l'application) : à réutiliser pour le secret.
- Modulith : `ModularityTests` doit rester vert (un contrat dans `commun`, implémenté ailleurs, comme `AiguillageMaitre`).
- Dernière migration : V19 ; la suivante est V20. `application.properties` est ignoré par git (exemples dans `application.properties.example`).
- Authelia : l'empreinte attendue a la forme `$pbkdf2-sha512$310000$<sel>$<hash>` (encodage base64 adapté, sans remplissage) ; la
  vérification se fait par `authelia crypto hash validate` (syntaxe à vérifier).

## Conception
### 1. Stockage et priorité
- Table `reglages_compte_unique`, UNE ligne par serveur : `issuer_uri`, `client_id`, `client_secret` (chiffré), `url_publique` (adresse publique de CE serveur),
  `maitre_url_publique` (Maître seulement, pour l'aiguillage), `retour_deconnexion` (facultatif, sinon déduit de `url_publique` + `/login?logout`), date et auteur de la dernière modification.
- Contrat `ConfigurationCompteUnique` (module `commun`) : `configure()`, `issuerUri()`, `clientId()`, `clientSecret()`, `urlPublique()`,
  `retourDeconnexion()`, `maitreUrlPublique()`. Implémentation (module `auth`) : la VALEUR EN BASE prime ; à défaut, la variable d'environnement actuelle
  (noms inchangés) ; sinon vide. Un compteur de version permet aux consommateurs de se reconstruire quand les réglages changent, sans redémarrage.
- Sans aucun réglage (base vide et environnement vide) : AUCUN changement de comportement (comme aujourd'hui).

### 2. Consommateurs
Les six endroits ci-dessus lisent le contrat au lieu de l'environnement. Le registre du client OpenID Connect et le décodeur de jetons se reconstruisent
paresseusement quand la version change. La découverte réseau reste paresseuse et bornée (pas d'appel à l'enregistrement ni au chargement de `/login`).

### 3. Écran `/admin/sso-config` (ROLE_ADMIN)
- Formulaire : adresse d'Authelia, identifiant du client, secret (champ en écriture seule : on affiche « défini / non défini » et un bouton « remplacer »),
  adresse publique de ce serveur, adresse publique du Maître (affichée seulement sur un Maître), adresse de retour (facultative). CSRF, messages d'erreur par champ.
- Contrôles : https obligatoire ; pas d'identifiants intégrés ni de fragment ; l'adresse publique et l'adresse de retour dans le domaine parent du portail ;
  longueurs bornées.
- Bouton « Tester la connexion à Authelia » : requête https courte (délais de quelques secondes) vers `<émetteur>/.well-known/openid-configuration`, résultat
  « joignable / injoignable / réponse inattendue », jamais le contenu de la réponse ; réservé à l'administrateur.
- Bouton « Générer un secret client » : génère un mot de passe aléatoire fort (72 caractères de l'alphabet sûr, jeu RFC 3986), calcule l'empreinte
  PBKDF2-SHA512 (310 000 itérations, sel aléatoire, format Authelia), enregistre le secret CHIFFRÉ en base (c'est lui que le serveur présentera) et affiche
  UNE fois le bloc de client à coller dans la configuration d'Authelia (`client_id`, `client_secret: '<empreinte>'`, `redirect_uris` calculées depuis l'adresse
  publique) avec la commande de vérification de l'empreinte. Le mot de passe en clair n'est jamais affiché.
- Journal : chaque modification est journalisée (qui, quand, quels champs), jamais les valeurs secrètes.

### 4. Sécurité
- Le secret est chiffré au repos, absent des journaux, des messages d'erreur, des réponses HTTP et des gabarits ; jamais renvoyé dans un formulaire.
- Seul l'administrateur du serveur modifie ses propres réglages ; aucune route n'agit sur un autre serveur.
- Une valeur invalide est REFUSÉE à l'enregistrement (jamais enregistrée puis ignorée) ; l'écran n'enferme pas l'administrateur dehors (page de secours inchangée).

## Approches considérées
1. Réglages en base, priorité sur l'environnement (retenue). 2. Rester sur l'environnement et ajouter seulement un générateur d'empreinte (écartée : ne supprime pas
l'édition de fichiers par serveur). 3. Distribution immédiate depuis le Maître (reportée : étape 2).

## Questions laissées au constructeur
Noms de classes et de colonnes, forme exacte de la page, algorithme de comparaison de version, détail de l'encodage de l'empreinte (à valider contre la documentation d'Authelia).

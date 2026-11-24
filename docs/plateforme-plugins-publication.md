# Publier un plugin : clés, signature, publication, suivi

Procédure écrite pour quelqu'un qui n'a pas suivi le projet. Conception : 
`docs/superpowers/specs/2026-11-13-plugins-sp2-signature-distribution-design.md`. Mise en route locale et API
d'administration : `docs/plateforme-plugins-local.md`.

## En deux minutes

- Un plugin est un `.jar`. Il s'exécute **dans le processus du cœur avec tous les privilèges** (base, secrets, fichiers,
  réseau). Le cœur n'installe donc que les jars **signés** par l'équipe.
- La signature est un fichier `<jar>.sig` à côté du jar (Ed25519, clé du JDK, aucune dépendance). La **clé privée** ne
  quitte jamais le poste de signature ; les **clés publiques** sont dans le cœur
  (`src/main/resources/plateforme/cles-publiques.properties`) et c'est la seule source : aucune propriété ne permet d'en
  ajouter à l'exécution.
- On publie un jar signé **une seule fois, au Maître**. Chaque serveur (Maître compris) vient le chercher tout seul
  toutes les 10 minutes (« tirage »), le vérifie, l'installe et rapporte le résultat au Maître. Les serveurs n'ont
  besoin d'aucune connexion entrante.
- **Rien n'est publié par GitHub/GHCR pour les jars** : l'image du cœur ne contient aucun plugin. Le dépôt du Maître
  est la source unique de distribution.

## 1. Générer la paire de clés (une fois)

Sur un **poste de signature dédié** (pas un poste partagé, pas un serveur), dans un dossier **privé à votre compte
utilisateur**, jamais dans le dépôt Git, jamais dans un dossier synchronisé (cloud, partage réseau).

Il faut le jar du SDK. Depuis le dépôt (Java 25 et Maven) :

```
cd plugins-sdk
mvn -B -q package        # produit plugins-sdk/target/site-plugins-sdk-0.0.1.jar
```

Puis, avec un identifiant de clé (`keyId`, de 1 à 64 caractères parmi `A-Za-z0-9._-`, par exemple `prod-2026-11-a`) :

```
java -cp plugins-sdk/target/site-plugins-sdk-0.0.1.jar com.logicielpourtous.site.sdk.OutilSignature generer-cles C:\Cles\signature prod-2026-11-a
```

Cela écrit dans le dossier `prod-2026-11-a.prive` (**secret**, droits réduits à votre compte ; créé sans jamais
écraser un fichier existant) et `prod-2026-11-a.public`. Générer **deux** paires : une clé actuelle et une clé de
**secours** (`prod-2026-11-b`), pour la rotation. Sauvegarder la clé privée de secours hors ligne (clé USB
chiffrée, coffre) ; la clé privée n'est affichée nulle part, ne l'envoyer par aucun canal.

Limite connue : sous Windows le fichier est d'abord créé vide avec les droits hérités du dossier, puis restreint, puis
rempli. Un autre compte qui avait ouvert le fichier à cet instant précis pourrait le relire : c'est pourquoi le
dossier doit être privé et le poste non partagé.

## 2. Mettre les clés publiques dans le cœur et sortir une version

Les clés livrées initialement (`keyId` commençant par `dev-`) étaient des clés de **développement** aux clés privées détruites ;
elles ont été remplacées le 2026-11-18 par `prod-2026-11-a` et `prod-2026-11-b`. Pour une rotation, la procédure est :

1. Ouvrir `src/main/resources/plateforme/cles-publiques.properties`.
2. Remplacer les deux lignes `dev-...` par les deux clés de production, au format `keyId=<contenu du fichier .public>` :
   ```
   prod-2026-11-a=MCowBQYDK2VwAyEA....
   prod-2026-11-b=MCowBQYDK2VwAyEA....
   ```
3. Moins de deux clés lisibles : avertissement au démarrage. Une ligne illisible est ignorée et signalée. Avec
   `signature-obligatoire=true` (défaut) et **aucune** clé lisible, la plateforme refuse de démarrer.
4. Sortir une nouvelle version du cœur (image, déploiement habituel). **Tant que le cœur déployé contient les clés
   `dev-`, aucun jar n'est accepté.**

## 3. Signer un jar

Le jar doit contenir `plugin.properties` à la racine (voir `docs/plateforme-plugins-local.md`). Le **nom de fichier
n'a pas d'importance pour la vérification** ; au dépôt il sera rangé sous `<nom>-<version>.jar` d'après le manifeste.

```
java -cp plugins-sdk/target/site-plugins-sdk-0.0.1.jar com.logicielpourtous.site.sdk.OutilSignature signer mon-plugin-1.2.0.jar C:\Cles\signature\prod-2026-11-a.prive prod-2026-11-a
```

Écrit `mon-plugin-1.2.0.jar.sig` : `{"keyId":"…","algo":"Ed25519","signature":"<base64>"}`. La signature porte sur les
**octets exacts** du jar : **le moindre octet modifié après la signature** (recompilation, re-zip) l'invalide ; il
faut resigner. Contrôle avant publication (utile pour tester la paire de clés) :

```
java -cp plugins-sdk/target/site-plugins-sdk-0.0.1.jar com.logicielpourtous.site.sdk.OutilSignature verifier mon-plugin-1.2.0.jar src/main/resources/plateforme/cles-publiques.properties
```

Code retour 0 = signature valide, 1 = refusée (la raison est affichée).

### Signer avec le script

Sur le poste de signature (Java 25 requis ; le script le cherche dans `JAVA_HOME`, dans
`%USERPROFILE%\scoop\apps\temurin25-jdk\current` puis dans le `PATH`, et refuse un Java plus ancien) :

```
.\outils\signer.ps1 -Jar mon-plugin-1.2.0.jar -Cle C:\Cles\signature\prod-2026-11-a.prive -KeyId prod-2026-11-a
```

Le script signe, puis **vérifie** la signature avec `cles-publiques.properties` ; il affiche le chemin du `.sig` à
publier avec le jar. Il ne lit ni n'affiche la clé privée et n'en garde aucune copie. Si la vérification échoue, le
`.sig` est supprimé et le code retour est non nul. La vérification utilise le fichier de clés publiques **de la
copie locale du dépôt** : le tenir à jour (le récupérer avant de signer), sinon une clé récente peut être refusée à tort.
Si PowerShell refuse d'exécuter le script (politique d'exécution) :
`powershell -ExecutionPolicy Bypass -File .\outils\signer.ps1 -Jar ... -Cle ... -KeyId ...`. **La signature reste toujours hors ligne** : ni l'écran
d'administration ni le Maître ne signent, et la clé privée n'est jamais envoyée au serveur.

## 4. Publier au Maître

### Depuis l'écran `/admin/plugins` (recommandé)

Réservé à un compte `ROLE_ADMIN`, sur le Maître. Ouvrir `/admin/plugins`, choisir le `.jar` et son `.jar.sig`
(produit au chapitre 3), puis envoyer. Le Maître vérifie la signature, lit le manifeste et range le jar comme
avec l'API (mêmes réponses : version déjà publiée avec un contenu différent refusée, etc.). L'écran montre aussi le
dépôt, les plugins installés et l'état des serveurs annexes.

- **Désinstaller** : le bouton de l'écran retire le plugin **du Maître seulement** (jamais des serveurs annexes), et
  **seulement jusqu'au prochain redémarrage** : le jar reste dans le répertoire des plugins et le plugin revient
  au redémarrage. Il n'existe **pas de retrait durable depuis l'écran** : il faudrait supprimer tous les jars et
  `.sig` du plugin du répertoire des plugins ET ses versions du dépôt du Maître (sinon le Maître le réinstalle au
  tirage, environ 10 minutes plus tard). Publier une version supérieure n'est pas un retrait : c'est un
  remplacement, qui s'applique aussi aux serveurs annexes. Voir le chapitre 5 pour le comportement du tireur.
- **Limite d'envoi : 10 Mo** par jar. Au-delà, le comportement exact n'est pas garanti (message d'erreur
  possiblement absent ou connexion coupée).
- Ne jamais envoyer la clé privée : l'écran ne la demande pas.

### Avec `curl` (alternative)

Réservé à un compte `ROLE_ADMIN`, sur le Maître seulement (`app.role=MASTER`, `app.plateforme.actif=true`).
`https://MAITRE` ci-dessous est l'adresse du Maître.

Obtenir un jeton :

```
curl -s -X POST https://MAITRE/api/auth/login -H "Content-Type: application/json" -d "{\"email\":\"admin@exemple\",\"password\":\"...\"}"
```

La réponse contient `token`. Si elle contient `selectionRequise: true` (compte lié à plusieurs sociétés), choisir
d'abord la société (`POST /api/auth/societe`, corps `{"appartenanceId":…}`) et reprendre le jeton de cette réponse.

Publier le jar **et** sa signature :

```
curl -s -X POST https://MAITRE/api/plateforme/depot -H "Authorization: Bearer <jeton>" -F "jar=@mon-plugin-1.2.0.jar" -F "sig=@mon-plugin-1.2.0.jar.sig"
```

Le Maître **ne signe rien** : il vérifie la signature à la réception (avec les mêmes clés que le cœur, **même si
`signature-obligatoire=false`**), lit le manifeste, calcule le `sha256` et range `<nom>-<version>.jar` + `.sig`.

| Code | Sens |
|---|---|
| 200 | publié ; corps = `{nom, version, miseAJour, coeurMin, coeurMax, sha256, taille}`. Republier **le même contenu** est accepté (idempotent) |
| 400 | requête invalide : signature invalide, jar ou manifeste illisible, partie manquante... (`{"erreur":"…"}`) |
| 401 / 403 | sans jeton / compte non administrateur |
| 409 | **version déjà publiée avec un contenu différent** : on ne réécrit jamais une version, publier un numéro plus haut |
| 413 | jar plus gros que `taille-max-jar-mo` (voir limite de 10 Mo ci-dessous) |
| 500 | dépôt illisible côté Maître (voir son journal) |

Liste de ce qui est publié : `GET /api/plateforme/depot` (ADMIN).

**Limite effective de publication : 10 Mo par jar.** La limite multipart de Spring
(`spring.servlet.multipart.max-file-size=10MB`) coupe la requête **avant** le dépôt : au-delà, pas de corps
`{"erreur":…}` fiable et la connexion peut être coupée. La borne `taille-max-jar-mo` (100 par défaut) ne joue à la
publication que si elle est inférieure à 10. Choix assumé : les jars de plugin sont minces (les bibliothèques
partagées vivent dans le cœur). Alternative documentée si un jar plus gros devient nécessaire : accepter le jar en
corps de requête brut (flux) au lieu de multipart, ce qui demande de changer le contrat de la route ; **ne pas**
relever `max-file-size` pour cela seul, car la limite vaut pour tous les envois multipart de l'application.

**Dépôt manuel par un administrateur** (sans passer par l'API) : copier **d'abord** le `.jar.sig`, **puis** le `.jar`
dans le dossier `app.plateforme.depot-maitre`. Le catalogue est indexé par les jars : un jar arrivé avant sa
signature est vu comme paire incomplète. Le fichier doit s'appeler exactement `<nom>-<version>.jar` (+ `.sig`).

## 5. Ce qui se passe ensuite

- Premier tirage **un intervalle après le démarrage** de chaque serveur (10 minutes par défaut,
  `app.plateforme.intervalle-tirage-ms`), puis toutes les 10 minutes. Un serveur qui vient de démarrer ne voit donc
  rien tout de suite ; le Maître non plus (il tire de son propre dépôt, sans HTTP).
- Pour chaque plugin du catalogue : si la version est plus haute que l'active, compatible avec la version du cœur
  (`coeur-min`/`coeur-max`) et qu'elle n'a pas déjà été refusée, le serveur télécharge dans un dossier privé,
  contrôle la taille, le `sha256`, la signature et le manifeste, dépose `<nom>-<version>.jar` + `.sig` dans le
  répertoire des plugins et appelle l'installation. Il rapporte ensuite le résultat au Maître.
- **Le tireur installe aussi les plugins absents localement** : tout plugin du catalogue finit sur tous les serveurs,
  Maître compris. **Un plugin désinstallé (`DELETE /api/plateforme/plugins/{nom}`) n'est PAS réinstallé dans la même
  version** : la version active vue en mémoire et le jar resté dans le répertoire servent de plancher, et le tireur
  ignore toute version qui ne leur est pas strictement supérieure. Il revient seulement avec une version plus haute
  publiée au Maître, ou si le jar a été retiré du répertoire ET que le processus a redémarré (plancher perdu : la même
  version est alors retirée au tirage suivant). Un indicateur obligatoire/optionnel est prévu au sous-projet 3.
- Les jars anciens non actifs sont supprimés : on garde l'active et la précédente (et toute version plus haute).
- Maître injoignable, lent ou incohérent : le serveur continue avec ce qu'il a, sans bloquer le démarrage ni les requêtes.
- Authentification d'un serveur PARTNER/CLIENT : sa clé API de licence (en-tête `X-Licence-Api-Key`), routes
  `GET /api/licence/plugins/catalogue`, `GET /api/licence/plugins/{nom}/{version}/jar|sig`,
  `POST /api/licence/plugins/etat`.
- **`app.plateforme.master-url` doit être en `https://`.** Le code accepte aussi `http://`, mais la clé API de licence
  voyagerait alors en clair. Vide = `app.licence.master-url`.
- Ne **jamais** activer la journalisation de débogage du client HTTP du JDK (`-Djavax.net.debug`, loggers `sun.net.www`
  ou `sun.net.www.protocol.http.HttpURLConnection` en niveau fin) en production : elle écrirait l'en-tête
  `X-Licence-Api-Key` dans les journaux. Le code lui-même ne journalise jamais la clé.
- Fédération et maintenance : pendant une mise à jour à chaud d'un plugin, ses routes répondent
  **503 + `Retry-After`**. Les appels de fédération sortants rejouent un 503 **portant** un `Retry-After` valide
  (attente bornée à 30 s, 2 réessais au plus). Un **503 sans `Retry-After` n'est plus rejoué** (ni l'ancien rejeu
  implicite d'une seconde, ni celui des 429) : les planificateurs réessaient à leur propre cadence.

## 6. Vérifier l'état rapporté

```
curl -s "https://MAITRE/api/plateforme/serveurs-etat?limite=100" -H "Authorization: Bearer <jeton>"
```

(ADMIN ; `limite` de 1 à 2000, 500 par défaut). Liste
`[{licenceServeurId, nom, version, statut, raisons, rapporteLe, metaspace}]` : le **dernier** état rapporté par serveur
et par plugin. `metaspace` = `{metaspaceMo, seuilMo, redemarrageRecommande}` mesuré par le serveur au moment de son
rapport (`redemarrageRecommande: true` = ce serveur PARTNER/CLIENT devrait être redémarré ; **valeurs déclarées par le
serveur**, bornées côté Maître) ; `null` si le serveur n'en a pas joint (ancienne version) ou si la mesure était invalide.
La mesure date de `rapporteLe` : elle n'est remontée qu'avec un rapport, pas en continu. Seul le Maître sait ce que les autres serveurs ont fait ; le
Maître lui-même n'y figure pas : son propre résultat est seulement dans son journal (lignes « Tirage local (Maître) ») et
dans `GET /api/plateforme/plugins` sur lui.

**Les `raisons` viennent des serveurs** : toute interface qui les affiche doit les **échapper** (jamais de HTML brut).
Des lignes d'état peuvent subsister pour des serveurs ou des plugins qui n'existent plus (**lignes orphelines
tolérées**, sans effet).

### Lire `statut`

L'installation répond HTTP 200 même en cas de refus : **il faut lire `statut`**.

| `statut` | Sens | Que faire |
|---|---|---|
| `OK` | nouvelle version active | rien |
| `REFUSE` | refusée sans rien charger : signature invalide, taille ou `sha256` différents du catalogue, rétrogradation, migration destructrice « à chaud », montée majeure, dépendance, paquet réservé... | lire `raisons` ; corriger le jar (nouvelle version signée, **numéro plus haut**) ; la version refusée n'est pas retéléchargée en boucle |
| `ECHEC` | l'installation a échoué en route (ou catalogue incohérent avec le manifeste) ; l'ancienne version a été remise | lire `raisons` et le journal du serveur ; publier une version corrigée |
| `ANNULE` | demande abandonnée ; l'ancienne version tourne toujours | idem, ou réessayer |

Une version refusée ou en échec est mise de côté **en mémoire** jusqu'à l'apparition d'une version plus haute. Cette
mémoire est **perdue au redémarrage** du serveur : la version est alors retentée une fois, et rapportée de nouveau.
Pour la relancer à la main : `POST /api/plateforme/plugins/installer` sur le serveur concerné.

## 7. Rotation de clé et perte de clé privée

- **Rotation prévue** : générer une nouvelle paire, ajouter sa clé publique **sans retirer l'ancienne**, sortir une
  version du cœur contenant les deux, la déployer partout, puis seulement signer avec la nouvelle clé ; retirer
  l'ancienne au cœur suivant. Garder toujours au moins deux clés dans le cœur.
- **Clé privée perdue ou volée** :
  - Perdue, secours intacte : signer avec la clé de secours (déjà dans le cœur), générer une nouvelle clé de secours,
    sortir une version du cœur qui retire la clé perdue.
  - Volée : même procédure **en urgence** ; tant que le cœur contient la clé volée, son détenteur peut faire exécuter
    du code sur tous les serveurs. Retirer la clé volée du cœur, déployer, et auditer les plugins déjà installés.
  - Les deux perdues : aucun jar ne peut plus être accepté ; seule la sortie d'un cœur avec de nouvelles clés
    publiques (déploiement manuel) rétablit la situation.

## 8. Limites connues à garder en tête

- **Rétrogradation** : un jar de version inférieure à l'active est refusé. Pour revenir en arrière, publier une
  version **plus haute** contenant l'ancien code. **Mais** si l'on supprime ou désinstalle le jar de la version haute
  (retrait du jar du répertoire, pas seulement `DELETE`), un jar signé de version inférieure encore présent peut
  alors être (re)démarré : retirer aussi les jars inférieurs.
- **Montée du cœur avec `coeur-max`** : un plugin dont `coeur-max` est inférieur à la nouvelle version du cœur est
  refusé au redémarrage qui suit la mise à jour du cœur ; il reste **à l'arrêt** tant qu'aucune version compatible
  plus haute n'est publiée. Publier la version compatible avant ou juste après la montée du cœur.
- **Pull initial** : voir plus haut (un intervalle après le démarrage).
- **Mémoire des versions refusées** perdue au redémarrage (voir 6).
- **Dossiers `.work` anciens** : avant le sous-projet 2, les copies chargées étaient dans `<plugins>/.work/`.
  Désormais le dossier de travail est `<repertoire-plugins>.work` (frère du répertoire des plugins). **Supprimer à la
  main** l'ancien `<plugins>/.work` après la mise à jour ; il n'est plus nettoyé.
- **Taille** : 10 Mo à la publication (voir 4).
- **Téléversement depuis un navigateur** : seule la publication d'un jar DÉJÀ signé est possible, depuis l'écran
  `/admin/plugins` (ou l'API) ; la signature, elle, ne se fait jamais dans le navigateur ni sur le serveur.

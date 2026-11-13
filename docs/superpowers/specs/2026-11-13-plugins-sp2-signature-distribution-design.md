# Plugins — sous-projet 2 : signature, distribution, surveillance (conception)

Date : 2026-11-13. Complète `2026-11-12-plugins-design.md` (sections 4.4, 4.6, 7, 7 bis, 8), qui reste la référence générale.
Légende : **[Décidé]** par l'utilisateur ; **[Mon choix]** à confirmer ; **[Mesuré]** vérifié dans le code.

## 1. But
Qu'un jar de plugin écrit et signé sur le poste du développeur arrive tout seul sur chaque serveur, y compris ceux qui n'acceptent aucune connexion entrante, et n'y soit exécuté que s'il est signé par l'équipe. Et qu'un serveur dont le Metaspace grossit finisse par redémarrer proprement la nuit.

## 2. Hors périmètre
- Les jars de référence embarqués dans l'image du cœur : il n'existe aucun vrai plugin avant le sous-projet 3, on y revient là.
- Le redémarrage de nuit des serveurs pilotés par l'Agent de déploiement (PARTNER/CLIENT) : seul le Maître (NAS) est traité ici [Mon choix, voir 7].
- Une interface Angular de gestion : l'état est exposé en API (et lu par l'écran d'administration plus tard).
- Le téléversement du jar depuis un navigateur : un point d'entrée API pour l'administrateur du Maître suffit.

## 3. Signature [Décidé : jars signés, clé privée sur un autre poste, clé publique embarquée]
- **Algorithme [Mon choix]** : Ed25519, API standard du JDK (`Signature`/`KeyPairGenerator`), aucune dépendance ajoutée.
- **Fichier de signature [Mon choix]** : à côté du jar, `<jar>.sig`, JSON à trois champs `{"keyId":"…","algo":"Ed25519","signature":"<base64>"}`. La signature porte sur **les octets exacts du jar** (le manifeste y est donc inclus).
- **Clés publiques [Décidé : liste d'au moins deux]** : ressource du cœur `plateforme/cles-publiques.properties` (`keyId = base64 X.509`). Pas de propriété qui permette d'en ajouter à l'exécution (sinon le cœur ne protège plus rien). Rotation = nouvelle version du cœur contenant les deux listes.
- **Outil [Mon choix]** : une petite classe en ligne de commande dans le jar du SDK (`plugins-sdk`), avec trois commandes : `generer-cles`, `signer`, `verifier`. Elle tourne sur l'autre poste, avec la clé privée sur ce poste uniquement.
- **Vérification** : dans `PluginManager.installer` et au démarrage. Propriété `app.plateforme.signature-obligatoire`, **vrai par défaut**. Faux seulement pour le développement local et les tests existants ; un avertissement net s'affiche alors.
- Jar sans `.sig`, avec une signature illisible, d'une clé inconnue ou fausse : `REFUSE`, journalisé, rien n'est chargé.

## 4. Choix de version
- **Au démarrage** : parmi les jars du répertoire, pour chaque plugin, on ne considère que ceux dont la signature est valide, puis on prend la plus haute version [Décidé, 7 bis].
- **Rétrogradation** : refusée (`REFUSE`) quand la version proposée est inférieure à l'active. L'« ordre signé du Maître » prévu en 7 bis n'est **pas** construit ici [Mon choix] : pour revenir en arrière, on publie une version plus haute qui contient l'ancien code. Coût : un retour arrière demande une nouvelle publication.

## 5. Distribution [Décidé : les serveurs tirent, le Maître distribue]
**Dépôt du Maître [Mon choix]** : un répertoire du serveur Maître (volume persistant), `<nom>-<version>.jar` + `.sig`. L'administrateur y publie par `POST /api/plateforme/depot` (jar + signature, réservé ADMIN, actif seulement si `app.role=MASTER`). Le Maître **vérifie la signature à la réception** (il ne signe jamais) et refuse un jar illisible ou une version déjà présente avec un contenu différent.

**API pour les serveurs [Mesuré : le patron existe]** : sous `/api/licence/plugins/**`, authentifiées par la clé API de licence du serveur (`X-Licence-Api-Key`, rôle déjà géré par `licenceApiFilterChain`) :
- `GET catalogue` : liste `{nom, version, miseAJour, coeurMin, coeurMax, sha256, taille}`.
- `GET {nom}/{version}/jar` et `GET {nom}/{version}/sig`.
- `POST etat` : le serveur rapporte `{nom, version, statut, raisons}` après chaque tentative.

**Tirage côté serveur** : un planificateur (intervalle `app.plateforme.intervalle-tirage-ms`, 10 min par défaut [Mon choix]) interroge le catalogue ; pour chaque plugin dont une version plus haute que l'active est disponible : téléchargement dans un dossier temporaire, contrôle du `sha256`, dépôt de `<nom>-<version>.jar` et `.sig` dans le répertoire des plugins, puis `installer`.
- **[Décidé par l'utilisateur]** : le tireur **lit `statut`** de la réponse (HTTP 200 même en cas de refus) et le rapporte au Maître ; le Maître **stocke et affiche** ce statut (dernier état par serveur et par plugin).
- Une version refusée ou en échec n'est pas retentée en boucle : elle est mise de côté jusqu'à l'apparition d'une version plus haute.
- Les jars anciens non actifs sont supprimés (on garde l'active et la précédente).
- Maître injoignable : le serveur continue avec ce qu'il a [Décidé, 7 bis].
- Le **Maître lui-même** installe depuis son propre dépôt (sans HTTP), avec la même logique.

## 6. Surveillance du Metaspace [Décidé : redémarrage de nuit si seuil dépassé]
- Un contrôle périodique lit le pool mémoire « Metaspace » ; au-dessus de `app.plateforme.metaspace-seuil-mo` (512 par défaut [Mon choix]), le serveur écrit un fichier repère `redemarrage-recommande` (avec la valeur mesurée et l'heure) dans son répertoire de données et journalise un avertissement.
- Côté NAS du Maître [Mesuré : `mise_a_jour.sh` fait déjà le blue-green] : un script appelé chaque nuit par un minuteur systemd regarde le repère ; s'il existe et que l'heure est dans la fenêtre (`03:00`–`04:00` par défaut [Mon choix]), il relance **la même version** en blue-green et supprime le repère. Si la nouvelle instance n'est pas saine, l'ancienne continue (comportement déjà garanti par le script).

## 7. Choix à confirmer
1. Intervalle de tirage 10 min, seuil Metaspace 512 Mo, fenêtre 03:00–04:00.
2. Pas d'ordre signé de rétrogradation (voir 4).
3. Redémarrage de nuit seulement pour le Maître en SP2. Les serveurs PARTNER/CLIENT ont un Agent qui ne sait que « déployer une version » : un « redéployer la même version » demande de toucher l'Agent, ce qui est un autre chantier. En attendant, leur repère est visible dans l'état remonté.
4. Les jars signés ne sont pas dans GitHub/GHCR : le dépôt du Maître est la source unique de distribution.

## 8. Critères de réussite
- Un jar non signé, mal signé ou signé par une clé inconnue est refusé, journalisé, jamais chargé (installation, démarrage, tirage).
- Un jar signé publié au Maître arrive sur un serveur de test sans accès entrant et s'y installe sans commande manuelle ; le Maître voit le statut rapporté.
- Une version refusée (migration destructive « à chaud », dépendance, rétrogradation) apparaît en échec côté Maître et n'est pas retentée en boucle.
- Au-dessus du seuil, le repère apparaît ; le script de nuit relance en blue-green et l'ancienne instance sert encore si la nouvelle n'est pas saine.
- La procédure complète (générer les clés, signer, publier) est écrite pour quelqu'un qui n'a pas suivi le projet.

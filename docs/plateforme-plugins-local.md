# Lancer la plateforme de plugins en local

Note courte pour essayer la plateforme de plugins (sous-projet 1) sur un poste de dev. Spec :
`docs/superpowers/specs/2026-11-12-plugins-design.md`.

> ## ⚠ UN JAR DE PLUGIN S'EXÉCUTE AVEC TOUS LES PRIVILÈGES DE LA JVM
>
> Tout `.jar` chargé est exécuté dans le processus du cœur **avec tous ses privilèges** : base (DataSource du cœur),
> secrets, système de fichiers, réseau. Depuis le sous-projet 2, **seuls les jars signés** (`<jar>.sig`, Ed25519) par
> une clé de `src/main/resources/plateforme/cles-publiques.properties` sont installés (installation, démarrage,
> tirage). Le mode `signature-obligatoire=false` (ci-dessous) désactive cette protection : développement seulement.
> Procédure de signature et de publication : `docs/plateforme-plugins-publication.md`.

## Activer

La plateforme est **désactivée par défaut** : sans la propriété, aucun bean, aucune route
`/api/plateforme/**` ni `/api/plugins/**` (le cœur se comporte comme avant).

Dans `application-local.properties` (ou par variable d'environnement `PLATEFORME_ACTIF=true`) :

```properties
app.plateforme.actif=true
app.plateforme.repertoire-plugins=plugins        # relatif au répertoire de lancement
app.plateforme.delai-drainage-ms=30000
app.plateforme.version-coeur=0.0.0               # comparée à coeur-min / coeur-max des manifestes
app.plateforme.signature-obligatoire=false       # DEVELOPPEMENT LOCAL SEULEMENT, voir ci-dessous
```

### Mode `signature-obligatoire=false` (développement seulement)

Par défaut (`true`) un jar sans `.sig` valide d'une clé du cœur est `REFUSE`. Les clés livrées dans le dépôt sont des
clés de développement sans clé privée : **aucun jar réel n'est donc acceptable en local** sans passer en `false`.
Avec `false`, les jars sont installés **sans aucune vérification** et le cœur journalise un avertissement net au
démarrage. Ne **jamais** le mettre à `false` en production. (Le dépôt du Maître, lui, vérifie toujours la signature à
la publication, quelle que soit cette propriété.) Pour signer pour de vrai : `docs/plateforme-plugins-publication.md`.

`nettoyage-actif` (toujours `true`) et `diagnostic-fuites` (voir plus bas) sont décrits dans
`application.properties.example`.

## Déposer un plugin

1. Créer le dossier `plugins/` à la racine du projet (là où l'application est lancée).
2. Y copier le jar du plugin (`plugin.properties` à la racine du jar : `nom`, `version`, `mise-a-jour`, ...).
3. Démarrer l'application : pour chaque plugin (regroupement par `nom` du manifeste, le nom de fichier ne compte
   pas), **seule la plus haute version** présente (comparaison semver : `1.10.0 > 1.9.0`, `1.5.0 > 1.5.0-rc1`) est
   démarrée ; les versions inférieures sont ignorées (INFO). Deux jars portant la même plus haute version, ou un jar
   illisible, sont journalisés (WARN) et ignorés. Un jar refusé ou en échec n'est jamais bloquant ; un refus pour
   dépendance pas encore active est retenté après les autres. Les copies réellement chargées sont dans
   `<repertoire-plugins>.work` (dossier frère du répertoire des plugins, privé, purgé au démarrage ; avant le
   sous-projet 2 c'était `plugins/.work/` : l'ancien dossier est à supprimer à la main).

**Désinstaller n'est pas persistant** : `DELETE /api/plateforme/plugins/{nom}` arrête le plugin mais **laisse son
jar** dans `plugins/`. Au prochain redémarrage, le plugin revient. Pour le retirer durablement, supprimer (ou
déplacer) aussi ses jars du répertoire.

### Paquet du plugin : espace réservé

Le `paquet` du manifeste (et la classe `configuration`) ne doit **pas** être dans les paquets du cœur : est refusé
(`REFUSE`) tout paquet égal à `com.logicielpourtous.site`, commençant par `com.logicielpourtous.site.`, ou englobant
(`com`, `com.logicielpourtous`), **sauf** l'espace réservé aux plugins `com.logicielpourtous.site.plugins.<plugin>`
(par exemple `com.logicielpourtous.site.plugins.facturation`). Un paquet tiers (`com.exemple.facturation`) est
accepté. En plus, le routage d'un plugin ne publie jamais un contrôleur chargé par le chargeur du cœur.

Pour installer ou mettre à jour **à chaud** sans redémarrer : déposer le nouveau jar dans `plugins/` puis appeler
l'API d'administration.

## API d'administration (compte `ROLE_ADMIN`, jeton JWT)

| Méthode | Route | Effet |
|---|---|---|
| `GET` | `/api/plateforme/plugins` | `[{nom, version, etat}]`, `etat` = `ACTIF` ou `MAINTENANCE` (mise à jour en cours) |
| `POST` | `/api/plateforme/plugins/installer` corps `{"fichier":"mon-plugin-1.2.0.jar"}` | installe / met à jour ; renvoie `{statut, plugin, version, raisons, interruptionMs}` (`statut` : `OK`, `REFUSE`, `ECHEC`, `ANNULE`) |
| `DELETE` | `/api/plateforme/plugins/{nom}` | désinstalle (204 ; 404 si inconnu) ; le schéma `plugin_<nom>` est conservé |
| `GET` | `/api/plateforme/fuites[?pression=true]` | `{retires, toujoursVivants, etiquettesVivantes, metaspaceMo}` |
| `GET` | `/api/plateforme/metaspace` | `{metaspaceMo, seuilMo, redemarrageRecommande}` (ADMIN, sur **tout** serveur : le Metaspace est celui de la JVM locale ; vaut 0/faux jusqu'à la première mesure, au plus 60 s après le démarrage) |
| `POST` | `/api/plateforme/depot` (multipart `jar` + `sig`) | **Maître seulement** : publie un jar signé, voir `docs/plateforme-plugins-publication.md` |
| `GET` | `/api/plateforme/depot` | **Maître seulement** : catalogue publié |
| `GET` | `/api/plateforme/serveurs-etat[?limite=n]` | **Maître seulement** : dernier statut rapporté par serveur et par plugin |

> **`installer` répond HTTP 200 même pour `REFUSE`, `ECHEC` et `ANNULE`** (le code HTTP dit que la demande a été
> traitée, pas qu'elle a réussi). Décision : **tout appelant** (distribution côté Maître, interface
> d'administration du serveur, scripts) **DOIT lire `statut`** et agir en conséquence (afficher les `raisons`,
> ne pas considérer la version comme déployée, remonter l'état au Maître). Seul `OK` signifie « nouvelle version
> active » ; `ANNULE` signifie « l'ancienne version tourne toujours ».

`fichier` est un **nom** de fichier du répertoire des plugins : `/`, `\` ou `..` → 400 ; fichier absent → 404.
Sans jeton → 401 ; compte non administrateur → 403. Les routes du plugin sont servies sous
`/api/plugins/<nom>/...` pour tout compte authentifié.

## Points d'attention

- **Migrations** : seules les migrations `db/plugin/<nom>/V<n>__*.sql` au premier niveau sont acceptées ; tout
  autre `.sql` (sous-dossier, `R__`, `U__`, `B__`) fait **refuser** le jar (règle R14). Elles vont dans le schéma
  `plugin_<nom>`, avec un historique Flyway propre au plugin (jamais celui du cœur).
- **Transaction unique** : toutes les migrations en attente d'une installation sont jouées dans **une seule
  transaction** (Flyway `group(true)`) pour pouvoir tout annuler en cas d'échec. Conséquence : pas de
  `CREATE INDEX CONCURRENTLY` (ni autre instruction interdite en transaction) dans une migration de plugin.
- **À chaud ou cœur** : `mise-a-jour=chaud` dans le manifeste permet la mise à jour sans redémarrage (courte
  maintenance : 503 + `Retry-After` sur `/api/plugins/<nom>/**`, drainage des requêtes en vol, retour à
  l'ancienne version si la nouvelle échoue). Une migration destructrice, une montée de version MAJEURE ou
  `mise-a-jour=coeur` exigent un redémarrage : l'installation à chaud est alors refusée (`REFUSE`) quand une
  version est déjà active ; déposer le jar et redémarrer.
- **Test de fuite** : `?pression=true` n'a d'effet que si `app.plateforme.diagnostic-fuites=true` (remplit le tas
  jusqu'à `OutOfMemoryError` pour effacer les références souples : brutal, à ne jamais activer en production).
  Sans elle, le rapport se fait avec de simples GC et peut compter à tort des versions comme vivantes.
- **Tests hors CI** : les tests d'intégration de la plateforme (`*IT`, dont `FuiteMemoireIT`) et les tests qui
  démarrent un plugin réel ont besoin du PostgreSQL local (`application-local.properties`) ; ils ne tournent pas
  dans la CI aujourd'hui. Les lancer un par un :
  `mvn -B -q -Dskip.npm -Dskip.installnodenpm -Dtest=<Classe> test`.
- **Contrat indisponible** : quand une route de plugin appelle un contrat sans fournisseur actif (fournisseur
  absent, en maintenance ou retiré), le cœur répond **503 + `Retry-After: 2`** (`ContratIndisponibleException`).
  Un plugin consommateur peut exposer le proxy du registre comme bean du type contrat : il n'est jamais pris pour
  un fournisseur.

## Distribution, tirage et volumes persistants

- **Rien n'est publié par GitHub/GHCR pour les jars** : l'image du cœur ne contient aucun plugin. Le jar signé est
  publié au Maître (`POST /api/plateforme/depot`) et chaque serveur le tire toutes les
  `app.plateforme.intervalle-tirage-ms` (10 min, premier tirage un intervalle après le démarrage). Le tireur installe
  aussi un plugin **absent** localement. Le tirage travaille dans `<repertoire-plugins>/.tirage`.
- **Volumes persistants** : `app.plateforme.repertoire-plugins` (tous les serveurs) et
  `app.plateforme.depot-maitre` (Maître, dossier privé distinct, ni contenu dans ni contenant le répertoire des plugins
  ou le dossier de travail) doivent être sur un volume qui survit aux redéploiements blue-green. **Le paquet NAS
  (`nas-package/lib/docker_bootstrap.sh`, `preparer_options_application`) les monte** : `<données>/plateforme-plugins`
  -> `/var/lib/site/plugins` (`APP_PLATEFORME_REPERTOIREPLUGINS`, **tous les rôles**, car le tireur installe aussi sur
  PARTNER/CLIENT) et `<données>/plateforme-depot` -> `/var/lib/site/depot-plugins` (`APP_PLATEFORME_DEPOTMAITRE`,
  **Maître seulement**), propriétaire uid 10001, mode 0700. Le **dossier de travail** reste **local au conteneur**
  (`APP_PLATEFORME_REPERTOIRETRAVAIL=/tmp/plateforme-travail`, jamais sur un montage : blue et green le partageraient et
  la purge au démarrage de la nouvelle couleur supprimerait les copies que l'ancienne a encore chargées). **Un Maître
  déjà installé** doit recevoir la nouvelle version du paquet puis une bascule blue-green pour que ces montages existent
  (les jars et le dépôt déjà présents dans le conteneur actuel ne sont pas migrés : republier). Comme le repère de
  redémarrage, le montage du dépôt n'est posé que si `nas_est_maitre` (nom d'hôte `logipourtous`) : un Maître d'un autre
  nom n'aurait ni dépôt persistant ni repère (limite connue, non corrigée).
- **PARTNER/CLIENT** : ils tirent du Maître (`app.plateforme.master-url`, vide = `app.licence.master-url`, en
  `https://`) avec la clé API de licence de l'installation enrôlée. Ils n'ont **ni dépôt, ni redémarrage de nuit
  automatique** (l'Agent de déploiement ne sait que « déployer une version ») : leur repère Metaspace est écrit dans
  leur dossier de données et lisible par `GET /api/plateforme/metaspace` sur eux ; **le Maître le voit aussi** : chaque
  rapport d'état qu'ils lui envoient joint leur mesure (`metaspaceMo`, `seuilMo`, `redemarrageRecommande`), exposée par
  `GET /api/plateforme/serveurs-etat` (champ `metaspace`). La mesure n'est remontée qu'avec un rapport (donc après une
  tentative d'installation) : lire `rapporteLe` pour savoir de quand elle date ; `null` = serveur sans mesure ou mesure
  invalide (ignorée par le Maître). Le redémarrage reste à faire à la main sur ces serveurs.

## Metaspace et redémarrage de nuit (Maître seulement)

- Tout serveur à plateforme active mesure son Metaspace toutes les `intervalle-surveillance-ms` (60 s). Au-dessus de
  `metaspace-seuil-mo` (512 Mo), il écrit le repère `<repertoire-donnees>/redemarrage-recommande` (JSON
  `metaspaceMo`, `seuilMo`, `depuis`) et journalise un WARN. Le repère ne déclenche rien tout seul.
- **Sur le Maître (NAS) seulement**, le paquet installe un minuteur systemd (`site-redemarrage-nuit.timer`, toutes les
  10 minutes) qui appelle `redemarrage-nuit.sh`. Le script ne fait rien hors de la fenêtre **03:00-04:00, heure
  locale du NAS** (`REDEMARRAGE_HEURE_DEBUT`/`REDEMARRAGE_HEURE_FIN`) ; dedans, si le repère existe, il relance la
  **même version** en blue-green et supprime le repère. Si la nouvelle instance n'est pas saine, l'ancienne continue de
  servir et le repère est conservé (nouvel essai la nuit suivante).
- **Il faut `app.plateforme.actif=true` sur le Maître** : sans cela, rien n'écrit le repère.
- **Un Maître déjà installé** doit recevoir la nouvelle version du paquet **puis** un redémarrage blue-green : le
  montage du dossier du repère (`<dossier parent>/plateforme` monté sur `/var/lib/site/plateforme`, avec
  `APP_PLATEFORME_REPERTOIREDONNEES`) n'est ajouté qu'au démarrage d'un conteneur par le nouveau paquet.
  Sans ce montage le repère reste dans le conteneur, invisible du NAS.

## Avant de l'activer en production

La plateforme est prête pour des essais locaux ; en production, traiter d'abord :

1. **Signature des jars** : fait au sous-projet 2 ; les clés de production (`prod-2026-11-a` et la clé de secours
   `prod-2026-11-b`) sont dans le cœur depuis le 2026-11-18 ; `signature-obligatoire` doit rester à `true`.
2. **Répertoire des plugins et dépôt du Maître persistants** : voir « Distribution, tirage et volumes persistants »
   (montages fournis par le paquet NAS, voir ce chapitre).
3. **Metaspace** : ne **pas** fixer `-XX:MaxMetaspaceSize` sans marge ; chaque génération charge ses classes, et
   une fuite résiduelle ferait tomber le cœur entier au lieu d'être visible dans le rapport de fuites.
4. **Pool de connexions partagé et `prepareThreshold`** : les plugins utilisent le pool Hikari du cœur. Un DDL de
   plugin à chaud peut casser des requêtes préparées en cache (`cached plan must not change result type`).
   Décider : `prepareThreshold=0` (ou `autosave=conservative`) sur pgjdbc, ou un pool par plugin.
5. **Test de fuite hors CI** : `FuiteMemoireIT` et les autres `*IT` de la plateforme ne tournent pas en CI (base
   locale requise). Les faire tourner en CI avant toute mise en production.
6. **`@ControllerAdvice` des plugins ignorés** : les résolveurs d'exceptions sont ceux du cœur ; un
   `@ControllerAdvice` déclaré dans un plugin n'est pas pris en compte (seuls les `@ExceptionHandler` des
   contrôleurs du plugin eux-mêmes le sont).
7. **Identité, tenant et autorisations sur `/api/plugins/**` non définis** (prérequis du sous-projet 3) :
   **tout compte authentifié, y compris un compte CLIENT**, peut appeler les API d'un plugin. Il n'existe pas
   encore de contrat d'identité/tenant pour les plugins. En attendant, chaque plugin doit déclarer
   `@EnableMethodSecurity` dans son contexte et protéger ses méthodes (`@PreAuthorize`).
8. **Statut de l'installation** : tous les appelants de `installer` doivent lire `statut` (HTTP 200 même en cas
   d'échec, voir plus haut).
9. **Désinstallation non persistante** : retirer aussi le jar du répertoire (voir plus haut). Le tireur ne réinstalle **pas** la même version d'un plugin désinstallé (plancher = version vue active en mémoire + jars présents sur disque) : il revient avec une version plus haute publiée au Maître, ou si le jar est retiré du répertoire et que le processus redémarre ; pour le retirer durablement, retirer aussi le plugin du dépôt du Maître.

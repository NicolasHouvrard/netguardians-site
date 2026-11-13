# Lancer la plateforme de plugins en local

Note courte pour essayer la plateforme de plugins (sous-projet 1) sur un poste de dev. Spec :
`docs/superpowers/specs/2026-11-12-plugins-design.md`.

> ## ⚠ JARS NON SIGNÉS : CODE EXÉCUTÉ AVEC TOUS LES PRIVILÈGES DE LA JVM
>
> Dans ce sous-projet 1, **aucune signature n'est vérifiée**. Tout `.jar` déposé dans le répertoire des plugins
> (ou installé par l'API d'administration) est chargé dans le processus du cœur et s'exécute **avec tous ses
> privilèges** : accès à la base (DataSource du cœur), aux secrets, au système de fichiers, au réseau. Déposer un
> jar équivaut à déployer du code dans le cœur. La vérification de signature est l'objet du **sous-projet 2**.
> Au démarrage, quand la plateforme est active, le cœur journalise un avertissement (WARN) qui le rappelle.

## Activer

La plateforme est **désactivée par défaut** : sans la propriété, aucun bean, aucune route
`/api/plateforme/**` ni `/api/plugins/**` (le cœur se comporte comme avant).

Dans `application-local.properties` (ou par variable d'environnement `PLATEFORME_ACTIF=true`) :

```properties
app.plateforme.actif=true
app.plateforme.repertoire-plugins=plugins        # relatif au répertoire de lancement
app.plateforme.delai-drainage-ms=30000
app.plateforme.version-coeur=0.0.0               # comparée à coeur-min / coeur-max des manifestes
```

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
   `plugins/.work/` (purgé au démarrage).

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

## Avant de l'activer en production

La plateforme est prête pour des essais locaux, **pas** pour la production. Prérequis à traiter d'abord :

1. **Signature des jars** (sous-projet 2) : aujourd'hui tout jar s'exécute avec tous les privilèges (voir
   l'encadré en tête).
2. **Répertoire des plugins persistant** : `app.plateforme.repertoire-plugins` doit pointer vers un **volume
   persistant**. `nas-package/lib/docker_bootstrap.sh` ne monte aujourd'hui **aucun** volume pour les plugins (seulement secrets, licence et clé de l'agent) : en l'état, les jars
   disparaîtraient à chaque redéploiement blue-green.
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
9. **Désinstallation non persistante** : retirer aussi le jar du répertoire (voir plus haut).

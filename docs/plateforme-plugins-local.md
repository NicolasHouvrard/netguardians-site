# Lancer la plateforme de plugins en local

Note courte pour essayer la plateforme de plugins (sous-projet 1) sur un poste de dev. Spec :
`docs/superpowers/specs/2026-11-12-plugins-design.md`.

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
3. Démarrer l'application : chaque `.jar` du dossier est installé au démarrage (ordre alphabétique ; un jar
   refusé ou en échec est journalisé et ignoré, jamais bloquant). Les copies réellement chargées sont dans
   `plugins/.work/` (purgé au démarrage).

Pour installer ou mettre à jour **à chaud** sans redémarrer : déposer le nouveau jar dans `plugins/` puis appeler
l'API d'administration.

## API d'administration (compte `ROLE_ADMIN`, jeton JWT)

| Méthode | Route | Effet |
|---|---|---|
| `GET` | `/api/plateforme/plugins` | `[{nom, version, etat}]`, `etat` = `ACTIF` ou `MAINTENANCE` (mise à jour en cours) |
| `POST` | `/api/plateforme/plugins/installer` corps `{"fichier":"mon-plugin-1.2.0.jar"}` | installe / met à jour ; renvoie `{statut, plugin, version, raisons, interruptionMs}` (`statut` : `OK`, `REFUSE`, `ECHEC`, `ANNULE`) |
| `DELETE` | `/api/plateforme/plugins/{nom}` | désinstalle (204 ; 404 si inconnu) ; le schéma `plugin_<nom>` est conservé |
| `GET` | `/api/plateforme/fuites[?pression=true]` | `{retires, toujoursVivants, etiquettesVivantes, metaspaceMo}` |

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

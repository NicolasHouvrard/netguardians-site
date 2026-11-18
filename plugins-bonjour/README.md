# plugins-bonjour — plugin de test

Plugin minimal (`GET /api/plugins/bonjour/bonjour`) conservé **pour tester les déploiements** de plugins sur les
futurs serveurs (distribution par le Maître, tirage, mise à jour à chaud). Il n'a aucune donnée ni migration.

- Version courante : **1.0.1** (message « Bonjour, version 1.0.1 mise a jour a chaud »).
- La 1.0.0 ne différait que par la version du manifeste et le message (« Bonjour depuis le plugin de test »).
  Pour retester une mise à jour à chaud, publier une version supérieure (ex. 1.0.2) en changeant `version` dans
  `src/main/resources/plugin.properties`, `pom.xml` et le message du contrôleur.

## Construire

```
JAVA_HOME=<Java 25> mvn -f plugins-bonjour/pom.xml package
```
Produit `plugins-bonjour/target/bonjour-<version>.jar`. Le manifeste est `plugin.properties` à la racine du jar.

## Signer et publier

Voir `docs/plateforme-plugins-publication.md` : signature avec `OutilSignature signer` (Java 25, clé privée de
production sur le poste de signature, jamais dans le dépôt), puis dépôt sur le Maître (`POST /api/plateforme/depot`).
Les jars et les signatures ne sont pas versionnés.

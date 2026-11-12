# Architecture à plugins chargés à chaud : conception

Date : 2026-11-12. Statut : en attente d'approbation. Aucun code produit n'est écrit avant l'approbation.

Légende : **[Décidé]** = décision de l'utilisateur. **[Mon choix]** = choix de l'auteur du document, à confirmer. **[Mesuré]** = résultat du prototype jetable du 2026-11-10 (`C:\Users\nicolas\Desktop\springboot\plugin-spike\RESULTATS.md`, code jetable, non conservé comme base).

## 1. Intention et raisons

Aujourd'hui la même application Spring Boot (monolithe `site`, Spring Boot 4.0.2, Java 25, PostgreSQL, Spring Modulith, frontend Angular dans `frontend/intra`) est chargée partout : au Maître (NAS « LogiPourtous »), sur les NAS partenaires et sur un VPS. Les problèmes :

- chaque changement, même mineur, impose de redéployer l'application entière : démarrage d'environ 122 s, nombreux tests ;
- sur le NAS, une mise à jour demande une commande manuelle (`mettre-a-jour-maitre.sh <tag>`) ; sur le VPS, la mise à jour n'a lieu que lors d'un changement de version ;
- chaque serveur charge tout, même ce dont il n'a pas besoin ;
- le développement n'est pas facile à partager entre plusieurs personnes.

Objectif : un **cœur** petit et stable, plus des **plugins** livrés comme jars, que le Maître distribue et que chaque serveur charge et met à jour **sans redémarrer**, sauf cas lourds.

## 2. Objectifs, non-objectifs, anti-objectifs

**Objectifs [Décidé]**
- Simplifier la gestion globale depuis le Maître.
- Mettre à jour un plugin sans redémarrer le serveur, sans commande manuelle, sans changement de version du cœur.
- Raccourcir les déploiements et les tests (tests par plugin).
- Permettre à l'équipe (l'utilisateur et son équipe, de confiance) de développer des plugins séparément.
- Chaque serveur ne charge que les plugins dont il a besoin.

**Non-objectifs**
- Plugins optionnels et activation par abonnement chez un seul partenaire : **hors de ce projet**, à décider plus tard [Décidé].
- Plugins écrits par des tiers : non, les développeurs sont de confiance [Décidé].
- Mise à jour sans aucune coupure : non, une courte coupure par plugin est acceptée [Décidé].

**Anti-objectifs (échec même si « ça marche »)**
- Une fuite mémoire non détectée qui sature la JVM d'un serveur de production.
- Un jar exécuté sur un serveur sans avoir été signé par l'équipe.
- Une migration « à chaud » qui casse la base.

## 3. Contraintes

- Mêmes versions que `site-depot-neuf` : Spring Boot 4.0.2 (Spring 7.0.3, Hibernate 7.2.1, Flyway 11.14.1, Jackson 3.0.4), Java 25, PostgreSQL.
- Déploiement actuel conservé pour le cœur : image Docker sur GHCR, blue-green 18080/18081, Agent du NAS (port 18090), secrets en fichiers.
- Rôles existants : `app.role` MASTER ou PARTNER ; federation des serveurs (tenants) déjà en place.
- Les serveurs partenaires et le VPS ne sont pas tous accessibles par une commande depuis le Maître : les serveurs doivent **tirer** les jars eux-mêmes [Mon choix, voir 7].

## 4. Décisions

### 4.1 Cœur [Décidé]
Le cœur contient : l'inscription des serveurs partenaires et clients, la connexion, la gestion des plugins. Il change rarement. Sa mise à jour reste un redémarrage blue-green avec la commande actuelle.

### 4.2 Plugins [Décidé]
- Plugins obligatoires : demandes, factures, commandes, interventions, abonnements.
- Le « socle » (utilisateurs, clients, équipe, rôles, mail) est aussi mis à jour par plugin à chaud par défaut [Décidé]. Le découpage exact en plugins du socle est à faire dans le sous-projet 4 [Mon choix].
- Un plugin livre son API **et** son interface Angular [Décidé]. Le premier plugin réel est « demandes » [Décidé].
- Un plugin optionnel peut dépendre d'un obligatoire, jamais l'inverse [Décidé].

### 4.3 Mécanisme : chargement à chaud dans la JVM, mode maintenance [Décidé]
Chaque plugin est chargé dans un contexte Spring enfant avec son classloader, son EntityManagerFactory, son Flyway et son schéma PostgreSQL `plugin_<nom>` avec une table d'historique propre [Mesuré, voir prototype].
- Mise à jour = **mode maintenance du plugin** : drainage des requêtes en vol, 503 avec `Retry-After` pour ce plugin seul, arrêt de l'ancien contexte, migrations, démarrage du nouveau. [Mesuré] 0,2 à 0,8 s de 503 en général, 2,3 s si une requête de 2 s était en vol ; les autres plugins ne sont pas touchés.
- Migration échouée : annulation automatique et redémarrage de l'ancienne version (813 ms d'interruption au total, base intacte) [Mesuré].
- Bascule atomique (sans 503) : non retenue par défaut ; gardée comme option future pour un plugin qui ne tolère pas 0,5 s [Mon choix].
- PF4J : non retenu comme base [Mon choix] : il n'aide pas pour JPA, Flyway, les routes REST ni les fuites ; sa bibliothèque d'intégration Spring n'a pas de version depuis mai 2025. À réévaluer pour la gestion des dépendances entre plugins si l'écriture maison s'avère lourde.

### 4.4 Sécurité contre les fuites mémoire [Décidé]
- Test automatique de fuite en CI à chaque version : N mises à jour successives d'un plugin d'essai, puis vérification que les anciens classloaders sont libérés (méthode du prototype : références faibles, après pression sur les références souples).
- Nettoyage des caches qui retiennent l'ancien code (Spring Data, Hibernate, Jackson, Spring MVC) : à reprendre du `LeakCleaner` du prototype, **à retester à chaque montée de version** de ces bibliothèques.
- Surveillance du Metaspace et **redémarrage automatique de nuit en blue-green** si un seuil est dépassé. Seuil et heure : à régler par le constructeur [Mon choix].

### 4.5 Politique de mise à jour [Décidé]
Trois niveaux :
1. **Affichage seul** : mise à jour à chaud de l'interface. [Mon choix] sans 503, car l'interface est servie depuis le jar avec des URL versionnées ; à valider au sous-projet 3.
2. **Code serveur sans changement de base risqué** : mise à jour à chaud du plugin en mode maintenance.
3. **Changement de base sensible ou modification trop lourde** : « mise à jour du cœur ». C'est une nouvelle version du cœur qui embarque **tous les plugins obligatoires avec leurs versions en cours**, déployée par redémarrage blue-green.

Classification [Décidé] : le développeur déclare dans le manifeste du plugin « à chaud » ou « mise à jour du cœur ». Le cœur analyse en plus les migrations du jar et **refuse** une déclaration « à chaud » si elles contiennent : renommage, suppression, changement de type, ajout d'une colonne obligatoire sur une table existante, ou toute modification destructive d'une table ou colonne référencée par une clé étrangère d'un autre plugin (voir 5, « Données »). Règle de fond : l'ancienne version doit toujours pouvoir tourner sur le nouveau schéma (expand puis contract dans des versions séparées) [Mesuré : un rollback du code ne remet pas le schéma en arrière].

### 4.6 Signature et distribution [Décidé]
- Chaque jar est **signé** à la publication ; la clé privée reste sur un autre poste, jamais sur le Maître.
- Le cœur embarque la clé publique et **refuse** tout jar non signé ou mal signé.
- Le Maître ne fait que distribuer. Distribution par tirage (voir 7).

## 5. Règles de conception issues du prototype [Mesuré]
- **Contrats entre plugins** : un jar de contrats stable, chargé par le cœur, avec seulement des types JDK et des records. Aucun type de plugin dans un contrat, un événement, une session ou un cache partagé. Les événements publiés dans un contexte enfant ne remontent pas aux contextes frères : seuls des types du jar de contrats sont admis.
- **Données** [Décidé : la cohérence de la base prime, on ne supprime une clé étrangère que si on y est obligé] :
  - Les **clés étrangères au niveau de la base sont conservées**, y compris entre tables de plugins différents. PostgreSQL les accepte entre schémas.
  - Ce qui est supprimé, c'est la **relation JPA** entre entités de plugins différents (`@ManyToOne`, `@OneToMany`, jointures JPQL) : deux plugins ont deux EntityManagerFactory et deux classloaders, ce qui rend la relation impossible. L'entité garde une colonne d'identifiant simple (`Long`), la clé étrangère reste en base, et la donnée de l'autre plugin s'obtient par un contrat.
  - Une clé étrangère ne peut viser que des tables d'un **plugin obligatoire ou du socle** (présents sur tous les serveurs), jamais d'un plugin optionnel (sa table pourrait ne pas exister).
  - Le manifeste d'un plugin déclare les plugins dont il référence les tables ; ce plugin ne se charge pas si ce fournisseur n'est pas installé à une version compatible.
  - Toute migration qui renomme, supprime ou change le type d'une table ou colonne **référencée** par une clé étrangère d'un autre plugin est classée « mise à jour du cœur » (ajoutée à l'analyse de 4.5).
  - Pas de jointure SQL native entre tables de plugins différents dans le code d'un plugin : passer par le contrat (la clé étrangère garantit la cohérence, pas l'accès).
  - Un schéma PostgreSQL par plugin pour ses **nouvelles** tables ; les tables existantes restent dans `public`, donc les clés étrangères existantes ne changent pas.
- **Dépendants** : traiter « fournisseur indisponible » comme un cas normal (503 avec `Retry-After` ou mode dégradé), jamais de blocage.
- **Versions** : le cœur impose les versions de Spring, Hibernate et Jackson (plugins en scope `provided`, parent-first). Un nom de persistence unit unique par génération.
- **Ressources** : aucun thread, executor ou `@Scheduled` d'un plugin ne doit survivre à la fermeture de son contexte. Le drainage avant fermeture est obligatoire (sinon `EntityManagerFactory is closed`).
- **Spring dans le contexte enfant** : `@EnableTransactionManagement` et `@EnableMethodSecurity` doivent être déclarés dans chaque plugin ; la chaîne de filtres de sécurité reste dans le cœur.
- **PostgreSQL** : un `ALTER TABLE` à chaud casse les requêtes préparées en cache (`cached plan must not change result type`) ; prévoir `prepareThreshold=0` ou un pool par plugin.
- **Hibernate 7.2 `validate`** contrôle la nullabilité ; les entités doivent correspondre aux colonnes.
- **Frontend** : URL versionnées et immuables, balises ou remotes versionnés, rechargement de la vue après une mise à jour (un module déjà chargé ne se décharge pas). Technologie de chargement (custom elements ou Angular Native Federation avec versions Angular strictement identiques) : voir 7.

## 6. Découpage en sous-projets [Mon choix, ordre accepté par l'utilisateur]
Chaque sous-projet est livrable et testable seul, avec son propre plan.

1. **Plateforme de plugins dans le cœur** : chargement, contextes enfants, schéma et Flyway par plugin, mode maintenance, drainage, retour arrière sur migration échouée, nettoyage des caches, test automatique de fuite, manifeste (version, dépendances, déclaration à chaud ou cœur), analyse des migrations. Aucun code métier n'est extrait.
2. **Signature et distribution** : signature des jars sur l'autre poste, dépôt sur le Maître, tirage et vérification par les serveurs, surveillance du Metaspace, redémarrage de sécurité de nuit.
3. **Premier plugin réel : demandes** (API + interface Angular), avec les contrats vers utilisateurs, clients, factures, commandes, interventions et abonnements dont il dépend. C'est le test grandeur nature.
4. **Autres plugins obligatoires**, un par un : factures, commandes, interventions, abonnements, puis socle.
5. *(Hors de ce projet)* Plugins optionnels et activation par abonnement.

## 7. Laissé au constructeur, ou choix à confirmer
- Comment un serveur apprend qu'une nouvelle version existe : [Mon choix] il interroge le Maître à intervalle régulier (le VPS et les NAS partenaires ne reçoivent pas d'ordre direct). Intervalle à régler.
- Format du manifeste de plugin et de la signature (algorithme, emplacement) : au constructeur.
- Technologie du frontend de plugin : au constructeur au sous-projet 3, en partant des résultats du prototype.
- Propriété des 88 tables existantes (migration `V1__baseline.sql` et suivantes, schéma `public`) : [Mon choix] elles restent dans `public` sous l'historique Flyway du cœur ; les tables d'un plugin extrait lui sont attribuées par convention de nommage, les nouvelles migrations du plugin vont dans son historique propre. Le détail d'extraction est traité dans le sous-projet 3.
- Spring Modulith : ses vérifications portent sur des modules connus à la compilation ; les plugins chargés à l'exécution en sortent. Comment conserver les tests d'architecture (`ModularityTests`, `IdentiteIndependanceTest`) : au constructeur.
- Seuils d'alerte Metaspace, heure du redémarrage de nuit : au constructeur.

## 7 bis. Choix ajoutés après relecture [Mon choix, à confirmer]
- **Où vivent les jars** : dans un répertoire de données du serveur (volume persistant), pas dans l'image. L'image du cœur embarque seulement les jars obligatoires de référence, copiés au premier démarrage ou si absents. Au démarrage, pour chaque plugin, la **plus haute version signée valide** présente localement gagne ; le serveur interroge ensuite le Maître. Si le Maître est injoignable, le serveur démarre avec ce qu'il a. Un serveur neuf obtient ses plugins de l'image puis du Maître.
- **Compatibilité cœur/plugin** : le manifeste porte `coeurMin` (et `coeurMax` facultatif). Un serveur dont le cœur est trop ancien **refuse** la version, garde l'actuelle et signale « mise à jour du cœur requise » dans son état remonté au Maître.
- **Jar de contrats** : il appartient au cœur ; il ne grandit que de façon additive et versionnée, et seulement avec ce dont les plugins livrés ont besoin (pas de contrats écrits à l'avance). Tout changement du jar de contrats est donc une mise à jour du cœur, et le sera plus souvent au début.
- **Clés de signature** : le cœur accepte une liste de clés publiques (au moins deux : actuelle et de secours) pour permettre une rotation sans coupure. Le serveur **refuse un retour en arrière** vers une version plus ancienne que l'installée, sauf ordre explicite signé du Maître.
- **Identité et tenant dans un plugin** : transmis par un contrat du jar de contrats (record JDK : utilisateur, rôles, tenant courant, rôle du serveur), jamais par un type partagé. Détail au sous-projet 1.
- **Appels de fédération vers un plugin en maintenance** : les clients de fédération traitent 503 + `Retry-After` par un nouvel essai avec délai ; ajout au sous-projet 2 si absent.
- **Mémoire et connexions sur NAS** : budget mémoire et nombre de connexions mesurés au sous-projet 1 sur le NAS réel ; le choix pool partagé ou pool par plugin en découle. Le budget devient un critère de réussite à ce moment.
- **Test de fuite** : un plugin d'essai synthétique tourne en CI du cœur ; **chaque vrai plugin** rejoue en plus le test dans sa propre CI avant publication (pour attraper ses threads et caches propres).

## 8. Critères de réussite
- Mise à jour d'un plugin sur un serveur de test sous charge : aucun redémarrage, 503 limités au plugin et inférieurs à 1 s (hors requête lente en vol), aucune requête perdue ailleurs.
- Mise à jour avec une migration cassée : version précédente revenue, base intacte.
- Test de fuite : 20 mises à jour successives, 20 anciens classloaders libérés, en CI.
- Jar non signé ou mal signé : refusé et journalisé.
- Déclaration « à chaud » contredite par une migration destructive : refusée.
- Un serveur de test sur un réseau sans accès entrant reçoit la nouvelle version par tirage, sans commande manuelle.

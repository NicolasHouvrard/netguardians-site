# Socle du front Thymeleaf + HTMX (projet 1 de la migration du front)

Date : 2026-11-22. Remplace, pour le front, l'approche « éléments personnalisés Angular livrés par les plugins » des lots A et B.
Ce document ne couvre que le **socle** ; les projets suivants sont listés en fin de document.

## Intention

Le front Angular et son build npm sont trop lourds pour la machine de développement (8 Go de mémoire : plusieurs plantages de
session pendant les builds). Le propriétaire décide de **migrer progressivement tout le site vers HTML, Thymeleaf et HTMX** (rendu
côté serveur, aucun build front), pour simplifier le développement et obtenir une interface homogène, d'apparence moderne.

## Décisions du propriétaire

- Migration progressive de **tout le site** vers Thymeleaf + HTMX, avec une vision homogène ; l'apparence de la partie Thymeleaf déjà
  existante est modernisée aussi.
- Interface proche d'**Odoo** (sélecteur d'applications, barre supérieure compacte, panneau de contrôle, listes et fiches avec barre de
  statut), avec des couleurs plus modernes. Référence visuelle :
  https://dribbble.com/shots/26500644-Enterprise-Resource-Planning-ERP-B2B-Analytics-Dashboard (la page n'a pas pu être lue
  automatiquement : la palette viendra d'une capture d'écran fournie plus tard ; en attendant, la palette par défaut est un choix du
  constructeur de conception et se règle ensuite dans l'écran d'apparence).
- L'**apparence est réglable par une interface graphique** (couleurs, polices, épaisseur des traits, arrondis) par **l'admin de chaque
  serveur**. Le serveur maître propose un **thème par défaut** ; chaque réglage est soit hérité du thème par défaut, soit modifié sur
  ce serveur.
- Approche retenue pour le thème par défaut : **A** : il est livré dans l'application (fichier versionné avec le code, mis à jour par le
  déploiement existant) ; les modifications de chaque serveur sont en base de données ; la source du thème par défaut est isolée
  derrière une interface pour pouvoir passer plus tard à un plugin du maître sans toucher aux réglages enregistrés.

## Choix du constructeur de conception (à contrôler)

- **Bootstrap 5.3 est conservé** (216 gabarits l'utilisent déjà) et habillé par un thème en variables CSS ; ni Tailwind, ni Node, ni
  Alpine.js (le script `cdn.tailwindcss.com` suggéré dans le texte de référence est un mode de démonstration, pas de production).
- Tous les fichiers (Bootstrap, HTMX, icônes, polices) sont **servis depuis le projet**, sans CDN. HTMX reste en **1.9.x** pour
  l'instant (version déjà utilisée).
- Hors du socle : le fil d'activité (« chatter »), les vues kanban et calendrier, le mode sombre (les variables du thème le prévoient,
  il n'est pas activé).

## Objectifs

1. **Une mise en page unique** (nouveau gabarit de mise en page) : barre supérieure compacte avec le sélecteur d'applications (les
   tuiles de navigation des plugins, rendues côté serveur) et les menus du module actif ; panneau de contrôle (fil d'Ariane, recherche,
   boutons) ; zone de contenu. Elle cohabite avec les cinq mises en page existantes (intra 137 gabarits, public 31, admin 22, espace 19,
   boutique 6, tous via `layout:decorate`), qui restent inchangées tant que leur domaine n'est pas migré.
2. **Gabarits réutilisables** : panneau de contrôle, vue liste (tri, sélection, recherche en direct par HTMX), vue fiche (barre de
   statut, boutons d'action selon l'état), grille de tuiles.
3. **Fichiers locaux** : Bootstrap 5.3.x (même version qu'aujourd'hui, pour ne pas changer l'aspect des gabarits existants), HTMX,
   un seul jeu d'icônes, polices hébergées.
4. **Thème et écran « Apparence »** :
   - variables CSS (couleurs, polices, épaisseur des traits, arrondis, taille du texte, densité) ;
   - un thème par défaut dans l'application ; les réglages d'un serveur en base, chacun « hérité » ou « modifié » ;
   - une page réservée à l'admin du serveur, avec sélecteurs de couleur, curseurs, liste de polices, aperçu en direct par HTMX,
     bouton « revenir à la valeur par défaut » (par réglage et pour l'ensemble) ;
   - un fichier de thème servi par le serveur à partir de ces réglages, avec cache et invalidation à chaque changement ;
   - validation stricte de chaque valeur : couleur `^#[0-9a-fA-F]{6}$`, nombres bornés (épaisseur de trait 0 à 4 px, arrondi 0 à 24 px,
     taille de texte 12 à 18 px), police prise dans une liste de polices hébergées ; aucune valeur libre ne peut produire du CSS
     arbitraire ;
   - trace des changements (qui, quand, quel réglage).
5. **Règles HTMX communes** : page complète ou fragment selon l'en-tête `HX-Request` ; mise à jour de l'adresse (`hx-push-url`) pour
   la navigation ; affichage d'un message d'erreur commun pour les réponses 4xx et 5xx ; **jeton CSRF** envoyé automatiquement par un
   gestionnaire commun sur tout appel qui modifie des données. Aujourd'hui la protection CSRF n'est active que pour une liste d'écrans
   sensibles de la chaîne web (`csrfCible()` dans `SecurityConfig`) : le socle fournit le mécanisme, et chaque domaine migré ajoute
   ses chemins à cette liste une fois ses formulaires vérifiés.
6. **Gabarits dans les plugins** : un plugin livre ses gabarits Thymeleaf (et ses éventuels fichiers statiques sûrs) dans son jar ; le
   noyau les rend avec la mise en page du socle ; ses routes renvoient des pages ou des fragments ; l'entrée de menu vient du manifeste
   comme aujourd'hui ; les routes HTML de plugin vivent sous `/p/<nom>/**` sur la chaîne web à session avec CSRF obligatoire (voir
   « Précisions »), les routes JSON `/api/plugins/**` restent inchangées. Contraintes : le moteur de gabarits actuel n'a
   qu'un seul chargeur de gabarits (`ThymeleafConfig`, chemin de classes de l'application) ; il faut un chargeur qui retrouve les
   gabarits dans le jar du plugin actif, qui respecte la mise à jour à chaud et la désinstallation (cache de gabarits invalidé, aucune
   fuite de chargeur de classes : `FuiteMemoireIT`) et qui laisse les expressions des gabarits accéder aux types du plugin.

## Hors périmètre / anti-objectifs

- Migrer les écrans existants (projets suivants) ; fil d'activité, kanban, calendrier, mode sombre.
- Toute étape de compilation front (npm, Node, Tailwind) ; tout chargement depuis un CDN ; Alpine.js.
- Un réglage de thème qui permette d'écrire du CSS libre ; un écran d'apparence accessible à un non-admin.
- Casser l'aspect des 216 gabarits existants : ils doivent continuer à s'afficher comme avant, avec leurs mises en page actuelles.

## Contraintes

- Java 25, Spring Boot 4.0.2, Thymeleaf avec le dialecte de mise en page déjà présent ; chaînes de sécurité web existantes (session) ;
  l'écran d'apparence est réservé au rôle `ROLE_ADMIN` du serveur courant.
- Chaque serveur a sa propre base : les réglages ne sont pas partagés entre serveurs.
- Toute migration de base est un **nouveau** fichier Flyway ; V11 à V17 sont pris sur les branches du lot A et du lot B ; le numéro libre
  est à confirmer au moment du plan (V18 si le lot B est fusionné avant).
- Les polices et fichiers sont servis avec les en-têtes de cache et de type corrects ; aucune dépendance externe au chargement d'une
  page du socle (vérifiable : aucune requête vers un domaine tiers).
- Le dépôt GitHub est public : aucune faiblesse non corrigée dans les messages de commit ni les descriptions de pull request.

## Approches considérées

- **Habillage : Bootstrap 5.3 thémé (retenu) ou Tailwind.** Bootstrap : un seul système de style pendant toute la migration progressive,
  pas de build, thème par variables ; Tailwind : plus de liberté de présentation, mais un second système de style à côté de Bootstrap,
  une étape de compilation (même avec l'exécutable autonome) et la réécriture des 216 gabarits.
- **Source du thème par défaut : A (retenue), B ou C.** A : livré dans l'application, rien à construire côté distribution, changement par
  publication ; B : canal signé du maître vers les serveurs, sans publication, mais nouveau canal à construire ; C : plugin du maître,
  réutilise le catalogue et la signature, mais demande des plugins sans code et l'autorisation des fichiers de polices par le chargeur
  de fichiers de plugin.

## Ce que le constructeur décide

- Nom du nouveau gabarit de mise en page, paramètres des gabarits réutilisables, noms des variables CSS, nom et format du fichier de
  thème par défaut, tables et colonnes des réglages et de la trace, jeu d'icônes, liste des polices hébergées, versions exactes des
  fichiers servis, valeurs de la palette par défaut.
- La façon de résoudre les gabarits d'un plugin (chargeur dédié, préfixe de chemin) tant qu'elle respecte les contraintes ci-dessus.
- La page de démonstration qui prouve le socle : l'accueil à tuiles du lot A, rendu côté serveur à partir de la navigation existante.

## Précisions issues de la relecture du constructeur

Réponses tirées du projet :

- **Authentification des pages de plugin.** Les routes de plugin actuelles (`/api/plugins/**`) sont sur la chaîne `angularApiFilterChain`
  (`@Order(1)`) : sans état, jeton JWT, CSRF désactivé, 401 sans jeton. Une navigation de page ou un appel HTMX porte le cookie de
  session, pas un en-tête `Authorization`. Les pages et fragments HTML des plugins ne peuvent donc pas être servis sous ce préfixe.
- **Rôle d'administrateur.** `ROLE_ADMIN` est le rôle « Administrateur » de type ESN créé par `DataInitializer` ; il protège déjà les
  réglages du site (`/intra/settings/site`), les utilisateurs, les rôles et les ESN. L'écran d'apparence réutilise la même règle
  (`hasRole("ADMIN")`) : il suit exactement la protection des autres réglages globaux du serveur, sans règle nouvelle.

Choix du constructeur de conception, à contrôler (les deux premiers touchent à la sécurité) :

- **URL et chaîne des pages de plugin :** les pages et fragments HTML d'un plugin sont servis sous un préfixe **distinct**, `/p/<nom>/**`,
  sur la **chaîne web à session**, avec un mécanisme parallèle à celui de `RoutagePlugins` (même aiguillage vers le plugin actif, même
  protection pendant la mise à jour à chaud, même réservation des chemins du noyau). Les routes JSON `/api/plugins/**` restent sur la chaîne
  JWT, inchangées. Toutes les routes `/p/<nom>/**` exigent une session authentifiée ; les droits de société restent vérifiés par le plugin
  via les contrats existants.
- **CSRF par défaut pour les plugins :** toute requête non-GET sur `/p/<nom>/**` exige le jeton CSRF, **sans liste à tenir** : le noyau
  applique la règle à tout le préfixe (un plugin ne peut pas ajouter de chemins à `csrfCible()`).
- **Mise en page et thème dans les écrans existants :** les cinq mises en page actuelles ne chargent **ni** le nouveau thème **ni** les
  nouveaux fichiers ; seul le nouveau gabarit de mise en page les charge. L'aspect des 216 gabarits ne change donc qu'au moment où leur
  domaine est migré. Le fichier de thème est servi sur un chemin public (`/theme.css`) pour que les pages de connexion et publiques
  utilisant la nouvelle mise en page le reçoivent.
- **URL de la page de démonstration et de l'écran d'apparence :** accueil à tuiles sur `/accueil` (session authentifiée, avec le sélecteur de
  société existant) ; écran d'apparence sur `/admin/apparence` (rôle ADMIN).
- **Gabarits de plugin :** noms **préfixés** par le plugin (`plugins/<nom>/…`) pour qu'un plugin ne puisse jamais remplacer ni masquer un
  gabarit ou fragment du noyau ; un gabarit de plugin peut utiliser la mise en page et les fragments du noyau (`layout:decorate`,
  `th:replace`), jamais l'inverse. Les plugins sont signés et de confiance : les expressions de leurs gabarits peuvent accéder à leurs
  propres types, sans bac à sable supplémentaire.
- **Thème par défaut « hérité » :** lu en direct, jamais copié ; le cache du fichier de thème est invalidé à chaque changement d'un réglage
  et à chaque démarrage (son identifiant de version est dérivé du fichier par défaut et de la date du dernier réglage).

## Projets suivants (non détaillés ici)

2. Écrans des lots A et B sur le socle : accueil à tuiles, plugin `clients`, plugin `partenaires` ; retrait du chargeur de front Angular et
   du service de fichiers `ui/` de la branche du lot A (pull request #1 encore en brouillon) ; adaptation du test de bout en bout du lot B.
3. Migration progressive du reste, un domaine à la fois : authentification et mon-compte, portail, tickets, customers, admin, marketing,
   avec la modernisation de l'apparence des gabarits existants.

# Plugin clients en pages Thymeleaf, arrivée sur /accueil et en-tête connecté

Date : 2026-11-25. Suite du socle Thymeleaf+HTMX (2026-11-22) et du lot B (partenaires). Le propriétaire veut voir un site cohérent,
sans mélange entre l'ancien habillage et le nouveau.

## Intention

1. Après connexion, tout le monde arrive sur `/accueil` (aujourd'hui : un administrateur va sur `/admin/console`, un utilisateur ESN sur
   `/intra`, les autres sur `/espace`).
2. L'en-tête du site public, pour un utilisateur connecté, a un lien « Mon espace » vers `/accueil`. Le site public et `/accueil` sont
   séparés : le socle n'affiche pas le menu public.
3. L'interface du plugin `clients` (lot A, encore en Angular) est reconstruite en pages Thymeleaf, comme `partenaires` : liste des
   clients d'un partenaire, création d'un client, fiche cliente. Depuis la fiche, le partenaire peut « entrer » dans l'espace du client.

## Hors périmètre (décidé par le propriétaire)

- Pas de liste déroulante ni de recherche de clients dans l'en-tête : « /accueil seul pour l'instant, le partenaire accède au client
  depuis la fiche cliente, on rajoutera si besoin ensuite ».
- Pas de changement de contexte de session : « entrer » n'a aujourd'hui aucun effet de session (le noyau calcule seulement le périmètre
  accordé : société cliente et droits effectifs). Il n'existe pas encore d'espace client où atterrir ; c'est une suite possible.
- Pas de plugin boutique ni des autres modules maintenant : ils viendront plus tard (la boutique avec accès public, client et partenaire).
- Pas de migration des anciennes mises en page (137 pages `intra-layout`, `layout`, `admin-layout`, `espace-layout`, `boutique-layout`).
- Pas de changement du contrat du noyau `AnnuaireClientsContrat` ni de ses contrôles (droits, rattachement du client au partenaire).

## Approches considérées

- **A (retenue)** : pages Thymeleaf dans le plugin, « Entrer » affiche le périmètre obtenu. Rapide, cohérent avec `partenaires`, ne touche pas
  la sécurité du noyau, supprime le front Angular et le build npm du plugin.
- B : A plus un vrai changement de contexte de session. Écartée pour l'instant : modifie l'identité de session du noyau, relecture de
  sécurité lourde, besoin non confirmé.
- C : garder Angular et le restyler. Écartée : le build npm fait planter la machine de développement (8 Go).

## Conception

### 1. Redirection après connexion (noyau)
`CustomTenantSuccessHandler.destinationApresConnexion` : le cas « mot de passe à changer » (`/change-password`) et le cas de la connexion
venant d'Angular restent inchangés ; tous les autres renvoient `/accueil`. Le cookie JWT court posé pour l'administrateur et l'utilisateur
ESN (SSO de l'ancienne application) reste posé : seul le choix de la destination change. Tests de la classe mis à jour (admin, ESN,
autre → `/accueil`).

### 2. En-tête connecté
Décision du propriétaire : **les pages publiques et `/accueil` sont séparées.** Le site public garde son en-tête (menu public) ; `/accueil`
et les pages des plugins utilisent le socle, **sans menu public**. La boutique (accès public, client, partenaire) puis les autres modules
deviendront plus tard des plugins, chacun avec ses propres pages.
- `header/connected.html` (en-tête du site public, affiché quand l'utilisateur est connecté) : le lien « Mon Espace » pointe vers
  `/accueil` au lieu de `/espace`. Les autres boutons (Admin, Application, Espace ESN) restent inchangés.
- `socle/fragments/topbar.html` : **aucun changement** (pas de menu public dans le socle).

### 3. Plugin `clients` en Thymeleaf
- Routes (modèle de `PartenairesPagesController`) : `@Controller` sous `/p/clients/…` : liste, formulaire de création, fiche d'un client,
  action « Entrer » (POST, CSRF) qui appelle `AnnuaireClientsContrat.entrerEspaceClient` et affiche le périmètre accordé.
- Les contrôles existants du contrôleur JSON sont conservés tels quels dans les pages : droit `clients.lire` / `clients.creer` /
  `clients.entrer` sur SA société, `404` identique pour un client inconnu ou d'un autre partenaire, `403` vide sans droit, messages neutres
  (« société déjà connue », « e-mail déjà utilisé ») sans identité d'une autre société. Un refus de « Entrer » est un 404 identique.
- Le plugin JSON `@RestController` et le front Angular (`frontend/`, build npm du pom) sont retirés ; le manifeste garde
  `entree.1=PARTENAIRE|Clients|people|/liste|clients.lire` (le chemin devient celui de la page liste) et le bloc `resume` si encore utilisé.
- Validation des champs à la création comme pour les partenaires : SIREN (clé de contrôle), TVA, e-mail, nom ; message d'erreur par champ.
- Version du plugin : 1.1.0. La publication se fait par `signer.ps1` puis `/admin/plugins`, avec la clé de production du propriétaire (jamais
  dans la session).

## Tests attendus
- Unitaires : destination après connexion ; pages du plugin (droits, 403/404, création valide/invalide, « Entrer » refusé et accordé).
- Intégration sur la vraie chaîne (modèle `PluginPartenairesIT`) : le jar construit, signé avec une clé de test, installé ; pages joignables
  seulement avec le droit ; CSRF exigé sur les POST.

## Questions laissées au constructeur
- Que montre exactement la page après « Entrer » (société cliente, droits effectifs) : texte simple du périmètre, sans lien vers un espace
  qui n'existe pas encore.
- Existe-t-il d'autres liens vers `/espace` ou `/admin/console` à rediriger vers `/accueil` : seulement ceux de l'en-tête et de la
  connexion sont demandés.

## Risques à relire
- Une redirection unique vers `/accueil` pour tous les rôles : un utilisateur sans société ni droit voit-il un accueil vide cohérent ?
- Plugin : fuite d'existence d'un client d'un autre partenaire (le 404 doit rester identique en toutes circonstances).

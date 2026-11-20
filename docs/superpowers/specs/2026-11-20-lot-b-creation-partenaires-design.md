# Lot B, sous-projet 1 : création de partenaires et création de clients par le maître

Date : 2026-11-20. Suite du lot A (branche `cadre-clients-lot-a`, spec `2026-11-18-cadre-plugins-gestion-clients-design.md`).
Ce document est volontairement limité au premier sous-projet du lot B. Les suivants sont listés en fin de document.

## Intention

Aujourd'hui aucun parcours ne crée un partenaire. Or un partenaire sans tenant ne peut plus créer de clients (le lot A refuse
la création quand la société partenaire n'a pas de tenant propriétaire valide). Il faut donc un moyen de créer un partenaire
complet, utilisable tout de suite, et permettre à l'admin du serveur maître de créer des clients au nom d'un autre partenaire.

## Décisions du propriétaire

- Quand un partenaire est créé avec son compte utilisateur, **un tenant est créé dans SQL** (schéma propre).
- Un partenaire est **actif tout de suite** et **peut créer des clients** dès sa création (pas de période en lecture seule).
- L'**admin du serveur maître** peut **créer des clients pour un autre partenaire**.
- La création de partenaires passe par un **plugin « partenaires » installé sur le maître** ; le noyau ne contient que le moteur
  (service + contrat). Migrer vers un plugin plus tard ne doit rien déplacer : c'est la conception retenue dès le départ.
- Création de partenaires par l'inscription autonome ou l'invitation par un autre partenaire : **plus tard** (route publique,
  risques d'abus propres) ; ce sous-projet couvre la création par l'admin du maître.

## Objectifs

1. Un service du noyau crée un partenaire complet **en une seule transaction** :
   - société avec la capacité `PARTENAIRE` ;
   - premier utilisateur, manager de cette société (`ROLE_GESTIONNAIRE` via `ManagersService`) ;
   - tenant et son schéma SQL (annulés avec le reste si une étape échoue) ;
   - société rattachée à ce tenant comme tenant propriétaire (`proprietaire_tenant_id`) ;
   - droits de départ : plafond et réglage manager issus d'un **modèle par défaut** modifiable par l'admin du maître ;
   - état `ACTIF` et date de début de la période de validation des documents (30 jours), enregistrés mais **pas encore appliqués**.
2. Un contrat du noyau, publié aux plugins (comme `AnnuaireClientsContrat`), expose :
   - `creerPartenaire(...)` ;
   - `creerClientPourPartenaire(...)` : création d'un client au nom d'un partenaire cible.
3. Un plugin `partenaires` (maître uniquement) offre les routes et un écran minimal pour ces deux actions, avec son interface dans
   son jar (même mécanisme que le plugin `clients`).
4. L'admin du maître peut modifier le modèle de droits par défaut des nouveaux partenaires (au minimum par une route du plugin).

## Hors périmètre / anti-objectifs

- Inscription autonome d'un partenaire, invitation par un partenaire, commission d'invitation.
- Application du passage automatique en `LIMITE` ou `BLOQUE` après 30 jours (suivi des documents : sous-projet ultérieur).
- Écran d'administration fin des plafonds et réglages par partenaire (sous-projet suivant, dans le même plugin).
- Ne doit jamais devenir : un moyen pour un plugin de créer des schémas, des comptes ou des droits lui-même ; un moyen pour un
  partenaire ordinaire de créer des clients pour un autre partenaire.

## Contraintes

- **Aucune migration appliquée n'est modifiée** : tout changement de schéma est un nouveau fichier Flyway. V11 à V16 sont pris par
  le lot A (V16 = journal d'accès des partenaires) ; ce sous-projet commence à **V17**.
- Le nom du schéma est **généré par le serveur** (dérivé de l'identifiant de la société, jamais saisi) et validé par une
  expression régulière stricte avant d'être utilisé dans une instruction SQL.
- La création du schéma doit utiliser la **connexion de la transaction en cours** pour être annulée avec elle. L'actuel
  `TenantManagementService.createNewTenant` ouvre sa propre connexion, ne renvoie pas le tenant et ne crée qu'une table
  `contacts` : il doit être étendu ou remplacé par une méthode qui répond à ces trois points. Le comportement existant de
  `createNewTenant` (appelants, événement `TenantEnCreationEvenement`) ne doit pas régresser.
- Le noyau vérifie **lui-même** que l'appelant de `creerClientPourPartenaire` est un admin du serveur maître (rôle `ROLE_ADMIN` et
  `app.role=MASTER`), à partir de l'identité de la requête, jamais d'un paramètre fourni par le plugin. Sinon : refus (403
  côté plugin), sans rien écrire.
- Le partenaire cible est une société existante avec la capacité `PARTENAIRE` et un tenant propriétaire valide ; sinon refus.
- Chaque action de l'admin du maître est **journalisée** : qui, pour quel partenaire cible, quel client créé, quand.
- Le compte du client créé pour un partenaire cible vit dans le **tenant de ce partenaire cible**.
- Le plugin `partenaires` n'est chargé que sur un serveur dont `app.role=MASTER` ; il suit les règles de signature et de mise à
  jour à chaud existantes.
- Le plugin ne dépend que du SDK (jamais du code du noyau) ; mêmes règles d'échec fermé que le plugin `clients` : identité
  absente, droit absent ou société absente donnent une réponse vide 403 sans lecture de données.

## Approches considérées

- **A. Une seule transaction dans le noyau, tenant compris (retenue pour le moteur).** Atomique grâce au DDL transactionnel de
  PostgreSQL ; pas d'état intermédiaire. Compromis : lent si un jour le schéma d'un tenant demande de longues migrations.
- **B. Deux temps** (société et utilisateur d'abord, schéma après, avec reprise) : plus robuste face à de longues migrations
  mais ajoute un état « tenant prêt » et contredit « actif tout de suite ». Écartée pour l'instant.
- **C. Tout dans un plugin :** écartée telle quelle, car un plugin ne doit pas créer de schémas ni de comptes. Retenue sous la
  forme : moteur dans le noyau (A) + plugin pour les routes et l'interface (étage 2).

Choix du propriétaire : approche C, à condition de pouvoir migrer plus tard ; satisfait par « moteur dans le noyau, interface
dans un plugin ».

## Ce que le constructeur décide

- Noms précis des méthodes, des enregistrements de contrat et des tables (journal, modèle de droits par défaut, état du partenaire).
- Format du nom de schéma (par exemple `p_<id>`), tant qu'il est généré, unique, validé et stable.
- Le jeu exact de droits du modèle par défaut (au minimum : lecture et création de clients, entrée dans l'espace d'un client).
- La forme minimale de l'écran du plugin, dans le style de l'interface du plugin `clients`.
- Comment un premier mot de passe est obtenu pour le compte créé (réutiliser « mot de passe oublié » comme pour les clients du
  lot A, sauf meilleure solution simple déjà présente dans le code).

## Précisions issues de la relecture du constructeur

Réponses tirées du projet :

- **Vérification « admin du maître » :** le contrat d'identité du lot A donne déjà `IdentiteCourante(userId, roles, societeId, tenantId,
  roleServeur)`. Le noyau lit `roles` (contient `ROLE_ADMIN`) et `roleServeur` (vaut `MASTER`) depuis ce contrat, jamais depuis un
  paramètre. Pas de lecture directe du contexte de sécurité dans le service.
- **Contexte de tenant :** le compte du client est créé avec le tenant du partenaire cible posé explicitement sur l'entité
  (`user.setTenant(...)`), comme le fait déjà la création de clients du lot A ; aucune écriture dans le schéma du tenant n'est
  nécessaire pour un client. Le contexte de tenant de la requête (celui du maître) n'est pas utilisé pour ces écritures.
- **Schéma d'un tenant :** aujourd'hui `createNewTenant` ne crée que le schéma et la table `contacts` ; aucune migration par schéma de
  tenant n'existe (Flyway ne gère que les schémas des plugins). Il n'a aucun appelant dans le code, seulement l'événement
  `TenantEnCreationEvenement` qu'il publie. Il peut donc être étendu (connexion de la transaction, valeur de retour, validation du
  nom) sans régression ; l'événement reste publié.
- **Premier mot de passe :** comme pour les clients du lot A, mot de passe aléatoire inutilisable, le titulaire passe par le parcours
  « mot de passe oublié » (`PasswordResetController`). Une invitation par e-mail dédiée relève du sous-projet « comptes plus sûrs ».

Choix du constructeur de conception, à contrôler :

- **Entrées de `creerPartenaire` :** SIREN (obligatoire, société française, comme un client), TVA facultative, raison sociale, nom et
  e-mail du premier manager. La raison sociale est enregistrée dans le tenant (`Tenant.name`), la société n'en stocke pas.
- **Doublon de SIREN pour un partenaire :** l'action de l'admin échoue avec un message clair (pas de demande d'accès à un manager,
  contrairement à l'inscription d'un client).
- **Droits du partenaire lui-même :** les mêmes tables que pour un client (plafond et réglage par société, rôle manager
  `ROLE_GESTIONNAIRE`), remplies pour la société du partenaire à partir du modèle par défaut. Le modèle est **copié à la création** :
  modifier le modèle n'affecte pas les partenaires existants (leurs droits se changent par l'outil du sous-projet suivant).
- **Admin du maître et droits du partenaire cible :** l'admin du maître n'est pas membre de la société cible ; il ne passe pas par
  le contrôle `autorise` du partenaire cible (autorité du maître). Il reste soumis aux vérifications de la cible (partenaire
  existant, capacité `PARTENAIRE`, tenant valide). Un futur état `BLOQUE` d'un partenaire pourra l'interdire : sous-projet ultérieur.
- **Journal :** une nouvelle table (migration V17) pour les actions de l'admin du maître, qui enregistre à la fois la création d'un
  partenaire et la création d'un client pour un partenaire (qui, cible, objet créé, date). Ce n'est pas le journal d'accès du lot A.
- **Modèle de droits par défaut :** stocké en base (nouvelle table, migration V17 ou suivante), initialisé avec la lecture et la
  création de clients et l'entrée dans l'espace d'un client ; modifiable par l'admin du maître via le plugin.

## Sous-projets suivants du lot B (non détaillés ici)

1. Outil d'administration des plafonds et réglages par société (dans le plugin `partenaires`).
2. Création de comptes plus sûre : invitation par e-mail, validation par le titulaire de l'adresse, trace du partenaire créateur,
   limitation de débit, rôles portés par l'appartenance à la société plutôt que par les rôles globaux.
3. Entrer dans l'espace d'un client : changement de session ou d'écran, droits effectifs appliqués côté serveur.
4. Inscription autonome des clients et des partenaires ; reprise des anciennes appartenances sans société ; notification de
   l'ancien partenaire lors d'un changement (listener `AFTER_COMMIT`).
5. Suivi des documents et états `LIMITE` / `BLOQUE` des partenaires.

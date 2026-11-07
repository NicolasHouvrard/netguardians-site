# Connexion déléguée au Maître : conception

Statut : **brouillon à valider** (rien n'est codé). Écrit le 2026-11-07.

## Intention

Aujourd'hui chaque serveur (Maître, VPS partenaire, futurs serveurs clients) a sa propre base d'utilisateurs : un compte créé sur le Maître n'existe pas sur le VPS. L'objectif est que le **Maître fasse foi pour tous les identifiants** : un utilisateur se connecte sur le serveur de son tenant avec ses identifiants du Maître, sans recréer son compte.

## Décisions prises par le propriétaire (reprises telles quelles)

1. Le Maître regroupe tous les identifiants.
2. Un identifiant est relié à un **tenant**, un tenant est relié à un **serveur** (partenaire ou client).
3. Tous les membres des tenants rattachés au serveur partenaire (le VPS) peuvent s'y connecter, ainsi que l'administrateur (partenaire par défaut), qui accède en plus à la partie « admin public ».
4. Si le compte n'existe pas en local, le serveur demande au Maître.
5. Visibilité : l'**administrateur** voit tous les tenants (partenaires et clients) ; un **partenaire** voit son tenant et ceux de ses clients ; un **client** voit uniquement son tenant.
6. Un nouveau partenaire ou client est **affecté par défaut au VPS**. S'il crée son propre serveur, il est **redirigé vers son serveur**.
7. Hors périmètre, à faire dans un second temps : un serveur de connexion dédié, adossé à un annuaire et à un code à usage unique (OTP). La conception ne doit pas l'empêcher.

## Buts, non-buts, anti-buts

- **Buts** : un seul jeu d'identifiants ; rattachement tenant → serveur ; redirection vers le serveur d'un tenant ; visibilité selon la hiérarchie ci-dessus.
- **Non-buts** : migration des données d'un tenant d'un serveur vers un autre (le déplacement quand un client crée son serveur est un chantier à part) ; serveur d'annuaire / OTP ; reprise des comptes de l'ancienne base.
- **Anti-buts** (ce qui rendrait la solution mauvaise même si elle « marche ») : un serveur compromis qui permettrait de collecter les mots de passe de comptes qui n'ont rien à y faire ; une réponse différente selon que l'identifiant existe ou non (énumération de comptes) ; un mot de passe stocké ou journalisé hors du Maître.

## Contraintes existantes (vérifiées dans le code)

- Le module de synchronisation actuel (`features.sync`) ne transmet que les demandes, **pas les comptes**.
- `LicenceServeur` porte `compteId`, `customerId`, `adresseDeclaree` (adresse Headscale) et une clé API ; **aucun lien tenant → serveur** n'existe. `Tenant` ne porte que `customerId`.
- Les routes `/api/licence/**` sont déjà protégées par la clé API de licence du serveur appelant (`LicenceApiKeyAuthFilter`).
- Après une connexion réussie, l'application lit `user.getTenant().getSchemaName()` pour choisir le schéma : un utilisateur **sans tenant** provoque une erreur 500. Le tenant est donc obligatoire.
- L'application sait aujourd'hui travailler avec **un seul schéma de tenant par session**. Voir un tenant « fils » (partenaire → clients) demande soit un sélecteur de tenant, soit une vue agrégée : à explorer.

## Conception proposée (mes choix, marqués « mon choix » : à valider)

### 1. Modèle (sur le Maître)
- `Tenant.serveurLicenceId` : serveur d'accueil du tenant. **Valeur par défaut des nouveaux tenants : le serveur désigné « serveur par défaut » (le VPS)** (mon choix : réglage unique modifiable par l'administrateur).
- `Tenant.tenantPartenaireId` : pour un tenant client, le tenant partenaire dont il dépend.
- `LicenceServeur.urlPublique` : adresse HTTPS publique du serveur (ex. `https://app.logicielpourtous.com`), distincte de l'adresse Headscale, utilisée pour la redirection du navigateur.
- Écran d'administration sur le Maître pour renseigner ces trois valeurs.

### 2. Connexion déléguée
- **Point d'entrée Maître** : `POST /api/licence/authentifier` (identifiant, mot de passe), authentifié par la clé API du serveur appelant. Le Maître vérifie le mot de passe, que le compte est actif, puis compare le serveur d'accueil du tenant au serveur appelant :
  - même serveur → **succès** : renvoie e-mail, nom, rôles, tenant (nom, schéma) ;
  - autre serveur → **redirection** : renvoie l'`urlPublique` du serveur d'accueil, sans identité ;
  - tout autre cas (inconnu, mauvais mot de passe, compte inactif) → **même échec, même délai** (pas d'énumération).
- **Repli côté serveur secondaire** : si l'identifiant est inconnu **en local**, appel du Maître. En cas de succès, création ou mise à jour d'un utilisateur local « fantôme » (mon choix : **mot de passe local inutilisable**, donc chaque connexion d'un compte fédéré est validée par le Maître : une révocation ou un changement de mot de passe sur le Maître est immédiat). En cas de redirection, le navigateur est renvoyé vers l'adresse reçue, sans identifiants.
- **Maître injoignable** (mon choix) : les comptes fédérés ne peuvent pas se connecter, message clair ; les comptes locaux (ex. administrateur local) restent utilisables.
- **Anti-force-brute** : limitation du nombre d'essais par identifiant et par serveur appelant côté Maître ; journal d'audit des tentatives (sans mot de passe).

### 3. Visibilité
- Le Maître renvoie aussi la liste des tenants visibles (administrateur : tous ; partenaire : le sien et ses clients ; client : le sien).
- Côté application : filtre selon cette liste. **Inconnu à explorer** : comment afficher plusieurs tenants dans une session (sélecteur, vue agrégée).

## Risque principal à décider : l'administrateur sur le VPS

Si l'administrateur du Maître se connecte au VPS par délégation, le VPS reçoit **son mot de passe en clair**. Un VPS compromis (il est exposé sur Internet) permettrait de le collecter. Le même risque existe pour tout membre, mais il est plus lourd pour l'administrateur, puisque ce mot de passe ouvre aussi le Maître.

Options :
1. **(recommandé pour l'instant)** Ne pas déléguer les comptes administrateur : l'administrateur garde un **compte local distinct sur chaque serveur**, avec un autre mot de passe. La délégation concerne les membres des tenants.
2. Autoriser la délégation de l'administrateur, avec un réglage par serveur (désactivé par défaut), en attendant l'OTP.
3. Autoriser sans réserve : non recommandé.

## Points laissés au réalisateur
Format exact des réponses ; nom des colonnes (le projet utilise des noms en minuscules sans soulignés, voir `V2__ordres_deploiement.sql`) ; politique de limitation d'essais ; durée de vie de la session d'un compte fédéré.

## Livraison en trois étapes
1. **Modèle** et écran d'administration (tenant → serveur, hiérarchie, URL publique, serveur par défaut).
2. **Connexion déléguée** (point d'entrée Maître, repli secondaire, redirection, audit, limitation d'essais).
3. **Visibilité** (règles partenaire / client, après exploration de l'affichage multi-tenant).
Chaque étape est livrée, déployée et validée avant la suivante.

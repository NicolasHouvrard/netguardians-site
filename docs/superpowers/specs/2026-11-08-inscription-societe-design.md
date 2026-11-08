# Inscription d'une société : identité, unicité, invitation, transfert

Statut : **brouillon à valider** (rien n'est codé). Écrit le 2026-11-08.

## Intention

Éviter les doublons de sociétés sur la plateforme et donner à chaque société un seul propriétaire (un partenaire à la fois), en rendant obligatoire une identité fiable à l'inscription : SIREN ou SIRET, et numéro de TVA. Une personne qui arrive pour une société déjà connue est orientée vers une invitation, jamais vers la création d'un doublon.

## Décisions du propriétaire (reprises telles quelles)

1. Un client s'inscrit seul sur la plateforme `app` ; il enregistre sa société avec **SIREN ou SIRET, et TVA obligatoire**, pour éviter les doublons.
2. Côté **Maître**, on peut enregistrer une société (**même contrainte**) et **inviter une personne** dans la société.
3. **SIREN déjà connu** : on renvoie vers une **invitation**.
4. **Société étrangère** : on saisit la **TVA** (européenne). Plus tard, elles ne seront que des **fournisseurs de matériel**, jamais des partenaires ni des clients.
5. **Société déjà dans la liste d'un partenaire** : l'inscripteur est renvoyé vers son partenaire. Le message public reste **neutre** (aucune relation commerciale n'est révélée à un inconnu).
6. **Exclusivité** : une société n'a qu'**un seul partenaire à la fois**. Si un partenaire B reprend le client d'un partenaire A, B envoie un **lien de transfert au client** ; A reçoit une **notification**. Il y a un **engagement d'un an entre deux transferts** d'une même société.
7. La **bascule** vers un sous-domaine ou un serveur propre est la phase 2 ; la gestion des **domaines des clients** viendra plus tard ; l'automatisation de l'installation vient **après** ce chantier.

## Buts, non-buts, anti-buts

- **Buts** : identité SIREN/SIRET/TVA contrôlée ; une société = un enregistrement unique sur toute la plateforme ; inscription client autonome ; enregistrement et invitation par le Maître ; renvoi vers l'invitation en cas de doublon.
- **Non-buts (ce chantier)** : le **transfert entre partenaires** (ses règles sont fixées ci-dessous mais il se réalise après) ; la bascule vers un serveur propre ; la vérification auprès de l'INSEE ou de VIES (peuvent venir plus tard) ; la reprise de données de l'ancienne base.
- **Anti-buts** : révéler à un inconnu qu'une société est cliente de tel partenaire ; permettre la création d'un doublon par une variante de saisie (espaces, points, majuscules, SIRET au lieu de SIREN) ; énumérer les SIREN existants par des réponses différentes selon le cas.

## Constat dans le code (vérifié)

- `AuthController` : **deux chemins**. Un **client** s'inscrit seul (création d'une société cliente, d'un siège, d'un contact et d'un compte de portail `GESTIONNAIRE`, avec vérification par e-mail). Une **ESN** dépose une `SubscriptionRequest` (société, contact, e-mail, téléphone, formule) que l'administrateur approuve dans `/intra/registrations`.
- **Seul l'e-mail** est contrôlé contre les doublons. **Ni SIREN ni TVA** ne sont demandés.
- Les colonnes `siren`, `siret` et `vat_number` existent dans plusieurs tables (`customers`, `tenants`, etc.) **sans aucune contrainte d'unicité**.
- Les noms de colonnes de `licence_serveurs` n'ont pas de soulignés ; ceux de `tenants` en ont (voir le plan précédent).

## Conception proposée (mes choix : à valider)

### 1. Contrôle des numéros (sans service externe)
- **Normalisation** : on ne garde que les chiffres (espaces, points, tirets retirés) ; la TVA en majuscules sans espaces. Un **SIRET** (14 chiffres) fournit son **SIREN** (9 premiers).
- **SIREN et SIRET** : longueur et clé de **Luhn**. Une **exception connue** existe (les SIRET de La Poste ne respectent pas Luhn) : à gérer comme cas particulier documenté.
- **TVA française** : `FR` + clé à 2 chiffres + SIREN, avec `clé = (12 + 3 × (SIREN mod 97)) mod 97` ; la TVA saisie doit être **cohérente avec le SIREN**.
- **TVA européenne étrangère** : préfixe pays à 2 lettres + suite alphanumérique de longueur plausible ; contrôle de format uniquement.

### 2. Unicité globale
- Un **registre unique des identités de société** (table dédiée, une ligne par société) porte l'unicité sur **toute la plateforme**, quel que soit le type de société (client, partenaire, fournisseur) : un même SIREN ne peut pas être à la fois un client et un partenaire. Il porte aussi le type, le partenaire propriétaire et le lien vers la fiche de la société.
- Index uniques sur le SIREN normalisé (sociétés françaises) et sur la TVA normalisée (sociétés étrangères).
- **Migration** : avant d'ajouter les contraintes sur les données existantes, produire un **rapport des doublons** ; aucune fusion automatique.

### 3. Inscription autonome d'un client
- Le formulaire demande SIREN ou SIRET, et TVA. Contrôles de la section 1.
- **Société libre** : création de la société (avec son enregistrement au registre) et du premier utilisateur, **administrateur de sa société**, avec vérification par e-mail comme aujourd'hui.
- **Société connue** : **jamais de création**. Message **neutre et identique** quel que soit le cas (société déjà inscrite, ou déjà suivie par un partenaire) : « cette société est déjà connue de la plateforme, une demande a été transmise à son responsable ». Une notification part vers l'administrateur de la société s'il existe, sinon vers le **partenaire propriétaire** (sans que le demandeur apprenne son nom). La réponse (message et délai) ne distingue pas les cas.

### 4. Inscription d'un partenaire (ESN)
- La demande reprend SIREN/SIRET et TVA, avec les mêmes contrôles et les mêmes refus de doublon ; l'approbation par l'administrateur ne change pas.

### 5. Enregistrement par le Maître et invitation
- Écran du Maître : créer une société (mêmes contrôles et unicité), puis **inviter une personne**.
- **Invitation** : jeton aléatoire non devinable (stocké haché), lié à la société, à l'e-mail et au rôle, valable **7 jours**, **à usage unique** ; la personne l'ouvre et crée son accès.

### 6. Transfert d'un client entre partenaires (réalisé après ; règles fixées ici)
- Le partenaire B envoie au **client** un **lien de transfert** ; le client **confirme**. Le partenaire A est **notifié**.
- **Un an minimum entre deux transferts** d'une même société : un nouveau transfert est refusé tant que le délai n'est pas écoulé.
- Les données déjà produites pour A (devis, factures, historique comptable) restent chez A ; le sort exact de ce qui suit le client est à trancher avant ce chantier.

## Points ouverts
- **Début du délai d'un an** : à partir du dernier transfert, ou aussi à partir du premier rattachement d'une société à son partenaire ?
- Une **personne peut-elle appartenir à plusieurs sociétés** (un même e-mail invité dans deux sociétés) ?
- Que **devient une société étrangère** sans partenaire (propriétaire : le Maître) ?
- Exception de La Poste et format TVA par pays : liste exacte à arrêter à la réalisation.

## Livraison en quatre étapes
1. **Identité** : contrôle des numéros, registre unique, contraintes d'unicité, rapport de doublons.
2. **Inscription autonome** : client et partenaire, avec la gestion neutre des sociétés connues et la notification.
3. **Enregistrement par le Maître et invitation.**
4. **Transfert entre partenaires** (avec le délai d'un an).
Chaque étape est livrée, déployée et validée avant la suivante.

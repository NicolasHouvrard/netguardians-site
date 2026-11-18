# Cadre des plugins (tuiles, zones, droits) et plugin « gestion des clients » — conception

Date : 2026-11-18. Statut : à relire par le propriétaire du produit.
Projet suivant dans l'ordre convenu : demandes, site public, LDAP/mail/Nextcloud (hors de ce document).

## 1. Intention

Le site Spring Boot actuel (`site-depot-neuf`) est en cours de passage à une architecture à plugins
(noyau léger + plugins jar signés, distribués par le serveur Maître ; SP1 et SP2 livrés et validés en
conditions réelles le 2026-11-18). Ce document définit :

1. le **cadre** par lequel les plugins se présentent à l'utilisateur (tuiles, zones, droits) ;
2. le premier plugin construit dessus : la **gestion des clients**, refaite sur une base propre.

Pourquoi : la structure Spring actuelle est jugée « fouillie » par le propriétaire (trois concepts qui se
recouvrent pour « une société » : `Tenant`, `Customer`, `SocieteIdentite`, plus l'ancien `PartnerEsn` ;
`Customer` mélange client, fournisseur, identité légale et clés d'API). Le modèle de référence est celui
déjà construit sous Firebase (projet `Nerguardians-pocket`, à ne jamais modifier), repris dans ses idées et
non dans son code. La base de production Spring est vide : **aucune migration de données**, on repart de zéro.

## 2. Objectifs, non-objectifs, anti-objectifs

**Objectifs**
- Une navigation à la Odoo : des **tuiles**, une par plugin, avec ses sous-ensembles, filtrées par les droits.
- Un plugin peut exister dans plusieurs **zones** (publique, client, partenaire, admin) avec des écrans différents.
- Un modèle de société unique, avec des **capacités** cumulables (client, partenaire, fournisseur).
- Droits évalués **par serveur**, avec un plafond fixé par le partenaire et des réglages fixés par le manager client.
- Migration d'un client d'un serveur à l'autre la plus transparente possible, sans import de base manuel.

**Non-objectifs (volontairement reportés)**
- Demandes, site public / boutique, LDAP, comptes mail et Nextcloud : projets séparés.
- Marché public d'interventions sur site entre partenaires.
- Commission du partenaire sur les partenaires qu'il a invités (seul le lien « invité par » est conservé).
- Séquestre des clés de chiffrement sur le Maître (déjà différé, voir mémoire du projet).

**Anti-objectifs (un échec même si ça « marche »)**
- Un partenaire qui obtient, par un détour de navigation, des droits supérieurs à ceux qu'il a fixés au client.
- Un lien de l'espace client qui ramène sur un écran réservé au partenaire (fuite déjà rencontrée sous Firebase).
- Des données personnelles de clients remontées au Maître alors que seuls des signaux devraient l'être.
- Un « méga-objet société » à cases à cocher qui recrée le désordre actuel.

## 3. Contraintes

- Spring Boot 4, Java 25, PostgreSQL, Flyway, front Angular `frontend/intra`.
- Les plugins respectent la plateforme livrée : manifeste `plugin.properties`, jars signés Ed25519, espace de
  paquets réservé `com.logicielpourtous.site.plugins.<plugin>`, routes `/api/plugins/<nom>/...`, mise à jour à chaud.
- Droits évalués **par serveur** : un partenaire admin n'a aucun pouvoir sur un autre serveur, sauf sur les
  serveurs de ses propres clients.
- RGPD : les données d'un client restent sur son serveur ; seuls des signaux (anomalies, actions à voir)
  remontent vers le Maître. L'admin du Maître accède aux autres serveurs **à la demande** ; un client sur son
  propre serveur y accède en direct.
- Chaque serveur a sa propre clé de chiffrement (décision du 2026-11-05) : un transfert déchiffre puis rechiffre.
- Le LDAP central (futur) aidera aux droits ; aucune décision sur ce qu'il stockera n'est prise ici.

## 4. Le cadre : tuiles, zones, droits

Décisions du propriétaire :
- Chaque plugin déclare dans son manifeste des **entrées** : zone (publique | client | partenaire | admin),
  libellé, icône, chemin, droit requis. Un plugin peut déclarer des entrées dans plusieurs zones.
  Exemple cité : « demandes » — le client crée et liste ses demandes ; le partenaire crée, liste et agit à
  partir d'une demande ; l'admin ou le partenaire admin paramètre le plugin (dont le formulaire public).
- Un plugin déclare aussi des **blocs de fiche client** : morceaux de tableau de bord ajoutés à la fiche client
  vue par le partenaire (données fiscales, factures, demandes, …). La fiche s'enrichit au fil des plugins.
- Deux administrations distinctes : le **grand admin** (gère le Maître) et le **partenaire admin** (gère son
  propre serveur et les plugins qu'il y a récupérés).
- Un utilisateur arrive par défaut dans son espace client ; s'il est partenaire, il peut parcourir les espaces
  de ses clients. L'adresse publique d'un serveur est un réglage ; un partenaire qui a son serveur gère son
  adresse publique et son contenu public.

**Droits : trois couches empilées.** Plafond fixé par le partenaire pour un client → réglages du manager client
dans ce plafond → droits individuels des utilisateurs dans ces réglages. Chaque couche ne peut qu'ajuster
dans la limite de la précédente. (Le modèle Firebase `reglesParModule` plafonné par la configuration sert de
référence d'idée.)

**À la charge du constructeur** : forme exacte du manifeste d'entrées/blocs, livraison du front Angular des
plugins (non évaluée : Native Federation ou autre), contrat d'identité exposé aux plugins (« contrat R17 »),
mécanisme technique d'évaluation des droits.

## 5. Modèle de la gestion des clients

Quatre notions séparées, sur base propre (aucun champ repris de l'ancien `Customer` par défaut) :

- **Société** : identité légale unique (nom, SIREN, TVA, adresse). Dédoublonnée par SIREN.
- **Capacités de la société**, cumulables, un petit enregistrement distinct par capacité : **client**,
  **partenaire** (un partenaire est un client avec des fonctionnalités en plus), **fournisseur** (porte par
  exemple ses clés d'API). Une société peut être client et fournisseur ; un client peut devenir partenaire sans
  nouvelle fiche. Pas de cases à cocher sur la société.
- **Utilisateur** : une personne avec un compte ; peut appartenir à plusieurs sociétés (rôle de l'actuelle
  `Appartenance` comme idée de départ).
- **Accès délégué** : droits d'un utilisateur sur une société.

**Relation client → partenaire.** Un client a **un seul partenaire référent**, contact unique. Chaque serveur a
un **partenaire par défaut**, réglage de son admin (ex. « Logiciel pour tous » sur le VPS). Un autre partenaire
ne peut intervenir que ponctuellement (marché public d'interventions, reporté).

**Comptable externe.** Traité comme un partenaire de type « comptable » ; ne voit que les sociétés qui l'ont
désigné ; le **client** choisit s'il en a un ; ses accès restreints sont choisis par le client (dans le plafond
du partenaire référent).

**Managers.** Un client a au moins un manager (plusieurs possibles). Le premier utilisateur créé est manager par
défaut ; ce statut ne peut être modifié que lorsqu'il y a au moins deux utilisateurs.

**Visibilité des demandes** (règle de référence pour tous les plugins) : le client voit les siennes ; le manager
client celles de sa société ; le partenaire les siennes et celles de tous ses clients ; l'admin voit tout.

## 6. Parcours

**Création d'un client — deux voies, même résultat** (société + lien client→partenaire + premier utilisateur
manager) : saisie par le partenaire ; ou inscription seul sur le site public / la boutique, auquel cas le client
est rattaché au partenaire par défaut du serveur. L'origine (« créé par le partenaire » / « inscrit seul ») est
conservée.

**Doublon de SIREN à l'inscription** (acté) : le nouvel inscrit est renvoyé vers le manager de la société
existante, qui accepte ou refuse l'accès. Pas de rattachement automatique.

**Changement de partenaire référent.** Possible vers un partenaire de n'importe quel serveur : le **Maître tient
l'annuaire** (société cliente → partenaire → serveur). Limité à **un changement par an** (durée du contrat) ;
au-delà, validation par l'admin du Maître. L'ancien partenaire n'a pas de droit d'opposition, il est
**prévenu** (notification, pas d'accord à obtenir).

**Gestion sans migration.** Le nouveau partenaire peut gérer le client à distance sur l'ancien serveur, tant que
l'ancien partenaire est prévenu. Aucun déplacement de données n'est obligatoire.

**Migration des données** (à la demande du partenaire qui reçoit, ou du client qui veut son propre serveur) :
la plus transparente possible, sans import de base ni sauvegarde manuels. **Un schéma PostgreSQL par client**
(décision du propriétaire, « pour la simplicité »), ce qui permet d'extraire les seules données d'un client.
Proposition (à confirmer à la conception du plan) : outil applicatif lancé par le receveur ; copie des seules
données du client avec déchiffrement/rechiffrement ; l'ancien serveur continue de servir pendant la copie ;
bascule dans l'annuaire du Maître après vérification, puis suppression des données d'origine.

**Accès du partenaire à l'espace d'un client.** Depuis la fiche client (tableau de bord), un bouton ouvre la page
d'accueil du client **comme si le partenaire était manager**. Choix par défaut (non dictés par le propriétaire,
à relire) : le partenaire reste identifié sous son propre nom, ses actions sont tracées « par le partenaire X »
et visibles du client ; ses droits ne dépassent jamais le plafond qu'il a lui-même fixé ; aucun lien de l'espace
client ne mène à un écran réservé au partenaire.

**Inscription d'un partenaire.** Deux voies : inscription seul, ou invitation par un partenaire existant ; les
deux suivent le même chemin. Le partenaire est **actif tout de suite**, puis doit fournir des **documents
fiscaux et bancaires** dans un délai de **30 jours** (valeur par défaut, réglable par l'admin du Maître).
L'**admin du serveur Maître** examine ces documents. État du partenaire après examen : **vérifié**, **limité**
ou **bloqué** (changement possible plus tard, p. ex. document expiré). Le lien « invité par » est conservé ; la
commission est hors périmètre.
Choix par défaut à relire : « limité » réutilise le mécanisme de plafond (fonctionnalités réduites, accès
conservé) ; un partenaire bloqué ne peut plus agir, ses clients restent servis par le partenaire par défaut du
serveur le temps d'être repris ; l'invitation n'ajoute aucun privilège.

## 7. Ce qui est laissé au constructeur

- Formats exacts : manifeste d'entrées/blocs, schéma des tables, API, ordonnancement des migrations Flyway par
  schéma client.
- Livraison du front des plugins dans Angular.
- Contrat d'identité (R17) et évaluation des droits ; gestion de l'annuaire du Maître et de ses échanges avec
  les serveurs.
- Mécanisme exact de la migration de données et de la gestion à distance entre serveurs.
- Gabarit des notifications (ancien partenaire prévenu) et de la traçabilité des actions du partenaire.

## 7 bis. Authentification (décision du propriétaire)

- **Dans un premier temps : authentification par serveur**, avec des comptes locaux à chaque serveur.
- Une fois le serveur LDAP central en place, on **bascule dessus**, ou sur **Google / Microsoft**, en gardant
  la possibilité d'ajouter d'autres fournisseurs d'authentification unique (SSO) plus tard.
- **Plusieurs sources d'identité acceptées côte à côte** (locale, LDAP, Google, Microsoft, autre SSO).
- **Second facteur (2FA)** : non géré par nos serveurs. Pour la solution LDAP, il sera fourni **plus tard par
  Authelia**. Le noyau n'implémente donc aucune 2FA ; il doit seulement pouvoir déléguer la connexion à un
  fournisseur externe qui l'applique (comme pour Google ou Microsoft).
- Conséquence pour le cadre : le noyau expose une **identité interne stable** par utilisateur, reliée à une ou
  plusieurs identités externes (locale, LDAP, Google, Microsoft, autre SSO). Aucun plugin ne dépend du mode de
  connexion. La gestion à distance entre serveurs (§8) ne suppose donc pas de compte partagé.

## 8. Mes choix de conception (à relire : non dictés par le propriétaire)

- **Découpage en plans, dans cet ordre** : (A) cadre + modèle société / capacités / utilisateurs / accès /
  droits sur un seul serveur, avec le plugin « clients » (fiche, parcours de création, doublon, managers) ;
  (B) inscription et cycle de vie du partenaire ; (C) annuaire du Maître, changement de partenaire, gestion à
  distance ; (D) migration de données entre serveurs. Chaque plan livre un résultat testable seul.
- **Frontière noyau / plugin** : les entités société, capacités, utilisateurs, accès et l'évaluation des droits
  vivent dans le **noyau** (contrat d'identité offert aux plugins) ; le plugin « clients » possède les écrans,
  la fiche client et les parcours. Le noyau ne dépend jamais d'un plugin.
- **Portée du schéma par client** : le schéma du client contient les données métier des plugins. Société,
  utilisateurs, accès et plafonds restent dans le schéma commun du serveur. Un partenaire qui est aussi client
  a son propre schéma client comme les autres.
- **Gestion à distance** : réutilise le mécanisme existant de connexion déléguée au Maître (le serveur distant
  accepte un jeton court signé par le Maître, qui atteste l'identité et le rôle du partenaire). Le plafond est
  évalué sur le serveur qui détient les données, jamais sur le serveur d'origine du partenaire.
- **Données remontant au Maître** : l'annuaire contient des identités d'entreprises (identifiant, nom, SIREN,
  partenaire référent, serveur) mais aucune donnée sur leurs utilisateurs ni sur leur activité ; les signaux
  portent des identifiants et des codes, jamais de contenu.
- **Partenaire « limité »** : l'admin du Maître choisit par partenaire les fonctionnalités retirées ; valeur par
  défaut : ni création de client ni invitation, mais les clients existants restent servis.
- **Doublon de SIREN** : détecté par serveur ; l'annuaire du Maître signale un même SIREN sur deux serveurs à
  l'admin, sans fusion automatique. Un client sans SIREN (particulier) est dédoublonné par e-mail du premier
  utilisateur.

## 9. Prérequis techniques indépendants (suivis à part)

Test de fuite de classes sur l'application complète ; remise à zéro du `TenantContext` (ThreadLocal) dans
`JwtAuthenticationFilter` ; exécution des tests `*IT` en CI. Ils conditionnent l'ouverture du cadre à des
plugins métier mais ne dépendent pas de ce document.

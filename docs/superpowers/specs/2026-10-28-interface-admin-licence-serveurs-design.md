# Interface d'administration — licences et enrôlement automatique des serveurs

## Intention et pourquoi

Le moteur de synchronisation client ↔ partenaire (plan `2026-10-27-moteur-synchronisation`, livré et clos) a laissé un trou connu, repéré par sa revue finale (finding I-4) : il n'existe aucune interface pour gérer l'enrôlement des serveurs secondaires, les rattacher à un client, les révoquer, ou consulter/trancher un conflit de synchronisation (`SyncConflict`) — tout se fait aujourd'hui par SQL direct. Le design d'origine de l'architecture distribuée (parent de ce plan) prévoyait déjà qu'un gestionnaire de licence centralisé distribue les accès, plutôt qu'une clé statique en configuration — ce sous-projet construit ce gestionnaire.

Ce document couvre deux choses distinctes mais liées :
1. **Un système de comptes/abonnements centralisé**, porté par le serveur maître principal (MP, celui que l'éditeur du logiciel exploite lui-même), qui contrôle combien de serveurs peuvent être installés sous un compte, et automatise entièrement leur enrôlement (plus de code à copier-coller à la main).
2. **Une interface de gestion des serveurs secondaires et des conflits**, sur chaque serveur maître-capable (le MP, et l'installation de chaque partenaire agissant comme maître pour ses propres clients) — c'est l'écran demandé à l'origine (I-4).

## Hiérarchie (rappel, actée par l'utilisateur)

```
MP (serveur maître principal, exploité par l'éditeur)
  — centralise TOUS les comptes/abonnements
  └─ Serveur PARTENAIRE (achète un abonnement chez l'éditeur, revend à ses clients)
       — ne voit QUE son propre compte
       └─ Serveur CLIENT (du partenaire)
```

Un compte couvre un partenaire ET l'ensemble de ses propres installations (sa propre instance "maître pour ses clients" ET chacune des installations de ses clients) sous **un seul quota de sièges partagé**. Tous les serveurs s'enrôlent sur ce principe, sauf le MP lui-même (racine de la hiérarchie, n'a rien au-dessus de lui).

## Goals

- Un compte (login + mot de passe, type d'abonnement, nombre de serveurs autorisés) créé à la main par l'éditeur sur le MP.
- Un serveur qui s'installe (partenaire OU client d'un partenaire) s'enrôle **automatiquement** : il ne connaît au départ que l'adresse fixe du MP (livrée avec le paquet, en dur dans la configuration — `localhost:8081` en test, l'adresse réelle en prod) et le login/mot de passe de son compte (saisis une fois à l'installation). Aucune étape manuelle de génération/copie de code.
- Le quota de sièges d'un compte est vérifié avant toute nouvelle inscription — un compte qui a atteint sa limite ne peut plus enrôler de nouveau serveur.
- Une interface, sur le MP, pour que l'éditeur crée/consulte/révoque des comptes et voit les serveurs enrôlés sous chacun.
- Une interface, sur CHAQUE serveur maître-capable (MP et chaque partenaire), pour gérer SES PROPRES `SecondaryServer` (lister, voir le statut y compris `ECHEC_DEFINITIF`, révoquer) et SES PROPRES `SyncConflict` (lister, voir le détail, trancher manuellement) — repris du besoin d'origine I-4, sans rien changer au mécanisme de synchronisation des données déjà livré.

## Non-goals (pour cette itération)

- **Aucun paiement réel.** Pas d'intégration carte bancaire/prélèvement automatique. L'utilisateur a explicitement reporté la question de la facturation à plus tard, pour l'aligner sur ce qui existe déjà côté Firebase.
- **Aucune page d'inscription en libre-service.** Les comptes sont créés uniquement par l'éditeur pour l'instant, même si le modèle de données n'empêche pas d'en ajouter une plus tard.
- **Pas de redistribution multi-sauts au-delà de 2 niveaux** (partenaire → ses propres clients) — un partenaire ne revend pas lui-même à d'autres revendeurs.
- **Pas de modification du protocole de synchronisation des données déjà livré** (push/pull, détection de conflit, filtrage des commentaires privés, plafond de tentatives) — ce sous-projet ajoute une couche d'enrôlement/licence AU-DESSUS, sans toucher au mécanisme existant de synchronisation Request/Comment entre client et partenaire.

## Anti-goals (ce qui serait un échec même si ça "marche")

- Un serveur partenaire ou client qui ne peut plus fonctionner (perdre la synchronisation de ses données métier) simplement parce que le MP est temporairement inaccessible. Le MP n'intervient que pour l'enrôlement initial — jamais dans le chemin critique de la synchronisation de données client ↔ partenaire déjà livrée.
- Le MP qui appelle lui-même un serveur secondaire (violerait la règle déjà posée dans le plan parent : les appels réseau sont toujours initiés par le secondaire, jamais par le maître). Toute découverte d'un nouvel enrôlement par un partenaire doit se faire par SONDAGE du partenaire vers le MP, pas l'inverse.
- Un partenaire qui voit les comptes ou les serveurs d'un autre partenaire.
- Un compte qui dépasse silencieusement son quota de sièges sans qu'aucune erreur claire ne soit renvoyée au serveur qui tente de s'enrôler.

## Contraintes

- Réutiliser au maximum ce qui existe déjà plutôt que dupliquer : le mécanisme de code d'enrôlement à usage unique → clé API (`EnrollmentController`, `SyncApiKeys`, Task 3 du plan précédent) reste le mécanisme de redemption final à chaque niveau ; cette nouvelle couche automatise uniquement l'étape qui précède (obtenir ce code sans action humaine).
- `SecondaryServer`/`SyncOutboxEntry`/`SyncConflict` restent des entités **purement locales à chaque serveur** (contrainte globale déjà actée dans le plan précédent, à ne pas rouvrir) — les nouvelles entités de comptes/licences, elles, n'existent QUE sur le MP.
- Nouveau module Spring Modulith `licence` (mon choix), dépendant de `sync` (pour réutiliser `ServerRole`, les notions de code à usage unique) et `commun` — pas de dépendance dans l'autre sens : `sync` ne doit rien connaître de `licence`.
- Mot de passe de compte haché (BCrypt, même mécanisme que `User`/Spring Security déjà utilisé dans ce dépôt) — jamais stocké ni journalisé en clair.
- Environnement de build identique à celui déjà en place (JDK 25, Maven manuel, PostgreSQL local, `git checkout -- src/main/resources/static/app/index.html` après chaque build).

## Décisions validées par l'utilisateur (relecture du 2026-10-28)

- **Révocation** : couper l'accès ACTIF d'un serveur déjà enrôlé (pas seulement bloquer les nouveaux enrôlements) — propagée via le sondage du partenaire vers le MP (jamais d'appel du MP vers le bas), donc avec un délai d'au plus un cycle de sondage.
- **Adresse déclarée par un serveur partenaire** : détectée automatiquement par défaut, mais modifiable ensuite depuis l'écran admin du MP (une détection auto peut se tromper — ex. adresse LAN non joignable depuis l'extérieur — sans qu'il faille réinstaller quoi que ce soit).
- **Identifiants des clients d'un partenaire** : un jeton SÉPARÉ par client, généré par le partenaire lui-même (pas le login/mot de passe du compte, trop puissant pour être distribué à chaque client). Ce jeton est tiré depuis la fiche d'un `Customer` existant côté partenaire — ce qui règle au passage le rattachement : le `customerId` est connu dès la génération du jeton, pas à deviner après coup. Voir le flux révisé ci-dessous.

## Le flux d'enrôlement automatique (mon appel sur le câblage précis — à valider par l'utilisateur à la lecture)

Flux pour l'enrôlement du serveur d'un **partenaire** (utilise le login/mot de passe du compte lui-même) :

1. **Création du compte** (manuelle, par l'éditeur, sur le MP) : login, mot de passe, type d'abonnement (`ILLIMITE` pour l'instant — modélisé en énumération pour permettre d'autres types plus tard), fréquence (`MENSUEL`/`ANNUEL`), nombre de serveurs autorisés.
2. **Installation** : seule config nécessaire au démarrage = adresse du MP (déjà en dur dans le paquet) + login/mot de passe du compte + indicateur de rôle voulu = `PARTNER`.
3. **Auto-enregistrement** : au premier démarrage, le serveur appelle `POST <MP>/api/licence/enregistrer` avec `{identifiant: login+motDePasse, adresseDeclaree, identifiantInstallationStable, roleVoulu=PARTNER}` — `identifiantInstallationStable` est un UUID généré une seule fois au tout premier démarrage et conservé localement (mon ajout : sans lui, un simple redémarrage ou un retry réseau, identifié seulement par une IP/nom de machine non fiable, consommerait un siège supplémentaire à chaque fois ; le MP reconnaît un serveur déjà connu par cet identifiant, jamais par son adresse réseau seule).
4. **Le MP vérifie** : identifiants, quota de sièges du compte, et qu'aucun autre serveur `PARTNER` n'est déjà enregistré sous ce compte (un seul partenaire par compte — mon call, mentionné dans "Mes autres choix" plus bas). Refus = erreur claire, jamais un faux succès.
5. **Si accepté** : le MP crée un `LicenceServeur` (compte, adresse déclarée, rôle, identifiant d'installation), génère un code d'enrôlement à usage unique (réutilise directement `SecondaryServer.enrollmentCode`/`enrollmentCodeExpiresAt` déjà existant — le MP traite chaque `LicenceServeur` comme un `SecondaryServer` local, qu'il s'agisse en réalité d'un partenaire ou d'un client de partenaire), le renvoie.
6. **Le serveur stocke ce code** puis le redemande immédiatement via le mécanisme déjà existant (`POST <MP>/api/sync/enroll`) pour obtenir sa clé API — UNE clé dédiée aux appels de licence, dans un magasin et un client HTTP séparés de `app.sync.api-key`/`RestSyncClient` (mon call : le MP n'est JAMAIS un maître de synchronisation de données ; `SyncApiKeyAuthFilter` doit explicitement refuser une clé de licence sur `/api/sync/**`, pas seulement compter sur `customerId = null`).
7. Ce serveur EST maintenant son propre maître pour ses futurs clients (`app.role=PARTNER`, pas de `master-url` — déjà en place depuis la Task 5). Mon call : le MP écrit ce qu'il a reçu dans un fichier de config local + déclenche un redémarrage (cohérent avec le fonctionnement déjà existant, où changer `app.sync.api-key` demande déjà un redémarrage) plutôt que de reconfigurer les beans Spring à chaud, ce qui aurait exigé de modifier le moteur de synchronisation déjà livré.

Flux pour l'enrôlement du serveur d'un **client du partenaire** (utilise un jeton par client, pas le login/mot de passe du compte) :

8. **Le partenaire génère un jeton**, depuis son écran (extension de l'écran I-4), pour un `Customer` précis déjà existant chez lui : jeton à usage unique, tiré de son propre compte + ce `customerId`.
9. **Installation du serveur client** : adresse du MP (en dur) + le jeton reçu du partenaire (pas de login/mot de passe du compte) + `roleVoulu=CLIENT`.
10. **Auto-enregistrement** : `POST <MP>/api/licence/enregistrer` avec `{identifiant: jeton, adresseDeclaree, identifiantInstallationStable, roleVoulu=CLIENT}`. Le MP valide le jeton (compte actif, quota, jeton non consommé), crée le `LicenceServeur` **avec le `customerId` porté par le jeton**, et répond — avec l'adresse du serveur partenaire associé à ce compte si elle est déjà connue (sinon le client réessaiera périodiquement le même appel, idempotent grâce à l'identifiant d'installation stable, jusqu'à ce qu'elle le soit).
11. **Découverte côté partenaire** : le partenaire sonde périodiquement `GET <MP>/api/licence/nouveaux-serveurs` (nouveau job, distinct du sondage de synchronisation de données ; authentifié par SA propre clé de licence). Pour chaque nouveau `LicenceServeur` de rôle `CLIENT` sous son compte, il crée localement un VRAI `SecondaryServer` **déjà rattaché au bon `customerId`** (connu depuis l'étape 10, aucune correspondance manuelle à faire), avec un nouveau code d'enrôlement LOCAL généré par le mécanisme déjà existant, qu'il renvoie au MP (`POST <MP>/api/licence/code-partenaire`) pour que le client en attente le récupère à son prochain appel de suivi. Ce même sondage porte aussi les révocations (un `LicenceServeur` marqué révoqué côté MP → le partenaire désactive le `SecondaryServer` local correspondant).
12. **Le client**, une fois l'adresse du partenaire et ce code local reçus, appelle automatiquement `POST <adresse partenaire>/api/sync/enroll` avec ce code — réutilisant exactement le mécanisme déjà construit, sans aucune modification — et obtient sa clé API pour la synchronisation de données avec son partenaire.

Ce flux est entièrement tolérant à la déconnexion à chaque étape (jamais d'appel du MP ou du partenaire vers quelqu'un "en dessous" d'eux), cohérent avec le principe déjà posé dans ce projet.

### Mes autres choix (pas remontés en question, à vérifier en lisant)

- Un seul serveur `PARTNER` par compte, refusé sinon avec une erreur claire — un compte = une relation de revente, pas plusieurs serveurs maîtres concurrents qui se disputeraient l'adresse "du" partenaire.
- Un identifiant d'installation stable (UUID local, généré une fois) plutôt que l'adresse réseau pour qu'un redémarrage, un retry, ou une réinstallation du même poste ne consomme jamais un siège supplémentaire.
- L'écran I-4 garde la génération manuelle de code à usage unique déjà existante (`EnrollmentController`), en plus du nouveau flux automatique — utile pour une installation isolée, sans aucun compte/abonnement, ou pour les tests.
- Le code d'enrôlement local généré par le partenaire à l'étape 11 transite en clair par le MP (qui est le serveur de l'éditeur, de confiance) avant d'atteindre le client — risque jugé négligeable, pas un chemin où un tiers non authentifié peut l'intercepter.

## Interface d'administration (I-4, sur chaque serveur maître-capable)

Indépendante du système de licence ci-dessus (déjà nécessaire même pour une installation à un seul niveau, sans aucun compte/abonnement) :

- Liste des `SecondaryServer` connus de CE serveur : rôle, statut (`ACTIF`/`REVOQUE`), `customerId` rattaché, date d'enrôlement. Actions : révoquer, générer un code d'enrôlement manuel (mécanisme déjà existant, conservé pour les installations hors du système de compte).
- **Sur un serveur partenaire uniquement, en plus** : pour chaque `Customer` existant, générer un jeton d'installation à usage unique à distribuer au client (voir flux révisé) — ce jeton porte le `customerId`, donc le serveur créé automatiquement à l'arrivée du client est déjà rattaché, sans correspondance manuelle à faire après coup.
- Liste des `SyncConflict` : demande concernée, version attendue/reçue, payload reçu. Action : trancher manuellement (choisir l'état qui doit faire autorité) — le mécanisme exact de résolution (ré-appliquer le payload gagnant, marquer comme traité) est à définir dans le plan.
- Sur le MP uniquement, en plus : écran de gestion des `CompteLicence` (créer, voir les `LicenceServeur` rattachés, révoquer un compte ou un serveur précis — la révocation coupe l'accès actif, propagée au partenaire concerné via son sondage, jamais par un appel du MP vers le bas) ; champ adresse déclarée d'un partenaire modifiable ici si la détection automatique s'est trompée.

## Ce qui reste au porteur du plan

- Le nom exact des entités/endpoints, les validations de forme (longueur de mot de passe, unicité du login, format du jeton par client), la pagination des listes.
- Le mécanisme précis de résolution d'un `SyncConflict` depuis l'écran (quelles actions sont possibles, qui peut les déclencher — probablement réservé à un rôle admin).
- Les tests de bout en bout du flux d'enrôlement automatique (probablement sur les mêmes 2 instances locales déjà mises en place pour la Task 5 du plan précédent, plus une 3e pour simuler le MP si besoin).
- La durée de vie de chaque code/jeton à chaque étape (le mécanisme existant a déjà une expiration — réutiliser la même valeur ou en choisir une adaptée à ce contexte plus automatisé).
- Le mécanisme exact par lequel le MP "déclenche un redémarrage" du serveur qui vient de s'auto-enregistrer (écriture d'un fichier + attente d'un redémarrage manuel/service système, ou redémarrage programmatique de la JVM) — dépend de comment le paquet sera réellement installé/exploité en prod (service Windows ? conteneur ? tâche planifiée qui relance si le fichier de config a changé ?), information que je n'ai pas sur l'environnement cible des partenaires/clients.

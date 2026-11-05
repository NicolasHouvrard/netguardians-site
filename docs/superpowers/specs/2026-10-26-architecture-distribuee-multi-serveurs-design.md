# Architecture distribuée multi-serveurs (maître / partenaire / client / public) — Design

## Intention

Transformer l'application actuelle (monolithe Spring Boot `site` + Angular `intra`) en un système déployable sur plusieurs nœuds reliés par Tailscale, avec une hiérarchie serveur maître → serveur partenaire → serveur client, plus un serveur public séparé (vitrine). But final : revendre ce système à d'autres sociétés sous forme d'abonnement SaaS. Les 2 clients existants de l'utilisateur serviront de premier déploiement réel, sur 2 NAS, "d'ici peu".

## Pourquoi maintenant

Préparation en amont, avant que ce ne soit strictement nécessaire — mais un déploiement concret sur 2 NAS clients est prévu à court terme, ce qui en fait le premier test réel du principe.

## Goals

- Un même code applicatif (modulaire) déployable en plusieurs **rôles** : maître (= serveur partenaire par défaut), partenaire (dédié ou mutualisé entre plusieurs partenaires), client (dédié ou mutualisé entre plusieurs clients d'un même partenaire), public (vitrine : boutique, prise de rendez-vous, demandes).
- Multi-tenance à **deux niveaux** : partenaire (déjà en place, schéma PostgreSQL par tenant) et client (à construire, même mécanisme, actif seulement en mode mutualisé).
- Les serveurs secondaires (partenaire ou client) communiquent avec le serveur maître/partenaire via le réseau privé **Tailscale**.
- **Flux nominal** : le client crée une demande sur son serveur client → enregistrée localement → envoyée au serveur de son partenaire (le maître par défaut) dès que la connexion existe → traitée côté partenaire → réponse redescend au client.
- **Vrai mode déconnecté** côté serveur client : les actions continuent de fonctionner localement sans connexion au serveur partenaire ; elles sont mises en attente d'envoi.
- **Gestionnaire d'événements** : détecte les conflits (donnée modifiée des deux côtés pendant une coupure) et alerte le partenaire pour vérification/mise à jour manuelle — jamais de fusion automatique.
- Le serveur maître peut gérer/servir les pages web des serveurs clients et du serveur public.
- Architecture interne en **monolithe modulaire**, frontières de module nettes, pensée pour pouvoir un jour extraire certains modules en services SaaS séparés.
- But commercial final : revendre ce système à d'autres sociétés via un système d'abonnement.

## Non-goals

- Pas de fusion automatique de conflits — toujours remonté à un humain (le partenaire).
- Ne retouche pas le contenu métier déjà écrit dans `features/*` (accounting, crm, invoice, order, etc.) dans ce chantier — uniquement la couche infrastructure/distribution/synchronisation.
- Pas de migration immédiate vers de vrais microservices séparés — seulement préparer la structure pour que ce soit possible plus tard.
- Pas de nouveau message-broker externe (Kafka/RabbitMQ) à ce stade.

## Anti-goals

Aucun signalé par l'utilisateur au-delà des non-goals ci-dessus pour les 2 premiers déploiements clients.

## Ce qui existe déjà (vérifié, pas supposé)

- Spring Boot 4.0.2, package `com.logicielpourtous.site`, ~44 modules sous `features/` (accounting très développé — intégration Qonto/Stripe/Payfit réelle ; le connecteur Qonto récupère déjà de vraies données bancaires aujourd'hui, statut global "test" mais pas une sandbox vide).
- `config/MultiTenantJpaConfiguration.java` : multi-tenance **partenaire** déjà implémentée par schéma PostgreSQL (Hibernate `MULTI_TENANT_CONNECTION_PROVIDER` / `CurrentTenantIdentifierResolver`, stratégie `SCHEMA`).
- Une seule app Angular `intra` (`frontend/intra`, Angular 21) — pas encore de distinction de rôle de déploiement en code.
- Pas encore de notion de "serveur client" séparé, pas de protocole de synchronisation inter-serveurs, pas de gestionnaire d'événements inter-serveurs, pas de multi-tenance au niveau client.
- Le NAS "maître" existe déjà : c'est là que ce backend tourne aujourd'hui, en test.
- Ce projet est indépendant du monorepo `netguardians` (Firebase/PocketBase) — les fonctionnalités à reprendre sont les mêmes, mais le code ne doit pas être partagé/importé entre les deux initiatives.

## Décisions prises par l'utilisateur

- Hiérarchie : maître (= partenaire par défaut) → partenaires (dédié ou mutualisé) → clients d'un partenaire (dédié ou mutualisé). + un serveur public séparé (vitrine : boutique, rendez-vous, demandes).
- Vrai mode déconnecté côté client : les actions continuent de fonctionner localement sans connexion au serveur partenaire.
- Résolution de conflit = alerte humaine (le partenaire doit s'en rendre compte et trancher), jamais de fusion automatique.
- But final : produit SaaS revendable à d'autres sociétés, avec abonnement.
- 2 clients réels vont être déployés sur NAS "d'ici peu" — premier test réel de ce principe.
- **Bootstrap des 2 clients réels = reprise d'une partie de leurs données depuis Firebase (le monorepo `netguardians`), pas depuis le schéma partenaire actuel de ce backend Spring Boot.** Ces 2 clients sont aujourd'hui des clients du système Firebase/PocketBase existant — leur nouveau serveur client NAS démarre avec un sous-ensemble de leurs données Firebase importées (le sous-ensemble exact reste à définir lors du planning), pas à vide, et pas depuis une migration de ce backend Spring Boot lui-même (qui ne les connaît pas encore). C'est un import ponctuel, pas un mécanisme de synchronisation permanent.
- **Modules financiers côté rôle client = consultation uniquement.** Un serveur en rôle client peut afficher les données financières déjà synchronisées (factures, historique de paiement) même hors ligne, mais ne peut déclencher AUCUNE action financière réelle (paiement, abonnement, appel Qonto/Stripe) — ces actions restent exclusivement côté serveur partenaire. Pas de file d'attente d'actions financières côté client : seul le contenu en lecture est répliqué.
- **Le login à froid doit fonctionner hors ligne.** Un utilisateur doit pouvoir se connecter sur un serveur client même sans aucune connexion au serveur partenaire au moment de la connexion — nécessite une réplication locale (chiffrée) des identifiants nécessaires à l'authentification, mise à jour dès que la connexion est disponible, pas récupérée à la demande.

## Décisions laissées au porteur du plan (calls de conception, actés ici)

- **Frontières de module = Spring Modulith.** Framework officiel de l'écosystème Spring pour exactement ce cas d'usage (monolithe modulaire avec frontières de package vérifiables, Event Publication Registry intégré pour la fiabilité des événements inter-modules, conçu pour permettre une extraction future en services séparés). Les `features/*` existants deviennent progressivement des modules Modulith explicites — migration progressive module par module, pas un big-bang sur les ~44 modules existants.
- **Protocole de synchronisation inter-serveurs = REST + table "outbox" locale + ré-essai, au-dessus de Tailscale.** Chaque serveur (client ou partenaire) tient une table d'événements sortants : une action métier crée l'enregistrement local + un événement en attente d'envoi ; un job tente de l'envoyer au serveur cible via son adresse Tailscale dès que la connexion répond ; en cas d'échec, ré-essai périodique (backoff). Pas de broker externe à ce stade — Tailscale + REST direct suffit à cette échelle (NAS, quelques nœuds).
- **Détection de conflit = version/horodatage sur l'enregistrement synchronisé.** Chaque donnée synchronisée porte une version (ou un `misAJourA`) ; si le serveur partenaire reçoit un événement dont la version de base ne correspond plus à sa version actuelle (donc modifiée des deux côtés pendant la coupure), il n'applique PAS automatiquement l'événement — il crée une alerte pour le partenaire avec les deux versions en présence, à charge pour lui de trancher manuellement.
- **Rôle de déploiement = un flag de configuration** (`app.role = master | partner | client | public`), pas une base de code séparée par rôle. Un seul artefact Spring Boot déployé partout ; le rôle actif détermine quels comportements/modules sont activés (ex. : le rôle `client` active le mode outbox + offline-first et désactive les modules métier qui n'ont de sens que côté partenaire ; le rôle `public` n'expose que boutique/rendez-vous/demandes).
- **Multi-tenance client = même mécanisme que partenaire (schéma PostgreSQL), activée seulement en mode mutualisé.** Un serveur client dédié à un seul client n'a besoin d'aucune multi-tenance (une seule tenant = lui-même) ; un serveur client mutualisé réutilise le système de schéma-par-tenant déjà en place côté partenaire.
- **Clé de synchronisation = un champ `uuid` ajouté aux entités synchronisées, pas un changement du type des clés primaires existantes.** Vérifié : toutes les entités (`User`, `AccountingEntry`, etc.) utilisent aujourd'hui `@Id @GeneratedValue(IDENTITY)` (`Long` auto-incrémenté par schéma). Deux serveurs écrivant chacun en autonome produiraient forcément des collisions d'ID s'ils se synchronisaient sur cette clé. Plutôt que de migrer le type de clé primaire sur les ~44 modules existants (gros chantier, risqué), chaque entité qui participe à la synchronisation reçoit un champ `uuid` (généré côté serveur d'origine, unique globalement) utilisé comme clé de rapprochement entre serveurs ; la clé primaire `Long` locale reste inchangée pour les jointures internes à un schéma. Seules les entités réellement synchronisées ont besoin de ce champ — pas une migration globale.
- **Rôle `public` = infrastructure backend seulement pour ce chantier ; quand son interface sera construite, ce sera en HTMX (pas Angular).** Décision utilisateur : le serveur public a besoin d'un SEO exploitable (indexation moteurs de recherche) — rendu côté serveur via Thymeleaf + HTMX (déjà une dépendance du projet : `htmx-spring-boot`), pas une SPA Angular qui rendrait le contenu côté client. Construire cette vitrine (boutique/rdv/demandes en HTMX) est un chantier frontend à part, qui ne bloque pas les 2 déploiements NAS prévus (rôles partenaire/client). Le rôle `public` est prévu dans la configuration dès maintenant, mais son interface utilisateur est différée.

## Ce qui reste au porteur du plan

- Découpage précis en tâches/sous-projets pour le plan d'implémentation (probable : 1. fondation Modulith + flag de rôle ; 2. mécanisme outbox + synchronisation REST basique entre 2 instances ; 3. détection de conflit + alerte côté partenaire ; 4. multi-tenance client ; 5. réplication locale chiffrée des identifiants pour le login à froid hors ligne ; 6. import ponctuel d'un sous-ensemble de données Firebase pour les 2 clients réels ; 7. déploiement réel sur les 2 premiers NAS clients).
- Format exact des événements échangés entre serveurs.
- Authentification entre serveurs (probablement un secret/clé partagée par paire de serveurs, à définir).
- Détail de l'interface d'alerte de conflit côté partenaire.
- Sous-ensemble exact des données Firebase à importer pour les 2 clients réels, et mapping entre les modèles de données Firebase et les entités Spring Boot équivalentes.
- Mécanisme exact de réplication/stockage chiffré des identifiants pour le login à froid hors ligne.

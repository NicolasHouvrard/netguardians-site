# Système de mise à jour / déploiement bleu-vert (Docker + Headscale) — Design

## Intention

Automatiser la mise à jour sans interruption de tous les serveurs de l'architecture distribuée (maître/partenaire/client, voir [2026-10-26-architecture-distribuee-multi-serveurs-design.md](2026-10-26-architecture-distribuee-multi-serveurs-design.md)) : à chaque nouvelle version poussée sur Git, l'image Docker correspondante est construite automatiquement, puis le serveur Maître ordonne à chaque serveur — lui-même compris — de basculer vers cette nouvelle version selon le principe bleu/vert (deux instances, deux ports, bascule du reverse-proxy une fois la nouvelle instance vérifiée saine). Le réseau privé qui relie tous ces serveurs passe de Tailscale à **Headscale** (auto-hébergé sur le VPS). Ce chantier produit aussi le fichier `compose.yaml` par serveur (dont la configuration PostgreSQL perdue sur le NAS), qui sert de livrable concret.

## Pourquoi maintenant

L'utilisateur a perdu la configuration Postgres de son NAS et souhaite la reconstruire proprement, en même temps qu'il formalise le mécanisme de mise à jour — avant les premiers déploiements réels chez des clients, déjà anticipés dans le design du 2026-10-26 ("d'ici peu").

## Goals

- Pipeline CI/CD : un tag Git (`v*`) déclenche GitHub Actions qui compile, teste, construit l'image Docker et la publie en **public** sur GHCR (`ghcr.io/<owner>/site:<tag>`).
- Le serveur Maître (le même que celui du module licence, sur le NAS) surveille l'apparition de nouvelles versions sur GitHub.
- Dès qu'une nouvelle version est détectée, le Maître envoie un simple signal de réveil à l'agent de chaque serveur enrôlé — y compris lui-même. L'agent **rappelle alors le Maître par le canal sortant déjà existant et déjà authentifié** (celui du module licence, même mécanisme que `LicenceClient`) pour récupérer l'ordre réel (version à déployer). C'est bien le Maître qui déclenche le cycle de mise à jour — exception délibérée au principe "le secondaire initie toujours" en vigueur pour la synchronisation métier, rendue possible par Headscale qui supprime la contrainte NAT — mais **aucun nouveau point d'entrée réseau à authentifier n'est construit côté agent** : l'appel qui porte réellement l'ordre part toujours du secondaire vers le Maître, comme partout ailleurs dans ce dépôt. L'agent réutilise ainsi la **même identité/clé API** que l'application principale, sans second mécanisme d'authentification à construire.
- Sur chaque serveur, un **agent** — un petit programme séparé de l'application métier, pas réécrit à chaque version — traite l'ordre récupéré et :
  1. récupère la nouvelle image Docker ;
  2. démarre la nouvelle version sur le port inactif (8081 si 8080 est actif, et inversement) ;
  3. vérifie qu'elle répond correctement (contrôle de santé) ;
  4. si tout est bon : bascule le Nginx local vers le nouveau port, puis arrête l'ancienne instance ;
  5. si le contrôle de santé échoue : arrête la nouvelle instance, ne touche pas à Nginx, et signale l'échec — l'ancienne version continue de tourner sans interruption.
- Une seule base **PostgreSQL par serveur**, partagée entre les deux couleurs (jamais dupliquée) — le basculement ne concerne que la couche applicative.
- Les migrations de schéma PostgreSQL passent par **Flyway** (remplace `ddl-auto=update` pour ce chantier), avec une discipline **"étendre avant de couper"** (expand/contract) : pendant la fenêtre de bascule où l'ancienne et la nouvelle version tournent toutes les deux sur la même base, aucune migration ne doit casser la compatibilité avec l'ancienne version de l'application encore en train de servir du trafic.
- L'hébergement public (nom de domaine, certificat SSL) est **centralisé sur le VPS OVH principal** : c'est son Nginx qui reçoit tout le trafic public, pour tous les domaines (Maître, partenaires, clients), et le redirige vers le bon serveur interne via Headscale. Le Nginx local de chaque serveur partenaire/client ne gère que le basculement bleu/vert entre ses deux ports internes — il n'a ni certificat ni nom de domaine public à gérer lui-même.
- **Headscale** remplace Tailscale : le VPS héberge le serveur Headscale ainsi que le point d'entrée public unique (Nginx + SSL) pour tous les domaines ; le NAS (Maître) et chaque serveur partenaire/client — chez l'utilisateur ou chez un tiers — le rejoignent comme clients du réseau.
- Livrable concret : un `compose.yaml` type par serveur (application, agent, PostgreSQL, Nginx), qui reconstruit la configuration Postgres perdue sur le NAS.

## Non-goals

- Pas de création automatique d'enregistrement DNS pour l'instant — ce sera traité au cas par cas selon le fournisseur DNS de chaque serveur ; jamais bloquant.
- Pas de notifications (email ou autre) à ce stade — fonctionnalité explicitement reportée à une prochaine itération.
- Ne remplace pas le mécanisme de synchronisation des données métier (outbox/REST, voir [2026-10-27-moteur-synchronisation-design.md](2026-10-27-moteur-synchronisation-design.md)) : ce chantier déploie et met à jour le binaire de l'application, il ne touche pas à la réplication des données.
- Ne modifie pas le principe "le secondaire initie toujours" pour le canal de synchronisation métier existant — l'ordre de mise à jour poussé par le Maître est un canal distinct et délibérément à part, propre à l'orchestration de déploiement.

## Anti-goals

Aucun signalé par l'utilisateur au-delà des non-goals ci-dessus.

## Ce qui existe déjà (vérifié, pas supposé)

- Module `licence` fusionné dans `master` (2026-10-28) : `CompteLicence`, `LicenceServeur`, enrôlement automatique, clé `X-Licence-Api-Key` — c'est ce mécanisme que l'agent réutilise pour s'authentifier.
- Flag `app.role` (`master | partner | client | public`) issu du design du 2026-10-26.
- La connexion base de données est déjà entièrement externalisée par variables d'environnement (`DATABASE_URL`, `DATABASE_USERNAME`, `DATABASE_PASSWORD` dans `application-prod.properties`) — prête pour un déploiement conteneurisé sans modification.
- Aucun fichier Docker, `compose.yaml` ou workflow GitHub Actions n'existe dans ce dépôt (vérifié : pas de `.github/workflows`, pas de fichier Docker/compose).
- La configuration PostgreSQL du NAS est perdue — à reconstruire entièrement dans ce chantier.

## Décisions prises par l'utilisateur

- Deux rôles de serveur pour ce chantier : **Maître** (sur le NAS de l'utilisateur, gère déjà la licence) et **Client/Partenaire** (sur un VPS ou un NAS, appartenant à l'utilisateur ou à un partenaire/client externe).
- Chaque serveur gère ses propres instances Docker pour basculer sans interruption, et récupère automatiquement sa nouvelle version depuis GitHub.
- Headscale remplace Tailscale ; le VPS joue le rôle de serveur Headscale.
- Les mêmes serveurs physiques peuvent héberger à la fois des tenants client et partenaire ; le Maître fait la "gare de triage" pour router les demandes vers le bon partenaire/client.
- Mise à jour déclenchée par tag Git poussé, traitée selon le principe bleu/vert.
- Une seule base PostgreSQL par serveur, jamais dupliquée par couleur.
- L'orchestration (Docker + Nginx) est confiée à un **petit programme séparé** de l'application métier (justification technique actée en conversation : donner l'accès au socket Docker à l'application métier elle-même est un risque de sécurité, et le code d'orchestration ne doit pas changer à chaque version de l'appli pour rester fiable même si une version casse au démarrage).
- Sens de communication : **le Maître déclenche** la mise à jour — ce n'est pas l'agent qui vient demander périodiquement. Rendu possible par Headscale (plus de contrainte NAT).
- L'agent réutilise la **même identité/clé API** que l'application principale (celle du module licence) — pas d'authentification séparée à construire.
- Automatisation complète du certificat SSL (Let's Encrypt) en plus du basculement de port (devient, après clarification, une automatisation côté VPS plutôt que par serveur — voir plus bas).
- Création automatique du DNS : traitée au cas par cas selon le fournisseur, non bloquante pour ce chantier.
- Notifications : par email, mais explicitement reportées à plus tard.
- **Migrations de schéma = Flyway**, avec discipline étendre-avant-de-couper pendant la fenêtre de bascule (plutôt qu'accepter le risque de `ddl-auto=update` entre deux versions qui tournent en même temps).
- **Mécanisme réel de l'ordre = signal de réveil + rappel sortant de l'agent vers le Maître** (réutilise le canal déjà authentifié), plutôt qu'un nouveau point d'entrée réseau côté agent à authentifier.
- **Hébergement public centralisé sur le VPS OVH principal** pour tous les domaines (Maître, partenaires, clients) — pas de certificat/domaine public géré localement par chaque serveur partenaire/client.

## Décisions laissées au porteur du plan (calls de conception, actés ici)

- **Contrôle de santé = point de contrôle HTTP de l'application** (ex. : `/actuator/health` si Spring Boot Actuator est ajouté, ou un endpoint équivalent déjà disponible), interrogé pendant une fenêtre de quelques secondes après le démarrage de la nouvelle instance. Healthy de façon stable → bascule Nginx puis arrêt de l'ancienne instance. Jamais healthy dans la fenêtre → arrêt de la nouvelle instance, Nginx inchangé, échec signalé au Maître.
- **Le Maître fait tourner son propre agent localement** (sur le NAS), selon le même mécanisme que n'importe quel autre nœud — pas de cas particulier pour sa propre mise à jour.
- **Contenu de l'ordre récupéré par l'agent** (lors de son rappel au Maître après le signal de réveil) : au minimum la référence de version/tag à déployer ; l'agent en déduit l'image à tirer par convention (`ghcr.io/<owner>/site:<tag>`).
- **`compose.yaml` par serveur** : au moins les services application (bleu/vert), PostgreSQL (partagé), Nginx (reverse-proxy local, bascule de port), et l'agent. Le détail exact du découpage en services/conteneurs reste au plan d'implémentation.

## Ce qui reste au porteur du plan

- Contrat exact du contrôle de santé : endpoint précis, faut-il ajouter Spring Boot Actuator au projet, durée de la fenêtre de vérification, nombre d'échecs tolérés.
- Mécanisme exact par lequel l'agent pilote Docker (socket Docker monté, CLI `docker` dans l'image de l'agent, API distante) et la portée du risque de sécurité que ce choix implique.
- Schéma exact de nommage/versionnage des images Docker au-delà de "un tag Git déclenche un build".
- Contenu exact et format du `compose.yaml` type (volumes PostgreSQL, stratégie de sauvegarde, version de l'image Postgres à épingler) — reconstruction de la configuration perdue du NAS.
- Intégration exacte de Flyway (emplacement des scripts de migration, convention de nommage, comment le plan impose la discipline étendre-avant-de-couper sur les migrations qui tombent pendant une fenêtre de bascule).
- Templating Nginx côté VPS : comment le reverse-proxy public sait vers quelle adresse Headscale router chaque domaine (probablement une table dérivée du registre `LicenceServeur` existant), et comment cette table se met à jour quand un nouveau partenaire/client est enrôlé.
- Contenu exact du workflow GitHub Actions (étapes, secrets nécessaires pour publier sur GHCR).
- Politique réseau/ACL Headscale entre les nœuds (qui peut joindre qui).
- Mécanisme de repli si le DNS n'est pas automatisable chez un fournisseur donné (processus manuel minimal à documenter).

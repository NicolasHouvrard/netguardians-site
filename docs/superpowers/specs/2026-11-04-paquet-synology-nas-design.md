# Paquet Synology (DSM) pour le déploiement bleu-vert — Design

## Intention

Donner à n'importe quel NAS Synology (le NAS "logipourtous" lui-même, ou celui d'un partenaire/client) un vrai paquet DSM installable depuis le Centre de paquets, qui amorce lui-même tout ce dont ce nœud a besoin pour rejoindre l'architecture distribuée et le système de déploiement bleu-vert déjà construits (voir [2026-10-26-architecture-distribuee-multi-serveurs-design.md](2026-10-26-architecture-distribuee-multi-serveurs-design.md) et [2026-11-02-deploiement-bleu-vert-docker-design.md](2026-11-02-deploiement-bleu-vert-docker-design.md)), sans que l'utilisateur ait à taper de longues suites de commandes.

## Pourquoi maintenant

Le système de déploiement bleu-vert (Agent, orchestrateur, licence) vient d'être construit et mergé. Le prochain pas concret est de déployer réellement sur le NAS — qui repart d'une base Postgres vierge — et, à terme, sur le VPS. Un paquet DSM réel rend ce premier déploiement (et tous les suivants, chez des partenaires/clients) répétable, au lieu d'une suite d'étapes manuelles par SSH.

## Goals

- Un vrai paquet `.spk`, installable depuis le Centre de paquets DSM (double-clic), pas un script à lancer soi-même par SSH.
- À l'installation, le paquet :
  1. installe et connecte le client Tailscale/Headscale lui-même, à l'aide d'une **clé de connexion limitée dans le temps** que l'utilisateur colle (générée côté VPS, qui hébergera le serveur Headscale) ;
  2. détecte son propre rôle en lisant le **nom d'hôte du NAS** : si c'est `logipourtous`, il s'installe directement en **Maître** ; sinon, il tente de rejoindre le Maître déjà joignable via Headscale ;
  3. installe l'**Agent** (le petit programme Java du chantier bleu-vert), **une des deux instances Docker** bleu/vert de l'application (démarrage initial, pas de bascule à ce stade), une instance **PostgreSQL**, et le **Nginx local** déjà configuré pour la bascule bleu/vert — nécessité technique directe de l'Agent déjà construit (`BasculeNginx`), qui écrit et recharge CE Nginx local à chaque déploiement ; **distinct** du Nginx public centralisé sur le VPS (déjà décidé dans le chantier bleu-vert, routage public vers le bon NAS, hors périmètre de ce paquet) — sur des **ports non standard**, en **prévenant l'utilisateur** de ces ports ;
  4. ne donne **aucun accès fonctionnel** à l'application tant que l'utilisateur ne s'est pas connecté avec un **compte licence** dont l'abonnement est valide (réutilise le module `licence` déjà construit — enrôlement par login/mot de passe ou jeton).
- Deux portes **distinctes et indépendantes** :
  - Headscale = accès **réseau** vers le Maître (sans lui, rien n'est même joignable) ;
  - Licence/abonnement = droit d'**usage** de l'application (sans lui, le réseau fonctionne mais l'utilisateur n'a accès à rien).
- Le paquet VPS équivalent (hors périmètre détaillé ici, voir Non-goals) hébergera le serveur Headscale et génère les clés de connexion que ce paquet NAS consomme.

## Non-goals

- Le paquet **VPS** lui-même (serveur Headscale, génération des clés de connexion, serveur OTP) : chantier séparé, explicitement reporté par l'utilisateur ("on verra après"). Ce design-ci **suppose** qu'un VPS sait déjà générer une clé de connexion Headscale limitée dans le temps — c'est une dépendance externe à ce paquet, pas une responsabilité qu'il assume.
- Le serveur **OTP** : explicitement reporté à une 2e phase, après le paquet VPS lui-même.
- La bascule bleu/vert elle-même (Agent, orchestrateur) : déjà construite, ce paquet ne fait qu'**amorcer** le premier démarrage (une seule couleur active), pas gérer les déploiements suivants.
- Construire un vrai compte "logipourtous" multi-NAS générique : le nom d'hôte `logipourtous` est, par décision de l'utilisateur, la convention qui identifie LE Maître — pas un mécanisme générique de désignation de rôle.

## Anti-goals

Aucun signalé par l'utilisateur au-delà des non-goals ci-dessus.

## Ce qui existe déjà (vérifié, pas supposé)

- Le module `licence` (enrôlement, `CompteLicence`, abonnement, `app.role`) et le système de déploiement bleu-vert (Agent, orchestrateur, `DeploiementScheduler`) sont construits et mergés sur `master`.
- Le NAS doit repartir d'une base Postgres **vierge** ; le vrai schéma Flyway (`V1__baseline.sql`) a été reconstruit et validé pour ce cas exact (chantier précédent, même session) — ce paquet s'appuie dessus, il ne le reconstruit pas.
- Recherche effectuée sur le format réel des paquets DSM (voir sources) :
  - un `.spk` est une archive tar au format **POSIX ustar** (pas PAX — piège de compatibilité documenté) contenant un fichier `INFO`, le contenu (`package.tgz`), et des scripts de cycle de vie (`preinst`, `postinst`, `preuninst`, `postuninst`, `postupgrade`, **`prestart`** — ce dernier tourne à chaque démarrage pour décider si le paquet peut démarrer, point d'ancrage naturel pour la vérification Headscale/Maître) ;
  - un paquet peut déclarer une **dépendance** sur un autre paquet du Centre de paquets (ex. "Container Manager"), que DSM installe alors automatiquement si absent ;
  - l'exemple officiel Synology de paquet Docker ne compile aucun code : il pilote des conteneurs déjà construits ailleurs (`docker run`/pull) — correspond directement à nos deux instances bleu/vert + Postgres ;
  - construire un vrai `.spk` nécessite l'outillage de build Synology (`pkgscripts-ng`/`spksrc`), pas un simple zip/tar à la main ;
  - l'Agent (programme Java, doit tourner **hors Docker** par décision déjà actée dans le design bleu-vert) n'a pas de mécanisme officiel Synology pour "juste tourner en tâche de fond" — le motif répandu chez d'autres paquets communautaires est un **service systemd** installé par le script d'installation. Implication directe : le NAS a aussi besoin d'un **runtime Java** disponible, ce que ce design doit encore trancher (bundlé dans le paquet, ou dépendance déclarée).

Sources consultées : [Package Introduction — Synology Developer Guide](https://help.synology.com/developer-guide/synology_package/introduction.html) · [SPK package format (pkgscripts-ng)](https://deepwiki.com/SynologyOpenSource/pkgscripts-ng/6.1-spk-package-format) · [Compile Docker Package — Synology](https://help.synology.com/developer-guide/examples/compile_docker_package.html) · [Tackling Synology's "Invalid File Format" Error](https://omervk.com/posts/2026-04-05-synology-invalid-file-format/).

## Décisions prises par l'utilisateur

- Un **vrai** paquet DSM (`.spk`), pas un script — installable depuis le Centre de paquets, avec un minimum de lignes de commande à taper soi-même.
- Détection du rôle par **nom d'hôte du NAS** : `logipourtous` = Maître, directement ; tout autre nom = tente de rejoindre un Maître déjà joignable.
- Le paquet installe et connecte lui-même Tailscale/Headscale (pas juste une vérification passive).
- La clé de connexion Headscale est **collée par l'utilisateur** à l'installation (pas récupérée automatiquement via le compte licence) — deux canaux bien séparés, voir plus haut.
- Cette clé est **limitée dans le temps** ; l'utilisateur accepte la contrainte opérationnelle que cela implique (régénérer/redistribuer le paquet ou la clé régulièrement).
- Séparation stricte Headscale (accès réseau) / licence+abonnement (droit d'usage) : sans la 2de porte, "il n'aura accès à rien", même avec le réseau qui fonctionne.
- Le paquet installe : l'Agent, une des deux instances Docker bleu/vert, Postgres — sur des **ports non standard**, avec avertissement à l'utilisateur.
- Le paquet VPS (Headscale serveur + génération de clés) viendra après ; le serveur OTP encore après ça.
- **Partenaire vs client** : ce n'est **pas** le paquet qui décide. C'est la même application partout ; c'est le **type du compte licence** utilisé pour l'enrôlement (déjà porté par le module `licence` existant — `roleVoulu` à l'enregistrement, ou un jeton CLIENT à usage unique généré par un partenaire) qui fixe les droits dans l'application. Ce paquet n'a donc aucune logique de détection partner/client à construire : il se contente de faire passer l'utilisateur par l'écran de connexion/enrôlement licence déjà existant.
- **Multi-architecture** : le paquet doit fonctionner sur les différentes architectures CPU Synology (x86_64 et ARM), pas seulement x86_64. **Dépendance externe à ce chantier** : le pipeline CI/CD qui construit et publie l'image Docker sur GHCR (chantier séparé, pas encore construit — voir [2026-11-02-deploiement-bleu-vert-docker-design.md](2026-11-02-deploiement-bleu-vert-docker-design.md), goal "Pipeline CI/CD") doit donc publier une image **multi-arch** dès sa construction. Noté ici pour que quiconque bâtit ce pipeline le sache dès le départ.
- **Nginx local de bascule** : fait partie de ce paquet (voir Goals) — nécessité technique de l'Agent déjà construit, pas un choix ouvert. Distinct du Nginx public du VPS, qui route déjà vers les NAS et reste hors périmètre ici.

## Décisions laissées au porteur du plan (calls de conception, actés ici)

- **Comportement en cas d'échec de vérification** (pas de Headscale connecté, ou connecté mais Maître injoignable et ce n'est pas `logipourtous`) : l'utilisateur n'a pas tranché malgré deux relances. Call retenu : le `prestart`/premier démarrage **réessaie avec un backoff** (le paquet ne bloque pas définitivement sur un réseau qui se stabilise encore) plutôt que d'échouer immédiatement, et utilise le **même canal d'avertissement** que celui retenu pour les ports non standard (voir point suivant) pour signaler un échec persistant à l'utilisateur plutôt que de rester silencieux.
- **Canal d'avertissement** (ports non standard, échecs de connexion persistants) : pas précisé par l'utilisateur. Call retenu : les mécanismes DSM natifs du Centre de paquets (le texte affiché en fin d'installation + le journal DSM consultable depuis l'interface), pas un canal à construire (pas d'email ni de notification push à ce stade — cohérent avec "notifications reportées à plus tard" du chantier bleu-vert dont celui-ci dépend).
- **Runtime Java pour l'Agent** : bundlé dans le paquet (un JRE minimal embarqué) plutôt qu'une dépendance déclarée sur un paquet Java tiers du Centre de paquets, pour éviter de dépendre de la disponibilité/version d'un paquet que Synology ou la communauté pourrait retirer — accepté comme un paquet plus lourd en échange de la fiabilité.
- **Numéros de ports non standard exacts** : laissés au porteur du plan (l'exigence de l'utilisateur est "non standard + prévenir", pas des valeurs précises) — à choisir en évitant les ports déjà conventionnels de ce chantier (8080/8081 bleu/vert interne, 8090 Agent, 5432 Postgres par défaut) et les ports DSM courants.
- **Mise à jour du paquet** (`postupgrade`) : doit explicitement **préserver** les données Postgres et la configuration déjà en place — l'incident fondateur de tout ce chantier est justement une configuration Postgres perdue sur ce même NAS ; ce paquet ne doit jamais reproduire ce problème à l'occasion d'une mise à jour de lui-même.
- **Désinstallation du paquet** (`preuninst`/`postuninst`) : même logique que la mise à jour — les données Postgres doivent survivre à une désinstallation (stockées hors du cycle de vie du paquet, ex. un dossier partagé dédié plutôt qu'un chemin que DSM nettoie automatiquement). Une désinstallation qui efface tout reproduirait exactement l'incident fondateur de ce chantier ; pas une option.
- **Exposition réseau des ports non standard** : liés **uniquement à l'interface Tailscale/Headscale** (ou loopback), jamais aux interfaces LAN du NAS. Cohérent avec le design bleu-vert existant, qui centralise tout l'accès public sur le VPS — un partenaire/client n'est jamais censé atteindre directement Postgres ou les conteneurs applicatifs d'un autre NAS depuis son propre réseau local.

## Ce qui reste au porteur du plan

- Le détail exact du `postinst`/`prestart` (ordre précis des vérifications, format de la page de saisie de la clé Headscale dans l'assistant d'installation DSM).
- Le choix technique précis de l'outillage de build (`pkgscripts-ng` vs `spksrc`) et de la chaîne de compilation/empaquetage.
- Le contenu exact du service systemd de l'Agent (unité, chemins, utilisateur d'exécution).
- Le mécanisme précis de démarrage de la première instance Docker (compose.yaml embarqué dans le paquet, ou appels `docker run` directs depuis les scripts).
- La liste exacte des ports non standard retenus.
- Le format exact de la clé de connexion Headscale attendue (brute, ou avec un préfixe/format spécifique) — dépend de ce que le futur paquet VPS produira.

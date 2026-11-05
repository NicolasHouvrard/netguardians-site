# nas-package — paquet DSM « site-nas »

Paquet Synology DSM (`.spk`) qui amorce un NAS dans l'architecture distribuée du projet :
connexion Headscale, détection du rôle (Maître = nom d'hôte `logipourtous`), Postgres,
Nginx local de bascule bleu-vert, Agent (service systemd, JRE embarqué) et première
instance Docker de l'application. Design : `docs/superpowers/specs/2026-11-04-paquet-synology-nas-design.md`.

## Arborescence

| Chemin | Rôle |
|---|---|
| `lib/*.sh` | Bibliothèques testées (Tasks 2-9), sourcées par les scripts DSM. |
| `systemd/site-agent.service.template` | Gabarit de l'unité systemd de l'Agent (Task 8). |
| `tests/` | Harnais shell maison + tests unitaires des bibliothèques. |
| `spk/Makefile`, `spk/INFO.sh`, `spk/build-spk.sh` | Construction du `.spk`. |
| `spk/src/scripts/` | Scripts de cycle de vie DSM (+ `commun.sh`, `prestart.sh`). |
| `spk/src/wizard/install_uifile` | Assistant d'installation (saisie de la clé Headscale). |
| `spk/src/conf/privilege` | Privilèges DSM (`run-as: root`, voir « Limites connues »). |

## Valeurs fixes du projet

Écrites littéralement à un seul endroit, `spk/src/scripts/commun.sh` ; chaque
bibliothèque les reçoit en paramètre.

| Élément | Valeur | Exposition |
|---|---|---|
| Application bleu / vert | `18080` (actif initial) / `18081` | `127.0.0.1` uniquement |
| Postgres (hôte → 5432) | `15432` | `127.0.0.1` uniquement |
| Agent | `18090` | adresse Headscale du NAS uniquement |
| Nginx (écoute) | `8072` | adresse Headscale du NAS uniquement |

Si l'un des ports `18080`/`18081`/`15432`/`18090` est déjà occupé, l'installation
s'arrête sans rien démarrer (aucun port de remplacement n'est deviné : ils sont
partagés avec l'Agent).

## Données : `/volume1/site-data/`

- `postgres/` : données Postgres (monté dans le conteneur `site-postgres`) ;
- `agent/` : répertoire de travail de l'Agent et fichier de clé API licence
  (`cle-api-licence`).

Ce dossier est **hors** de tout chemin que DSM associe au cycle de vie du paquet
(`/var/packages/site-nas/`, `/volumeX/@appstore|@appdata|@apphome|@apptemp/`) :
`preinst`, `postinst`, `postupgrade` et `preuninst` refusent de continuer s'il s'y
trouvait. Il survit à une mise à jour (Postgres n'est réamorcé que si `postgres/`
n'existe pas) et à une désinstallation (seuls les conteneurs, l'unité systemd et les
fichiers Nginx sont retirés) ; une réinstallation repart de ces données. Pour
vraiment les effacer, supprimer ce dossier à la main.

Autres emplacements hors paquet : `/usr/local/etc/nginx/sites-enabled/site-nas-*.conf`
(inclus par le Nginx de DSM, survit aux mises à jour DSM), `/etc/systemd/system/site-agent.service`
et `site-agent.service.d/10-site-nas.conf`.

## Cycle de vie

| Script DSM | Action |
|---|---|
| `preinst` | Garde-fous immédiats (dossier de données, volume présent, clé ou connexion Headscale) : un refus ici annule proprement l'installation. |
| `postinst` | Les 8 étapes de la Task 10 dans l'ordre : Headscale (connexion avec la clé si besoin + réessai), Maître (réessai, sauf sur le Maître), ports, Postgres, Nginx (`nginx -t` puis un seul reload), Agent, première instance. Ne fait rien en mise à jour. |
| `start-stop-status` | `prestart` → `prestart.sh` (vérifications Headscale + Maître, sans rien réinstaller, aussi au démarrage du NAS) ; `start`/`stop` des conteneurs et de l'Agent ; `status`. |
| `preupgrade` / `postupgrade` | Rien à sauvegarder ; `postupgrade` ne réamorce Postgres que si son dossier est absent et rafraîchit l'unité de l'Agent. |
| `preuninst` / `postuninst` | Ne font rien en mise à jour ; en désinstallation, retirent services/conteneurs/fichiers Nginx, jamais les données. |

Avertissements (ports, échecs persistants, fin d'installation) : texte de fin
d'opération du Centre de paquets (`$SYNOPKG_TEMP_LOGFILE`) + journal système
(`logger -t site-nas`).

## Décisions prises dans ce paquet

### Saisie de la clé Headscale : `WIZARD_UIFILES/install_uifile`

Assistant DSM au format JSON classique (`step_title` / `items` / `subitems`, champ
`type: "password"`), convention utilisée par spksrc (`src/wizard/install_uifile`) et
documentée par Synology : la valeur de chaque `key` est exposée aux scripts
`preinst`/`postinst` comme variable d'environnement. Clé : `wizard_headscale_cle`
(préfixe `wizard_` de spksrc), lue par `postinst` dans `CLE_SAISIE`. Le champ peut
être laissé vide uniquement si le NAS est déjà connecté (réinstallation) ; `preinst`
refuse sinon. La clé n'est jamais passée en argument de commande (fichier
temporaire 0600, voir `lib/headscale.sh`). Le format « v2 » de DSM 7.2.2 (Vue +
webpack) n'a pas été retenu : il exige une chaîne de build JavaScript pour un seul
champ texte.

### `HEADSCALE_URL` : valeur du projet figée à la construction

Fournie au `make` (`HEADSCALE_URL=...`), écrite dans `scripts/constantes.sh` du
`.spk`. Identique pour tous les NAS (comme le port `8072`), jamais demandée à
l'utilisateur. Aucune valeur par défaut : le serveur Headscale sera hébergé par le
paquet VPS, pas encore construit, et deviner un domaine ferait pointer les NAS vers
un serveur inexistant ou étranger. Même discipline pour `IMAGE_OWNER`/`IMAGE_TAG`
(image `ghcr.io/<owner>/site:<tag>` de la première instance).

### `URL_MAITRE` : convention fixe, pas un champ de l'assistant

`URL_MAITRE = http://<adresse Headscale du nœud « logipourtous »>:8072`, où
l'adresse est demandée au client Tailscale lui-même (`tailscale ip -4 logipourtous`,
carte du réseau Headscale), recalculée à chaque essai du réessai.

Raisons :
- même philosophie « convention de projet plutôt que configuration par nœud » que le
  port `8072` et le nom d'hôte `logipourtous` lui-même : le Maître est un NAS comme
  les autres, installé avec ce même paquet, donc joignable par définition sur son
  adresse Headscale et ce port ;
- un champ de l'assistant serait une saisie de plus, sujette à erreur, pour une
  valeur qui n'a qu'une seule réponse correcte ;
- passer par `tailscale ip` plutôt que par le nom DNS `logipourtous` évite de
  dépendre de MagicDNS / du résolveur système de DSM ;
- recalculer l'adresse à chaque essai fait qu'un Maître pas encore visible dans le
  réseau au premier essai est rattrapé par le backoff au lieu d'échouer sur une URL
  vide.

Sur le Maître lui-même, aucune vérification n'est faite (il EST le Maître) et l'URL
transmise à son Agent est sa propre adresse Headscale sur `8072`.

### Configuration complémentaire de l'Agent

Le gabarit systemd (Task 8) ne fixe que `SERVER_ADDRESS` et `AGENT_PORT`. L'Agent
exige aussi (sans valeur par défaut côté Java) l'URL du Maître, le chemin du fichier
de clé API, le propriétaire GHCR, le fichier upstream Nginx et les ports bleu/vert
`18080`/`18081`. `postinst`/`postupgrade` écrivent donc un complément systemd
(`site-agent.service.d/10-site-nas.conf`) **avant** `installer_service_agent`, avec
les noms d'environnement canoniques Spring Boot (`APP_MAITRE_URL`,
`APP_DEPLOIEMENT_PORTBLEU`, …) et `SERVER_PORT=18090` — ils s'appliquent même si le jar
n'embarque aucun `application.properties` (fichier ignoré par git dans ce dépôt).
Il ajoute aussi `RestartSec=15` / `StartLimitIntervalSec=0` : l'Agent se lie à
l'adresse Headscale, qui peut apparaître après lui au démarrage du NAS.

### Première instance : port relu dans l'upstream

`postinst` démarre la première instance sur le port lu dans l'upstream qu'il vient
d'initialiser (= `18080` sur toute installation neuve), pour que l'instance démarrée
soit toujours celle vers laquelle Nginx relaie ; un conteneur résiduel du même nom
est retiré avant (`docker rm -f`, aucune donnée dedans).

## Construction

```bash
cd nas-package/spk
make spk SPK_ARCH=x86_64 \
  JRE_DIR=/chemin/jre-linux-x64 AGENT_JAR=/chemin/agent.jar \
  HEADSCALE_URL=https://<serveur-headscale> IMAGE_OWNER=<owner-ghcr> IMAGE_TAG=<tag>
# idem SPK_ARCH=armv8 avec un JRE linux-aarch64 ; ou make all-archs JRE_DIR_x86_64=... JRE_DIR_armv8=...
```

Produit `spk/dist/site-nas-<arch>-<version>.spk` : archive tar **POSIX ustar non
compressée** (jamais PAX — cause connue de l'erreur DSM « Invalid file format »),
membres listés explicitement, `INFO` en premier, sans préfixe `./`, propriétaire
0/0, scripts en `0755`, `checksum` d'`INFO` = MD5 de `package.tgz`. Le build refuse
les fichiers en fin de ligne CRLF (`core.autocrlf` sous Windows). Le JRE est propre
à l'architecture : un JRE minimal Linux (`jlink`) par architecture, fourni en entrée.

**Adaptation délibérée** : le `Makefile` reprend les noms de variables spksrc
(`SPK_NAME`, `SPK_VERS`, `SPK_REV`, `SPK_DEPENDS`, `WIZARDS_DIR`…) mais n'inclut pas
`mk/spksrc.spk.mk` : la chaîne spksrc n'a pas pu être installée dans l'environnement
où ce paquet a été écrit (téléchargement de code tiers bloqué), et le paquet ne
compile rien (comme l'exemple Docker officiel Synology). L'empaquetage est fait par
`build-spk.sh` avec GNU tar. Un portage dans un vrai arbre spksrc est mécanique.

## Limites connues (à trancher avant une première installation réelle)

- **Privilèges DSM 7** : les scripts doivent tourner en root (`systemctl`, `/etc/systemd`,
  `nginx -s reload`, `tailscale up`, `docker`). DSM 7 refuse d'installer un paquet
  tiers non signé par Synology qui demande `run-as: root`. Options : signature /
  dérogation Synology, ou refonte autour des « resource workers » DSM (systemd user
  unit, port config, data share, docker) — hors périmètre de ce chantier.
- **Tailscale sur DSM 7** : le paquet Tailscale tourne par défaut en mode « userspace
  networking » (pas d'interface `tailscale0`) ; une IP `100.x` n'est alors pas liable
  par Nginx/l'Agent et `curl` ne passe pas par le tunnel. Il faut activer le mode TUN
  (`tailscale configure-host`, à rejouer à chaque démarrage du NAS).
- **Nginx de DSM** : les fichiers vont dans le Nginx qui sert aussi l'interface DSM.
  Une écoute sur l'IP Headscale échoue si Nginx démarre avant Tailscale au boot.
- **Défauts relevés dans les bibliothèques déjà livrées** (non corrigés ici, hors
  fichiers de cette tâche) : `configurer_ecoute_nginx` écrit `proxy_pass` directement
  dans `server {}` (Nginx ne l'accepte que dans `location`) — `postinst` le détecte
  via `nginx -t`, retire ses fichiers et s'arrête proprement ; `demarrer_postgres`
  ne passe ni `POSTGRES_PASSWORD` ni réseau, et `demarrer_premiere_instance` ne passe
  aucune configuration (base, licence, clé API) au conteneur applicatif.
- Dépendances déclarées : `ContainerManager` (DSM ≥ 7.2) et `Tailscale` ; volume
  `/volume1` requis.
- Aucune installation sur un vrai DSM n'a encore été faite : la vérification a été
  structurelle (archive) + simulation des scripts avec commandes externes simulées.

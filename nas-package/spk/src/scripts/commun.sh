# Socle commun des scripts de cycle de vie du paquet DSM "site-nas".
# Source (jamais execute) par preinst/postinst/postupgrade/preuninst/postuninst/
# prestart/start-stop-status, qui sont tous livres a plat dans le dossier scripts/
# du .spk (meme convention que les fichiers "functions"/"service-setup" de spksrc).
#
# C'est ICI, et nulle part ailleurs, que les valeurs fixes du projet (Global
# Constraints du plan) sont ecrites litteralement ; chaque bibliotheque de
# nas-package/lib/ les recoit en parametre.

# Garde : start-stop-status source prestart.sh, qui source aussi ce fichier ;
# un 2e chargement echouerait sur les "readonly" ci-dessous.
if [ -n "${_SITE_NAS_COMMUN_CHARGE:-}" ]; then
  return 0
fi
_SITE_NAS_COMMUN_CHARGE=1

DOSSIER_SCRIPTS="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Valeurs figees a la construction du paquet (build-spk.sh genere ce fichier a
# partir des variables du Makefile : HEADSCALE_URL, IMAGE_OWNER, IMAGE_TAG).
# shellcheck source=/dev/null
. "$DOSSIER_SCRIPTS/constantes.sh"

# --- Valeurs fixes du projet (Global Constraints) -----------------------------
readonly PORT_APP_BLEU=18080        # port actif initial (convention du projet)
readonly PORT_APP_VERT=18081
readonly PORT_POSTGRES=15432
readonly PORT_AGENT=18090
readonly PORT_NGINX_ECOUTE=8072     # identique sur tous les NAS, Maitre compris
readonly NOM_HOTE_MAITRE="logipourtous"

# Reessai avec delai progressif (avec_reessai double le delai a chaque echec) :
#  - Headscale : 2+4+8+16+32 = 62 s d'attente cumulee au maximum ;
#  - Maitre    : 5+10+20+40+80 = 155 s d'attente cumulee au maximum.
readonly FENETRE_HEADSCALE=120
readonly DELAI_HEADSCALE=2
readonly FENETRE_MAITRE=300
readonly DELAI_MAITRE=5

# Version minimale du client Tailscale acceptee par le serveur Headscale (v0.29 :
# "Clients with a lower minimum version will be rejected", v1.80). Un client plus
# ancien (ex. le paquet du Centre de paquets Synology, 1.58) est rejete a la
# connexion avec une erreur peu lisible : on refuse donc des l'installation.
readonly TAILSCALE_VERSION_MIN_MAJEURE=1
readonly TAILSCALE_VERSION_MIN_MINEURE=80

# Code 0 si la version donnee (ex. "1.104.5") est >= 1.80, 1 sinon (y compris
# vide ou illisible : on ne laisse jamais passer une version qu'on ne comprend pas).
version_tailscale_suffisante() {
  local v="$1" majeur mineur
  [[ "$v" =~ ^([0-9]+)\.([0-9]+)(\.[0-9]+)?([^0-9].*)?$ ]] || return 1
  majeur="${BASH_REMATCH[1]}"; mineur="${BASH_REMATCH[2]}"
  if [ "$majeur" -gt "$TAILSCALE_VERSION_MIN_MAJEURE" ]; then return 0; fi
  [ "$majeur" -eq "$TAILSCALE_VERSION_MIN_MAJEURE" ] && [ "$mineur" -ge "$TAILSCALE_VERSION_MIN_MINEURE" ]
}

# --- Chemins (surchargeables par l'environnement : simulation hors NAS) -------
# Donnees : dossier partage DEDIE, hors de tout chemin gere par le cycle de vie
# DSM du paquet (/var/packages/<nom>/, /volumeX/@appstore|@appdata|@apphome|@apptemp).
: "${SITE_NAS_RACINE_DONNEES:=/volume1/site-data}"
# Nginx DSM : sites-enabled est inclus dans le bloc http de /etc/nginx/nginx.conf
# et survit aux mises a jour DSM (contrairement a /etc/nginx/ lui-meme).
: "${SITE_NAS_DOSSIER_NGINX:=/usr/local/etc/nginx/sites-enabled}"
: "${SITE_NAS_DOSSIER_SYSTEMD:=/etc/systemd/system}"

DOSSIER_DONNEES_POSTGRES="$SITE_NAS_RACINE_DONNEES/postgres"
DOSSIER_DONNEES_AGENT="$SITE_NAS_RACINE_DONNEES/agent"
FICHIER_CLE_API_AGENT="$DOSSIER_DONNEES_AGENT/cle-api-licence"
FICHIER_NGINX_UPSTREAM="$SITE_NAS_DOSSIER_NGINX/site-nas-upstream.conf"
FICHIER_NGINX_ECOUTE="$SITE_NAS_DOSSIER_NGINX/site-nas-ecoute.conf"
FICHIER_UNITE_AGENT="$SITE_NAS_DOSSIER_SYSTEMD/site-agent.service"
DOSSIER_DROPIN_AGENT="$SITE_NAS_DOSSIER_SYSTEMD/site-agent.service.d"
FICHIER_DROPIN_AGENT="$DOSSIER_DROPIN_AGENT/10-site-nas.conf"

# Contenu du paquet (package.tgz, extrait par DSM dans SYNOPKG_PKGDEST) : les
# bibliotheques lib/*.sh et systemd/ y sont a la meme place relative que dans le
# depot, donc NAS_PKG_DIR (convention des Tasks 2-9) = racine du contenu.
NAS_PKG_DIR="${SYNOPKG_PKGDEST:-/var/packages/${SYNOPKG_PKGNAME:-site-nas}/target}"
CHEMIN_JRE="$NAS_PKG_DIR/jre/bin/java"
CHEMIN_JAR_AGENT="$NAS_PKG_DIR/agent/agent.jar"
IMAGE_APPLICATION="ghcr.io/${IMAGE_OWNER}/site:${IMAGE_TAG}"

# Binaires fournis par les paquets dont celui-ci depend (INFO install_dep_packages) :
# ils ne sont pas toujours dans le PATH minimal sous lequel DSM lance les scripts.
# Les dossiers systeme usuels sont ajoutes EN FIN (repli), pas devant le PATH recu.
PATH="/var/packages/Tailscale/target/bin:/var/packages/ContainerManager/target/usr/bin:$PATH:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin"
export PATH

# --- Canal d'avertissement (Global Constraints : mecanismes DSM natifs) -------
# Remplace le _exec_log_dsm par defaut de lib/ports.sh (stderr), comme prevu par
# la Task 5 : texte de fin d'operation du Centre de paquets (SYNOPKG_TEMP_LOGFILE,
# en ajout pour cumuler plusieurs messages) + journal systeme (logger/syslog).
# Defini AVANT de sourcer lib/ports.sh, qui ne le redefinit alors pas.
_exec_log_dsm() {
  if [ -n "${SYNOPKG_TEMP_LOGFILE:-}" ]; then
    printf '%s\n' "$1" >> "$SYNOPKG_TEMP_LOGFILE"
  fi
  if command -v logger >/dev/null 2>&1; then
    logger -t site-nas -p user.warning -- "$1"
  fi
  return 0
}

charger_bibliotheques() {
  local lib
  for lib in role headscale maitre ports postgres_bootstrap nginx_bascule agent_service docker_bootstrap; do
    if [ ! -r "$NAS_PKG_DIR/lib/$lib.sh" ]; then
      printf 'Bibliotheque manquante : %s\n' "$NAS_PKG_DIR/lib/$lib.sh" >&2
      return 1
    fi
    # shellcheck source=/dev/null
    . "$NAS_PKG_DIR/lib/$lib.sh"
  done
}

# Code 0 si le chemin donne est absolu et hors de toute arborescence que DSM
# associe au cycle de vie d'un paquet (et donc nettoie a la desinstallation).
# /var/packages/<nom>/target est un lien vers /volumeX/@appstore/<nom> : on
# controle donc le chemin tel quel ET sa forme resolue.
dossier_hors_cycle_de_vie() {
  local chemin="$1" resolu
  case "$chemin" in
    /*) ;;
    *) return 1 ;;
  esac
  resolu="$(readlink -f -- "$chemin" 2>/dev/null || printf '%s' "$chemin")"
  local c
  for c in "$chemin" "$resolu"; do
    case "$c" in
      /var/packages/*|*/@appstore/*|*/@appstore|*/@appdata/*|*/@appdata|*/@apphome/*|*/@apphome|*/@apptemp/*|*/@apptemp)
        return 1 ;;
    esac
    case "$c/" in
      "$NAS_PKG_DIR"/*) return 1 ;;
    esac
  done
  return 0
}

# Port actif lu dans le fichier upstream (meme expression que
# BasculeNginx.portActifActuel cote Agent). PORT_APP_BLEU si le fichier est absent.
port_actif_nginx() {
  local port
  if [ -r "$FICHIER_NGINX_UPSTREAM" ]; then
    port="$(sed -n 's/.*server 127\.0\.0\.1:\([0-9][0-9]*\);.*/\1/p' "$FICHIER_NGINX_UPSTREAM" | head -n 1)"
  fi
  printf '%s\n' "${port:-$PORT_APP_BLEU}"
}

# URL du Maitre, par CONVENTION FIXE du projet (voir README, "URL_MAITRE") :
# l'adresse Headscale du noeud nomme "logipourtous", obtenue aupres du client
# Tailscale lui-meme (carte du reseau Headscale, sans dependre d'un DNS), sur le
# port d'ecoute Nginx 8072 commun a tous les NAS. Code 1 si le Maitre est encore
# inconnu du reseau.
url_maitre() {
  local ip
  ip="$(_exec_tailscale ip -4 "$NOM_HOTE_MAITRE" 2>/dev/null | head -n 1)" || return 1
  [ -n "$ip" ] || return 1
  printf 'http://%s:%s\n' "$ip" "$PORT_NGINX_ECOUTE"
}

# Commande reessayable par avec_reessai : l'URL est recalculee a chaque essai,
# pour qu'un Maitre pas encore annonce par Headscale au premier essai soit
# rattrape par le backoff au lieu de figer une URL vide.
maitre_joignable_par_convention() {
  local url
  url="$(url_maitre)" || return 1
  maitre_est_joignable "$url"
}

# Verification 1 de postinst (Headscale), reutilisee telle quelle par prestart.
# $1 : cle saisie dans l'assistant (vide = ne pas tenter de connexion).
verifier_headscale() {
  local cle="${1:-}"
  # Mode TUN d'abord : perdu a chaque redemarrage du NAS (voir lib/headscale.sh).
  if ! assurer_interface_tailscale; then
    avertir_utilisateur "L'interface reseau Tailscale (tailscale0) n'est pas disponible : sans elle, l'adresse Headscale ne peut pas etre utilisee. Rien n'a ete demarre. Lancez 'sudo tailscale configure-host' puis redemarrez le paquet Tailscale."
    return 1
  fi
  if ! headscale_est_connecte; then
    if [ -n "$cle" ]; then
      export HEADSCALE_URL
      if ! headscale_connecter "$cle"; then
        avertir_utilisateur "Connexion Headscale refusee avec la cle fournie (serveur $HEADSCALE_URL). Verifiez que la cle n'a pas expire."
      fi
    fi
  fi
  if ! avec_reessai "$FENETRE_HEADSCALE" "$DELAI_HEADSCALE" headscale_est_connecte; then
    avertir_utilisateur "Ce NAS n'est pas connecte au reseau Headscale ($HEADSCALE_URL). Rien n'a ete demarre. Fournissez une cle de connexion valide (non expiree) et relancez l'operation."
    return 1
  fi
  return 0
}

# Verification 3 de postinst (Maitre), reutilisee telle quelle par prestart.
verifier_maitre() {
  if nas_est_maitre; then
    return 0
  fi
  if ! avec_reessai "$FENETRE_MAITRE" "$DELAI_MAITRE" maitre_joignable_par_convention; then
    avertir_utilisateur "Headscale est connecte mais le Maitre ($NOM_HOTE_MAITRE, port $PORT_NGINX_ECOUTE) ne repond pas sur /actuator/health. Reessayez plus tard ; aucune donnee n'a ete modifiee."
    return 1
  fi
  return 0
}

# Ecrit (atomiquement) le complement systemd de l'unite de l'Agent : les
# proprietes que le gabarit livre en Task 8 ne porte pas (il ne fixe que
# SERVER_ADDRESS/AGENT_PORT) mais dont l'Agent a besoin, sans aucune valeur par
# defaut cote Java (voir agent/src/main/resources/application.properties.example).
# Noms d'environnement canoniques Spring Boot (liaison relachee) : ils alimentent
# les @Value(app.*) meme si le jar n'embarque aucun application.properties.
# A ecrire AVANT installer_service_agent (son daemon-reload la prend en compte).
ecrire_complement_agent() {
  local url_maitre_agent="$1"
  mkdir -p "$DOSSIER_DROPIN_AGENT" || return 1
  local temp
  temp="$(mktemp "$DOSSIER_DROPIN_AGENT/.10-site-nas.conf.XXXXXX")" || return 1
  cat > "$temp" <<EOF
# Genere par le paquet DSM site-nas (postinst/postupgrade) : ne pas editer a la main.
[Unit]
StartLimitIntervalSec=0

[Service]
RestartSec=15
WorkingDirectory=$DOSSIER_DONNEES_AGENT
Environment=SERVER_PORT=$PORT_AGENT
Environment=APP_MAITRE_URL=$url_maitre_agent
Environment=APP_MAITRE_CLEAPIFILEPATH=$FICHIER_CLE_API_AGENT
Environment=APP_DEPLOIEMENT_DOCKERIMAGEOWNER=$IMAGE_OWNER
Environment=APP_DEPLOIEMENT_PORTBLEU=$PORT_APP_BLEU
Environment=APP_DEPLOIEMENT_PORTVERT=$PORT_APP_VERT
Environment=APP_NGINX_FICHIERCONFIGUPSTREAM=$FICHIER_NGINX_UPSTREAM
EOF
  mv "$temp" "$FICHIER_DROPIN_AGENT"
}

# URL du Maitre transmise a l'Agent : sur le Maitre lui-meme, sa propre adresse
# Headscale ; ailleurs, la convention url_maitre.
url_maitre_pour_agent() {
  local adresse_locale="$1"
  if nas_est_maitre; then
    printf 'http://%s:%s\n' "$adresse_locale" "$PORT_NGINX_ECOUTE"
  else
    url_maitre
  fi
}

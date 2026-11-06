NAS_PKG_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# Environnement factice : dossier de donnees avec mot de passe Postgres, fichier upstream actif sur 18080,
# commandes externes remplacees par des fonctions qui journalisent dans APPELS.
_preparer_maj() {
  T=$(mktemp -d); mkdir -p "$T/data/postgres" "$T/nginx" "$T/scripts"
  echo mdp > "$T/data/.postgres-password"
  printf 'upstream site_actif {\n  server 127.0.0.1:18080;\n}\n' > "$T/nginx/site-nas-upstream.conf"
  printf 'HEADSCALE_URL=https://hs.test\nIMAGE_OWNER=proprio\nIMAGE_TAG=v1.2\nGITHUB_REPO=\n' > "$T/scripts/constantes.sh"
  cp "$NAS_PKG_DIR/spk/src/scripts/commun.sh" "$T/scripts/"
  APPELS=""; SANTE_NOUVELLE=oui; NGINX_OK=oui
  export SITE_NAS_RACINE_DONNEES="$T/data" SITE_NAS_DOSSIER_NGINX="$T/nginx" SYNOPKG_PKGDEST="$NAS_PKG_DIR"
  source "$T/scripts/commun.sh"
  # commun.sh ne se charge qu'une fois par processus (garde) : les chemins sont donc reaffectes ici
  # pour que chaque test travaille dans SON dossier temporaire.
  FICHIER_NGINX_UPSTREAM="$T/nginx/site-nas-upstream.conf"
  DOSSIER_DONNEES_POSTGRES="$T/data/postgres"
  charger_bibliotheques
  _exec_docker() { APPELS="$APPELS|d:$*"; case "$1" in pull) [ "${PULL_OK:-oui}" = oui ] ;; *) return 0 ;; esac; }
  _exec_nginx() { APPELS="$APPELS|n:$*"; [ "$NGINX_OK" = oui ]; }
  _exec_curl() {
    case "$*" in
      *:18081/*) [ "$SANTE_NOUVELLE" = oui ] && echo '{"status":"UP"}' ;;
      *:18080/*) echo '{"status":"UP"}' ;;
    esac
  }
  _exec_sleep() { :; }
  _exec_chown() { :; }
  _exec_log_dsm() { :; }
  MAJ_SANTE_ESSAIS=3
}

test_mise_a_jour_bascule_vers_le_port_inactif_puis_arrete_l_ancienne() {
  _preparer_maj
  mettre_a_jour_maitre v1.3 >/dev/null; code=$?
  [ "$code" -eq 0 ] && grep -q "server 127.0.0.1:18081;" "$FICHIER_NGINX_UPSTREAM" || return 1
  [[ "$APPELS" == *"d:pull ghcr.io/proprio/site:v1.3"* && "$APPELS" == *"d:run -d --name site-18081"* \
     && "$APPELS" == *"-e SERVER_PORT=18081"* && "$APPELS" == *"--network host"* && "$APPELS" == *"SPRING_PROFILES_ACTIVE=prod"* \
     && "$APPELS" == *"n:-s reload"* && "$APPELS" == *"d:stop site-18080"* ]]
}

test_mise_a_jour_garde_l_ancienne_version_si_la_nouvelle_n_est_jamais_saine() {
  _preparer_maj
  SANTE_NOUVELLE=non
  mettre_a_jour_maitre v1.3 >/dev/null; code=$?
  [ "$code" -eq 1 ] && grep -q "server 127.0.0.1:18080;" "$FICHIER_NGINX_UPSTREAM" \
    && [[ "$APPELS" != *"d:stop site-18080"* && "$APPELS" != *"n:-s reload"* && "$APPELS" == *"d:stop site-18081"* ]]
}

test_mise_a_jour_restaure_l_upstream_si_nginx_refuse_la_bascule() {
  _preparer_maj
  NGINX_OK=non
  mettre_a_jour_maitre v1.3 >/dev/null; code=$?
  [ "$code" -eq 1 ] && grep -q "server 127.0.0.1:18080;" "$FICHIER_NGINX_UPSTREAM" \
    && [[ "$APPELS" != *"d:stop site-18080"* ]]
}

test_mise_a_jour_ne_touche_a_rien_si_l_image_n_existe_pas() {
  _preparer_maj
  PULL_OK=non
  mettre_a_jour_maitre v9.9 >/dev/null; code=$?
  unset PULL_OK
  [ "$code" -eq 1 ] && [[ "$APPELS" != *"d:run"* && "$APPELS" != *"d:rm"* && "$APPELS" != *"d:stop"* ]]
}

test_mise_a_jour_refuse_un_tag_invalide() {
  _preparer_maj
  mettre_a_jour_maitre 'v1.3; rm -rf /' >/dev/null; a=$?
  mettre_a_jour_maitre '' >/dev/null; b=$?
  mettre_a_jour_maitre 'latest' >/dev/null; c=$?
  [ "$a" -eq 2 ] && [ "$b" -eq 2 ] && [ "$c" -eq 2 ] && [ -z "$APPELS" ]
}

test_mise_a_jour_repart_du_port_vert_quand_il_est_actif() {
  _preparer_maj
  printf 'upstream site_actif {\n  server 127.0.0.1:18081;\n}\n' > "$FICHIER_NGINX_UPSTREAM"
  _exec_curl() { echo '{"status":"UP"}'; }
  mettre_a_jour_maitre v1.4 >/dev/null; code=$?
  [ "$code" -eq 0 ] && grep -q "server 127.0.0.1:18080;" "$FICHIER_NGINX_UPSTREAM" \
    && [[ "$APPELS" == *"d:run -d --name site-18080"* && "$APPELS" == *"d:stop site-18081"* ]]
}

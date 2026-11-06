NAS_PKG_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

test_initialiser_nginx_local_cree_le_fichier_au_bon_format_si_absent() {
  dir=$(mktemp -d)
  source "$NAS_PKG_DIR/lib/nginx_bascule.sh"
  initialiser_nginx_local "$dir/upstream.conf" 18080
  grep -q "server 127.0.0.1:18080;" "$dir/upstream.conf"
}

test_initialiser_nginx_local_ne_touche_pas_un_fichier_deja_present() {
  dir=$(mktemp -d)
  printf 'server 127.0.0.1:18081;\n' > "$dir/upstream.conf"
  source "$NAS_PKG_DIR/lib/nginx_bascule.sh"
  initialiser_nginx_local "$dir/upstream.conf" 18080
  grep -q "server 127.0.0.1:18081;" "$dir/upstream.conf"
}

test_configurer_ecoute_nginx_cree_le_bloc_server_au_bon_format_si_absent() {
  dir=$(mktemp -d)
  source "$NAS_PKG_DIR/lib/nginx_bascule.sh"
  configurer_ecoute_nginx "$dir/server.conf" "100.64.0.12" 8072
  grep -q "listen 100.64.0.12:8072;" "$dir/server.conf" \
    && grep -q "proxy_pass http://site_actif;" "$dir/server.conf"
}

test_configurer_ecoute_nginx_ecrase_les_en_tetes_x_forwarded_plutot_que_de_les_relayer() {
  # sans ces directives, un client pourrait injecter son propre X-Forwarded-For
  # et le faire relayer tel quel a l'application (usurpation d'IP dans les logs)
  dir=$(mktemp -d)
  source "$NAS_PKG_DIR/lib/nginx_bascule.sh"
  configurer_ecoute_nginx "$dir/server.conf" "100.64.0.12" 8072
  grep -Fq 'proxy_set_header X-Real-IP $remote_addr;' "$dir/server.conf" \
    && grep -Fq 'proxy_set_header X-Forwarded-For $remote_addr;' "$dir/server.conf" \
    && grep -Fq 'proxy_set_header X-Forwarded-Proto $scheme;' "$dir/server.conf" \
    && grep -Fq 'proxy_set_header Host $host;' "$dir/server.conf"
}

test_configurer_ecoute_nginx_place_proxy_pass_dans_un_bloc_location() {
  # nginx n'autorise proxy_pass que dans un contexte location (ou if/limit_except) ;
  # le mettre directement dans server{} est une erreur de syntaxe reelle (nginx -t echoue)
  dir=$(mktemp -d)
  source "$NAS_PKG_DIR/lib/nginx_bascule.sh"
  configurer_ecoute_nginx "$dir/server.conf" "100.64.0.12" 8072
  awk '/location[[:space:]]*\/[[:space:]]*\{/{dans_location=1} /proxy_pass/{if (dans_location) trouve=1} /^\}/{dans_location=0} END{exit !trouve}' "$dir/server.conf"
}

test_configurer_ecoute_nginx_ne_touche_pas_un_fichier_deja_present() {
  dir=$(mktemp -d)
  printf 'listen 100.64.0.99:9999;\n' > "$dir/server.conf"
  source "$NAS_PKG_DIR/lib/nginx_bascule.sh"
  configurer_ecoute_nginx "$dir/server.conf" "100.64.0.12" 8072
  grep -q "100.64.0.99:9999" "$dir/server.conf"
}

test_activer_ecoute_nginx_copie_valide_puis_recharge() {
  dir=$(mktemp -d); mkdir "$dir/sites"; appels=""
  printf 'listen 100.64.0.12:8072;\n' > "$dir/source.conf"
  _exec_nginx() { appels="$appels|$*"; }
  source "$NAS_PKG_DIR/lib/nginx_bascule.sh"
  activer_ecoute_nginx "$dir/source.conf" "$dir/sites/ecoute.conf"
  grep -q "100.64.0.12:8072" "$dir/sites/ecoute.conf" && [ "$appels" = "|-t|-s reload" ] && [ -e "$dir/source.conf" ]
}

test_activer_ecoute_nginx_retire_le_fichier_si_nginx_t_echoue() {
  dir=$(mktemp -d); mkdir "$dir/sites"; appels=""
  printf 'listen 100.64.0.12:8072;\n' > "$dir/source.conf"
  _exec_nginx() { appels="$appels|$*"; [ "$1" != "-t" ]; }
  source "$NAS_PKG_DIR/lib/nginx_bascule.sh"
  activer_ecoute_nginx "$dir/source.conf" "$dir/sites/ecoute.conf"; code=$?
  [ "$code" -ne 0 ] && [ ! -e "$dir/sites/ecoute.conf" ] && [ "$appels" = "|-t" ]
}

test_activer_ecoute_nginx_echoue_sans_fichier_source() {
  dir=$(mktemp -d); mkdir "$dir/sites"
  _exec_nginx() { :; }
  source "$NAS_PKG_DIR/lib/nginx_bascule.sh"
  activer_ecoute_nginx "$dir/absent.conf" "$dir/sites/ecoute.conf"; code=$?
  [ "$code" -ne 0 ] && [ ! -e "$dir/sites/ecoute.conf" ]
}

test_desactiver_ecoute_nginx_retire_la_cible_et_garde_la_source() {
  dir=$(mktemp -d); : > "$dir/source.conf"; : > "$dir/cible.conf"
  source "$NAS_PKG_DIR/lib/nginx_bascule.sh"
  desactiver_ecoute_nginx "$dir/cible.conf"
  desactiver_ecoute_nginx "$dir/cible.conf"
  [ ! -e "$dir/cible.conf" ] && [ -e "$dir/source.conf" ]
}

test_installer_nettoyage_ecoute_nginx_ecrit_l_unite_avec_execstop_et_l_active() {
  dir=$(mktemp -d); appels=""
  _exec_systemctl() { appels="$appels|$*"; }
  source "$NAS_PKG_DIR/lib/nginx_bascule.sh"
  installer_nettoyage_ecoute_nginx "$dir/u.service" "/usr/local/etc/nginx/sites-enabled/site-nas-ecoute.conf"
  grep -q "ExecStop=/bin/rm -f /usr/local/etc/nginx/sites-enabled/site-nas-ecoute.conf" "$dir/u.service" \
    && [ "$appels" = "|daemon-reload|enable site-nas-nginx|start site-nas-nginx" ]
}

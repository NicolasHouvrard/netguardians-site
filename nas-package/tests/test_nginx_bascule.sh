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

NAS_PKG_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

test_domaine_valide_accepte_un_nom_normal_et_refuse_tout_le_reste() {
  source "$NAS_PKG_DIR/lib/vps.sh"
  domaine_valide "app.logicielpourtous.com" || return 1
  domaine_valide "a-b.exemple.fr" || return 1
  ! domaine_valide "" && ! domaine_valide "localhost" && ! domaine_valide "a b.com" \
    && ! domaine_valide 'x.com; rm -rf /' && ! domaine_valide 'x.com{}' && ! domaine_valide '-x.com' && ! domaine_valide 'x..com'
}

test_ecrire_sysctl_ecoute_tardive_ecrit_le_reglage_et_l_applique() {
  dir=$(mktemp -d); appel=""
  _exec_sysctl() { appel="$*"; }
  source "$NAS_PKG_DIR/lib/vps.sh"
  ecrire_sysctl_ecoute_tardive "$dir/99-site-nas.conf"
  grep -q "^net.ipv4.ip_nonlocal_bind = 1" "$dir/99-site-nas.conf" && [ "$appel" = "-p $dir/99-site-nas.conf" ]
}

test_ecrire_vhost_http_valide_puis_garde_le_fichier() {
  dir=$(mktemp -d); _exec_nginx() { return 0; }
  source "$NAS_PKG_DIR/lib/vps.sh"
  ecrire_vhost_http "$dir/public.conf" "app.exemple.com" \
    && grep -q "server_name app.exemple.com;" "$dir/public.conf" && grep -q "return 301 https://" "$dir/public.conf"
}

test_ecrire_vhost_retire_le_fichier_si_nginx_refuse() {
  dir=$(mktemp -d); _exec_nginx() { return 1; }
  source "$NAS_PKG_DIR/lib/vps.sh"
  ecrire_vhost_http "$dir/public.conf" "app.exemple.com"; code=$?
  [ "$code" -ne 0 ] && [ ! -e "$dir/public.conf" ]
}

test_ecrire_vhost_restaure_l_ancien_contenu_si_nginx_refuse_le_nouveau() {
  dir=$(mktemp -d); printf 'ancien contenu\n' > "$dir/public.conf"; _exec_nginx() { return 1; }
  source "$NAS_PKG_DIR/lib/vps.sh"
  ecrire_vhost_https "$dir/public.conf" "app.exemple.com"; code=$?
  [ "$code" -ne 0 ] && [ "$(cat "$dir/public.conf")" = "ancien contenu" ]
}

test_ecrire_vhost_https_relaie_vers_l_upstream_bleu_vert_avec_le_certificat_du_domaine() {
  dir=$(mktemp -d); _exec_nginx() { return 0; }
  source "$NAS_PKG_DIR/lib/vps.sh"
  ecrire_vhost_https "$dir/public.conf" "app.exemple.com" || return 1
  grep -q "proxy_pass http://site_actif;" "$dir/public.conf" \
    && grep -q "ssl_certificate     /etc/letsencrypt/live/app.exemple.com/fullchain.pem;" "$dir/public.conf" \
    && grep -Fq 'proxy_set_header X-Forwarded-For $remote_addr;' "$dir/public.conf" \
    && grep -Fq 'proxy_set_header X-Forwarded-Proto https;' "$dir/public.conf"
}

test_ecrire_vhost_refuse_un_domaine_invalide_sans_rien_ecrire() {
  dir=$(mktemp -d); _exec_nginx() { return 0; }
  source "$NAS_PKG_DIR/lib/vps.sh"
  ecrire_vhost_http "$dir/public.conf" 'x.com; evil'; code=$?
  [ "$code" -ne 0 ] && [ ! -e "$dir/public.conf" ]
}

test_obtenir_certificat_utilise_le_plugin_nginx_et_s_inscrit_sans_email_si_aucun_compte() {
  dir=$(mktemp -d); mkdir "$dir/comptes"; appel=""
  _exec_certbot() { appel="$*"; }
  source "$NAS_PKG_DIR/lib/vps.sh"
  obtenir_certificat "app.exemple.com" "$dir/comptes"
  [[ "$appel" == *"certonly --nginx -d app.exemple.com"* && "$appel" == *"--non-interactive"* && "$appel" == *"--register-unsafely-without-email"* ]]
}

test_obtenir_certificat_reutilise_le_compte_certbot_existant() {
  dir=$(mktemp -d); mkdir -p "$dir/comptes/acme"; appel=""
  _exec_certbot() { appel="$*"; }
  source "$NAS_PKG_DIR/lib/vps.sh"
  obtenir_certificat "app.exemple.com" "$dir/comptes"
  [[ "$appel" != *"register-unsafely"* ]]
}

test_definir_reglage_ajoute_remplace_et_conserve_les_autres_lignes() {
  dir=$(mktemp -d); printf 'CAPTCHA_ENABLED=false\nAPP_ROLE=MASTER\n' > "$dir/app.env"
  source "$NAS_PKG_DIR/lib/vps.sh"
  definir_reglage "$dir/app.env" APP_ROLE PARTNER && definir_reglage "$dir/app.env" NOUVEAU "a=b c"
  [ "$(grep -c '^APP_ROLE=' "$dir/app.env")" -eq 1 ] && grep -q '^APP_ROLE=PARTNER$' "$dir/app.env" \
    && grep -q '^CAPTCHA_ENABLED=false$' "$dir/app.env" && grep -q '^NOUVEAU=a=b c$' "$dir/app.env"
}

test_definir_reglage_refuse_une_cle_ou_une_valeur_dangereuse() {
  dir=$(mktemp -d); : > "$dir/app.env"
  source "$NAS_PKG_DIR/lib/vps.sh"
  definir_reglage "$dir/app.env" 'bad key' x; a=$?
  definir_reglage "$dir/app.env" 'a-b' x; b=$?
  definir_reglage "$dir/app.env" CLE $'ligne1\nligne2'; c=$?
  [ "$a" -ne 0 ] && [ "$b" -ne 0 ] && [ "$c" -ne 0 ] && [ ! -s "$dir/app.env" ]
}

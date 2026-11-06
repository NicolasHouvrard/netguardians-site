# Installation sur un serveur Linux generique (VPS Ubuntu) qui SERT DEJA D'AUTRES SITES via son Nginx :
# toute erreur de configuration Nginx peut faire tomber ces sites. Chaque ecriture est donc validee
# par "nginx -t" et retiree si elle est refusee. Bibliotheque : destinee a etre sourcee.

if ! declare -F _exec_nginx >/dev/null; then _exec_nginx() { nginx "$@"; }; fi
if ! declare -F _exec_sysctl >/dev/null; then _exec_sysctl() { sysctl "$@"; }; fi
if ! declare -F _exec_certbot >/dev/null; then _exec_certbot() { certbot "$@"; }; fi

# Autorise Nginx a ecouter sur une adresse qui n'existe pas (encore) : l'adresse Headscale n'apparait
# qu'une fois Tailscale demarre. Sans cela, "listen 100.x.x.x:8072" empecherait Nginx de DEMARRER au
# boot si Tailscale est en retard, et donc tous les sites du serveur avec lui. Applique tout de suite
# et a chaque demarrage (sysctl.d est lu tres tot au boot, avant Nginx).
#   fichier_sysctl : ex. /etc/sysctl.d/99-site-nas.conf
ecrire_sysctl_ecoute_tardive() {
  local fichier="$1"
  printf '# Genere par le paquet site-nas : ecoute Nginx sur l adresse Headscale avant sa creation.\nnet.ipv4.ip_nonlocal_bind = 1\n' > "$fichier" || return 1
  chmod 644 "$fichier"
  _exec_sysctl -p "$fichier" >/dev/null
}

# Nom de domaine : lettres, chiffres, points, tirets, au moins un point (insere dans une config Nginx
# executee en root : aucun autre caractere ne doit passer).
domaine_valide() {
  [[ "$1" =~ ^([A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?\.)+[A-Za-z]{2,}$ ]]
}

# Ecrit un fichier de configuration Nginx, le valide, et le retire (ou restaure l'ancien) si Nginx le
# refuse. Code 0 : en place et valide (non recharge). Code 1 : refuse, etat precedent retabli.
#   fichier  contenu (stdin)
_ecrire_conf_nginx_validee() {
  local fichier="$1" precedent="" existait=0 temp
  if [ -e "$fichier" ]; then existait=1; precedent="$(cat "$fichier")"; fi
  temp="$(mktemp "$(dirname "$fichier")/.site-nas.XXXXXX")" || return 1
  cat > "$temp" || { rm -f "$temp"; return 1; }
  chmod 644 "$temp"
  mv -f "$temp" "$fichier" || { rm -f "$temp"; return 1; }
  if ! _exec_nginx -t >/dev/null 2>&1; then
    if [ "$existait" -eq 1 ]; then printf '%s\n' "$precedent" > "$fichier"; else rm -f "$fichier"; fi
    return 1
  fi
  return 0
}

# Bloc HTTP (port 80) du domaine public : sert le defi Let's Encrypt (via le plugin nginx de certbot)
# puis redirige vers HTTPS.
#   fichier  domaine
ecrire_vhost_http() {
  local fichier="$1" domaine="$2"
  domaine_valide "$domaine" || return 1
  _ecrire_conf_nginx_validee "$fichier" <<EOF
server {
  listen 80;
  listen [::]:80;
  server_name $domaine;
  location / { return 301 https://\$host\$request_uri; }
}
EOF
}

# Bloc HTTPS du domaine public, qui relaie vers l'upstream "site_actif" (bleu-vert, voir
# initialiser_nginx_local). Remplace le bloc HTTP du meme fichier.
#   fichier  domaine
ecrire_vhost_https() {
  local fichier="$1" domaine="$2"
  domaine_valide "$domaine" || return 1
  _ecrire_conf_nginx_validee "$fichier" <<EOF
server {
  listen 80;
  listen [::]:80;
  server_name $domaine;
  location / { return 301 https://\$host\$request_uri; }
}

server {
  listen 443 ssl;
  listen [::]:443 ssl;
  http2 on;
  server_name $domaine;

  ssl_certificate     /etc/letsencrypt/live/$domaine/fullchain.pem;
  ssl_certificate_key /etc/letsencrypt/live/$domaine/privkey.pem;

  client_max_body_size 50m;

  location / {
    proxy_pass http://site_actif;
    proxy_set_header Host \$host;
    proxy_set_header X-Real-IP \$remote_addr;
    proxy_set_header X-Forwarded-For \$remote_addr;
    proxy_set_header X-Forwarded-Proto https;
    proxy_read_timeout 120s;
  }
}
EOF
}

# Obtient le certificat Let's Encrypt du domaine (le bloc HTTP doit deja etre en place et le DNS du
# domaine pointer sur ce serveur). Utilise le compte certbot existant ; sinon s'inscrit sans e-mail.
#   domaine  [repertoire_comptes_certbot]
obtenir_certificat() {
  local domaine="$1" comptes="${2:-/etc/letsencrypt/accounts}" options=()
  domaine_valide "$domaine" || return 1
  if [ -z "$(ls -A "$comptes" 2>/dev/null)" ]; then
    options+=(--register-unsafely-without-email)
  fi
  _exec_certbot certonly --nginx -d "$domaine" --non-interactive --agree-tos "${options[@]}"
}

# Definit (ou remplace) une ligne CLE=valeur dans le fichier de reglages de l'application
# (docker --env-file). La cle est restreinte a [A-Z0-9_], la valeur ne peut pas contenir de saut de
# ligne : le fichier est relu tel quel par Docker.
#   fichier  CLE  valeur
definir_reglage() {
  local fichier="$1" cle="$2" valeur="$3" temp
  [[ "$cle" =~ ^[A-Z][A-Z0-9_]*$ ]] || return 1
  case "$valeur" in *$'\n'*|*$'\r'*) return 1 ;; esac
  temp="$(umask 077; mktemp "$fichier.XXXXXX")" || return 1
  { [ -f "$fichier" ] && grep -v "^${cle}=" "$fichier"; printf '%s=%s\n' "$cle" "$valeur"; } > "$temp" || { rm -f "$temp"; return 1; }
  mv -f "$temp" "$fichier"
}

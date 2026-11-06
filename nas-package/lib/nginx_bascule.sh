# Nginx local de bascule bleu-vert et d'ecoute publique pour l'installeur NAS.
# Bibliotheque : destinee a etre sourcee, pas executee directement.

# Execution de nginx, isolee pour pouvoir etre remplacee dans les tests.
# Definie seulement si absente, pour ne pas ecraser un remplacement deja en place.
if ! declare -F _exec_nginx >/dev/null; then
  _exec_nginx() { nginx "$@"; }
fi

# Ecrit le fichier upstream initial, au meme format que celui relu/ecrit par
# BasculeNginx (Agent, chantier bleu-vert) via portActifActuel() :
# "server 127.0.0.1:<port>;". SEULEMENT si le fichier n'existe pas deja, pour
# ne jamais ecraser une bascule deja effectuee par l'Agent (reinstallation,
# mise a jour).
initialiser_nginx_local() {
  local fichier_upstream="$1" port_actif_initial="$2"

  if [ -e "$fichier_upstream" ]; then
    return 0
  fi

  cat > "$fichier_upstream" <<EOF
upstream site_actif {
  server 127.0.0.1:${port_actif_initial};
}
EOF
}

# Ecrit le bloc server public qui relaie vers l'upstream "site_actif" defini
# par initialiser_nginx_local()/BasculeNginx. SEULEMENT si le fichier
# n'existe pas deja (idempotent, jamais destructif sur une configuration
# d'ecoute deja en place).
configurer_ecoute_nginx() {
  local fichier_serveur="$1" adresse_ip_liaison="$2" port_ecoute="$3"

  if [ -e "$fichier_serveur" ]; then
    return 0
  fi

  cat > "$fichier_serveur" <<EOF
server {
  listen ${adresse_ip_liaison}:${port_ecoute};
  location / {
    proxy_pass http://site_actif;
    proxy_set_header Host \$host;
    proxy_set_header X-Real-IP \$remote_addr;
    proxy_set_header X-Forwarded-For \$remote_addr;
    proxy_set_header X-Forwarded-Proto \$scheme;
  }
}
EOF
}

# Execution de systemctl, isolee pour les tests (meme convention que agent_service.sh).
if ! declare -F _exec_systemctl >/dev/null; then
  _exec_systemctl() { systemctl "$@"; }
fi

# Rend l'ecoute Headscale active : copie le fichier source (garde HORS de sites-enabled) dans
# sites-enabled, valide la configuration, recharge Nginx. A n'appeler que lorsque l'interface
# Headscale (tailscale0) existe : "listen <adresse Headscale>" fait echouer "nginx -t" tant que
# l'adresse n'existe pas, ce qui empeche DSM de generer la configuration web de ses paquets
# (File Station, Synology Drive...). En cas de refus de nginx -t, le fichier est retire.
#   fichier_source : ecoute complete (ecrite par configurer_ecoute_nginx)
#   fichier_cible  : emplacement dans sites-enabled
activer_ecoute_nginx() {
  local fichier_source="$1" fichier_cible="$2" temp
  [ -r "$fichier_source" ] || return 1
  temp="$(mktemp "$(dirname "$fichier_cible")/.site-nas-ecoute.XXXXXX")" || return 1
  if ! cp -- "$fichier_source" "$temp"; then rm -f "$temp"; return 1; fi
  chmod 644 "$temp"
  mv -f "$temp" "$fichier_cible" || { rm -f "$temp"; return 1; }
  if ! _exec_nginx -t >/dev/null 2>&1; then
    rm -f "$fichier_cible"
    return 1
  fi
  _exec_nginx -s reload
}

# Retire l'ecoute de sites-enabled (sans toucher au fichier source). Idempotent.
desactiver_ecoute_nginx() {
  rm -f -- "$1"
}

# Unite systemd qui retire l'ecoute de sites-enabled A L'ARRET du NAS : au demarrage suivant,
# Nginx (et le test de configuration de DSM) ne rencontre alors jamais d'adresse Headscale
# absente. Au demarrage elle ne fait rien ; c'est son ExecStop qui travaille.
#   fichier_unite_cible : .service a ecrire     fichier_a_retirer : ecoute dans sites-enabled
installer_nettoyage_ecoute_nginx() {
  local fichier_unite_cible="$1" fichier_a_retirer="$2" temp
  local gabarit="$NAS_PKG_DIR/systemd/site-nas-nginx.service.template"
  mkdir -p "$(dirname "$fichier_unite_cible")" || return 1
  temp="$(mktemp "$(dirname "$fichier_unite_cible")/.site-nas-nginx.service.XXXXXX")" || return 1
  if ! sed -e "s|__FICHIER__|$(printf '%s' "$fichier_a_retirer" | sed -e 's/[&\|]/\&/g')|g" "$gabarit" > "$temp"; then
    rm -f "$temp"; return 1
  fi
  mv -f "$temp" "$fichier_unite_cible" || { rm -f "$temp"; return 1; }
  _exec_systemctl daemon-reload || return 1
  _exec_systemctl enable site-nas-nginx || return 1
  _exec_systemctl start site-nas-nginx
}

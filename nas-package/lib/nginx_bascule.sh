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

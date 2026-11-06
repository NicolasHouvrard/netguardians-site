# Service systemd de l'Agent (chantier paquet Synology NAS).
# Bibliotheque : destinee a etre sourcee, pas executee directement.

# Execution de systemctl, isolee pour pouvoir etre remplacee dans les tests.
# Definie seulement si absente, pour ne pas ecraser un remplacement deja en place.
if ! declare -F _exec_systemctl >/dev/null; then
  _exec_systemctl() { systemctl "$@"; }
fi

# Echappe une valeur pour l'utiliser comme partie de remplacement dans sed
# (delimiteur "|" : & et \ doivent etre echappes, ainsi que le delimiteur lui-meme).
_echapper_pour_sed() {
  printf '%s' "$1" | sed -e 's/[&\\|]/\\&/g'
}

# Ecrit fichier_unite_cible a partir de systemd/site-agent.service.template
# (relatif a NAS_PKG_DIR), en remplacant les quatre placeholders, puis active
# et demarre le service.
#   chemin_jre          : binaire java du JRE embarque
#   chemin_jar          : jar de l'Agent
#   adresse_ip_liaison  : IP Headscale a laquelle lier l'Agent (joignable par le Maitre)
#   port_ecoute         : port d'ecoute de l'Agent (fourni par l'orchestration, ex. 18090)
#   fichier_unite_cible : chemin du fichier .service a ecrire
#
# Echoue ferme (1, rien d'ecrit, systemctl jamais appele) si adresse_ip_liaison n'est pas
# une IPv4 du bloc CGNAT Tailscale (100.64.0.0/10 - meme plage que le tailnet Headscale)
# ou si port_ecoute n'est pas un port TCP valide : headscale_adresse_ip() (Task 3) peut
# renvoyer une sortie vide en cas d'echec, et ecrire SERVER_ADDRESS vide laisserait Spring
# Boot se lier a 0.0.0.0 - exactement ce que les Global Constraints interdisent pour ce port
# joignable depuis le reseau.
installer_service_agent() {
  local chemin_jre="$1" chemin_jar="$2" adresse_ip_liaison="$3" port_ecoute="$4" fichier_unite_cible="$5"
  local gabarit="$NAS_PKG_DIR/systemd/site-agent.service.template"

  [[ "$adresse_ip_liaison" =~ ^100\.(6[4-9]|[7-9][0-9]|1[01][0-9]|12[0-7])\.[0-9]{1,3}\.[0-9]{1,3}$ ]] || return 1
  [[ "$port_ecoute" =~ ^[0-9]{1,5}$ ]] && [ "$port_ecoute" -ge 1 ] && [ "$port_ecoute" -le 65535 ] || return 1

  local fichier_temp
  fichier_temp="$(mktemp "$(dirname "$fichier_unite_cible")/.site-agent.service.XXXXXX")" || return 1

  if ! sed \
    -e "s|__JRE__|$(_echapper_pour_sed "$chemin_jre")|g" \
    -e "s|__JAR__|$(_echapper_pour_sed "$chemin_jar")|g" \
    -e "s|__SERVER_ADDRESS__|$(_echapper_pour_sed "$adresse_ip_liaison")|g" \
    -e "s|__AGENT_PORT__|$(_echapper_pour_sed "$port_ecoute")|g" \
    "$gabarit" > "$fichier_temp"; then
    rm -f "$fichier_temp"
    return 1
  fi
  mv "$fichier_temp" "$fichier_unite_cible"

  # Pas de "enable --now" : le systemctl de DSM ne connait pas --now (constate sur DSM 7.4).
  # Rechargement, activation au demarrage, puis (re)demarrage, en commandes separees.
  _exec_systemctl daemon-reload || return 1
  _exec_systemctl enable site-agent || return 1
  _exec_systemctl restart site-agent
}

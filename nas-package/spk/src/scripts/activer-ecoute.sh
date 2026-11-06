#!/bin/bash
# Active l'ecoute Nginx sur l'adresse Headscale. A lancer APRES prestart.sh (qui garantit que
# tailscale0 existe) : au demarrage du NAS l'ecoute n'est volontairement PAS en place, car son
# adresse n'existe pas encore et ferait echouer "nginx -t" (donc File Station, Synology Drive...).
# shellcheck source=commun.sh
. "$(dirname "${BASH_SOURCE[0]}")/commun.sh"

charger_bibliotheques || exit 1
if ! activer_ecoute_nginx "$FICHIER_NGINX_ECOUTE_SOURCE" "$FICHIER_NGINX_ECOUTE"; then
  avertir_utilisateur "Ecoute Nginx sur l'adresse Headscale non activee (voir 'nginx -t'). L'application locale n'est pas affectee."
  exit 1
fi
exit 0

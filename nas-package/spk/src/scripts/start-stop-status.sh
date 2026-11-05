#!/bin/bash
# start-stop-status du paquet DSM site-nas (livre sous le nom scripts/start-stop-status).
#
#  prestart : verifications Headscale + Maitre (prestart.sh) ; non-zero = refus.
#  start    : redemarre Postgres, l'instance applicative ACTIVE (lue dans
#             l'upstream Nginx, que l'Agent tient a jour) et l'Agent.
#  stop     : arrete l'Agent puis les conteneurs (docker stop : jamais de
#             suppression, les donnees Postgres sont dans le dossier partage).
#  status   : 0 si l'Agent tourne, 3 sinon (codes DSM).

# shellcheck source=commun.sh
. "$(dirname "$0")/commun.sh"

case "${1:-}" in
  prestart)
    exec /bin/bash "$DOSSIER_SCRIPTS/prestart.sh"
    ;;
  prestop)
    exit 0
    ;;
  start)
    charger_bibliotheques || exit 1
    resultat=0
    _exec_docker start site-postgres >/dev/null || resultat=1
    _exec_docker start "site-$(port_actif_nginx)" >/dev/null || resultat=1
    _exec_systemctl start site-agent || resultat=1
    if [ "$resultat" -ne 0 ]; then
      avertir_utilisateur "Demarrage incomplet du paquet (Postgres, application ou Agent) : voir le journal."
    fi
    exit "$resultat"
    ;;
  stop)
    charger_bibliotheques || exit 1
    _exec_systemctl stop site-agent || true
    _exec_docker stop "site-$PORT_APP_BLEU" "site-$PORT_APP_VERT" >/dev/null 2>&1 || true
    _exec_docker stop site-postgres >/dev/null 2>&1 || true
    exit 0
    ;;
  status)
    if command -v systemctl >/dev/null 2>&1 && systemctl is-active --quiet site-agent; then
      exit 0
    fi
    exit 3
    ;;
  *)
    exit 1
    ;;
esac

#!/bin/bash
# preuninst du paquet DSM site-nas (Task 10, Step 2).
#
# Pendant une MISE A JOUR, DSM execute aussi ce script (celui de l'ancien paquet)
# avec SYNOPKG_PKG_STATUS=UPGRADE : il ne doit alors RIEN faire, sinon chaque
# mise a jour arreterait et supprimerait les services.
#
# En DESINSTALLATION : retire ce que le paquet a installe HORS de son dossier
# (service systemd de l'Agent, conteneurs, fichiers Nginx dans sites-enabled),
# mais ne touche JAMAIS au dossier de donnees ($SITE_NAS_RACINE_DONNEES) : les
# conteneurs sont jetables, les donnees Postgres vivent dans le dossier partage
# dedie et y restent pour une reinstallation ulterieure.

# shellcheck source=commun.sh
. "$(dirname "$0")/commun.sh"

if [ "${SYNOPKG_PKG_STATUS:-}" != "UNINSTALL" ]; then
  exit 0
fi

# Garde-fou ultime : si, malgre preinst, les donnees se trouvaient sous un chemin
# que DSM supprime avec le paquet, on REFUSE la desinstallation (un code non nul
# en preuninst l'annule) plutot que de laisser DSM les effacer.
if ! dossier_hors_cycle_de_vie "$SITE_NAS_RACINE_DONNEES"; then
  _exec_log_dsm "Desinstallation refusee : les donnees ($SITE_NAS_RACINE_DONNEES) seraient supprimees avec le paquet. Copiez-les ailleurs avant de desinstaller."
  exit 1
fi

charger_bibliotheques || exit 1

# Agent
_exec_systemctl disable --now site-agent >/dev/null 2>&1 || true
rm -f "$FICHIER_UNITE_AGENT" "$FICHIER_DROPIN_AGENT"
rmdir "$DOSSIER_DROPIN_AGENT" 2>/dev/null || true
_exec_systemctl daemon-reload >/dev/null 2>&1 || true

# Conteneurs (jamais de "docker volume rm" ni de suppression du dossier monte)
_exec_docker rm -f "site-$PORT_APP_BLEU" "site-$PORT_APP_VERT" site-postgres >/dev/null 2>&1 || true

# Nginx : sans nos fichiers, le Nginx de DSM ne doit plus ecouter sur 8072 ni
# relayer vers un upstream disparu.
rm -f "$FICHIER_NGINX_UPSTREAM" "$FICHIER_NGINX_ECOUTE"
if _exec_nginx -t >/dev/null 2>&1; then
  _exec_nginx -s reload >/dev/null 2>&1 || true
fi

exit 0

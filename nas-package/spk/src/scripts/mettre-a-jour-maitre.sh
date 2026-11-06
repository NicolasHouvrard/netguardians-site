#!/bin/bash
# Mise a jour manuelle du Maitre vers une version deja publiee sur GHCR.
# Usage :  sudo bash /usr/local/site-nas/scripts/mettre-a-jour-maitre.sh v1.3
# Bascule bleu-vert sans coupure ; l'ancienne version continue de servir si la nouvelle echoue.
set -u
if [ "$(id -u)" -ne 0 ] && [ "${SITE_NAS_SANS_ROOT:-}" != "1" ]; then
  echo "A lancer en root : sudo bash $0 <version>" >&2
  exit 1
fi
# Dossier d'installation = parent du dossier scripts (installation par installer-nas.sh).
SYNOPKG_PKGDEST="${SYNOPKG_PKGDEST:-$(dirname "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)")}"
export SYNOPKG_PKGDEST
# shellcheck source=commun.sh
. "$(dirname "${BASH_SOURCE[0]}")/commun.sh"

charger_bibliotheques || exit 1
mettre_a_jour_maitre "${1:-}"
exit $?

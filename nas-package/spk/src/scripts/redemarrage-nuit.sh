#!/bin/bash
# Lance par le minuteur systemd site-redemarrage-nuit.timer (Maitre uniquement) : redemarre le Maitre
# par bascule bleu-vert si l'application a pose le repere et que l'heure est dans la fenetre.
set -u
SYNOPKG_PKGDEST="${SYNOPKG_PKGDEST:-$(dirname "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)")}"
export SYNOPKG_PKGDEST
# shellcheck source=commun.sh
. "$(dirname "${BASH_SOURCE[0]}")/commun.sh"
charger_bibliotheques || exit 1
trap liberer_verrou_redemarrage EXIT
trap 'exit 143' INT TERM
redemarrer_si_recommande
exit $?

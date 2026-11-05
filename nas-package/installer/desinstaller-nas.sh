#!/bin/bash
# Desinstalle ce qu'a mis en place installer-nas.sh : memes scripts preuninst/postuninst que ceux
# de DSM (arret de l'Agent, suppression de ses fichiers de service, des conteneurs de
# l'application et de Postgres, et des fichiers Nginx du paquet).
#
# Les DONNEES ne sont JAMAIS supprimees (dossier de donnees Postgres, secrets) : une reinstallation
# les reutilise. Pour les effacer vraiment, supprimez ce dossier vous-meme.
#
# Usage :  sudo bash desinstaller-nas.sh
# Variables de test (hors NAS) : SITE_NAS_PREFIXE et SITE_NAS_SANS_ROOT=1.
set -u

PREFIXE="${SITE_NAS_PREFIXE:-/usr/local/site-nas}"

echec() { printf 'desinstaller-nas : %s\n' "$1" >&2; exit 1; }

if [ "${SITE_NAS_SANS_ROOT:-}" != "1" ] && [ "$(id -u)" -ne 0 ]; then
  echec "ce script doit etre lance en root : sudo bash desinstaller-nas.sh"
fi
[ -f "$PREFIXE/scripts/preuninst" ] || echec "aucune installation trouvee dans $PREFIXE"

JOURNAL="$(mktemp)" || echec "impossible de creer le journal"
trap 'rm -f "$JOURNAL"' EXIT

export SYNOPKG_PKGDEST="$PREFIXE" SYNOPKG_PKGNAME=site-nas SYNOPKG_PKG_STATUS=UNINSTALL SYNOPKG_TEMP_LOGFILE="$JOURNAL"
code=0
bash "$PREFIXE/scripts/preuninst" || code=$?
if [ "$code" -ne 0 ]; then
  [ -s "$JOURNAL" ] && cat "$JOURNAL" >&2
  echec "desinstallation interrompue (code $code), rien n'a ete supprime"
fi
bash "$PREFIXE/scripts/postuninst" || true

# Fichiers du programme (scripts, bibliotheques, JRE, jar) : sans valeur une fois le service arrete.
rm -rf "$PREFIXE"

[ -s "$JOURNAL" ] && cat "$JOURNAL"
echo
echo "Desinstallation terminee. Pensez a supprimer la tache planifiee de demarrage (Planificateur de taches de DSM)."

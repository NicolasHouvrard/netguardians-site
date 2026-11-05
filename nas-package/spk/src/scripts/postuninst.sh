#!/bin/bash
# postuninst du paquet DSM site-nas (Task 10, Step 2).
#
# Ne fait rien pendant une mise a jour (SYNOPKG_PKG_STATUS=UPGRADE).
# En desinstallation : DSM a deja supprime le dossier du paquet ; on CONSTATE
# (sans rien modifier) que le dossier de donnees est toujours la, et on le dit a
# l'utilisateur (texte de fin de desinstallation + journal), pour qu'une
# reinstallation ulterieure reparte de ces donnees.
# N'utilise aucune bibliotheque de lib/ : elles ont ete supprimees avec le paquet.

# shellcheck source=commun.sh
. "$(dirname "$0")/commun.sh"

if [ "${SYNOPKG_PKG_STATUS:-}" != "UNINSTALL" ]; then
  exit 0
fi

if [ -d "$DOSSIER_DONNEES_POSTGRES" ]; then
  _exec_log_dsm "Paquet desinstalle. Les donnees sont conservees dans $SITE_NAS_RACINE_DONNEES (Postgres : $DOSSIER_DONNEES_POSTGRES) ; une reinstallation les reutilisera. Supprimez ce dossier vous-meme seulement si vous voulez vraiment les effacer."
else
  _exec_log_dsm "Paquet desinstalle. Aucun dossier de donnees Postgres trouve dans $SITE_NAS_RACINE_DONNEES."
fi
exit 0

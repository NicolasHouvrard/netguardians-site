#!/bin/bash
# preinst du paquet DSM site-nas : garde-fous qui doivent AVORTER proprement
# l'operation (pour DSM, un code non nul en preinst annule l'installation sans
# rien laisser ; en postinst il laisse le paquet "corrompu"). Uniquement des
# controles locaux immediats : les verifications reseau avec reessai (Headscale,
# Maitre, ports) restent dans postinst, dans l'ordre exact de la Task 10.
#
# N'utilise aucune bibliotheque de lib/ : le contenu du paquet (package.tgz)
# n'est pas encore garanti extrait a ce stade.

# shellcheck source=commun.sh
. "$(dirname "$0")/commun.sh"

refuser() {
  _exec_log_dsm "$1"
  exit 1
}

# Les donnees Postgres/Agent ne doivent jamais vivre sous un chemin que DSM
# nettoie avec le paquet (incident fondateur de ce chantier).
if ! dossier_hors_cycle_de_vie "$SITE_NAS_RACINE_DONNEES"; then
  refuser "Dossier de donnees refuse ($SITE_NAS_RACINE_DONNEES) : il serait supprime avec le paquet."
fi

volume="$(dirname "$SITE_NAS_RACINE_DONNEES")"
if [ ! -d "$volume" ]; then
  refuser "Le volume $volume est introuvable sur ce NAS : le dossier de donnees $SITE_NAS_RACINE_DONNEES ne peut pas y etre cree."
fi

# Client Tailscale present et assez recent pour Headscale (>= 1.80). Verifie meme
# quand le NAS est deja connecte : une mise a jour ou une reconnexion future echouerait.
if ! command -v tailscale >/dev/null 2>&1; then
  refuser "Tailscale n'est pas installe sur ce NAS : installez la version 1.80 ou plus recente (paquet .spk depuis https://pkgs.tailscale.com/stable/), puis relancez l'installation."
fi
version_tailscale="$(tailscale version 2>/dev/null | head -n 1)"
if ! version_tailscale_suffisante "$version_tailscale"; then
  refuser "Le client Tailscale de ce NAS (version '${version_tailscale:-inconnue}') est trop ancien pour le reseau Headscale (1.80 minimum) : installez la version recente depuis https://pkgs.tailscale.com/stable/ (paquet Synology), puis relancez l'installation."
fi

# Installation neuve sans cle ET sans connexion Headscale existante : postinst
# echouerait de toute facon apres son reessai ; autant le dire tout de suite.
if [ "${SYNOPKG_PKG_STATUS:-}" = "INSTALL" ] && [ -z "${wizard_headscale_cle:-}" ]; then
  if ! command -v tailscale >/dev/null 2>&1 || ! tailscale status >/dev/null 2>&1; then
    refuser "Ce NAS n'est pas encore connecte au reseau Headscale : collez une cle de connexion (generee cote VPS) dans l'assistant d'installation."
  fi
fi

exit 0

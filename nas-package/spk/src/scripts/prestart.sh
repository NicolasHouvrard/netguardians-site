#!/bin/bash
# Verification "le paquet peut-il demarrer ?" (Task 10, Step 3).
#
# DSM n'a pas de script "prestart" separe : c'est l'action "prestart" de
# start-stop-status (INFO precheckstartstop="yes"), executee avant chaque
# demarrage du paquet, y compris au demarrage du NAS depuis DSM 7.0.
# start-stop-status delegue a ce fichier. Code 0 = demarrage autorise.
#
# Relance UNIQUEMENT les verifications 1 (Headscale) et 3 (Maitre) de postinst,
# avec le meme reessai a delai progressif, sans rien reinstaller. Aucune cle n'est
# disponible ici (l'assistant ne tourne qu'a l'installation) : si Headscale est
# deconnecte, on attend qu'il revienne, on ne tente pas de reconnexion.

# shellcheck source=commun.sh
. "$(dirname "${BASH_SOURCE[0]}")/commun.sh"

charger_bibliotheques || exit 1
verifier_headscale "" || exit 1
verifier_maitre || exit 1
exit 0

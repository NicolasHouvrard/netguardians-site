#!/bin/bash
# preupgrade du paquet DSM site-nas : rien a sauvegarder ici. Toutes les donnees
# et la configuration vivantes sont HORS du dossier du paquet (dossier partage
# dedie, /usr/local/etc/nginx/sites-enabled, /etc/systemd/system), que DSM ne
# remplace pas pendant la mise a jour. Present parce que DSM attend l'ensemble
# des scripts de cycle de vie dans scripts/.
exit 0

#!/bin/bash
# Genere le fichier INFO du paquet DSM sur stdout (meme role que l'INFO.sh des
# paquets pkgscripts-ng / que la cible INFO de spksrc), a partir des variables
# exportees par le Makefile / build-spk.sh. Champs : guide developpeur Synology,
# "INFO - Necessary Fields" et "INFO - Optional Fields".
set -euo pipefail

: "${SPK_NAME:?}" "${SPK_VERS:?}" "${SPK_REV:?}" "${SPK_ARCH:?}" "${PACKAGE_TGZ_MD5:?}"
: "${DISPLAY_NAME:?}" "${MAINTAINER:?}" "${DESCRIPTION:?}" "${OS_MIN_VER:?}" "${SPK_DEPENDS:?}"

# Chaque valeur est emise entre guillemets doubles : refuser tout guillemet ou
# retour a la ligne qui casserait le format cle="valeur" d'INFO.
for v in "$SPK_NAME" "$SPK_VERS" "$SPK_REV" "$SPK_ARCH" "$DISPLAY_NAME" "$MAINTAINER" "$DESCRIPTION" "$OS_MIN_VER" "$SPK_DEPENDS"; do
  case "$v" in
    *'"'*|*$'\n'*) echo "INFO.sh : valeur invalide (guillemet ou retour a la ligne) : $v" >&2; exit 1 ;;
  esac
done

cat <<EOF
package="${SPK_NAME}"
version="${SPK_VERS}-${SPK_REV}"
os_min_ver="${OS_MIN_VER}"
description="${DESCRIPTION}"
arch="${SPK_ARCH}"
maintainer="${MAINTAINER}"
displayname="${DISPLAY_NAME}"
install_dep_packages="${SPK_DEPENDS}"
precheckstartstop="yes"
ctl_stop="yes"
checksum="${PACKAGE_TGZ_MD5}"
EOF

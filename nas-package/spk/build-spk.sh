#!/bin/bash
# Construit nas-package/spk/$SORTIE/<SPK_NAME>-<arch>-<version>.spk.
# Appele par "make spk" ; utilisable aussi directement (les metadonnees absentes de
# l'environnement sont alors relues dans le Makefile, source unique).
#
# Format (guide developpeur Synology + piege "Invalid file format" documente dans
# la spec) : le .spk est une archive tar NON compressee au format POSIX ustar
# (jamais PAX), membres listes explicitement, INFO en premier, sans prefixe "./".
set -euo pipefail

ICI="$(cd "$(dirname "$0")" && pwd)"
RACINE_PAQUET="$(cd "$ICI/.." && pwd)"   # nas-package/

# --- Metadonnees : environnement d'abord, sinon Makefile ----------------------
lire_makefile() {
  sed -n "s/^$1 = //p" "$ICI/Makefile" | head -n 1
}
for cle in SPK_NAME SPK_VERS SPK_REV DISPLAY_NAME MAINTAINER DESCRIPTION OS_MIN_VER SPK_DEPENDS WIZARDS_DIR; do
  if [ -z "${!cle:-}" ]; then
    printf -v "$cle" '%s' "$(lire_makefile "$cle")"
  fi
  export "${cle?}"
done
SPK_ARCH="${SPK_ARCH:-x86_64}"
SORTIE="${SORTIE:-dist}"
export SPK_ARCH

# --- Entrees obligatoires, jamais devinees -------------------------------------
manque=""
for cle in JRE_DIR AGENT_JAR HEADSCALE_URL IMAGE_OWNER IMAGE_TAG; do
  [ -n "${!cle:-}" ] || manque="$manque $cle"
done
if [ -n "$manque" ]; then
  echo "build-spk.sh : variable(s) obligatoire(s) manquante(s) :$manque" >&2
  exit 1
fi
case "$SPK_ARCH" in
  x86_64|armv8) ;;
  *) echo "build-spk.sh : SPK_ARCH doit valoir x86_64 ou armv8 (recu : $SPK_ARCH)" >&2; exit 1 ;;
esac
[ -x "$JRE_DIR/bin/java" ] || { echo "build-spk.sh : $JRE_DIR/bin/java absent ou non executable" >&2; exit 1; }
[ -f "$AGENT_JAR" ] || { echo "build-spk.sh : jar de l'Agent introuvable : $AGENT_JAR" >&2; exit 1; }
[[ "$HEADSCALE_URL" =~ ^https?://[A-Za-z0-9.:-]+(/.*)?$ ]] || { echo "build-spk.sh : HEADSCALE_URL invalide : $HEADSCALE_URL" >&2; exit 1; }
[[ "$IMAGE_OWNER" =~ ^[a-z0-9][a-z0-9._-]*$ ]] || { echo "build-spk.sh : IMAGE_OWNER invalide : $IMAGE_OWNER" >&2; exit 1; }
[[ "$IMAGE_TAG" =~ ^[A-Za-z0-9_][A-Za-z0-9._-]{0,127}$ ]] || { echo "build-spk.sh : IMAGE_TAG invalide : $IMAGE_TAG" >&2; exit 1; }

# --- Les scripts doivent etre en fins de ligne LF (core.autocrlf sous Windows) --
for f in "$RACINE_PAQUET"/lib/*.sh "$RACINE_PAQUET"/systemd/* "$ICI"/src/scripts/*.sh "$ICI"/src/wizard/* "$ICI"/src/conf/*; do
  if grep -q $'\r' "$f"; then
    echo "build-spk.sh : fins de ligne CRLF dans $f (un script DSM en CRLF ne s'execute pas)" >&2
    exit 1
  fi
done

CONSTRUCTION="$ICI/build/$SPK_ARCH"
rm -rf "$CONSTRUCTION"
mkdir -p "$CONSTRUCTION/spk" "$CONSTRUCTION/package"
SPK="$CONSTRUCTION/spk"
CONTENU="$CONSTRUCTION/package"

# --- package.tgz : contenu installe dans SYNOPKG_PKGDEST (= NAS_PKG_DIR) --------
mkdir -p "$CONTENU/lib" "$CONTENU/systemd" "$CONTENU/agent"
cp "$RACINE_PAQUET"/lib/*.sh "$CONTENU/lib/"
cp "$RACINE_PAQUET"/systemd/site-agent.service.template "$CONTENU/systemd/"
cp "$AGENT_JAR" "$CONTENU/agent/agent.jar"
cp -R "$JRE_DIR" "$CONTENU/jre"

TAR_COMMUN=(--format=ustar --owner=0 --group=0 --numeric-owner --sort=name)
tar "${TAR_COMMUN[@]}" -czf "$SPK/package.tgz" -C "$CONTENU" lib systemd agent jre
if ! tar -tvzf "$SPK/package.tgz" jre/bin/java | grep -q '^-..x'; then
  echo "build-spk.sh : jre/bin/java n'est pas executable dans package.tgz" >&2
  exit 1
fi
PACKAGE_TGZ_MD5="$(md5sum "$SPK/package.tgz" | cut -d' ' -f1)"
export PACKAGE_TGZ_MD5

# --- scripts/ : cycle de vie (sans extension) + fichiers communs ---------------
mkdir -p "$SPK/scripts"
for s in preinst postinst preuninst postuninst preupgrade postupgrade start-stop-status; do
  cp "$ICI/src/scripts/$s.sh" "$SPK/scripts/$s"
done
cp "$ICI/src/scripts/commun.sh" "$ICI/src/scripts/prestart.sh" "$SPK/scripts/"
# Valeurs du projet figees a la construction (lues par commun.sh). printf %q :
# aucune valeur ne peut injecter de code dans ce fichier source par les scripts.
{
  echo "# Genere par build-spk.sh : ne pas editer."
  printf 'HEADSCALE_URL=%q\n' "$HEADSCALE_URL"
  printf 'IMAGE_OWNER=%q\n' "$IMAGE_OWNER"
  printf 'IMAGE_TAG=%q\n' "$IMAGE_TAG"
} > "$SPK/scripts/constantes.sh"

# --- conf/, WIZARD_UIFILES/, icones, INFO ---------------------------------------
mkdir -p "$SPK/conf" "$SPK/WIZARD_UIFILES"
cp "$ICI/src/conf/privilege" "$SPK/conf/privilege"
cp "$ICI/$WIZARDS_DIR"/install_uifile* "$SPK/WIZARD_UIFILES/"
cp "$ICI/src/PACKAGE_ICON.PNG" "$ICI/src/PACKAGE_ICON_256.PNG" "$SPK/"
bash "$ICI/INFO.sh" > "$SPK/INFO"

# --- Archive .spk : ustar, liste explicite, INFO en premier ---------------------
# Modes forces (les permissions du systeme de fichiers source ne sont pas fiables
# sous Windows) : scripts en 0755, le reste en lecture seule pour le groupe/autres.
mkdir -p "$ICI/$SORTIE"
FICHIER_SPK="$ICI/$SORTIE/${SPK_NAME}-${SPK_ARCH}-${SPK_VERS}-${SPK_REV}.spk"
rm -f "$FICHIER_SPK"
tar "${TAR_COMMUN[@]}" --mode=0644 -cf "$FICHIER_SPK" -C "$SPK" INFO package.tgz
tar "${TAR_COMMUN[@]}" --mode=0755 -rf "$FICHIER_SPK" -C "$SPK" scripts
tar "${TAR_COMMUN[@]}" --mode='u=rwX,go=rX' -rf "$FICHIER_SPK" -C "$SPK" conf WIZARD_UIFILES PACKAGE_ICON.PNG PACKAGE_ICON_256.PNG

echo "$FICHIER_SPK"

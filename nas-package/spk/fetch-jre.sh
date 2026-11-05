#!/usr/bin/env bash
# Recupere le JRE Temurin EPINGLE (jre-pins.txt) pour une architecture du .spk et
# l'extrait dans <dossier_sortie>, pret a servir de JRE_DIR (make spk JRE_DIR=...).
#
# Le JRE finit dans un paquet execute en root sur le NAS : l'URL ET la somme
# SHA-256 viennent donc du depot (jre-pins.txt, relu comme du code), jamais d'une
# reponse reseau. Une API qui fournirait a la fois le lien et la somme ne prouverait
# rien contre un lien detourne.
#
# Pourquoi un JRE complet et pas jlink : jlink en cross-plateforme exige les JMODs
# de la plateforme cible, que les JDK Temurin recents ne livrent pas toujours. Un
# eventuel jlink (reduction de taille) pourra venir plus tard, sur le NAS.
#
# Usage :
#   ./fetch-jre.sh <armv8|x86_64> <dossier_sortie_vide_ou_inexistant>
#   ./fetch-jre.sh proposer <armv8|x86_64> [version_java=25]   (affiche une ligne a relire, n'ecrit rien)
set -euo pipefail

ICI="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CURL_SUR=(--proto '=https' --proto-redir '=https' --tlsv1.2 -fSL)

arch_adoptium() {
  case "$1" in
    armv8)  echo aarch64 ;;
    x86_64) echo x64 ;;
    *) echo "arch inconnue : $1 (attendu armv8 ou x86_64)" >&2; return 1 ;;
  esac
}

trouver_python() {
  local c
  for c in python3 python; do
    if command -v "$c" >/dev/null 2>&1 && "$c" -c "" 2>/dev/null; then echo "$c"; return 0; fi
  done
  echo "python3 (ou python) est requis pour lire la reponse JSON d'Adoptium." >&2; return 1
}

if [ "${1:-}" = "proposer" ]; then
  arch="${2:?arch (armv8|x86_64)}"; java="${3:-25}"
  a="$(arch_adoptium "$arch")"; PY="$(trouver_python)"
  curl "${CURL_SUR[@]}" -s "https://api.adoptium.net/v3/assets/latest/${java}/hotspot?architecture=${a}&image_type=jre&os=linux&vendor=eclipse" \
    | "$PY" -c 'import json,sys; d=json.load(sys.stdin)[0]; p=d["binary"]["package"]; print(sys.argv[1], p["checksum"], p["link"])' "$arch"
  echo "A RELIRE : recouper la somme avec <url>.sha256.txt (page de la release GitHub) avant de l'ajouter a jre-pins.txt." >&2
  exit 0
fi

arch="${1:?arch (armv8|x86_64)}"
sortie="${2:?dossier de sortie}"
arch_adoptium "$arch" >/dev/null

ligne="$(grep -E "^${arch} " "$ICI/jre-pins.txt" | head -n 1 || true)"
if [ -z "$ligne" ]; then
  echo "Aucun JRE epingle pour $arch dans jre-pins.txt (voir : $0 proposer $arch)." >&2; exit 1
fi
attendu="$(echo "$ligne" | awk '{print $2}')"
lien="$(echo "$ligne" | awk '{print $3}')"

# Garde-fous sur l'entree epinglee elle-meme : hote GitHub d'Adoptium, somme bien formee.
case "$lien" in
  https://github.com/adoptium/*) ;;
  *) echo "URL epinglee refusee (attendu https://github.com/adoptium/...) : $lien" >&2; exit 1 ;;
esac
[[ "$attendu" =~ ^[0-9a-f]{64}$ ]] || { echo "Somme epinglee invalide pour $arch." >&2; exit 1; }

if [ -e "$sortie" ] && [ -n "$(ls -A "$sortie" 2>/dev/null)" ]; then
  echo "Le dossier $sortie existe et n'est pas vide : refus d'ecraser." >&2; exit 1
fi
mkdir -p "$sortie"

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

echo "JRE epingle ($arch) : $lien"
curl "${CURL_SUR[@]}" "$lien" -o "$tmp/jre.tar.gz"
obtenu="$(sha256sum "$tmp/jre.tar.gz" | cut -d' ' -f1)"
if [ "$obtenu" != "$attendu" ]; then
  echo "Somme SHA-256 differente de celle epinglee (attendue $attendu, obtenue $obtenu) : abandon." >&2; exit 1
fi

tar -xzf "$tmp/jre.tar.gz" -C "$sortie" --strip-components=1
test -f "$sortie/bin/java" || { echo "bin/java introuvable apres extraction" >&2; exit 1; }
echo "OK : JRE dans $sortie ($(du -sh "$sortie" | cut -f1))"

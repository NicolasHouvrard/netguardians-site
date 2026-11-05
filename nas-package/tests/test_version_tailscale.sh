RACINE_TEST_VERSION="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# commun.sh lit constantes.sh a cote de lui : on le copie avec un constantes.sh factice.
_charger_commun() {
  tmp=$(mktemp -d)
  cp "$RACINE_TEST_VERSION/spk/src/scripts/commun.sh" "$tmp/commun.sh"
  printf 'HEADSCALE_URL=https://x\nIMAGE_OWNER=o\nIMAGE_TAG=t\n' > "$tmp/constantes.sh"
  source "$tmp/commun.sh"
}

test_version_tailscale_suffisante_accepte_1_80_et_plus() {
  _charger_commun
  version_tailscale_suffisante "1.80.0" && version_tailscale_suffisante "1.104.5" \
    && version_tailscale_suffisante "1.80" && version_tailscale_suffisante "2.0.0"
}

test_version_tailscale_suffisante_refuse_en_dessous_de_1_80() {
  _charger_commun
  version_tailscale_suffisante "1.58.2"; a=$?
  version_tailscale_suffisante "1.79.9"; b=$?
  version_tailscale_suffisante "0.99.0"; c=$?
  [ "$a" -eq 1 ] && [ "$b" -eq 1 ] && [ "$c" -eq 1 ]
}

test_version_tailscale_suffisante_compare_numeriquement_pas_en_texte() {
  # "1.9" > "1.80" en comparaison de chaines : piege classique
  _charger_commun
  version_tailscale_suffisante "1.9.0"; [ "$?" -eq 1 ]
}

test_version_tailscale_suffisante_refuse_le_vide_et_l_illisible() {
  _charger_commun
  version_tailscale_suffisante ""; a=$?
  version_tailscale_suffisante "inconnue"; b=$?
  version_tailscale_suffisante "v1.104"; c=$?
  [ "$a" -eq 1 ] && [ "$b" -eq 1 ] && [ "$c" -eq 1 ]
}

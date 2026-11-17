NAS_PKG_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# Meme environnement factice que test_mise_a_jour.sh ; mettre_a_jour_maitre est remplace par un
# espion (la bascule elle-meme est testee dans test_mise_a_jour.sh). HEURE = heure locale simulee.
_preparer_redemarrage() {
  T=$(mktemp -d); mkdir -p "$T/data/postgres" "$T/nginx" "$T/scripts" "$T/data/plateforme"
  printf 'upstream site_actif {\n  server 127.0.0.1:18081;\n}\n' > "$T/nginx/site-nas-upstream.conf"
  printf 'HEADSCALE_URL=https://hs.test\nIMAGE_OWNER=proprio\nIMAGE_TAG=v1.2\nGITHUB_REPO=\n' > "$T/scripts/constantes.sh"
  cp "$NAS_PKG_DIR/spk/src/scripts/commun.sh" "$T/scripts/"
  APPELS=""; MAJ_CODE=0; HEURE=03; IMAGE_COURANTE="ghcr.io/proprio/site:v1.7"; AVERTISSEMENTS=""
  unset REDEMARRAGE_HEURE_DEBUT REDEMARRAGE_HEURE_FIN
  export SITE_NAS_RACINE_DONNEES="$T/data" SITE_NAS_DOSSIER_NGINX="$T/nginx" SYNOPKG_PKGDEST="$NAS_PKG_DIR"
  source "$T/scripts/commun.sh"
  FICHIER_NGINX_UPSTREAM="$T/nginx/site-nas-upstream.conf"
  DOSSIER_DONNEES_POSTGRES="$T/data/postgres"
  charger_bibliotheques
  FICHIER_REPERE_REDEMARRAGE="$T/data/plateforme/redemarrage-recommande"
  VERROU_REDEMARRAGE="$T/data/.redemarrage-nuit.lock"
  _exec_docker() { echo "d:$*" >> "$T/docker.log"; case "$1" in inspect) [ -n "$IMAGE_COURANTE" ] && echo "$IMAGE_COURANTE" ;; esac; }
  _exec_date_heure() { echo "$HEURE"; }
  _exec_log_dsm() { AVERTISSEMENTS="$AVERTISSEMENTS|$1"; }
  mettre_a_jour_maitre() { APPELS="$APPELS|maj:$*"; return "$MAJ_CODE"; }
}
_poser_repere() { printf '{"metaspaceMo":900,"seuilMo":800,"depuis":"2026-11-13T10:00:00Z"}\n' > "$FICHIER_REPERE_REDEMARRAGE"; }

test_redemarrage_pas_de_repere_ne_fait_rien() {
  _preparer_redemarrage
  redemarrer_si_recommande >/dev/null; code=$?
  [ "$code" -eq 0 ] && [ -z "$APPELS" ]
}

test_redemarrage_repere_hors_fenetre_ne_fait_rien() {
  _preparer_redemarrage; _poser_repere
  HEURE=14
  redemarrer_si_recommande >/dev/null; code=$?
  [ "$code" -eq 0 ] && [[ "$APPELS" != *"maj:"* ]] && [ -e "$FICHIER_REPERE_REDEMARRAGE" ]
}

test_redemarrage_la_fin_de_fenetre_est_exclue() {
  _preparer_redemarrage; _poser_repere
  HEURE=04
  redemarrer_si_recommande >/dev/null
  [[ "$APPELS" != *"maj:"* ]]
}

test_redemarrage_repere_dans_la_fenetre_appelle_mettre_a_jour_maitre_avec_le_tag_courant() {
  _preparer_redemarrage; _poser_repere
  redemarrer_si_recommande >/dev/null; code=$?
  [ "$code" -eq 0 ] && grep -q "d:inspect .*site-18081" "$T/docker.log" && [[ "$APPELS" == *"|maj:v1.7" ]]
}

test_redemarrage_fenetre_surchargee_et_heure_a_zero_initial() {
  _preparer_redemarrage; _poser_repere
  REDEMARRAGE_HEURE_DEBUT=1; REDEMARRAGE_HEURE_FIN=2; HEURE=01
  redemarrer_si_recommande >/dev/null
  [[ "$APPELS" == *"maj:v1.7" ]] || return 1
  APPELS=""; HEURE=03
  redemarrer_si_recommande >/dev/null
  [[ "$APPELS" != *"maj:"* ]]
}

test_redemarrage_fenetre_a_cheval_sur_minuit() {
  _preparer_redemarrage; _poser_repere
  REDEMARRAGE_HEURE_DEBUT=23; REDEMARRAGE_HEURE_FIN=2
  local h dedans=""
  for h in 23 00 01 02 03 12 22; do
    APPELS=""; HEURE=$h; _poser_repere
    redemarrer_si_recommande >/dev/null
    [[ "$APPELS" == *"maj:"* ]] && dedans="$dedans $h"
  done
  [ "$dedans" = " 23 00 01" ]
}

test_redemarrage_fenetre_invalide_ne_fait_rien_et_avertit() {
  _preparer_redemarrage; _poser_repere
  REDEMARRAGE_HEURE_DEBUT='3; rm -rf /'; REDEMARRAGE_HEURE_FIN=4
  redemarrer_si_recommande >/dev/null; code=$?
  [ "$code" -eq 0 ] && [[ "$APPELS" != *"maj:"* ]] && [[ "$AVERTISSEMENTS" == *"REDEMARRAGE_HEURE"* ]] && [ -e "$FICHIER_REPERE_REDEMARRAGE" ]
}

test_redemarrage_echec_garde_le_repere() {
  _preparer_redemarrage; _poser_repere
  MAJ_CODE=1
  redemarrer_si_recommande >/dev/null; code=$?
  [ "$code" -ne 0 ] && [[ "$APPELS" == *"maj:v1.7" ]] && [ -e "$FICHIER_REPERE_REDEMARRAGE" ]
}

test_redemarrage_succes_supprime_le_repere() {
  _preparer_redemarrage; _poser_repere
  redemarrer_si_recommande >/dev/null; code=$?
  [ "$code" -eq 0 ] && [ ! -e "$FICHIER_REPERE_REDEMARRAGE" ]
}

test_redemarrage_verrou_empeche_la_double_execution() {
  _preparer_redemarrage; _poser_repere
  mkdir "$VERROU_REDEMARRAGE"; echo "$$" > "$VERROU_REDEMARRAGE/pid"
  redemarrer_si_recommande >/dev/null; code=$?
  [ "$code" -eq 0 ] && [[ "$APPELS" != *"maj:"* ]] && [ -e "$FICHIER_REPERE_REDEMARRAGE" ] && [ -d "$VERROU_REDEMARRAGE" ]
}

test_redemarrage_verrou_perime_est_repris_et_libere_apres_usage() {
  _preparer_redemarrage; _poser_repere
  mkdir "$VERROU_REDEMARRAGE"; echo "999999999" > "$VERROU_REDEMARRAGE/pid"
  redemarrer_si_recommande >/dev/null
  [[ "$APPELS" == *"maj:v1.7" ]] && [ ! -e "$VERROU_REDEMARRAGE" ]
}

test_redemarrage_verrou_libere_meme_apres_echec() {
  _preparer_redemarrage; _poser_repere
  MAJ_CODE=1
  redemarrer_si_recommande >/dev/null
  [ ! -e "$VERROU_REDEMARRAGE" ]
}

test_redemarrage_tag_courant_introuvable_ne_fait_rien_et_avertit() {
  _preparer_redemarrage; _poser_repere
  IMAGE_COURANTE=""
  redemarrer_si_recommande >/dev/null; code=$?
  [ "$code" -ne 0 ] && [[ "$APPELS" != *"maj:"* ]] && [[ "$AVERTISSEMENTS" == *"tag"* ]] && [ -e "$FICHIER_REPERE_REDEMARRAGE" ]
}

test_redemarrage_tag_courant_invalide_ne_fait_rien() {
  _preparer_redemarrage; _poser_repere
  IMAGE_COURANTE="ghcr.io/proprio/site@sha256:abcdef"
  redemarrer_si_recommande >/dev/null; code=$?
  [ "$code" -ne 0 ] && [[ "$APPELS" != *"maj:"* ]]
}

test_redemarrage_installe_le_minuteur_seulement_avec_des_gabarits_valides() {
  _preparer_redemarrage
  SITE_NAS_DOSSIER_SYSTEMD="$T/systemd"; mkdir -p "$SITE_NAS_DOSSIER_SYSTEMD"
  _exec_systemctl() { APPELS="$APPELS|s:$*"; }
  installer_minuteur_redemarrage_nuit "/chemin/scripts/redemarrage-nuit.sh" "$SITE_NAS_DOSSIER_SYSTEMD" || return 1
  grep -q "ExecStart=/bin/bash /chemin/scripts/redemarrage-nuit.sh" "$SITE_NAS_DOSSIER_SYSTEMD/site-redemarrage-nuit.service" \
    && grep -q "OnUnitActiveSec=10min" "$SITE_NAS_DOSSIER_SYSTEMD/site-redemarrage-nuit.timer" \
    && ! grep -q "__" "$SITE_NAS_DOSSIER_SYSTEMD"/site-redemarrage-nuit.* \
    && [[ "$APPELS" == *"s:enable site-redemarrage-nuit.timer"* && "$APPELS" == *"s:start site-redemarrage-nuit.timer"* ]]
}

test_redemarrage_build_spk_embarque_tous_les_gabarits_systemd() {
  # Regression : build-spk.sh listait les gabarits un par un, les nouveaux n'etaient pas packages et le
  # minuteur n'etait jamais installe sur un vrai NAS (test_installateur copie systemd/* et ne le voit pas).
  local f nom ligne
  ligne="$(grep -E '^cp .*systemd' "$NAS_PKG_DIR/spk/build-spk.sh")"
  [ -n "$ligne" ] || return 1
  for f in "$NAS_PKG_DIR"/systemd/*; do
    nom="$(basename "$f")"
    [[ "$ligne" == *"$nom"* || "$ligne" == *'/systemd/*.template'* || "$ligne" == *'/systemd/*"'* ]] || { echo "non package : $nom"; return 1; }
  done
}

test_redemarrage_script_d_entree_est_package_par_build_spk() {
  grep -q 'redemarrage-nuit.sh' "$NAS_PKG_DIR/spk/build-spk.sh"
}

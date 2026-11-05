NAS_PKG_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

_charger() {
  _exec_chown() { CHOWN_APPELS="${CHOWN_APPELS:-}$* ;"; }
  source "$NAS_PKG_DIR/lib/secrets.sh"
  dir=$(mktemp -d)
}

# NTFS (Git Bash sous Windows) ne porte pas de droits POSIX ni toujours de liens symboliques :
# les tests qui en dependent se declarent sans objet plutot que de passer a vide ou d'echouer a tort.
_fs_a_des_droits() { _charger; _fs_respecte_chmod "$dir"; }

test_generer_secret_texte_ecrit_au_moins_32_caracteres_sur_une_ligne_et_ne_regenere_pas() {
  _charger
  generer_secret_une_fois "$dir/s" texte || return 1
  premier=$(cat "$dir/s")
  generer_secret_une_fois "$dir/s" texte || return 1
  [ "${#premier}" -ge 32 ] && [[ "$premier" != *$'\n'* ]] && [ "$premier" = "$(cat "$dir/s")" ]
}

test_generer_secret_hex_ecrit_une_chaine_hexadecimale_de_longueur_paire() {
  _charger
  generer_secret_une_fois "$dir/s" hex || return 1
  v=$(cat "$dir/s")
  [[ "$v" =~ ^[0-9a-f]+$ ]] && [ $(( ${#v} % 2 )) -eq 0 ] && [ "${#v}" -ge 16 ]
}

test_generer_secret_refuse_un_type_inconnu_sans_rien_ecrire() {
  _charger
  generer_secret_une_fois "$dir/s" nimporte; code=$?
  [ "$code" -eq 1 ] && [ ! -e "$dir/s" ]
}

test_generer_secret_ne_laisse_aucun_fichier_temporaire() {
  _charger
  generer_secret_une_fois "$dir/s" texte || return 1
  [ "$(ls -A "$dir" | wc -l)" -eq 1 ]
}

test_generer_secret_refuse_un_lien_symbolique_et_ne_touche_pas_a_sa_cible() {
  _charger
  echo "intact" > "$dir/cible"
  ln -s "$dir/cible" "$dir/s" 2>/dev/null
  [ -L "$dir/s" ] || return 0   # pas de liens symboliques sur ce systeme : sans objet
  generer_secret_une_fois "$dir/s" texte; code=$?
  [ "$code" -eq 1 ] && [ "$(cat "$dir/cible")" = "intact" ]
}

test_generer_secret_refuse_un_dossier_a_la_place_du_fichier() {
  _charger
  mkdir "$dir/s"
  generer_secret_une_fois "$dir/s" texte; code=$?
  [ "$code" -eq 1 ]
}

test_generer_secret_refuse_un_dossier_parent_qui_est_un_lien_symbolique() {
  _charger
  mkdir "$dir/vrai"
  ln -s "$dir/vrai" "$dir/lien" 2>/dev/null
  [ -L "$dir/lien" ] || return 0
  generer_secret_une_fois "$dir/lien/s" texte; code=$?
  [ "$code" -eq 1 ] && [ ! -e "$dir/vrai/s" ]
}

test_generer_secret_refuse_un_dossier_inscriptible_par_les_autres() {
  _fs_a_des_droits || return 0
  chmod 777 "$dir"
  generer_secret_une_fois "$dir/s" texte; code=$?
  [ "$code" -eq 1 ] && [ ! -e "$dir/s" ]
}

test_generer_secret_cree_le_fichier_en_0600_sans_uid() {
  _fs_a_des_droits || return 0
  generer_secret_une_fois "$dir/s" texte || return 1
  [ "$(stat -c %a "$dir/s")" = "600" ]
}

test_generer_secret_avec_uid_donne_le_fichier_a_cet_uid_en_0400() {
  _charger
  # le journal passe par un fichier : l'ecriture se fait dans un sous-shell (tube), une variable y serait perdue
  journal="$dir.chown"; : > "$journal"
  _exec_chown() { echo "$*" >> "$journal"; }
  generer_secret_une_fois "$dir/s" texte 10001 || return 1
  grep -q "^10001:10001 $dir/s\." "$journal" || return 1
  # mode 0400 : verifiable seulement si le systeme de fichiers le porte (NTFS le rend en 444)
  sonde=$(mktemp "$dir/.m.XXXXXX"); chmod 400 "$sonde"
  [ "$(stat -c %a "$sonde")" = "400" ] || return 0
  [ "$(stat -c %a "$dir/s")" = "400" ]
}

test_generer_secret_corrige_le_mode_d_un_fichier_existant_trop_ouvert_sans_changer_son_contenu() {
  _fs_a_des_droits || return 0
  echo "existant-0123456789-0123456789-0123456789" > "$dir/s"
  chmod 644 "$dir/s"
  generer_secret_une_fois "$dir/s" texte || return 1
  [ "$(stat -c %a "$dir/s")" = "600" ] && [ "$(cat "$dir/s")" = "existant-0123456789-0123456789-0123456789" ]
}

test_generer_secret_echoue_si_la_commande_chown_echoue_et_ne_laisse_rien() {
  _charger
  _exec_chown() { return 1; }
  generer_secret_une_fois "$dir/s" texte 10001; code=$?
  [ "$code" -eq 1 ] && [ ! -e "$dir/s" ] && [ "$(ls -A "$dir" | wc -l)" -eq 0 ]
}

test_copier_secret_copie_le_contenu_une_fois_et_ne_l_ecrase_pas() {
  _charger
  printf 'valeur-source' > "$dir/src"
  copier_secret_une_fois "$dir/src" "$dir/dst" 10001 || return 1
  [ "$(cat "$dir/dst")" = "valeur-source" ] || return 1
  printf 'autre' > "$dir/src"
  copier_secret_une_fois "$dir/src" "$dir/dst" 10001 || return 1
  [ "$(cat "$dir/dst")" = "valeur-source" ]
}

test_copier_secret_echoue_si_la_source_est_absente_ou_vide() {
  _charger
  copier_secret_une_fois "$dir/absente" "$dir/dst" 10001; a=$?
  : > "$dir/vide"
  copier_secret_une_fois "$dir/vide" "$dir/dst" 10001; b=$?
  [ "$a" -eq 1 ] && [ "$b" -eq 1 ] && [ ! -e "$dir/dst" ]
}

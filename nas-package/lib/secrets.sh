# Creation et verification de fichiers de secrets (cle de chiffrement, salt, secret JWT,
# mot de passe de base). Bibliotheque : destinee a etre sourcee, pas executee directement.
#
# Durcissement (revue de securite du 2026-11-05) : le paquet s'execute en ROOT et ecrit dans
# un dossier partage du NAS. Un simple "> fichier" suit un lien symbolique qu'un autre
# utilisateur y aurait pose et ecraserait n'importe quel fichier. Donc : dossier verifie
# (ni lien, ni inscriptible par groupe/autres, bien a nous), fichier jamais suivi s'il est un
# lien, ecriture dans un fichier temporaire du meme dossier puis renommage (rename remplace
# l'entree, il ne suit pas un lien), contenu valide avant mise en place.
#
# Quand le systeme de fichiers ne sait pas porter de droits POSIX (poste Windows des tests),
# les controles de droits/proprietaire sont ignores ; ceux sur les liens et le contenu restent.

if ! declare -F _exec_chown >/dev/null; then
  _exec_chown() { chown "$@"; }
fi

# Code 0 si le dossier donne honore chmod (sonde reelle : un fichier 0600 doit rester 0600).
_fs_respecte_chmod() {
  local sonde mode
  sonde="$(mktemp "$1/.sonde.XXXXXX" 2>/dev/null)" || return 1
  chmod 600 "$sonde" 2>/dev/null
  mode="$(stat -c %a "$sonde" 2>/dev/null)"
  rm -f "$sonde"
  [ "$mode" = "600" ]
}

# Le dossier doit exister, ne pas etre un lien, nous appartenir et ne pas etre
# inscriptible par le groupe ou les autres.
verifier_dossier_secrets() {
  local dossier="$1" mode proprietaire
  [ -d "$dossier" ] && [ ! -L "$dossier" ] || return 1
  _fs_respecte_chmod "$dossier" || return 0
  mode="$(stat -c %a "$dossier")"
  proprietaire="$(stat -c %u "$dossier")"
  [ "$proprietaire" = "$(id -u)" ] || return 1
  [ $(( 8#$mode & 8#022 )) -eq 0 ]
}

# Ecrit stdin dans "fichier" de facon atomique et sure. $2 = uid proprietaire voulu (optionnel :
# lecture par l'utilisateur non-root du conteneur applicatif) ; mode 0400 si uid, 0600 sinon.
_ecrire_secret_atomique() {
  local fichier="$1" uid="${2:-}" tmp
  tmp="$( umask 077; mktemp "$fichier.XXXXXX" )" || return 1
  if ! cat > "$tmp" || [ ! -s "$tmp" ]; then
    rm -f "$tmp"
    return 1
  fi
  if [ -n "$uid" ]; then
    if ! _exec_chown "$uid:$uid" "$tmp" || ! chmod 400 "$tmp"; then
      rm -f "$tmp"
      return 1
    fi
  else
    chmod 600 "$tmp" || { rm -f "$tmp"; return 1; }
  fi
  mv -f "$tmp" "$fichier" || { rm -f "$tmp"; return 1; }
}

# Etat d'un fichier de secret : code 0 = deja present et valide (conserve, mode recale),
# 2 = absent ou vide (a creer), 1 = refuse (lien, dossier, autre proprietaire...).
_secret_existant_valide() {
  local fichier="$1" uid="${2:-}" proprietaire mode_attendu
  [ -L "$fichier" ] && return 1
  [ -e "$fichier" ] || return 2
  [ -f "$fichier" ] || return 1
  [ -s "$fichier" ] || return 2
  if _fs_respecte_chmod "$(dirname "$fichier")"; then
    proprietaire="$(stat -c %u "$fichier")"
    if [ "$proprietaire" != "$(id -u)" ] && { [ -z "$uid" ] || [ "$proprietaire" != "$uid" ]; }; then
      return 1
    fi
    if [ "$proprietaire" = "$(id -u)" ]; then
      mode_attendu=600
      [ -n "$uid" ] && mode_attendu=400
      chmod "$mode_attendu" "$fichier" || return 1
    fi
  fi
  return 0
}

# Cree "fichier" avec un secret aleatoire UNE SEULE FOIS (jamais regenere s'il existe : changer
# le secret JWT invaliderait les sessions, changer la cle de chiffrement rendrait illisibles les
# identifiants deja chiffres). Type : "texte" (base64 sur une ligne, >= 32 caracteres) ou "hex"
# (32 caracteres hexadecimaux, longueur paire exigee pour le salt). $3 = uid proprietaire (optionnel).
generer_secret_une_fois() {
  local fichier="$1" type="$2" uid="${3:-}" etat contenu
  verifier_dossier_secrets "$(dirname "$fichier")" || return 1
  _secret_existant_valide "$fichier" "$uid"
  etat=$?
  [ "$etat" -eq 0 ] && return 0
  [ "$etat" -eq 2 ] || return 1
  case "$type" in
    texte)
      contenu="$(head -c 48 /dev/urandom | base64 | tr -d '\n')"
      [ "${#contenu}" -ge 32 ] || return 1
      ;;
    hex)
      contenu="$(head -c 16 /dev/urandom | od -An -tx1 | tr -d ' \n')"
      [[ "$contenu" =~ ^[0-9a-f]{32}$ ]] || return 1
      ;;
    *) return 1 ;;
  esac
  printf '%s' "$contenu" | _ecrire_secret_atomique "$fichier" "$uid"
}

# Copie "source" vers "dest" (meme contenu, autre proprietaire) une seule fois. Sert a donner a
# l'application (uid non-root) une copie lisible du mot de passe Postgres, dont l'original reste
# reserve a root pour le conteneur de base de donnees.
copier_secret_une_fois() {
  local source="$1" dest="$2" uid="${3:-}" etat
  verifier_dossier_secrets "$(dirname "$dest")" || return 1
  { [ -f "$source" ] && [ ! -L "$source" ] && [ -s "$source" ]; } || return 1
  _secret_existant_valide "$dest" "$uid"
  etat=$?
  [ "$etat" -eq 0 ] && return 0
  [ "$etat" -eq 2 ] || return 1
  _ecrire_secret_atomique "$dest" "$uid" < "$source"
}

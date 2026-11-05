# Detection du role de la machine (Maitre ou Client), par nom d'hote.
# Bibliotheque : destinee a etre sourcee, pas executee directement.

# Lecture du nom d'hote, isolee pour pouvoir etre remplacee dans les tests.
# Definie seulement si absente, pour ne pas ecraser un remplacement deja en place.
if ! declare -F _exec_hostname >/dev/null; then
  _exec_hostname() { hostname; }
fi

# Code 0 si la machine courante est le Maitre (nom d'hote "logipourtous").
# Insensible a la casse ; le suffixe ".local" ou le domaine est ignore.
nas_est_maitre() {
  local nom
  nom="$(_exec_hostname)"
  nom="${nom%%.*}"
  nom="$(printf '%s' "$nom" | tr '[:upper:]' '[:lower:]')"
  [ "$nom" = "logipourtous" ]
}

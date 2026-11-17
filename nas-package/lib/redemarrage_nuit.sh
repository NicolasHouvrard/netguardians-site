# Redemarrage de nuit du MAITRE quand l'application le recommande (Metaspace trop rempli apres de
# nombreux rechargements de plugins). L'application ecrit le repere "redemarrage-recommande" dans son
# dossier plateforme (SurveillanceMetaspace) ; ce script, lance toutes les 10 minutes par un minuteur
# systemd, ne fait rien hors de la fenetre horaire, puis relance la MEME version par la bascule
# bleu-vert existante (mettre_a_jour_maitre) : si la nouvelle instance n'est pas saine, l'ancienne
# continue de servir et le repere est conserve (nouvel essai a la prochaine nuit).
# Bibliotheque : destinee a etre sourcee, apres mise_a_jour.sh (et commun.sh pour les chemins).

if ! declare -F _exec_date_heure >/dev/null; then
  _exec_date_heure() { date +%H; }
fi
if ! declare -F _exec_systemctl >/dev/null; then
  _exec_systemctl() { systemctl "$@"; }
fi

# Fenetre [debut, fin[ en heures locales (0-23). Si debut > fin, la fenetre passe minuit
# (ex. 23 -> 2 = 23h, 0h, 1h). debut == fin : fenetre vide, jamais de redemarrage.
: "${REDEMARRAGE_HEURE_DEBUT:=3}"
: "${REDEMARRAGE_HEURE_FIN:=4}"

# FICHIER_REPERE_REDEMARRAGE et VERROU_REDEMARRAGE sont definis dans spk/src/scripts/commun.sh (a partir du dossier de donnees).

# Code 0 si l'heure locale courante est dans la fenetre. Code 2 si la configuration est invalide.
_dans_fenetre_redemarrage() {
  local debut="$REDEMARRAGE_HEURE_DEBUT" fin="$REDEMARRAGE_HEURE_FIN" h
  [[ "$debut" =~ ^[0-9]{1,2}$ && "$fin" =~ ^[0-9]{1,2}$ ]] || return 2
  debut=$((10#$debut)); fin=$((10#$fin))
  [ "$debut" -le 23 ] && [ "$fin" -le 23 ] || return 2
  h="$(_exec_date_heure)"
  [[ "$h" =~ ^[0-9]{1,2}$ ]] || return 2
  h=$((10#$h))
  if [ "$debut" -lt "$fin" ]; then
    [ "$h" -ge "$debut" ] && [ "$h" -lt "$fin" ]
  elif [ "$debut" -gt "$fin" ]; then
    [ "$h" -ge "$debut" ] || [ "$h" -lt "$fin" ]
  else
    return 1
  fi
}

# Verrou par repertoire (mkdir est atomique). Un verrou dont le processus n'existe plus est repris.
# Code 0 si pris, 1 si deja detenu par un processus vivant.
_prendre_verrou_redemarrage() {
  local pid
  if mkdir "$VERROU_REDEMARRAGE" 2>/dev/null; then
    printf '%s\n' "$$" > "$VERROU_REDEMARRAGE/pid"
    return 0
  fi
  pid="$(cat "$VERROU_REDEMARRAGE/pid" 2>/dev/null)"
  if [[ "$pid" =~ ^[0-9]+$ ]] && ! kill -0 "$pid" 2>/dev/null; then
    rm -rf "$VERROU_REDEMARRAGE"
    if mkdir "$VERROU_REDEMARRAGE" 2>/dev/null; then
      printf '%s\n' "$$" > "$VERROU_REDEMARRAGE/pid"
      return 0
    fi
  fi
  return 1
}

# Ne libere que le verrou pris par CE processus.
liberer_verrou_redemarrage() {
  if [ "$(cat "$VERROU_REDEMARRAGE/pid" 2>/dev/null)" = "$$" ]; then
    rm -rf "$VERROU_REDEMARRAGE"
  fi
}

# Tag de l'image du conteneur actif (ex. v1.7), vide si introuvable ou de forme inattendue.
_tag_image_courante() {
  local port image tag
  port="$(port_actif_nginx)" || return 1
  [[ "$port" =~ ^[0-9]+$ ]] || return 1
  image="$(_exec_docker inspect --format '{{.Config.Image}}' "site-$port" 2>/dev/null | head -n 1)" || return 1
  [[ "$image" == *:* && "$image" != *@* ]] || return 1
  tag="${image##*:}"
  [[ "$tag" =~ ^v[0-9]+(\.[0-9]+){0,3}$ ]] || return 1
  printf '%s\n' "$tag"
}

# Codes : 0 rien a faire ou redemarrage reussi (repere supprime), 1 echec (repere conserve).
redemarrer_si_recommande() {
  local code_fenetre tag code=0

  [ -e "$FICHIER_REPERE_REDEMARRAGE" ] || return 0

  _dans_fenetre_redemarrage; code_fenetre=$?
  if [ "$code_fenetre" -eq 2 ]; then
    avertir_utilisateur "Redemarrage de nuit ignore : REDEMARRAGE_HEURE_DEBUT/REDEMARRAGE_HEURE_FIN doivent etre des heures entieres de 0 a 23."
    return 0
  fi
  [ "$code_fenetre" -eq 0 ] || return 0

  _prendre_verrou_redemarrage || return 0

  if ! tag="$(_tag_image_courante)"; then
    avertir_utilisateur "Redemarrage de nuit impossible : tag de l'image en cours introuvable (docker inspect). Le repere est conserve."
    code=1
  else
    avertir_utilisateur "Redemarrage de nuit du Maitre (repere Metaspace) : relance de $tag."
    if mettre_a_jour_maitre "$tag"; then
      rm -f "$FICHIER_REPERE_REDEMARRAGE"
    else
      avertir_utilisateur "Redemarrage de nuit en echec : l'ancienne instance sert toujours, le repere est conserve."
      code=1
    fi
  fi
  liberer_verrou_redemarrage
  return "$code"
}

# installer_minuteur_redemarrage_nuit <chemin_script> <dossier_systemd>
# Ecrit le service et le minuteur (gabarits systemd/) et active le minuteur. A n'appeler que sur le Maitre.
installer_minuteur_redemarrage_nuit() {
  local script="$1" dossier="$2" ext temp gabarit
  [[ "$script" == /* && "$script" != *[[:space:]]* ]] || return 1
  mkdir -p "$dossier" || return 1
  for ext in service timer; do
    gabarit="$NAS_PKG_DIR/systemd/site-redemarrage-nuit.$ext.template"
    [ -r "$gabarit" ] || return 1
    temp="$(mktemp "$dossier/.site-redemarrage-nuit.$ext.XXXXXX")" || return 1
    if ! sed -e "s|__SCRIPT__|$(_echapper_pour_sed "$script")|g" "$gabarit" > "$temp"; then
      rm -f "$temp"; return 1
    fi
    mv -f "$temp" "$dossier/site-redemarrage-nuit.$ext" || { rm -f "$temp"; return 1; }
  done
  _exec_systemctl daemon-reload || return 1
  _exec_systemctl enable site-redemarrage-nuit.timer || return 1
  _exec_systemctl start site-redemarrage-nuit.timer
}

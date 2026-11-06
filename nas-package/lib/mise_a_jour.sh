# Mise a jour bleu-vert du MAITRE, lancee a la main (le Maitre n'a pas de licence pour lui-meme :
# son Agent n'est jamais reveille ni autorise a lire un ordre de deploiement, voir README).
# Meme enchainement que l'Agent (OrchestrateurDeploiement) : demarrer la nouvelle version sur le port
# INACTIF, attendre qu'elle soit saine, basculer Nginx, arreter l'ancienne. Si la nouvelle version
# n'est pas saine ou si Nginx refuse la bascule, l'ancienne continue de servir, rien n'est coupe.
# Bibliotheque : destinee a etre sourcee. Necessite commun.sh (ports, chemins) et docker_bootstrap.sh.

if ! declare -F _exec_curl >/dev/null; then
  _exec_curl() { curl "$@"; }
fi
if ! declare -F _exec_sleep >/dev/null; then
  _exec_sleep() { sleep "$@"; }
fi

# Nombre d'essais x delai entre essais pour attendre la sante de la nouvelle version : 60 x 10 s =
# 10 minutes (l'application met 2 a 3 minutes a demarrer sur un NAS ARM). Surchargeables (tests).
: "${MAJ_SANTE_ESSAIS:=60}"
: "${MAJ_SANTE_DELAI:=10}"

# Code 0 si l'application repond "UP" sur ce port local.
application_saine() {
  local port="$1" reponse
  reponse="$(_exec_curl -s --max-time 5 "http://127.0.0.1:$port/actuator/health" 2>/dev/null)" || return 1
  [[ "$reponse" =~ \"status\"[[:space:]]*:[[:space:]]*\"UP\" ]]
}

# Ecrit le fichier upstream (meme format que initialiser_nginx_local / BasculeNginx) de facon atomique.
_ecrire_upstream() {
  local fichier="$1" port="$2" temp
  temp="$(mktemp "$(dirname "$fichier")/.site-nas-upstream.XXXXXX")" || return 1
  printf 'upstream site_actif {\n  server 127.0.0.1:%s;\n}\n' "$port" > "$temp" || { rm -f "$temp"; return 1; }
  chmod 644 "$temp"
  mv -f "$temp" "$fichier"
}

# mettre_a_jour_maitre <tag>   (ex. v1.3)
# Codes : 0 reussi, 2 tag invalide, 1 echec (l'ancienne version sert toujours).
mettre_a_jour_maitre() {
  local tag="${1:-}" image actif inactif essai precedent

  if ! [[ "$tag" =~ ^v[0-9]+(\.[0-9]+){0,3}$ ]]; then
    avertir_utilisateur "Version invalide : '$tag' (attendu par exemple v1.3)."
    return 2
  fi
  image="ghcr.io/${IMAGE_OWNER}/site:${tag}"
  actif="$(port_actif_nginx)"
  if [ "$actif" = "$PORT_APP_BLEU" ]; then inactif="$PORT_APP_VERT"; else inactif="$PORT_APP_BLEU"; fi

  # Memes secrets, base et options que la premiere instance et que l'Agent (OPTIONS_APPLICATION).
  if ! preparer_options_application "$PORT_POSTGRES" "$DOSSIER_DONNEES_POSTGRES"; then
    avertir_utilisateur "Mise a jour impossible : Postgres ou les secrets de l'application sont introuvables."
    return 1
  fi

  avertir_utilisateur "Mise a jour du Maitre : $image (actif : $actif, nouveau : $inactif)."
  if ! _exec_docker pull "$image"; then
    avertir_utilisateur "Telechargement de $image impossible (le tag existe-t-il ? l'image est-elle publiee ?). Rien n'a ete modifie."
    return 1
  fi

  _exec_docker rm -f "site-$inactif" >/dev/null 2>&1 || true
  # shellcheck disable=SC2086  # separation en jetons voulue (aucune valeur ne contient d'espace)
  if ! _exec_docker run -d --name "site-$inactif" \
      --restart unless-stopped \
      --network host \
      -e "SERVER_PORT=$inactif" \
      -e SERVER_ADDRESS=127.0.0.1 \
      $OPTIONS_APPLICATION \
      "$image" >/dev/null; then
    avertir_utilisateur "Demarrage de la nouvelle version impossible. L'ancienne ($actif) continue de servir."
    return 1
  fi

  essai=0
  until application_saine "$inactif"; do
    essai=$((essai + 1))
    if [ "$essai" -ge "$MAJ_SANTE_ESSAIS" ]; then
      avertir_utilisateur "La nouvelle version n'est pas devenue saine a temps : abandon. L'ancienne ($actif) continue de servir. Voir : docker logs site-$inactif"
      _exec_docker stop "site-$inactif" >/dev/null 2>&1 || true
      return 1
    fi
    _exec_sleep "$MAJ_SANTE_DELAI"
  done

  precedent=""
  [ -r "$FICHIER_NGINX_UPSTREAM" ] && precedent="$(cat "$FICHIER_NGINX_UPSTREAM")"
  if ! _ecrire_upstream "$FICHIER_NGINX_UPSTREAM" "$inactif" || ! _exec_nginx -t >/dev/null 2>&1 \
     || ! _exec_nginx -s reload; then
    if [ -n "$precedent" ]; then printf '%s\n' "$precedent" > "$FICHIER_NGINX_UPSTREAM"; fi
    avertir_utilisateur "Bascule Nginx refusee : configuration restauree, l'ancienne version ($actif) sert toujours."
    _exec_docker stop "site-$inactif" >/dev/null 2>&1 || true
    return 1
  fi

  _exec_docker stop "site-$actif" >/dev/null 2>&1 || true
  avertir_utilisateur "Mise a jour terminee : $tag sert maintenant sur le port $inactif ; l'ancienne version (port $actif) est arretee."
  return 0
}

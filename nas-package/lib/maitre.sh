# Accessibilite du Maitre (serveur Spring Boot) via son endpoint de sante.
# Bibliotheque : destinee a etre sourcee, pas executee directement.

# Execution de curl, isolee pour pouvoir etre remplacee dans les tests.
# Definie seulement si absente, pour ne pas ecraser un remplacement deja en place.
if ! declare -F _exec_curl >/dev/null; then
  _exec_curl() { curl "$@"; }
fi

# Code 0 si le Maitre repond sur /actuator/health, 1 sinon.
# Le code de sortie brut de curl n'est pas relaye : il n'est pas garanti binaire (7, 22...).
maitre_est_joignable() {
  if _exec_curl -sf --connect-timeout 5 --max-time 5 "$1/actuator/health" >/dev/null 2>&1; then
    return 0
  fi
  return 1
}

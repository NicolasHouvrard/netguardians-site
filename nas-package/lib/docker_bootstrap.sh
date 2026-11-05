# Demarrage de la premiere instance applicative (conteneur "site-<port>").
# Bibliotheque : destinee a etre sourcee, pas executee directement.

# Execution de docker, isolee pour pouvoir etre remplacee dans les tests.
# Definie seulement si absente, pour ne pas ecraser un remplacement deja en place.
if ! declare -F _exec_docker >/dev/null; then
  _exec_docker() { docker "$@"; }
fi

# Demarre la premiere instance applicative depuis "image".
#
# Reseau : --network host, pour que l'application puisse joindre Postgres
# (127.0.0.1:port_postgres, publie en loopback par postgres_bootstrap.sh) ET,
# plus tard, des services du NAS. Consequence : -p est sans effet en mode host,
# donc l'ecoute est fixee par SERVER_PORT=port_hote et SERVER_ADDRESS=127.0.0.1
# (jamais 0.0.0.0 : seul le Nginx local de bascule doit pouvoir l'atteindre).
#
# Secrets : le mot de passe Postgres (genere par demarrer_postgres) et un secret
# JWT (genere ici, une seule fois, jamais regenere : le changer invaliderait
# toutes les sessions) sont montes en lecture seule dans /run/secrets-app et lus
# par Spring via configtree (nom de fichier = nom de propriete). Ils n'apparaissent
# ni dans argv ni dans les variables d'environnement (docker inspect, ps).
#
# Le nom du conteneur est deterministe : "site-<port_hote>", meme convention que
# DockerControl.nomConteneur cote Agent, pour que l'Agent puisse reconnaitre et
# gerer ce conteneur lors d'une future bascule bleu-vert.
demarrer_premiere_instance() {
  local image="$1" port_hote="$2" port_postgres="$3" dossier_donnees_postgres="$4"
  local dossier_parent fichier_mdp fichier_jwt
  dossier_parent="$(dirname "$dossier_donnees_postgres")"
  fichier_mdp="$dossier_parent/.postgres-password"
  fichier_jwt="$dossier_parent/.app-jwt-secret"

  if [ ! -s "$fichier_mdp" ]; then
    return 1   # Postgres doit avoir ete amorce avant : jamais d'application sans base
  fi
  if [ ! -s "$fichier_jwt" ]; then
    ( umask 077
      head -c 48 /dev/urandom | base64 | tr -d '
' > "$fichier_jwt"
    ) || return 1
  fi

  _exec_docker run -d --name "site-$port_hote"     --restart unless-stopped     --network host     -v "$fichier_mdp:/run/secrets-app/DATABASE_PASSWORD:ro"     -v "$fichier_jwt:/run/secrets-app/app.jwt.secret:ro"     -e SPRING_CONFIG_IMPORT=configtree:/run/secrets-app/     -e SPRING_PROFILES_ACTIVE=prod     -e "DATABASE_URL=jdbc:postgresql://127.0.0.1:$port_postgres/site"     -e DATABASE_USERNAME=site     -e "SERVER_PORT=$port_hote"     -e SERVER_ADDRESS=127.0.0.1     "$image"
}

# Demarrage de la premiere instance applicative (conteneur "site-<port>").
# Bibliotheque : destinee a etre sourcee, pas executee directement.

if ! declare -F generer_secret_une_fois >/dev/null; then
  # shellcheck source=/dev/null
  . "$NAS_PKG_DIR/lib/secrets.sh"
fi

# Execution de docker, isolee pour pouvoir etre remplacee dans les tests.
# Definie seulement si absente, pour ne pas ecraser un remplacement deja en place.
if ! declare -F _exec_docker >/dev/null; then
  _exec_docker() { docker "$@"; }
fi

# Utilisateur du conteneur applicatif : DOIT rester identique a l'uid du Dockerfile
# (useradd --uid 10001). Les secrets montes lui sont attribues en lecture seule (0400) ; un
# fichier reserve a root serait illisible et l'application retomberait sur ses valeurs par defaut.
: "${UID_APPLICATION:=10001}"

# Options "docker run" COMMUNES a toutes les instances de l'application, qu'elles soient lancees
# par ce paquet (premiere instance) ou plus tard par l'Agent (bascule bleu-vert, via
# app.agent.docker.run-options) : une nouvelle version doit voir exactement la meme base, les
# memes secrets et le meme profil que la precedente, sinon elle ne demarre pas. Ce qui varie par
# couleur (port d'ecoute) est ajoute separement. Resultat dans OPTIONS_APPLICATION (jetons
# separes par des espaces : aucune valeur ne doit contenir d'espace, verifie ci-dessous).
#
# Secrets : mot de passe Postgres (genere par demarrer_postgres), secret JWT, cle et salt de
# chiffrement (generes ici, une seule fois), montes en lecture seule dans /run/secrets-app et lus
# par Spring via configtree (nom de fichier = nom de propriete). Ils n'apparaissent ni dans argv ni
# dans l'environnement (docker inspect, ps). La cle de chiffrement est PROPRE A CE NAS (decision du
# 2026-11-05) : le transfert de donnees vers un autre serveur doit dechiffrer puis rechiffrer,
# jamais copier la base brute.
#
# Depot GitHub a sonder pour detecter les nouvelles versions : fourni seulement sur le MAITRE
# (seul a interroger GitHub, quota non authentifie de 60 requetes/heure par IP).
OPTIONS_APPLICATION=""
preparer_options_application() {
  local port_postgres="$1" dossier_donnees_postgres="$2"
  local dossier_parent fichier_mdp fichier_mdp_app fichier_jwt fichier_cle fichier_salt
  dossier_parent="$(dirname "$dossier_donnees_postgres")"
  fichier_mdp="$dossier_parent/.postgres-password"
  fichier_mdp_app="$dossier_parent/.app-db-password"
  fichier_jwt="$dossier_parent/.app-jwt-secret"
  fichier_cle="$dossier_parent/.app-crypto-key"
  fichier_salt="$dossier_parent/.app-crypto-salt"
  OPTIONS_APPLICATION=""

  if [ ! -s "$fichier_mdp" ]; then
    return 1   # Postgres doit avoir ete amorce avant : jamais d'application sans base
  fi
  if [[ "$dossier_parent" =~ [[:space:]] ]]; then
    return 1   # les options sont transmises a l'Agent sous forme de jetons separes par des espaces
  fi
  copier_secret_une_fois  "$fichier_mdp" "$fichier_mdp_app" "$UID_APPLICATION" || return 1
  generer_secret_une_fois "$fichier_jwt"  texte "$UID_APPLICATION" || return 1
  generer_secret_une_fois "$fichier_cle"  texte "$UID_APPLICATION" || return 1
  generer_secret_une_fois "$fichier_salt" hex   "$UID_APPLICATION" || return 1

  OPTIONS_APPLICATION="-v $fichier_mdp_app:/run/secrets-app/DATABASE_PASSWORD:ro"
  OPTIONS_APPLICATION="$OPTIONS_APPLICATION -v $fichier_jwt:/run/secrets-app/app.jwt.secret:ro"
  OPTIONS_APPLICATION="$OPTIONS_APPLICATION -v $fichier_cle:/run/secrets-app/app.crypto.key:ro"
  OPTIONS_APPLICATION="$OPTIONS_APPLICATION -v $fichier_salt:/run/secrets-app/app.crypto.salt:ro"
  OPTIONS_APPLICATION="$OPTIONS_APPLICATION -e SPRING_CONFIG_IMPORT=configtree:/run/secrets-app/"
  OPTIONS_APPLICATION="$OPTIONS_APPLICATION -e SPRING_PROFILES_ACTIVE=prod"
  OPTIONS_APPLICATION="$OPTIONS_APPLICATION -e DATABASE_URL=jdbc:postgresql://127.0.0.1:$port_postgres/site"
  OPTIONS_APPLICATION="$OPTIONS_APPLICATION -e DATABASE_USERNAME=site"
  if [[ "${GITHUB_REPO:-}" =~ ^[A-Za-z0-9._-]+/[A-Za-z0-9._-]+$ ]] \
     && declare -F nas_est_maitre >/dev/null && nas_est_maitre; then
    OPTIONS_APPLICATION="$OPTIONS_APPLICATION -e APP_DEPLOIEMENT_GITHUBREPO=$GITHUB_REPO"
  fi
  return 0
}

# Demarre la premiere instance applicative depuis "image".
#
# Reseau : --network host, pour que l'application puisse joindre Postgres
# (127.0.0.1:port_postgres, publie en loopback par postgres_bootstrap.sh) ET,
# plus tard, des services du NAS. Consequence : -p est sans effet en mode host,
# donc l'ecoute est fixee par SERVER_PORT=port_hote et SERVER_ADDRESS=127.0.0.1
# (jamais 0.0.0.0 : seul le Nginx local de bascule doit pouvoir l'atteindre). L'Agent fait de
# meme pour les instances suivantes (app.agent.docker.reseau-hote).
#
# Le nom du conteneur est deterministe : "site-<port_hote>", meme convention que
# DockerControl.nomConteneur cote Agent, pour que l'Agent puisse reconnaitre et
# gerer ce conteneur lors d'une future bascule bleu-vert.
demarrer_premiere_instance() {
  local image="$1" port_hote="$2" port_postgres="$3" dossier_donnees_postgres="$4"

  preparer_options_application "$port_postgres" "$dossier_donnees_postgres" || return 1

  # shellcheck disable=SC2086  # separation en jetons voulue (aucune valeur ne contient d'espace)
  _exec_docker run -d --name "site-$port_hote" \
    --restart unless-stopped \
    --network host \
    -e "SERVER_PORT=$port_hote" \
    -e SERVER_ADDRESS=127.0.0.1 \
    $OPTIONS_APPLICATION \
    "$image"
}

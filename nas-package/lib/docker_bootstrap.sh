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

# Demarre la premiere instance applicative depuis "image".
#
# Reseau : --network host, pour que l'application puisse joindre Postgres
# (127.0.0.1:port_postgres, publie en loopback par postgres_bootstrap.sh) ET,
# plus tard, des services du NAS. Consequence : -p est sans effet en mode host,
# donc l'ecoute est fixee par SERVER_PORT=port_hote et SERVER_ADDRESS=127.0.0.1
# (jamais 0.0.0.0 : seul le Nginx local de bascule doit pouvoir l'atteindre).
#
# Secrets : mot de passe Postgres (genere par demarrer_postgres), secret JWT, cle et salt
# de chiffrement (generes ici, une seule fois) sont montes en lecture seule dans
# /run/secrets-app et lus par Spring via configtree (nom de fichier = nom de propriete).
# Ils n'apparaissent ni dans argv ni dans les variables d'environnement (docker inspect, ps).
# La cle de chiffrement est PROPRE A CE NAS (decision du 2026-11-05) : le transfert de
# donnees vers un autre serveur doit dechiffrer puis rechiffrer, jamais copier la base brute.
#
# Le nom du conteneur est deterministe : "site-<port_hote>", meme convention que
# DockerControl.nomConteneur cote Agent, pour que l'Agent puisse reconnaitre et
# gerer ce conteneur lors d'une future bascule bleu-vert.
demarrer_premiere_instance() {
  local image="$1" port_hote="$2" port_postgres="$3" dossier_donnees_postgres="$4"
  local dossier_parent fichier_mdp fichier_mdp_app fichier_jwt fichier_cle fichier_salt
  dossier_parent="$(dirname "$dossier_donnees_postgres")"
  fichier_mdp="$dossier_parent/.postgres-password"
  fichier_mdp_app="$dossier_parent/.app-db-password"
  fichier_jwt="$dossier_parent/.app-jwt-secret"
  fichier_cle="$dossier_parent/.app-crypto-key"
  fichier_salt="$dossier_parent/.app-crypto-salt"

  if [ ! -s "$fichier_mdp" ]; then
    return 1   # Postgres doit avoir ete amorce avant : jamais d'application sans base
  fi
  copier_secret_une_fois  "$fichier_mdp" "$fichier_mdp_app" "$UID_APPLICATION" || return 1
  generer_secret_une_fois "$fichier_jwt"  texte "$UID_APPLICATION" || return 1
  generer_secret_une_fois "$fichier_cle"  texte "$UID_APPLICATION" || return 1
  generer_secret_une_fois "$fichier_salt" hex   "$UID_APPLICATION" || return 1

  _exec_docker run -d --name "site-$port_hote" \
    --restart unless-stopped \
    --network host \
    -v "$fichier_mdp_app:/run/secrets-app/DATABASE_PASSWORD:ro" \
    -v "$fichier_jwt:/run/secrets-app/app.jwt.secret:ro" \
    -v "$fichier_cle:/run/secrets-app/app.crypto.key:ro" \
    -v "$fichier_salt:/run/secrets-app/app.crypto.salt:ro" \
    -e SPRING_CONFIG_IMPORT=configtree:/run/secrets-app/ \
    -e SPRING_PROFILES_ACTIVE=prod \
    -e "DATABASE_URL=jdbc:postgresql://127.0.0.1:$port_postgres/site" \
    -e DATABASE_USERNAME=site \
    -e "SERVER_PORT=$port_hote" \
    -e SERVER_ADDRESS=127.0.0.1 \
    "$image"
}

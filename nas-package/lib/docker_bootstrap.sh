# Demarrage de la premiere instance applicative (conteneur "site-<port>").
# Bibliotheque : destinee a etre sourcee, pas executee directement.

# Execution de docker, isolee pour pouvoir etre remplacee dans les tests.
# Definie seulement si absente, pour ne pas ecraser un remplacement deja en place.
if ! declare -F _exec_docker >/dev/null; then
  _exec_docker() { docker "$@"; }
fi

# Cree "fichier" avec un secret aleatoire UNE SEULE FOIS (jamais regenere s'il est non vide :
# changer le secret JWT invaliderait les sessions, changer la cle de chiffrement rendrait
# illisibles les identifiants deja chiffres). Mode 0600. Type : "texte" (base64 sur une ligne)
# ou "hex" (chaine hexadecimale de longueur paire, exigee pour le salt de chiffrement).
# Code 1 si le fichier n'a pas pu etre ecrit ou reste vide.
generer_secret_une_fois() {
  local fichier="$1" type="$2"
  # -f d'abord : sous Linux, -s est vrai pour un dossier (taille 4096), qui serait pris pour un secret.
  if [ -f "$fichier" ] && [ -s "$fichier" ]; then
    return 0
  fi
  case "$type" in
    texte) ( umask 077; head -c 48 /dev/urandom | base64 | tr -d '
' > "$fichier" ) || return 1 ;;
    hex)   ( umask 077; head -c 16 /dev/urandom | od -An -tx1 | tr -d ' 
' > "$fichier" ) || return 1 ;;
    *) return 1 ;;
  esac
  [ -f "$fichier" ] && [ -s "$fichier" ]
}

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
  local dossier_parent fichier_mdp fichier_jwt fichier_cle fichier_salt
  dossier_parent="$(dirname "$dossier_donnees_postgres")"
  fichier_mdp="$dossier_parent/.postgres-password"
  fichier_jwt="$dossier_parent/.app-jwt-secret"
  fichier_cle="$dossier_parent/.app-crypto-key"
  fichier_salt="$dossier_parent/.app-crypto-salt"

  if [ ! -s "$fichier_mdp" ]; then
    return 1   # Postgres doit avoir ete amorce avant : jamais d'application sans base
  fi
  generer_secret_une_fois "$fichier_jwt"  texte || return 1
  generer_secret_une_fois "$fichier_cle"  texte || return 1
  generer_secret_une_fois "$fichier_salt" hex   || return 1

  _exec_docker run -d --name "site-$port_hote"     --restart unless-stopped     --network host     -v "$fichier_mdp:/run/secrets-app/DATABASE_PASSWORD:ro"     -v "$fichier_jwt:/run/secrets-app/app.jwt.secret:ro"     -v "$fichier_cle:/run/secrets-app/app.crypto.key:ro"     -v "$fichier_salt:/run/secrets-app/app.crypto.salt:ro"     -e SPRING_CONFIG_IMPORT=configtree:/run/secrets-app/     -e SPRING_PROFILES_ACTIVE=prod     -e "DATABASE_URL=jdbc:postgresql://127.0.0.1:$port_postgres/site"     -e DATABASE_USERNAME=site     -e "SERVER_PORT=$port_hote"     -e SERVER_ADDRESS=127.0.0.1     "$image"
}

# Amorcage du conteneur Postgres avec donnees hors cycle de vie du paquet DSM.
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

# Demarre (ou redemarre) le conteneur Postgres "site-postgres", monte sur
# dossier_donnees_externe (TOUJOURS hors de l'arborescence geree par le paquet
# DSM, ex. un dossier partage dedie) et expose uniquement sur loopback.
#
# Le conteneur precedent est supprime d'abord (rm -f, idempotent meme si
# absent) pour que la fonction soit sans danger a reinvoquer (Task 10,
# postupgrade) sans "nom de conteneur deja utilise". Ceci ne touche jamais au
# contenu de dossier_donnees_externe : Postgres ne garde rien dans la couche
# inscriptible du conteneur, tout est dans le dossier monte, et Postgres gere
# lui-meme un volume deja peuple au redemarrage.
#
# Deux particularites de l'image officielle postgres:18 geres ici :
# - son PGDATA par defaut a change (plus /var/lib/postgresql/data) - fixe
#   explicitement via -e PGDATA pour garder le meme chemin de montage que les
#   installations deja en place (sinon : crash au demarrage, ou pire, une
#   nouvelle base vide creee silencieusement sur un volume non reconnu) ;
# - elle refuse d'amorcer un dossier de donnees vide sans mot de passe. Un mot
#   de passe est genere UNE SEULE FOIS (fichier sibling du dossier de donnees,
#   donc dans le meme dossier partage hors cycle de vie du paquet) et reutilise
#   a chaque reinvocation - ne jamais le regenerer, une base deja initialisee
#   refuserait la connexion applicative avec un mot de passe different. Fourni
#   au conteneur via POSTGRES_PASSWORD_FILE (jamais en variable d'env en clair
#   ni en argv) sur un montage en lecture seule.
demarrer_postgres() {
  local dossier_donnees_externe="$1" port_hote="$2"
  local fichier_mdp="$(dirname "$dossier_donnees_externe")/.postgres-password"

  generer_secret_une_fois "$fichier_mdp" texte || return 1

  _exec_docker rm -f site-postgres >/dev/null 2>&1 || true

  _exec_docker run -d --name site-postgres \
    -v "$dossier_donnees_externe:/var/lib/postgresql/data" \
    -v "$fichier_mdp:/run/secrets/postgres_password:ro" \
    -e PGDATA=/var/lib/postgresql/data \
    -e POSTGRES_USER=site \
    -e POSTGRES_DB=site \
    -e POSTGRES_PASSWORD_FILE=/run/secrets/postgres_password \
    -p "127.0.0.1:$port_hote:5432" \
    postgres:18
}

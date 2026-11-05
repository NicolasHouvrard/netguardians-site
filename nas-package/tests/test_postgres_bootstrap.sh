NAS_PKG_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

test_demarrer_postgres_monte_le_dossier_de_donnees_externe_et_le_port_sur_loopback() {
  dir=$(mktemp -d); mkdir -p "$dir/postgres"
  recu=""
  _exec_docker() { recu="$*"; }
  source "$NAS_PKG_DIR/lib/postgres_bootstrap.sh"
  demarrer_postgres "$dir/postgres" 15432
  [[ "$recu" == *"-v $dir/postgres:/var/lib/postgresql/data"* ]] \
    && [[ "$recu" == *"-p 127.0.0.1:15432:5432"* ]]
}

test_demarrer_postgres_ne_supprime_jamais_le_dossier_de_donnees_existant() {
  # le dossier existe deja avec un fichier dedans ; la fonction ne doit faire
  # aucun appel a rm/docker volume rm SUR LE DOSSIER, seulement docker rm/run
  # (le conteneur, lui, est jetable - voir le test de reinvocation ci-dessous)
  touch_marker=$(mktemp -d)/marker
  mkdir -p "$(dirname "$touch_marker")" && touch "$touch_marker"
  _exec_docker() { :; }
  source "$NAS_PKG_DIR/lib/postgres_bootstrap.sh"
  demarrer_postgres "$(dirname "$touch_marker")" 15432
  [ -f "$touch_marker" ]
}

test_demarrer_postgres_est_reinvocable_sans_erreur_de_nom_deja_pris() {
  # simule une 2e invocation (Task 10, postupgrade) : le conteneur precedent
  # doit etre supprime AVANT la recreation, jamais l'inverse
  dir=$(mktemp -d); mkdir -p "$dir/postgres"
  appels=()
  _exec_docker() { appels+=("$*"); }
  source "$NAS_PKG_DIR/lib/postgres_bootstrap.sh"
  demarrer_postgres "$dir/postgres" 15432
  [[ "${appels[0]}" == *"rm -f site-postgres"* ]] && [[ "${appels[1]}" == *"run"*"site-postgres"* ]]
}

test_demarrer_postgres_fixe_pgdata_pour_lancienne_mise_en_page_de_postgres18() {
  # postgres:18 a change son PGDATA par defaut (plus /var/lib/postgresql/data) ;
  # sans -e PGDATA explicite, le montage existant pointerait vers un chemin que
  # l'image n'utilise plus (crash, ou pire, une base vide creee ailleurs)
  dir=$(mktemp -d); mkdir -p "$dir/postgres"
  recu=""
  _exec_docker() { recu="$*"; }
  source "$NAS_PKG_DIR/lib/postgres_bootstrap.sh"
  demarrer_postgres "$dir/postgres" 15432
  [[ "$recu" == *"PGDATA=/var/lib/postgresql/data"* ]]
}

test_demarrer_postgres_genere_un_mot_de_passe_au_premier_demarrage_et_le_fournit_par_fichier() {
  # l'image postgres refuse d'amorcer un dossier de donnees vide sans mot de
  # passe ; jamais en variable d'env en clair ni en argv - via POSTGRES_PASSWORD_FILE
  dir=$(mktemp -d); mkdir -p "$dir/postgres"
  recu=""
  _exec_docker() { recu="$*"; }
  source "$NAS_PKG_DIR/lib/postgres_bootstrap.sh"
  demarrer_postgres "$dir/postgres" 15432
  [ -s "$dir/.postgres-password" ] \
    && [[ "$recu" == *"POSTGRES_PASSWORD_FILE=/run/secrets/postgres_password"* ]] \
    && [[ "$recu" == *"-v $dir/.postgres-password:/run/secrets/postgres_password:ro"* ]]
}

test_demarrer_postgres_ne_regenere_jamais_un_mot_de_passe_deja_present() {
  # une base deja initialisee avec un mot de passe donne refuserait la
  # connexion applicative si ce mot de passe changeait a une reinvocation
  dir=$(mktemp -d); mkdir -p "$dir/postgres"
  _exec_docker() { :; }
  source "$NAS_PKG_DIR/lib/postgres_bootstrap.sh"
  printf 'mot-de-passe-existant\n' > "$dir/.postgres-password"
  demarrer_postgres "$dir/postgres" 15432
  [ "$(cat "$dir/.postgres-password")" = "mot-de-passe-existant" ]
}

test_demarrer_postgres_cree_l_utilisateur_et_la_base_site_attendus_par_l_application() {
  # l'application est lancee avec DATABASE_USERNAME=site et .../site (voir docker_bootstrap.sh) :
  # sans POSTGRES_USER/POSTGRES_DB cote Postgres, l'image creerait "postgres" et l'app ne se connecterait pas
  dir=$(mktemp -d); mkdir -p "$dir/postgres"
  recu=""
  _exec_docker() { recu="$*"; }
  source "$NAS_PKG_DIR/lib/postgres_bootstrap.sh"
  demarrer_postgres "$dir/postgres" 15432
  [[ "$recu" == *"-e POSTGRES_USER=site"* ]] && [[ "$recu" == *"-e POSTGRES_DB=site"* ]]
}

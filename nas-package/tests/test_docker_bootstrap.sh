NAS_PKG_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# prepare un dossier parent avec un dossier de donnees Postgres et le mot de passe deja genere
_preparer() {
  dir=$(mktemp -d); mkdir -p "$dir/postgres"; echo "mdp" > "$dir/.postgres-password"
}

test_demarrer_premiere_instance_nomme_le_conteneur_site_port_en_reseau_host_sur_loopback() {
  _preparer
  recu=""
  _exec_docker() { recu="$*"; }
  source "$NAS_PKG_DIR/lib/docker_bootstrap.sh"
  demarrer_premiere_instance "ghcr.io/owner/site:v1.0.0" 18080 15432 "$dir/postgres"
  [[ "$recu" == *"--name site-18080"* ]]     && [[ "$recu" == *"--network host"* ]]     && [[ "$recu" == *"-e SERVER_PORT=18080"* ]]     && [[ "$recu" == *"-e SERVER_ADDRESS=127.0.0.1"* ]]     && [[ "$recu" == *"ghcr.io/owner/site:v1.0.0"* ]]     && [[ "$recu" == *"--restart unless-stopped"* ]]
}

test_demarrer_premiere_instance_derive_le_nom_et_le_port_hote_du_parametre_pas_dune_valeur_fixe() {
  _preparer
  recu=""
  _exec_docker() { recu="$*"; }
  source "$NAS_PKG_DIR/lib/docker_bootstrap.sh"
  demarrer_premiere_instance "ghcr.io/owner/site:v1.0.0" 19091 15432 "$dir/postgres"
  [[ "$recu" == *"--name site-19091"* ]] && [[ "$recu" == *"-e SERVER_PORT=19091"* ]]
}

test_demarrer_premiere_instance_pointe_vers_postgres_via_le_port_fourni_sans_mot_de_passe_en_clair() {
  _preparer
  recu=""
  _exec_docker() { recu="$*"; }
  source "$NAS_PKG_DIR/lib/docker_bootstrap.sh"
  demarrer_premiere_instance "img" 18080 16543 "$dir/postgres"
  [[ "$recu" == *"DATABASE_URL=jdbc:postgresql://127.0.0.1:16543/site"* ]]     && [[ "$recu" == *"DATABASE_USERNAME=site"* ]]     && [[ "$recu" == *"-v $dir/.postgres-password:/run/secrets-app/DATABASE_PASSWORD:ro"* ]]     && [[ "$recu" != *"DATABASE_PASSWORD="* ]]     && [[ "$recu" != *"mdp"* ]]
}

test_demarrer_premiere_instance_genere_le_secret_jwt_une_seule_fois() {
  _preparer
  _exec_docker() { :; }
  source "$NAS_PKG_DIR/lib/docker_bootstrap.sh"
  demarrer_premiere_instance "img" 18080 15432 "$dir/postgres"
  premier=$(cat "$dir/.app-jwt-secret")
  demarrer_premiere_instance "img" 18080 15432 "$dir/postgres"
  [ -n "$premier" ] && [ "${#premier}" -ge 32 ] && [ "$premier" = "$(cat "$dir/.app-jwt-secret")" ]     && _mode_0600_ou_fs_sans_permissions "$dir/.app-jwt-secret"
}

# NTFS (Git Bash sous Windows) ne porte pas de permissions POSIX : le mode 0600 n'y est
# pas verifiable. On sonde le systeme de fichiers d'abord, et on n'exige 0600 que s'il
# honore chmod - sinon le test serait faux-rouge sans rien dire du code.
_mode_0600_ou_fs_sans_permissions() {
  sonde=$(mktemp); chmod 600 "$sonde"
  if [ "$(stat -c %a "$sonde")" != "600" ]; then rm -f "$sonde"; return 0; fi
  rm -f "$sonde"
  [ "$(stat -c %a "$1")" = "600" ]
}

test_demarrer_premiere_instance_echoue_ferme_si_postgres_n_a_pas_ete_amorce() {
  dir=$(mktemp -d); mkdir -p "$dir/postgres"   # pas de .postgres-password
  appele=0
  _exec_docker() { appele=1; }
  source "$NAS_PKG_DIR/lib/docker_bootstrap.sh"
  demarrer_premiere_instance "img" 18080 15432 "$dir/postgres"; code=$?
  [ "$code" -eq 1 ] && [ "$appele" -eq 0 ]
}

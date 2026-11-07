NAS_PKG_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# prepare un dossier parent avec un dossier de donnees Postgres et le mot de passe deja genere
_preparer() {
  dir=$(mktemp -d); mkdir -p "$dir/postgres"; echo "mdp" > "$dir/.postgres-password"; _exec_chown() { :; }
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
  [[ "$recu" == *"DATABASE_URL=jdbc:postgresql://127.0.0.1:16543/site"* ]]     && [[ "$recu" == *"DATABASE_USERNAME=site"* ]]     && [[ "$recu" == *"-v $dir/.app-db-password:/run/secrets-app/DATABASE_PASSWORD:ro"* ]]     && [[ "$recu" != *"DATABASE_PASSWORD="* ]]     && [[ "$recu" != *"mdp"* ]]
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
  case "$(stat -c %a "$1")" in 400|600) return 0 ;; *) return 1 ;; esac
}

test_demarrer_premiere_instance_echoue_ferme_si_postgres_n_a_pas_ete_amorce() {
  dir=$(mktemp -d); mkdir -p "$dir/postgres"   # pas de .postgres-password
  appele=0
  _exec_docker() { appele=1; }
  source "$NAS_PKG_DIR/lib/docker_bootstrap.sh"
  demarrer_premiere_instance "img" 18080 15432 "$dir/postgres"; code=$?
  [ "$code" -eq 1 ] && [ "$appele" -eq 0 ]
}

test_demarrer_premiere_instance_genere_cle_et_salt_de_chiffrement_une_seule_fois() {
  _preparer
  _exec_docker() { :; }
  source "$NAS_PKG_DIR/lib/docker_bootstrap.sh"
  demarrer_premiere_instance "img" 18080 15432 "$dir/postgres"
  cle=$(cat "$dir/.app-crypto-key"); salt=$(cat "$dir/.app-crypto-salt")
  demarrer_premiere_instance "img" 18080 15432 "$dir/postgres"
  # cle >= 16 caracteres (MailEncryptionConverter n'en utilise que 16 octets), salt hexa de
  # longueur paire (Encryptors.text l'exige), et rien n'est regenere au 2e appel
  [ "${#cle}" -ge 32 ]     && [[ "$salt" =~ ^[0-9a-f]+$ ]] && [ $(( ${#salt} % 2 )) -eq 0 ] && [ "${#salt}" -ge 16 ]     && [ "$cle" = "$(cat "$dir/.app-crypto-key")" ] && [ "$salt" = "$(cat "$dir/.app-crypto-salt")" ]     && [ "$cle" != "$salt" ]
}

test_demarrer_premiere_instance_monte_cle_et_salt_sans_les_exposer_dans_la_commande() {
  _preparer
  recu=""
  _exec_docker() { recu="$*"; }
  source "$NAS_PKG_DIR/lib/docker_bootstrap.sh"
  demarrer_premiere_instance "img" 18080 15432 "$dir/postgres"
  cle=$(cat "$dir/.app-crypto-key"); salt=$(cat "$dir/.app-crypto-salt")
  [[ "$recu" == *"-v $dir/.app-crypto-key:/run/secrets-app/app.crypto.key:ro"* ]]     && [[ "$recu" == *"-v $dir/.app-crypto-salt:/run/secrets-app/app.crypto.salt:ro"* ]]     && [[ "$recu" != *"$cle"* ]] && [[ "$recu" != *"$salt"* ]]     && [[ "$recu" != *"APP_CRYPTO"* ]]
}

test_demarrer_premiere_instance_n_ecrase_jamais_une_cle_de_chiffrement_existante() {
  # une cle regeneree rendrait illisibles les identifiants deja chiffres en base
  _preparer
  printf 'cle-existante-a-conserver-0123456789abcdef' > "$dir/.app-crypto-key"
  printf 'aabbccddeeff0011' > "$dir/.app-crypto-salt"
  _exec_docker() { :; }
  source "$NAS_PKG_DIR/lib/docker_bootstrap.sh"
  demarrer_premiere_instance "img" 18080 15432 "$dir/postgres"
  [ "$(cat "$dir/.app-crypto-key")" = "cle-existante-a-conserver-0123456789abcdef" ]     && [ "$(cat "$dir/.app-crypto-salt")" = "aabbccddeeff0011" ]
}

test_demarrer_premiere_instance_echoue_ferme_si_un_secret_ne_peut_pas_etre_ecrit() {
  _preparer
  appele=0
  _exec_docker() { appele=1; }
  source "$NAS_PKG_DIR/lib/docker_bootstrap.sh"
  mkdir "$dir/.app-crypto-key"   # un dossier a la place du fichier : l'ecriture echoue
  demarrer_premiere_instance "img" 18080 15432 "$dir/postgres"; code=$?
  [ "$code" -eq 1 ] && [ "$appele" -eq 0 ]
}

test_preparer_options_application_donne_les_memes_options_que_celles_de_la_premiere_instance() {
  _preparer
  recu=""
  _exec_docker() { recu="$*"; }
  source "$NAS_PKG_DIR/lib/docker_bootstrap.sh"
  preparer_options_application 15432 "$dir/postgres" || return 1
  demarrer_premiere_instance "img" 18080 15432 "$dir/postgres"
  [[ "$OPTIONS_APPLICATION" == *"SPRING_PROFILES_ACTIVE=prod"* && "$OPTIONS_APPLICATION" == *"-v $dir/.app-crypto-key:/run/secrets-app/app.crypto.key:ro"* \
     && "$OPTIONS_APPLICATION" != *"SERVER_PORT"* && "$recu" == *"$OPTIONS_APPLICATION"* ]]
}

test_preparer_options_application_ajoute_le_depot_github_seulement_sur_le_maitre() {
  _preparer
  source "$NAS_PKG_DIR/lib/docker_bootstrap.sh"
  GITHUB_REPO="Proprietaire/depot-site"
  nas_est_maitre() { return 0; }
  preparer_options_application 15432 "$dir/postgres"; sur_maitre="$OPTIONS_APPLICATION"
  nas_est_maitre() { return 1; }
  preparer_options_application 15432 "$dir/postgres"; sur_client="$OPTIONS_APPLICATION"
  GITHUB_REPO="pas un depot valide"
  nas_est_maitre() { return 0; }
  preparer_options_application 15432 "$dir/postgres"; invalide="$OPTIONS_APPLICATION"
  [[ "$sur_maitre" == *"-e APP_DEPLOIEMENT_GITHUBREPO=Proprietaire/depot-site"* && "$sur_client" != *GITHUBREPO* && "$invalide" != *GITHUBREPO* ]]
}

test_preparer_options_application_refuse_un_chemin_avec_espace() {
  dir=$(mktemp -d); mkdir -p "$dir/avec espace/postgres"; echo mdp > "$dir/avec espace/.postgres-password"
  _exec_chown() { :; }
  source "$NAS_PKG_DIR/lib/docker_bootstrap.sh"
  preparer_options_application 15432 "$dir/avec espace/postgres"
  [ "$?" -ne 0 ] && [ -z "$OPTIONS_APPLICATION" ]
}

test_preparer_options_application_cree_le_fichier_de_reglages_vide_et_le_donne_a_docker() {
  _preparer
  source "$NAS_PKG_DIR/lib/docker_bootstrap.sh"
  preparer_options_application 15432 "$dir/postgres" || return 1
  [ -f "$dir/app.env" ] && [ ! -s "$dir/app.env" ] && [[ "$OPTIONS_APPLICATION" == *"--env-file $dir/app.env"* ]] \
    && _mode_0600_ou_fs_sans_permissions_reglages "$dir/app.env"
}

_mode_0600_ou_fs_sans_permissions_reglages() {
  sonde=$(mktemp); chmod 600 "$sonde"
  if [ "$(stat -c %a "$sonde")" != "600" ]; then rm -f "$sonde"; return 0; fi
  rm -f "$sonde"
  [ "$(stat -c %a "$1")" = "600" ]
}

test_preparer_options_application_ne_touche_pas_un_fichier_de_reglages_existant_et_le_place_avant_les_e() {
  _preparer
  printf 'CAPTCHA_ENABLED=false\n' > "$dir/app.env"
  source "$NAS_PKG_DIR/lib/docker_bootstrap.sh"
  preparer_options_application 15432 "$dir/postgres" || return 1
  avant="${OPTIONS_APPLICATION%%-e SPRING_PROFILES_ACTIVE*}"
  [ "$(cat "$dir/app.env")" = "CAPTCHA_ENABLED=false" ] && [[ "$avant" == *"--env-file $dir/app.env"* ]]
}

test_preparer_options_application_donne_a_l_application_le_port_de_l_agent_du_paquet() {
  # Le Maitre reveille l'Agent de chaque serveur sur app.agent.port (defaut 8090 cote application) ;
  # l'Agent du paquet ecoute sur PORT_AGENT (18090) : sans cette variable le reveil echoue toujours.
  _preparer
  PORT_AGENT=18090
  source "$NAS_PKG_DIR/lib/docker_bootstrap.sh"
  preparer_options_application 15432 "$dir/postgres" || return 1
  [[ "$OPTIONS_APPLICATION" == *"-e AGENT_PORT=18090"* ]]
}

test_preparer_options_application_suit_port_agent_quand_il_change() {
  _preparer
  PORT_AGENT=19999
  source "$NAS_PKG_DIR/lib/docker_bootstrap.sh"
  preparer_options_application 15432 "$dir/postgres" || return 1
  [[ "$OPTIONS_APPLICATION" == *"-e AGENT_PORT=19999"* && "$OPTIONS_APPLICATION" != *"AGENT_PORT=18090"* ]]
}

RACINE_TEST_INSTALLATEUR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# Fabrique un faux .spk avec les VRAIS scripts et bibliotheques, des commandes systeme simulees
# (tailscale, docker, nginx, systemctl...) qui journalisent leurs appels dans $STUB_LOG, et des
# dossiers de donnees/Nginx/systemd rediriges vers un dossier temporaire.
_hash() { sha256sum "$1" | cut -d" " -f1; }

_preparer_installation() {
  local src="$RACINE_TEST_INSTALLATEUR"
  T="$(mktemp -d)"
  SPKDIR="$T/spk"; CONTENU="$T/contenu"; STUBS="$T/stubs"
  mkdir -p "$SPKDIR/scripts" "$CONTENU/lib" "$CONTENU/systemd" "$CONTENU/agent" "$CONTENU/jre/bin" "$STUBS"
  local s
  for s in preinst postinst postuninst postupgrade preuninst preupgrade start-stop-status; do
    cp "$src/spk/src/scripts/$s.sh" "$SPKDIR/scripts/$s"
  done
  cp "$src/spk/src/scripts/commun.sh" "$src/spk/src/scripts/prestart.sh" "$src/spk/src/scripts/activer-ecoute.sh" "$src/spk/src/scripts/mettre-a-jour-maitre.sh" "$src/spk/src/scripts/postinst-vps.sh" "$SPKDIR/scripts/"
  printf 'HEADSCALE_URL=https://hs.exemple.test\nIMAGE_OWNER=proprietaire\nIMAGE_TAG=v9.9\n' > "$SPKDIR/scripts/constantes.sh"
  cp "$src"/lib/*.sh "$CONTENU/lib/"
  cp "$src"/systemd/* "$CONTENU/systemd/"
  echo "jar" > "$CONTENU/agent/agent.jar"; echo "java" > "$CONTENU/jre/bin/java"
  tar -czf "$SPKDIR/package.tgz" -C "$CONTENU" lib systemd agent jre
  printf 'package="site-nas"\nchecksum="%s"\n' "$(md5sum "$SPKDIR/package.tgz" | cut -d' ' -f1)" > "$SPKDIR/INFO"
  SPK="$T/site-nas.spk"
  tar -cf "$SPK" -C "$SPKDIR" INFO package.tgz scripts

  STUB_LOG="$T/appels.log"; : > "$STUB_LOG"
  local cmd
  for cmd in docker systemctl synosystemctl nginx curl logger chown sleep; do
    printf '#!/bin/bash\necho "%s $*" >> "%s"\nexit 0\n' "$cmd" "$STUB_LOG" > "$STUBS/$cmd"
  done
  printf '#!/bin/bash\ncase "$1" in\n  version) echo 1.104.5 ;;\n  ip) echo 100.64.0.2 ;;\nesac\necho "tailscale $*" >> "%s"\nexit 0\n' "$STUB_LOG" > "$STUBS/tailscale"
  printf '#!/bin/bash\necho "54: tailscale0    inet 100.64.0.2/32 scope global tailscale0"\n' > "$STUBS/ip"
  printf '#!/bin/bash\nexit 0\n' > "$STUBS/ss"
  printf '#!/bin/bash\necho LogiPourtous\n' > "$STUBS/hostname"
  chmod +x "$STUBS"/*

  export STUB_LOG
  export PATH="$STUBS:$PATH"
  export SITE_NAS_SANS_ROOT=1 SITE_NAS_PREFIXE="$T/prefixe"
  export SITE_NAS_RACINE_DONNEES="$T/data" SITE_NAS_DOSSIER_NGINX="$T/nginx" SITE_NAS_DOSSIER_SYSTEMD="$T/systemd-etc"
}

test_installer_nas_installe_de_bout_en_bout_avec_les_vrais_scripts() {
  _preparer_installation
  sortie="$(bash "$RACINE_TEST_INSTALLATEUR/installer/installer-nas.sh" "$SPK" "$(_hash "$SPK")" 2>&1 < /dev/null)"; code=$?
  [ "$code" -eq 0 ] || { echo "$sortie" | tail -8; return 1; }
  [[ "$sortie" == *"Installation terminee"* ]] \
    && grep -q "^docker run -d --name site-postgres" "$STUB_LOG" \
    && grep -q "^docker run -d --name site-18080" "$STUB_LOG" \
    && grep -q "proprietaire/site:v9.9" "$STUB_LOG" \
    && [ -f "$T/systemd-etc/site-agent.service" ] \
    && [ -f "$T/data/.postgres-password" ] && [ -f "$T/data/.app-crypto-key" ] \
    && [ -f "$SITE_NAS_PREFIXE/demarrage-nas.sh" ] && [ -f "$SITE_NAS_PREFIXE/lib/secrets.sh" ] \
    && grep -q "prestart.sh" "$SITE_NAS_PREFIXE/demarrage-nas.sh"
}

test_installer_nas_refuse_un_paquet_dont_le_package_tgz_a_ete_altere_apres_la_somme_fournie() {
  _preparer_installation
  somme_officielle="$(_hash "$SPK")"
  echo "alteration" >> "$SPKDIR/package.tgz"
  tar -cf "$SPK" -C "$SPKDIR" INFO package.tgz scripts
  sortie="$(bash "$RACINE_TEST_INSTALLATEUR/installer/installer-nas.sh" "$SPK" "$somme_officielle" 2>&1 < /dev/null)"; code=$?
  [ "$code" -eq 1 ] && [[ "$sortie" == *"ne correspond pas"* ]] && [ ! -e "$SITE_NAS_PREFIXE/lib" ] && ! grep -q "^docker" "$STUB_LOG"
}

test_installer_nas_refuse_un_paquet_dont_un_script_execute_en_root_a_ete_altere() {
  # le cas que l'ancienne somme MD5 de INFO ne couvrait pas : scripts/ modifie, package.tgz et INFO intacts
  _preparer_installation
  somme_officielle="$(_hash "$SPK")"
  echo 'touch "$HOME/pwned-par-un-script-altere"' >> "$SPKDIR/scripts/postinst"
  tar -cf "$SPK" -C "$SPKDIR" INFO package.tgz scripts
  HOME="$T/home" ; mkdir -p "$HOME"
  bash "$RACINE_TEST_INSTALLATEUR/installer/installer-nas.sh" "$SPK" "$somme_officielle" >/dev/null 2>&1 < /dev/null; code=$?
  [ "$code" -eq 1 ] && [ ! -e "$HOME/pwned-par-un-script-altere" ] && [ ! -e "$SITE_NAS_PREFIXE/scripts" ]
}

test_installer_nas_refuse_sans_somme_sha256_ou_avec_une_somme_mal_formee() {
  _preparer_installation
  bash "$RACINE_TEST_INSTALLATEUR/installer/installer-nas.sh" "$SPK" >/dev/null 2>&1 < /dev/null; a=$?
  bash "$RACINE_TEST_INSTALLATEUR/installer/installer-nas.sh" "$SPK" "abc123" >/dev/null 2>&1 < /dev/null; b=$?
  bash "$RACINE_TEST_INSTALLATEUR/installer/installer-nas.sh" "$SPK" "$(printf 'z%.0s' $(seq 64))" >/dev/null 2>&1 < /dev/null; c=$?
  [ "$a" -eq 1 ] && [ "$b" -eq 1 ] && [ "$c" -eq 1 ] && [ ! -e "$SITE_NAS_PREFIXE" ] && ! grep -q "^docker" "$STUB_LOG"
}

test_installer_nas_accepte_la_somme_en_majuscules() {
  _preparer_installation
  maj="$(_hash "$SPK" | tr 'a-f' 'A-F')"
  bash "$RACINE_TEST_INSTALLATEUR/installer/installer-nas.sh" "$SPK" "$maj" >/dev/null 2>&1 < /dev/null
}

test_installer_nas_refuse_sans_les_droits_root() {
  _preparer_installation
  unset SITE_NAS_SANS_ROOT
  [ "$(id -u)" -ne 0 ] || return 0   # lance en root : sans objet
  sortie="$(bash "$RACINE_TEST_INSTALLATEUR/installer/installer-nas.sh" "$SPK" "$(_hash "$SPK")" 2>&1 < /dev/null)"; code=$?
  [ "$code" -eq 1 ] && [[ "$sortie" == *"root"* ]] && [ ! -e "$SITE_NAS_PREFIXE" ]
}

test_installer_nas_refuse_un_chemin_de_paquet_absent() {
  _preparer_installation
  bash "$RACINE_TEST_INSTALLATEUR/installer/installer-nas.sh" "$T/nexiste-pas.spk" >/dev/null 2>&1 < /dev/null; code=$?
  [ "$code" -eq 1 ]
}

test_installer_nas_refuse_un_dossier_d_installation_sous_l_arborescence_dsm_des_paquets() {
  _preparer_installation
  SITE_NAS_PREFIXE="/var/packages/site-nas/target" bash "$RACINE_TEST_INSTALLATEUR/installer/installer-nas.sh" "$SPK" "$(_hash "$SPK")" >/dev/null 2>&1 < /dev/null; code=$?
  [ "$code" -eq 1 ]
}

test_installer_nas_ne_met_jamais_la_cle_headscale_dans_les_arguments_des_commandes() {
  _preparer_installation
  # ce NAS n'est pas connecte : tailscale status echoue, la cle est donc utilisee
  printf '#!/bin/bash\ncase "$1" in\n  version) echo 1.104.5 ;;\n  ip) echo 100.64.0.2 ;;\n  status) [ -f "%s/connecte" ] && exit 0 || exit 1 ;;\n  up) touch "%s/connecte" ;;\nesac\necho "tailscale $*" >> "%s"\nexit 0\n' "$T" "$T" "$STUB_LOG" > "$STUBS/tailscale"
  # lecture de $2 (preinst/postinst) ne doit pas faire echouer : on fournit la cle par l'environnement
  CLE_HEADSCALE="secret-de-test-0123456789" bash "$RACINE_TEST_INSTALLATEUR/installer/installer-nas.sh" "$SPK" "$(_hash "$SPK")" >/dev/null 2>&1 < /dev/null
  ! grep -q "secret-de-test-0123456789" "$STUB_LOG"
}

test_desinstaller_nas_retire_le_service_et_le_programme_mais_garde_les_donnees() {
  _preparer_installation
  bash "$RACINE_TEST_INSTALLATEUR/installer/installer-nas.sh" "$SPK" "$(_hash "$SPK")" >/dev/null 2>&1 < /dev/null || return 1
  [ -f "$T/data/.postgres-password" ] || return 1
  : > "$STUB_LOG"
  sortie="$(bash "$RACINE_TEST_INSTALLATEUR/installer/desinstaller-nas.sh" 2>&1)"; code=$?
  [ "$code" -eq 0 ] \
    && [ ! -e "$SITE_NAS_PREFIXE" ] \
    && [ ! -e "$T/systemd-etc/site-agent.service" ] \
    && grep -q "^docker rm -f" "$STUB_LOG" \
    && [ -f "$T/data/.postgres-password" ] && [ -d "$T/data/postgres" ]
}

test_desinstaller_nas_refuse_s_il_n_y_a_rien_d_installe() {
  _preparer_installation
  bash "$RACINE_TEST_INSTALLATEUR/installer/desinstaller-nas.sh" >/dev/null 2>&1; code=$?
  [ "$code" -eq 1 ]
}

test_installer_nas_installe_la_copie_verifiee_meme_si_le_fichier_d_origine_est_remplace_pendant_la_verification() {
  _preparer_installation
  somme_officielle="$(_hash "$SPK")"
  # version piegee du meme paquet : son postinst creerait un fichier marqueur
  cp -r "$SPKDIR" "$T/spkdir-piege"
  echo "touch \"$T/marqueur-piege\"" >> "$T/spkdir-piege/scripts/postinst"
  tar -cf "$T/piege.spk" -C "$T/spkdir-piege" INFO package.tgz scripts
  # faux sha256sum : des qu'on lui demande une somme, il remplace le .spk d'ORIGINE par la version piegee
  printf '#!/bin/bash\nif [ ! -f "%s/deja" ]; then touch "%s/deja"; cp "%s/piege.spk" "%s"; fi\nexec /usr/bin/sha256sum "$@"\n' "$T" "$T" "$T" "$SPK" > "$STUBS/sha256sum"
  chmod +x "$STUBS/sha256sum"
  bash "$RACINE_TEST_INSTALLATEUR/installer/installer-nas.sh" "$SPK" "$somme_officielle" >/dev/null 2>&1 < /dev/null; code=$?
  [ "$code" -eq 0 ] && [ ! -e "$T/marqueur-piege" ] && ! grep -q "marqueur-piege" "$SITE_NAS_PREFIXE/scripts/postinst"
}

test_installer_nas_garde_l_ecoute_hors_de_sites_enabled_et_la_reactive_au_demarrage() {
  _preparer_installation
  bash "$RACINE_TEST_INSTALLATEUR/installer/installer-nas.sh" "$SPK" "$(_hash "$SPK")" >/dev/null 2>&1 < /dev/null || return 1
  [ -f "$T/data/nginx/site-nas-ecoute.conf" ] \
    && [ -f "$T/nginx/site-nas-ecoute.conf" ] \
    && [ -f "$T/systemd-etc/site-nas-nginx.service" ] \
    && grep -q "ExecStop=/bin/rm -f $T/nginx/site-nas-ecoute.conf" "$T/systemd-etc/site-nas-nginx.service" \
    && grep -q "activer-ecoute.sh" "$SITE_NAS_PREFIXE/demarrage-nas.sh" \
    && grep -Fq 'prestart=$?' "$SITE_NAS_PREFIXE/demarrage-nas.sh" \
    && bash -n "$SITE_NAS_PREFIXE/demarrage-nas.sh" \
    && [ -f "$SITE_NAS_PREFIXE/scripts/activer-ecoute.sh" ]
}

test_installer_nas_donne_a_l_agent_les_memes_options_que_la_premiere_instance() {
  _preparer_installation
  bash "$RACINE_TEST_INSTALLATEUR/installer/installer-nas.sh" "$SPK" "$(_hash "$SPK")" >/dev/null 2>&1 < /dev/null || return 1
  dropin="$T/systemd-etc/site-agent.service.d/10-site-nas.conf"
  grep -Fq 'Environment=APP_AGENT_DOCKER_RESEAUHOTE=true' "$dropin" \
    && grep -F 'Environment="APP_AGENT_DOCKER_RUNOPTIONS=' "$dropin" | grep -Fq -- "-e SPRING_PROFILES_ACTIVE=prod" \
    && grep -F 'Environment="APP_AGENT_DOCKER_RUNOPTIONS=' "$dropin" | grep -Fq -- "/run/secrets-app/app.crypto.key:ro" \
    && grep -F 'Environment="APP_AGENT_DOCKER_RUNOPTIONS=' "$dropin" | grep -Fq -- "DATABASE_URL=jdbc:postgresql://127.0.0.1:15432/site"
}

# Installation en mode VPS (serveur Linux generique), toutes les commandes systeme simulees.
_preparer_vps() {
  _preparer_installation
  export SITE_NAS_CIBLE=vps SITE_NAS_DOMAINE=app.exemple.test SITE_NAS_DOSSIER_SYSCTL="$T/sysctl"
  export LICENCE_LOGIN=partenaire1 LICENCE_MOT_DE_PASSE='mot-de-passe-secret'
  mkdir -p "$T/sysctl" "$T/nginx"   # sur un vrai serveur, /etc/nginx/conf.d existe deja
  printf '#!/bin/bash\necho "certbot $*" >> "%s"\nexit 0\n' "$STUB_LOG" > "$STUBS/certbot"
  printf '#!/bin/bash\necho "sysctl $*" >> "%s"\nexit 0\n' "$STUB_LOG" > "$STUBS/sysctl"
  printf '#!/bin/bash\necho vps-01\n' > "$STUBS/hostname"
  # L'application repond "UP" ; "docker run" simule l'enrolement en ecrivant la cle de licence de l'Agent.
  printf '#!/bin/bash\necho "{\\"status\\":\\"UP\\"}"\nexit 0\n' > "$STUBS/curl"
  printf '#!/bin/bash\necho "docker $*" >> "%s"\nif [ "$1" = run ] && [[ "$*" == *"--name site-18080"* ]]; then mkdir -p "%s/agent-cle" && echo cle > "%s/agent-cle/cle-api-licence"; fi\nexit 0\n' "$STUB_LOG" "$T/data" "$T/data" > "$STUBS/docker"
  chmod +x "$STUBS"/*
}

test_installer_vps_installe_nginx_public_enrole_et_demarre_l_agent() {
  _preparer_vps
  sortie="$(bash "$RACINE_TEST_INSTALLATEUR/installer/installer-nas.sh" "$SPK" "$(_hash "$SPK")" 2>&1 < /dev/null)"; code=$?
  [ "$code" -eq 0 ] || { echo "$sortie" | tail -12; return 1; }
  [[ "$sortie" == *"Installation VPS terminee"* ]] \
    && [ -f "$T/nginx/site-nas-public.conf" ] && grep -q "server_name app.exemple.test;" "$T/nginx/site-nas-public.conf" \
    && grep -q "listen 100.64.0.2:8072;" "$T/nginx/site-nas-ecoute.conf" \
    && grep -q "ip_nonlocal_bind = 1" "$T/sysctl/99-site-nas.conf" \
    && grep -q "^certbot certonly --nginx -d app.exemple.test" "$STUB_LOG" \
    && grep -q '^APP_ROLE=PARTNER$' "$T/data/app.env" && grep -q '^APP_LICENCE_MASTERURL=http://' "$T/data/app.env" \
    && grep -q '^APP_LICENCE_ADRESSEPUBLIQUE=http://100.64.0.2:8072$' "$T/data/app.env" \
    && [ "$(cat "$T/data/.app-licence-login")" = "partenaire1" ] \
    && grep -q "^docker run -d --name site-18080 .*app.licence.mot-de-passe:ro" "$STUB_LOG" \
    && grep -q "^docker restart site-18080" "$STUB_LOG" \
    && grep -q 'Environment="APP_AGENT_DOCKER_RUNOPTIONS=.*app.licence.login' "$T/systemd-etc/site-agent.service.d/10-site-nas.conf" \
    && grep -q 'FENETRESANTESECONDES=600' "$T/systemd-etc/site-agent.service.d/10-site-nas.conf" \
    && [ ! -e "$SITE_NAS_PREFIXE/demarrage-nas.sh" ]
}

test_installer_vps_ne_laisse_jamais_le_mot_de_passe_dans_la_sortie_ni_dans_les_arguments() {
  _preparer_vps
  sortie="$(bash "$RACINE_TEST_INSTALLATEUR/installer/installer-nas.sh" "$SPK" "$(_hash "$SPK")" 2>&1 < /dev/null)"
  [[ "$sortie" != *"mot-de-passe-secret"* ]] && ! grep -q "mot-de-passe-secret" "$STUB_LOG" \
    && ! grep -rq "mot-de-passe-secret" "$T/systemd-etc" "$T/nginx" "$T/data/app.env"
}

test_installer_vps_refuse_un_domaine_invalide_avant_toute_modification() {
  _preparer_vps
  export SITE_NAS_DOMAINE='x.com; rm -rf /'
  sortie="$(bash "$RACINE_TEST_INSTALLATEUR/installer/installer-nas.sh" "$SPK" "$(_hash "$SPK")" 2>&1 < /dev/null)"; code=$?
  [ "$code" -ne 0 ] && [ ! -e "$SITE_NAS_PREFIXE" ] && [ ! -s "$STUB_LOG" ]
}

test_installer_vps_retire_ses_fichiers_nginx_si_le_certificat_echoue_et_ne_touche_pas_aux_autres_sites() {
  _preparer_vps
  printf '#!/bin/bash\necho "certbot $*" >> "%s"\nexit 1\n' "$STUB_LOG" > "$STUBS/certbot"; chmod +x "$STUBS/certbot"
  mkdir -p "$T/nginx"; echo "server { # autre site }" > "$T/nginx/autre-site.conf"
  sortie="$(bash "$RACINE_TEST_INSTALLATEUR/installer/installer-nas.sh" "$SPK" "$(_hash "$SPK")" 2>&1 < /dev/null)"; code=$?
  [ "$code" -ne 0 ] && [ ! -e "$T/nginx/site-nas-public.conf" ] && [ -f "$T/nginx/autre-site.conf" ] \
    && [[ "$sortie" == *"Certificat"* ]]
}

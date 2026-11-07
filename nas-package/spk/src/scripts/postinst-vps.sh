#!/bin/bash
# Installation du paquet sur un SERVEUR LINUX GENERIQUE (VPS Ubuntu) qui sert deja d'autres sites :
# partenaire (PARTNER) enrole aupres du Maitre, application publique derriere le Nginx existant.
# Lance par installer-nas.sh en mode VPS (SITE_NAS_CIBLE=vps), en root. Variables attendues :
#   SITE_NAS_DOMAINE        nom de domaine public de l'application (ex. app.logicielpourtous.com)
#   LICENCE_LOGIN, LICENCE_MOT_DE_PASSE   compte de licence PARTNER cree sur le Maitre
#                           (a defaut, saisis au clavier ; jamais en argument)
#
# Principe de securite : le Nginx du serveur porte d'autres sites. Chaque fichier ecrit est valide par
# "nginx -t" et retire s'il est refuse ; l'ecoute sur l'adresse Headscale ne peut pas empecher Nginx
# de demarrer (voir ecrire_sysctl_ecoute_tardive).

: "${SITE_NAS_RACINE_DONNEES:=/var/lib/site-data}"
: "${SITE_NAS_DOSSIER_NGINX:=/etc/nginx/conf.d}"
: "${SITE_NAS_DOSSIER_SYSCTL:=/etc/sysctl.d}"
export SITE_NAS_RACINE_DONNEES SITE_NAS_DOSSIER_NGINX

# shellcheck source=commun.sh
. "$(dirname "${BASH_SOURCE[0]}")/commun.sh"
charger_bibliotheques || exit 1

DOMAINE="${SITE_NAS_DOMAINE:-}"
FICHIER_NGINX_PUBLIC="$SITE_NAS_DOSSIER_NGINX/site-nas-public.conf"

echec() { avertir_utilisateur "$1"; exit 1; }

# --- 0. Pre-requis -------------------------------------------------------------------------
domaine_valide "$DOMAINE" || echec "Nom de domaine absent ou invalide : '$DOMAINE'. Rien n'a ete modifie."
for cmd in docker nginx certbot tailscale; do
  command -v "$cmd" >/dev/null 2>&1 || echec "Commande requise introuvable : $cmd. Rien n'a ete modifie."
done
_exec_docker info >/dev/null 2>&1 || echec "Docker ne repond pas. Rien n'a ete modifie."
# Si la configuration Nginx EXISTANTE est deja invalide, on ne la touche pas : on ne saurait pas
# distinguer nos erreurs de celles deja presentes.
_exec_nginx -t >/dev/null 2>&1 || echec "La configuration Nginx actuelle est deja invalide ('nginx -t'). Corrigez-la avant d'installer. Rien n'a ete modifie."
version_tailscale_suffisante "$(_exec_tailscale version 2>/dev/null | head -n 1)" \
  || echec "Tailscale 1.80 ou plus recent est requis. Rien n'a ete modifie."
headscale_est_connecte || echec "Ce serveur n'est pas connecte a Headscale (tailscale up --login-server ...). Rien n'a ete modifie."
ADRESSE_HEADSCALE="$(headscale_adresse_ip | head -n 1)"
[[ "$ADRESSE_HEADSCALE" =~ ^100\.(6[4-9]|[7-9][0-9]|1[01][0-9]|12[0-7])\.[0-9]{1,3}\.[0-9]{1,3}$ ]] \
  || echec "Adresse Headscale introuvable ('$ADRESSE_HEADSCALE'). Rien n'a ete modifie."
dossier_hors_cycle_de_vie "$SITE_NAS_RACINE_DONNEES" || echec "Dossier de donnees refuse ($SITE_NAS_RACINE_DONNEES)."

# Compte de licence PARTNER (cree par l'administrateur sur le Maitre).
LOGIN="${LICENCE_LOGIN:-}"; MDP="${LICENCE_MOT_DE_PASSE:-}"
if [ -z "$LOGIN" ] && [ -t 0 ]; then printf 'Identifiant du compte de licence partenaire : ' >&2; read -r LOGIN; fi
if [ -z "$MDP" ] && [ -t 0 ]; then printf 'Mot de passe du compte (saisie masquee) : ' >&2; read -rs MDP; printf '\n' >&2; fi
[ -n "$LOGIN" ] && [ -n "$MDP" ] || echec "Identifiant et mot de passe du compte de licence partenaire requis (variables LICENCE_LOGIN / LICENCE_MOT_DE_PASSE ou saisie). Rien n'a ete modifie."
unset LICENCE_LOGIN LICENCE_MOT_DE_PASSE

# --- 1. Le Maitre est-il joignable ? ------------------------------------------------------
verifier_maitre || exit 1

# --- 2. Ports ---------------------------------------------------------------------------
arreter_composants_du_paquet "site-$PORT_APP_BLEU" "site-$PORT_APP_VERT" site-postgres
ports_occupes=""
for port in "$PORT_APP_BLEU" "$PORT_APP_VERT" "$PORT_POSTGRES" "$PORT_AGENT"; do
  port_est_libre "$port" || ports_occupes="$ports_occupes $port"
done
if [ -n "$ports_occupes" ]; then
  relancer_composants_arretes
  echec "Port(s) deja utilise(s) sur ce serveur :$ports_occupes. Rien n'a ete modifie."
fi

# --- 3. Donnees et Postgres ---------------------------------------------------------------
mkdir -p "$DOSSIER_DONNEES_POSTGRES" "$DOSSIER_DONNEES_AGENT" || echec "Impossible de creer $SITE_NAS_RACINE_DONNEES."
chmod 700 "$SITE_NAS_RACINE_DONNEES" || echec "Impossible de restreindre $SITE_NAS_RACINE_DONNEES."
demarrer_postgres "$DOSSIER_DONNEES_POSTGRES" "$PORT_POSTGRES" >/dev/null || echec "Le demarrage de Postgres a echoue (port $PORT_POSTGRES)."

# --- 4. Nginx : ecoute tardive, upstream, ecoute Headscale, bloc public -------------------
ecrire_sysctl_ecoute_tardive "$SITE_NAS_DOSSIER_SYSCTL/99-site-nas.conf" \
  || echec "Impossible d'activer l'ecoute tardive (sysctl). Nginx n'a pas ete modifie."
upstream_cree=0; [ -e "$FICHIER_NGINX_UPSTREAM" ] || upstream_cree=1
initialiser_nginx_local "$FICHIER_NGINX_UPSTREAM" "$PORT_APP_BLEU"
ecoute_creee=0; [ -e "$FICHIER_NGINX_ECOUTE" ] || ecoute_creee=1
configurer_ecoute_nginx "$FICHIER_NGINX_ECOUTE" "$ADRESSE_HEADSCALE" "$PORT_NGINX_ECOUTE"
retirer_nos_fichiers_nginx() {
  [ "$upstream_cree" -eq 1 ] && rm -f "$FICHIER_NGINX_UPSTREAM"
  [ "$ecoute_creee" -eq 1 ] && rm -f "$FICHIER_NGINX_ECOUTE"
  rm -f "$FICHIER_NGINX_PUBLIC"
}
if ! _exec_nginx -t >/dev/null 2>&1; then
  retirer_nos_fichiers_nginx
  echec "Configuration Nginx refusee ('nginx -t') : nos fichiers sont retires, vos autres sites ne sont pas touches."
fi
ecrire_vhost_http "$FICHIER_NGINX_PUBLIC" "$DOMAINE" || { retirer_nos_fichiers_nginx; echec "Bloc HTTP refuse par Nginx : retire."; }
_exec_nginx -s reload || { retirer_nos_fichiers_nginx; _exec_nginx -s reload; echec "Rechargement de Nginx impossible : nos fichiers sont retires."; }
if ! obtenir_certificat "$DOMAINE"; then
  rm -f "$FICHIER_NGINX_PUBLIC"; _exec_nginx -s reload
  echec "Certificat Let's Encrypt impossible pour $DOMAINE (le DNS pointe-t-il sur ce serveur ? le port 80 est-il joignable ?). Bloc public retire ; Postgres et les donnees sont conserves."
fi
ecrire_vhost_https "$FICHIER_NGINX_PUBLIC" "$DOMAINE" || { rm -f "$FICHIER_NGINX_PUBLIC"; _exec_nginx -s reload; echec "Bloc HTTPS refuse par Nginx : retire."; }
_exec_nginx -s reload || echec "Rechargement de Nginx impossible apres le bloc HTTPS."

# --- 5. Enrolement aupres du Maitre : reglages et secrets de l'application --------------------
URL_MAITRE_AGENT="$(url_maitre_pour_agent "$ADRESSE_HEADSCALE")"
[ -n "$URL_MAITRE_AGENT" ] || echec "URL du Maitre introuvable."
FICHIER_REGLAGES="$SITE_NAS_RACINE_DONNEES/app.env"
( umask 077; touch "$FICHIER_REGLAGES" ) || echec "Impossible de creer $FICHIER_REGLAGES."
definir_reglage "$FICHIER_REGLAGES" APP_ROLE PARTNER \
  && definir_reglage "$FICHIER_REGLAGES" APP_LICENCE_MASTERURL "$URL_MAITRE_AGENT" \
  && definir_reglage "$FICHIER_REGLAGES" APP_LICENCE_ADRESSEPUBLIQUE "http://$ADRESSE_HEADSCALE:$PORT_NGINX_ECOUTE" \
  || echec "Impossible d'ecrire les reglages de l'application."
printf '%s' "$LOGIN" | _ecrire_secret_atomique "$SITE_NAS_RACINE_DONNEES/.app-licence-login" "$UID_APPLICATION" \
  && printf '%s' "$MDP" | _ecrire_secret_atomique "$SITE_NAS_RACINE_DONNEES/.app-licence-mdp" "$UID_APPLICATION" \
  || echec "Impossible d'ecrire les identifiants de licence."
unset LOGIN MDP
# Dossier DEDIE ou l'application ecrit sa cle API de licence (lue ensuite par l'Agent, qui ne suit pas
# les liens symboliques). Le dossier de travail de l'Agent reste a root et hors de portee du conteneur.
mkdir -p "$DOSSIER_CLE_API_AGENT" \
  && _exec_chown "$UID_APPLICATION:$UID_APPLICATION" "$DOSSIER_CLE_API_AGENT" && chmod 700 "$DOSSIER_CLE_API_AGENT" \
  || echec "Impossible de preparer $DOSSIER_CLE_API_AGENT."
# Dossier de travail de l'Agent (root) : on le remet a root et on retire tout ce qu'une version ou un
# conteneur precedent aurait pu y laisser (ancienne cle, configuration Spring lue depuis le dossier
# courant) : seul ce que le paquet cree lui-meme doit y rester.
_exec_chown -R root:root "$DOSSIER_DONNEES_AGENT" \
  && rm -rf "$DOSSIER_DONNEES_AGENT/config" "$DOSSIER_DONNEES_AGENT/cle-api-licence" \
  && rm -f "$DOSSIER_DONNEES_AGENT"/application*.properties "$DOSSIER_DONNEES_AGENT"/application*.yml "$DOSSIER_DONNEES_AGENT"/application*.yaml \
  && chmod 700 "$DOSSIER_DONNEES_AGENT" || echec "Impossible de securiser $DOSSIER_DONNEES_AGENT."

# --- 6. Agent -------------------------------------------------------------------------------
preparer_options_application "$PORT_POSTGRES" "$DOSSIER_DONNEES_POSTGRES" || echec "Impossible de preparer les secrets et options de l'application."
ecrire_complement_agent "$URL_MAITRE_AGENT" "$OPTIONS_APPLICATION" || echec "Impossible d'ecrire la configuration de l'Agent."
installer_service_agent "$CHEMIN_JRE" "$CHEMIN_JAR_AGENT" "$ADRESSE_HEADSCALE" "$PORT_AGENT" "$FICHIER_UNITE_AGENT" \
  || echec "L'installation du service de l'Agent a echoue (port $PORT_AGENT)."

# --- 7. Premiere instance, attente de sa sante, prise en compte de la cle de licence -------------
PORT_INITIAL="$(port_actif_nginx)"
_exec_docker rm -f "site-$PORT_INITIAL" >/dev/null 2>&1 || true
demarrer_premiere_instance "$IMAGE_APPLICATION" "$PORT_INITIAL" "$PORT_POSTGRES" "$DOSSIER_DONNEES_POSTGRES" >/dev/null \
  || echec "Le demarrage de l'application ($IMAGE_APPLICATION, port $PORT_INITIAL) a echoue."

attendre_sante() {
  local essai=0
  until application_saine "$1"; do
    essai=$((essai + 1))
    [ "$essai" -ge "$MAJ_SANTE_ESSAIS" ] && return 1
    _exec_sleep "$MAJ_SANTE_DELAI"
  done
}
attendre_sante "$PORT_INITIAL" || echec "L'application n'est pas devenue saine a temps. Voir : docker logs site-$PORT_INITIAL"

# L'application s'enregistre aupres du Maitre au demarrage et ecrit sa cle ; elle n'est appliquee qu'au
# demarrage SUIVANT du processus : on redemarre une fois.
essai=0
until [ -s "$FICHIER_CLE_API_AGENT" ]; do
  essai=$((essai + 1))
  if [ "$essai" -ge 24 ]; then
    avertir_utilisateur "Application demarree mais PAS enrolee (aucune cle de licence recue du Maitre apres 2 minutes). Verifiez le compte de licence (identifiant, mot de passe, actif, quota de serveurs) puis : docker logs site-$PORT_INITIAL | grep -i licence"
    exit 1
  fi
  _exec_sleep 5
done
_exec_docker restart "site-$PORT_INITIAL" >/dev/null
attendre_sante "$PORT_INITIAL" || echec "L'application n'est pas redevenue saine apres la prise en compte de la cle de licence."
_exec_systemctl restart site-agent

avertir_utilisateur "Installation VPS terminee : https://$DOMAINE (application sur 127.0.0.1:$PORT_INITIAL), partenaire enrole aupres du Maitre, Agent actif sur $ADRESSE_HEADSCALE:$PORT_AGENT. Pensez a renseigner les cles Turnstile de ce domaine dans $FICHIER_REGLAGES (TURNSTILE_SITE_KEY, TURNSTILE_SECRET_KEY) puis a relancer l'application."
exit 0

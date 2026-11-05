#!/bin/bash
# postinst du paquet DSM site-nas : amorce ce NAS (Headscale, role, ports,
# Postgres, Nginx local, Agent, premiere instance applicative), dans l'ordre
# exact de la Task 10 (Step 1). Chaque etape s'appuie sur une bibliotheque deja
# testee de nas-package/lib/ (Tasks 2-9), qui recoit ses valeurs fixes en parametre.
#
# Code de sortie : 0 si tout est en place ; 1 sinon, apres un message lisible dans
# le texte de fin d'installation DSM et le journal systeme (avertir_utilisateur).
# Les verifications 1 a 4 ne demarrent rien : un echec a ce stade n'a rien installe.

# shellcheck source=commun.sh
. "$(dirname "$0")/commun.sh"

# Pendant une mise a jour, DSM execute aussi le postinst du NOUVEAU paquet (ordre :
# preupgrade, preuninst/postuninst de l'ancien, preinst/postinst du nouveau,
# postupgrade). Tout ce qui concerne la mise a jour est dans postupgrade.sh, qui
# preserve les donnees et la configuration deja en place : rien a faire ici.
if [ "${SYNOPKG_PKG_STATUS:-}" = "UPGRADE" ]; then
  exit 0
fi

charger_bibliotheques || exit 1

# Valeur saisie dans l'assistant d'installation (WIZARD_UIFILES/install_uifile,
# champ "wizard_headscale_cle") : DSM l'expose comme variable d'environnement
# des scripts preinst/postinst.
CLE_SAISIE="${wizard_headscale_cle:-}"

if ! dossier_hors_cycle_de_vie "$SITE_NAS_RACINE_DONNEES"; then
  avertir_utilisateur "Dossier de donnees refuse ($SITE_NAS_RACINE_DONNEES) : il serait supprime avec le paquet. Installation interrompue, rien n'a ete demarre."
  exit 1
fi

# --- 1 + 2. Headscale : connexion avec la cle saisie si besoin, puis reessai ----
if ! verifier_headscale "$CLE_SAISIE"; then
  exit 1
fi
unset CLE_SAISIE wizard_headscale_cle

# --- 3. Maitre : rien a verifier si ce NAS EST le Maitre ; sinon reessai --------
if ! verifier_maitre; then
  exit 1
fi

# --- 4. Ports non standard : tout port deja occupe fait abandonner -------------
# (8072, le port d'ecoute Nginx, est traite a l'etape 6.)
ports_occupes=""
for port in "$PORT_APP_BLEU" "$PORT_APP_VERT" "$PORT_POSTGRES" "$PORT_AGENT"; do
  if ! port_est_libre "$port"; then
    ports_occupes="$ports_occupes $port"
  fi
done
if [ -n "$ports_occupes" ]; then
  avertir_utilisateur "Installation interrompue : port(s) deja utilise(s) sur ce NAS :$ports_occupes. Ces ports sont fixes par le projet (partages avec l'Agent) ; liberez-les puis relancez l'installation. Rien n'a ete demarre."
  exit 1
fi

# Adresse Headscale de ce NAS, obtenue UNE seule fois (reutilisee aux etapes 6 et 7).
# Validee ici : vide, elle ferait ecrire "listen :8072" a Nginx, c'est-a-dire une
# ecoute sur TOUTES les interfaces, LAN compris (interdit par les Global Constraints).
ADRESSE_HEADSCALE="$(headscale_adresse_ip | head -n 1)"
if ! [[ "$ADRESSE_HEADSCALE" =~ ^100\.(6[4-9]|[7-9][0-9]|1[01][0-9]|12[0-7])\.[0-9]{1,3}\.[0-9]{1,3}$ ]]; then
  avertir_utilisateur "Adresse Headscale de ce NAS introuvable ou invalide ('$ADRESSE_HEADSCALE'). Installation interrompue, rien n'a ete demarre."
  exit 1
fi

# --- 5. Postgres, donnees dans le dossier partage dedie -------------------------
if ! mkdir -p "$DOSSIER_DONNEES_POSTGRES"; then
  avertir_utilisateur "Impossible de creer le dossier de donnees $DOSSIER_DONNEES_POSTGRES."
  exit 1
fi
if ! demarrer_postgres "$DOSSIER_DONNEES_POSTGRES" "$PORT_POSTGRES" >/dev/null; then
  avertir_utilisateur "Le demarrage du conteneur Postgres a echoue (port $PORT_POSTGRES)."
  exit 1
fi

# --- 6. Nginx local : upstream de bascule + ecoute Headscale, un seul reload ----
if ! mkdir -p "$SITE_NAS_DOSSIER_NGINX"; then
  avertir_utilisateur "Impossible de creer $SITE_NAS_DOSSIER_NGINX."
  exit 1
fi
upstream_cree=0
ecoute_cree=0
[ -e "$FICHIER_NGINX_UPSTREAM" ] || upstream_cree=1
[ -e "$FICHIER_NGINX_ECOUTE" ] || ecoute_cree=1
initialiser_nginx_local "$FICHIER_NGINX_UPSTREAM" "$PORT_APP_BLEU"
configurer_ecoute_nginx "$FICHIER_NGINX_ECOUTE" "$ADRESSE_HEADSCALE" "$PORT_NGINX_ECOUTE"
# Le Nginx recharge est celui de DSM (il sert aussi l'interface DSM) : une
# configuration invalide laissee dans sites-enabled casserait son prochain
# rechargement. On valide d'abord, et on retire ce qu'on vient d'ecrire si refus.
if ! _exec_nginx -t >/dev/null 2>&1; then
  [ "$upstream_cree" -eq 1 ] && rm -f "$FICHIER_NGINX_UPSTREAM"
  [ "$ecoute_cree" -eq 1 ] && rm -f "$FICHIER_NGINX_ECOUTE"
  avertir_utilisateur "Configuration Nginx refusee par 'nginx -t' : fichiers retires, installation interrompue (Postgres reste demarre, ses donnees sont intactes)."
  exit 1
fi
if ! _exec_nginx -s reload; then
  avertir_utilisateur "Le rechargement de Nginx a echoue (port $PORT_NGINX_ECOUTE)."
  exit 1
fi

# --- 7. Agent (service systemd, JRE embarque), lie a l'adresse Headscale --------
if ! mkdir -p "$DOSSIER_DONNEES_AGENT" || ! chmod 700 "$DOSSIER_DONNEES_AGENT"; then
  avertir_utilisateur "Impossible de preparer $DOSSIER_DONNEES_AGENT."
  exit 1
fi
URL_MAITRE_AGENT="$(url_maitre_pour_agent "$ADRESSE_HEADSCALE")"
if [ -z "$URL_MAITRE_AGENT" ] || ! ecrire_complement_agent "$URL_MAITRE_AGENT"; then
  avertir_utilisateur "Impossible d'ecrire la configuration complementaire de l'Agent."
  exit 1
fi
# 5 arguments : le 4e (port d'ecoute) est obligatoire, sans lui l'Agent ecouterait
# sur son port par defaut 8090 et le Maitre ne pourrait jamais le reveiller.
if ! installer_service_agent "$CHEMIN_JRE" "$CHEMIN_JAR_AGENT" "$ADRESSE_HEADSCALE" "$PORT_AGENT" "$FICHIER_UNITE_AGENT"; then
  avertir_utilisateur "L'installation du service de l'Agent a echoue (port $PORT_AGENT)."
  exit 1
fi

# --- 8. Premiere instance applicative -------------------------------------------
# Port actif relu dans l'upstream (= 18080 sur une installation neuve, puisque
# l'etape 6 vient de l'ecrire) pour que l'instance demarree soit TOUJOURS celle
# vers laquelle Nginx relaie, meme si un upstream d'une installation precedente
# avait survecu. Un conteneur residuel du meme nom est d'abord retire (il n'a
# aucune donnee : tout est dans Postgres).
PORT_INITIAL="$(port_actif_nginx)"
_exec_docker rm -f "site-$PORT_INITIAL" >/dev/null 2>&1 || true
if ! demarrer_premiere_instance "$IMAGE_APPLICATION" "$PORT_INITIAL" "$PORT_POSTGRES" "$DOSSIER_DONNEES_POSTGRES" >/dev/null; then
  avertir_utilisateur "Le demarrage de l'application ($IMAGE_APPLICATION, port $PORT_INITIAL) a echoue."
  exit 1
fi

avertir_utilisateur "Installation terminee. Ports non standard utilises par ce paquet : $PORT_APP_BLEU/$PORT_APP_VERT (application, local uniquement), $PORT_POSTGRES (Postgres, local uniquement), $PORT_AGENT (Agent) et $PORT_NGINX_ECOUTE (Nginx), ces deux derniers sur l'adresse Headscale $ADRESSE_HEADSCALE uniquement. Donnees : $SITE_NAS_RACINE_DONNEES (conservees en cas de mise a jour ou de desinstallation). L'application reste inaccessible tant que vous ne vous etes pas connecte avec un compte licence dont l'abonnement est valide."
exit 0

#!/bin/bash
# postupgrade du paquet DSM site-nas (Task 10, Step 2).
#
# Ordre DSM d'une mise a jour : stop (si demarre), preupgrade, preuninst/postuninst
# de l'ANCIEN paquet (qui ne font rien en UPGRADE), preinst/postinst du NOUVEAU
# (postinst ne fait rien en UPGRADE), postupgrade, puis prestart/start.
#
# Regle centrale : les donnees Postgres et la configuration deja en place ne sont
# JAMAIS recreees ni modifiees.
#  - Postgres : l'etape 5 de postinst n'est rejouee QUE si le dossier de donnees
#    n'existe pas encore (meme test d'idempotence que les Tasks 6-7) ; sinon on
#    ne touche ni au dossier ni au conteneur, que "start" relancera.
#  - Nginx : rien. L'upstream appartient a l'Agent (BasculeNginx) depuis
#    l'installation ; initialiser_nginx_local ne l'ecraserait pas, mais il n'y a
#    aucune raison de le rappeler.
#  - Premiere instance : rien. Les deploiements suivants sont l'affaire de l'Agent.
#  - Agent : l'unite systemd et son complement sont reecrits (nouveau gabarit
#    eventuel, meme jar a meme chemin dans le nouveau package.tgz).

# shellcheck source=commun.sh
. "$(dirname "$0")/commun.sh"

charger_bibliotheques || exit 1

if ! dossier_hors_cycle_de_vie "$SITE_NAS_RACINE_DONNEES"; then
  avertir_utilisateur "Dossier de donnees refuse ($SITE_NAS_RACINE_DONNEES) : il serait supprime avec le paquet. Mise a jour terminee sans modifier les services."
  exit 1
fi

if [ ! -d "$DOSSIER_DONNEES_POSTGRES" ]; then
  mkdir -p "$DOSSIER_DONNEES_POSTGRES" || exit 1
  if ! demarrer_postgres "$DOSSIER_DONNEES_POSTGRES" "$PORT_POSTGRES" >/dev/null; then
    avertir_utilisateur "Le demarrage du conteneur Postgres a echoue (port $PORT_POSTGRES)."
    exit 1
  fi
fi

# Agent : necessite l'adresse Headscale (liaison de son port). Si Headscale est
# momentanement indisponible, on garde l'unite deja installee (elle pointe deja
# vers le meme jar, remplace par la mise a jour) plutot que d'echouer : la
# mise a jour ne doit pas casser une installation qui fonctionnait.
if verifier_headscale ""; then
  ADRESSE_HEADSCALE="$(headscale_adresse_ip | head -n 1)"
  URL_MAITRE_AGENT="$(url_maitre_pour_agent "$ADRESSE_HEADSCALE")"
  if [ -n "$URL_MAITRE_AGENT" ] && ecrire_complement_agent "$URL_MAITRE_AGENT" \
    && installer_service_agent "$CHEMIN_JRE" "$CHEMIN_JAR_AGENT" "$ADRESSE_HEADSCALE" "$PORT_AGENT" "$FICHIER_UNITE_AGENT"; then
    exit 0
  fi
fi
avertir_utilisateur "Mise a jour installee ; la configuration de l'Agent n'a pas pu etre rafraichie (Headscale ou Maitre indisponible), l'ancienne est conservee."
exit 0

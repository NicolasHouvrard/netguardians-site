#!/bin/bash
# Installateur "voie A" du paquet site-nas : DSM 7 refuse d'installer par le Centre de
# paquets un .spk tiers qui s'execute en root ("Impossible d'installer Site NAS, car il
# s'execute avec des privileges root"). Ce script fait la meme installation, mais lance
# a la main, en root (sudo), par SSH : il verifie et extrait le .spk dans un dossier a part
# puis execute les memes scripts preinst/postinst que ceux que DSM aurait lances.
#
# Usage :  sudo bash installer-nas.sh /chemin/vers/site-nas-armv8-1.0.0-1.spk <sha256-du-fichier-spk>
#
# La somme SHA-256 du .spk ENTIER est OBLIGATOIRE et doit venir d'un autre canal que le fichier
# lui-meme : celle affichee dans le journal (et le resume) de l'execution GitHub qui l'a construit.
# Elle couvre tout ce qui sera execute en root (scripts/ compris). Une somme placee DANS l'archive
# ne prouverait rien : qui modifie l'archive modifie aussi la somme.
#
# Cle Headscale : inutile si ce NAS est deja connecte (tailscale status). Sinon, fournie par la
# variable d'environnement CLE_HEADSCALE ou saisie au clavier (jamais en argument, pour ne pas
# apparaitre dans la liste des processus).
#
# Variables de test (hors NAS) : SITE_NAS_PREFIXE (dossier d'installation) et SITE_NAS_SANS_ROOT=1.
set -u

PREFIXE="${SITE_NAS_PREFIXE:-/usr/local/site-nas}"
# Cible : "nas" (Synology DSM, par defaut) ou "vps" (serveur Linux generique : Nginx, certbot et systemd
# du systeme, domaine public). En mode vps, SITE_NAS_DOMAINE = nom de domaine public de l'application.
CIBLE="${SITE_NAS_CIBLE:-nas}"
SPK="${1:-}"
SHA256_ATTENDU="$(printf '%s' "${2:-${SITE_NAS_SHA256:-}}" | tr 'A-F' 'a-f')"

echec() { printf 'installer-nas : %s\n' "$1" >&2; exit 1; }

[ -n "$SPK" ] && [ -f "$SPK" ] || echec "usage : sudo bash installer-nas.sh /chemin/vers/site-nas-<arch>-<version>.spk <sha256-du-fichier-spk>"
[[ "$SHA256_ATTENDU" =~ ^[0-9a-f]{64}$ ]] || echec "somme SHA-256 du .spk manquante ou mal formee (64 caracteres hexadecimaux, a copier depuis le journal de l'execution GitHub qui a construit le paquet)"

if [ "${SITE_NAS_SANS_ROOT:-}" != "1" ] && [ "$(id -u)" -ne 0 ]; then
  echec "ce script doit etre lance en root : sudo bash installer-nas.sh $SPK"
fi

case "$CIBLE" in
  nas) ;;
  vps) [[ "${SITE_NAS_DOMAINE:-}" =~ ^([A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?\.)+[A-Za-z]{2,}$ ]] \
         || echec "mode vps : SITE_NAS_DOMAINE (ex. app.exemple.com) manquant ou invalide" ;;
  *) echec "SITE_NAS_CIBLE doit valoir nas ou vps (recu : $CIBLE)" ;;
esac

# Le dossier d'installation ne doit pas etre sous l'arborescence que DSM associe a un paquet
# (nettoyee a la desinstallation) ni relatif.
case "$PREFIXE" in
  /var/packages/*|*/@appstore*|*/@appdata*|*/@apphome*|*/@apptemp*) echec "dossier d'installation refuse : $PREFIXE" ;;
  /*) ;;
  *) echec "le dossier d'installation doit etre un chemin absolu : $PREFIXE" ;;
esac

TMP="$(mktemp -d)" || echec "impossible de creer un dossier temporaire"
JOURNAL="$(mktemp)" || echec "impossible de creer le journal"
trap 'rm -rf "$TMP" "$JOURNAL"' EXIT

# --- 1. Authenticite : SHA-256 du .spk ENTIER, AVANT toute extraction ou execution ---------------
# On travaille sur UNE COPIE PRIVEE (le dossier temporaire est reserve a root, mode 0700) :
# verifier le fichier d'origine puis le relire pour l'extraire laisserait une fenetre pendant
# laquelle quelqu'un pouvant ecrire dans son dossier le remplacerait apres la verification.
mkdir -p "$TMP/copie" || echec "impossible de creer le dossier de travail"
cp -- "$SPK" "$TMP/copie/paquet.spk" || echec "impossible de copier le .spk"
reelle="$(sha256sum "$TMP/copie/paquet.spk" | cut -d' ' -f1)"
[ "$reelle" = "$SHA256_ATTENDU" ] || echec "le .spk ne correspond pas a la somme SHA-256 fournie (attendue $SHA256_ATTENDU, obtenue $reelle) : fichier altere ou mauvaise version, rien n'a ete extrait ni execute"

# --- 2. Extraction de la copie verifiee dans un dossier temporaire ----------------------------
tar -xf "$TMP/copie/paquet.spk" -C "$TMP" || echec "archive .spk illisible : $SPK"
for f in INFO package.tgz scripts/postinst scripts/preinst scripts/commun.sh scripts/constantes.sh; do
  [ -e "$TMP/$f" ] || echec "archive incomplete, il manque : $f"
done
[ "$CIBLE" != vps ] || [ -e "$TMP/scripts/postinst-vps.sh" ] || echec "archive incomplete, il manque : scripts/postinst-vps.sh"

# --- 3. Mise en place dans le dossier d'installation ----------------------------------------
mkdir -p "$PREFIXE/scripts" || echec "impossible de creer $PREFIXE"
cp -a "$TMP/scripts/." "$PREFIXE/scripts/" || echec "copie des scripts impossible"
tar -xzf "$TMP/package.tgz" -C "$PREFIXE" || echec "extraction de package.tgz impossible"

# Script de demarrage, a executer a chaque demarrage du NAS (tache planifiee root, voir ci-dessous).
# Inutile sur un serveur Linux : systemd relance l'Agent et Docker les conteneurs.
if [ "$CIBLE" = nas ]; then
cat > "$PREFIXE/demarrage-nas.sh" <<DEMARRAGE
#!/bin/bash
# Rejoue au demarrage ce que le Centre de paquets aurait fait avant de lancer le paquet :
# mode TUN de Tailscale, connexion Headscale, Maitre joignable, puis redemarrage de l'Agent
# (qui ecoute sur l'adresse Headscale et echoue si elle n'existe pas encore au demarrage).
export SYNOPKG_PKGDEST="$PREFIXE" SYNOPKG_PKGNAME=site-nas
# L'ecoute Nginx n'est activee qu'ici, une fois tailscale0 creee : en permanence dans sites-enabled
# elle ferait echouer "nginx -t" au demarrage (File Station, Synology Drive en erreur).
# L'activation de l'ecoute ne depend pas du verdict de prestart.sh (Maitre joignable ou non).
bash "$PREFIXE/scripts/prestart.sh"; prestart=\$?
bash "$PREFIXE/scripts/activer-ecoute.sh"
[ "\$prestart" -eq 0 ] && systemctl restart site-agent
DEMARRAGE
chmod 755 "$PREFIXE/demarrage-nas.sh"
fi

# --- 4. Cle Headscale : seulement si le NAS n'est pas deja connecte -------------------------------
CLE="${CLE_HEADSCALE:-}"
if [ "$CIBLE" = nas ] && [ -z "$CLE" ] && ! tailscale status >/dev/null 2>&1; then
  if [ -t 0 ]; then
    printf 'Cle de connexion Headscale (saisie masquee) : ' >&2
    read -rs CLE
    printf '\n' >&2
  fi
fi
unset CLE_HEADSCALE

# --- 5. Meme execution que DSM : preinst puis postinst ----------------------------------------------
export SYNOPKG_PKGDEST="$PREFIXE" SYNOPKG_PKGNAME=site-nas SYNOPKG_PKG_STATUS=INSTALL SYNOPKG_TEMP_LOGFILE="$JOURNAL"
code=0
if [ "$CIBLE" = vps ]; then
  # Serveur Linux generique : le VPS est deja connecte a Headscale (tailscale up fait a la main).
  SITE_NAS_DOMAINE="$SITE_NAS_DOMAINE" bash "$PREFIXE/scripts/postinst-vps.sh" || code=$?
else
  wizard_headscale_cle="$CLE" bash "$PREFIXE/scripts/preinst" || code=$?
  if [ "$code" -eq 0 ]; then
    wizard_headscale_cle="$CLE" bash "$PREFIXE/scripts/postinst" || code=$?
  fi
fi
unset CLE

echo
if [ -s "$JOURNAL" ]; then
  echo "---- Messages du paquet ----"
  cat "$JOURNAL"
  echo "----------------------------"
fi

if [ "$code" -ne 0 ]; then
  echo "L'installation a echoue (code $code). Rien d'irreversible : les donnees ne sont jamais supprimees." >&2
  exit "$code"
fi

if [ "$CIBLE" = vps ]; then
  cat <<FINVPS
Installation terminee.

Verifiez : https://$SITE_NAS_DOMAINE repond, puis sur le Maitre l'ecran des serveurs de licence doit lister ce serveur.
Si "nginx -t" ou un de vos sites ne repond plus, retirez nos fichiers et rechargez Nginx :
  sudo rm -f /etc/nginx/conf.d/site-nas-public.conf /etc/nginx/conf.d/site-nas-ecoute.conf && sudo nginx -t && sudo systemctl reload nginx
FINVPS
  exit 0
fi

cat <<FIN
Installation terminee.

A FAIRE UNE FOIS, a la main (DSM ne le permet pas depuis un script) : creer la tache de demarrage.
  DSM > Panneau de configuration > Planificateur de taches > Creer > Tache declenchee > Script defini par l'utilisateur
    Utilisateur : root     Evenement : Demarrage
    Script      : bash $PREFIXE/demarrage-nas.sh
Sans elle, apres un redemarrage du NAS, l'interface reseau Tailscale (tailscale0) n'est pas recreee et
l'Agent n'est pas relance.

Si, apres une coupure brutale du NAS, DSM signale des pages web en erreur (File Station, Drive), c'est
que l'ecoute Nginx est restee en place sans son adresse. Depuis SSH :
  sudo rm /usr/local/etc/nginx/sites-enabled/site-nas-ecoute.conf && sudo synosystemctl restart nginx
FIN

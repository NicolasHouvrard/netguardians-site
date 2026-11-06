# Connexion Headscale (client Tailscale) avec reessai a delai progressif.
# Bibliotheque : destinee a etre sourcee, pas executee directement.

# Execution de la commande tailscale, isolee pour pouvoir etre remplacee dans les tests.
# Definie seulement si absente, pour ne pas ecraser un remplacement deja en place.
if ! declare -F _exec_tailscale >/dev/null; then
  _exec_tailscale() { tailscale "$@"; }
fi

# Execution de synosystemctl (DSM), isolee pour les tests (meme convention).
if ! declare -F _exec_synosystemctl >/dev/null; then
  _exec_synosystemctl() { synosystemctl "$@"; }
fi
# Lecture des adresses de l'interface tailscale0, isolee pour les tests.
if ! declare -F _exec_ip >/dev/null; then
  _exec_ip() { ip "$@"; }
fi

# Code 0 si le client Tailscale est connecte au reseau Headscale, 1 sinon.
headscale_est_connecte() {
  if _exec_tailscale status >/dev/null 2>&1; then
    return 0
  fi
  return 1
}

# Connecte ce noeud a Headscale avec la cle d'authentification donnee.
# L'URL du serveur Headscale est lue dans HEADSCALE_URL ; code 1 si elle n'est pas definie.
# La cle est passee via --authkey=file:<fichier> (jamais en argv direct) pour ne pas
# apparaitre dans la liste des processus (ps) pendant l'appel ; fichier temporaire
# 0600, supprime par un trap sur chaque sortie.
headscale_connecter() {
  local cle="$1"
  if [ -z "${HEADSCALE_URL:-}" ]; then
    return 1
  fi
  local fichier_cle
  fichier_cle="$(umask 077 && mktemp)" || return 1
  printf '%s' "$cle" > "$fichier_cle"
  local resultat=1
  if _exec_tailscale up "--authkey=file:$fichier_cle" --login-server "$HEADSCALE_URL"; then
    resultat=0
  fi
  rm -f "$fichier_cle"
  return "$resultat"
}

# Affiche sur stdout l'adresse IPv4 assignee par Headscale a ce noeud.
# N'affiche rien et renvoie 1 si la commande echoue (NAS pas encore connecte).
headscale_adresse_ip() {
  local ip
  ip="$(_exec_tailscale ip -4 2>/dev/null)" || return 1
  printf '%s\n' "$ip"
}

# Reessaie la commande donnee avec un delai qui double a chaque echec, jusqu'a ce que
# la somme des delais atteigne fenetre_secondes.
# Renvoie 0 des le premier succes, 1 si la fenetre expire sans succes.
avec_reessai() {
  local fenetre="$1" delai="$2"
  local ecoule=0
  shift 2

  if "$@"; then
    return 0
  fi
  [ "$delai" -ge 1 ] || return 1
  while [ $((ecoule + delai)) -le "$fenetre" ]; do
    sleep "$delai"
    ecoule=$((ecoule + delai))
    delai=$((delai * 2))
    if "$@"; then
      return 0
    fi
  done
  return 1
}

# Code 0 si l'interface tailscale0 existe ET porte une adresse Headscale (100.x).
interface_tailscale_presente() {
  local sortie
  sortie="$(_exec_ip -o -4 addr show tailscale0 2>/dev/null)" || return 1
  case "$sortie" in
    *"inet 100."*) return 0 ;;
    *) return 1 ;;
  esac
}

# Sur DSM 7, Tailscale demarre par defaut en mode "userspace" : pas d'interface
# tailscale0, donc l'adresse Headscale n'est liable par aucun programme (Nginx,
# Agent). `tailscale configure-host` active le mode TUN, mais l'effet est PERDU a
# chaque redemarrage du NAS (constate : status reste connecte, tailscale0 disparait).
# Cette fonction est donc appelee a chaque demarrage du paquet. Idempotente : ne
# touche a rien si l'interface est deja la. Code 1 si elle n'apparait pas.
assurer_interface_tailscale() {
  if interface_tailscale_presente; then
    return 0
  fi
  _exec_tailscale configure-host || return 1
  _exec_synosystemctl restart pkgctl-Tailscale.service || return 1
  # 2+4+8+16+32+64 = 126 s d'attente cumulee : au demarrage du NAS, l'interface met plus de 30 s a
  # apparaitre apres le redemarrage de Tailscale (constate sur DSM 7.4 : elle arrive juste apres).
  avec_reessai 126 2 interface_tailscale_presente
}

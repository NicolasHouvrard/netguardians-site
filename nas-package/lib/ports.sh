# Selection de ports et avertissement utilisateur pour l'installeur NAS.
# Bibliotheque : destinee a etre sourcee, pas executee directement.

# Execution de ss, isolee pour pouvoir etre remplacee dans les tests.
# Definie seulement si absente, pour ne pas ecraser un remplacement deja en place.
if ! declare -F _exec_ss >/dev/null; then
  _exec_ss() { ss -ltn; }
fi

# Journalisation dans le journal systeme DSM. La commande DSM reelle de journalisation
# est precisee en Task 10 ; par defaut on ecrit sur stderr.
# Definie seulement si absente, pour ne pas ecraser un remplacement deja en place.
if ! declare -F _exec_log_dsm >/dev/null; then
  _exec_log_dsm() { printf '%s\n' "$1" >&2; }
fi

# Lecture brute des tables TCP du noyau (IPv4 et IPv6), isolee pour les tests.
if ! declare -F _lire_proc_net_tcp >/dev/null; then
  _lire_proc_net_tcp() { cat /proc/net/tcp /proc/net/tcp6 2>/dev/null; }
fi

# Affiche un ":<port>" par socket en ecoute (etat 0A = LISTEN), au meme format que ss pour
# que le meme filtre s'applique. Code 1 si les tables du noyau sont illisibles ou vides :
# l'etat reel est alors inconnu et l'appelant echoue ferme.
_ports_en_ecoute_proc() {
  local contenu hex
  contenu="$(_lire_proc_net_tcp)" || return 1
  [ -n "$contenu" ] || return 1
  printf '%s\n' "$contenu" | awk '$4 == "0A" { n = split($2, a, ":"); print a[n] }' | while read -r hex; do
    printf ':%d\n' "0x$hex"
  done
}

# Code 0 si le port n'est pas en ecoute sur le NAS, 1 sinon.
# Si _exec_ss echoue (commande absente, erreur), l'etat reel est inconnu :
# on echoue fermé (1, "occupe") plutot que de rapporter "libre" a tort.
port_est_libre() {
  local port="$1" sortie
  # ss d'abord ; s'il est absent ou echoue (cas de DSM, ou ss n'est pas toujours present), on lit
  # directement le noyau (/proc/net/tcp), source de verite dont ss n'est qu'un affichage.
  sortie="$(_exec_ss -ltn 2>/dev/null)" || sortie="$(_ports_en_ecoute_proc)" || return 1
  if printf '%s\n' "$sortie" | grep -Eq ":${port}( |\$)"; then
    return 1
  fi
  return 0
}

# Ecrit le message sur la sortie (texte de fin d'installation DSM) ET dans le journal DSM.
avertir_utilisateur() {
  local message="$1"
  printf '%s\n' "$message"
  _exec_log_dsm "$message"
}

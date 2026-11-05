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

# Code 0 si le port n'est pas en ecoute sur le NAS, 1 sinon.
# Si _exec_ss echoue (commande absente, erreur), l'etat reel est inconnu :
# on echoue fermé (1, "occupe") plutot que de rapporter "libre" a tort.
port_est_libre() {
  local port="$1" sortie
  sortie="$(_exec_ss -ltn 2>/dev/null)" || return 1
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

NAS_PKG_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

test_port_est_libre_echoue_si_le_port_figure_dans_la_liste_ss() {
  _exec_ss() { printf ':18080\n'; }
  source "$NAS_PKG_DIR/lib/ports.sh"
  port_est_libre 18080
  [ "$?" -eq 1 ]
}

test_port_est_libre_reussit_si_le_port_n_apparait_pas() {
  _exec_ss() { printf ':9999\n'; }
  source "$NAS_PKG_DIR/lib/ports.sh"
  port_est_libre 18080
}

test_port_est_libre_echoue_ferme_si_ss_et_proc_sont_tous_deux_indisponibles() {
  _exec_ss() { return 127; }
  _lire_proc_net_tcp() { return 1; }
  source "$NAS_PKG_DIR/lib/ports.sh"
  port_est_libre 18080
  [ "$?" -eq 1 ]
}

# Exemple reel de /proc/net/tcp : 46A0 = 18080 en ecoute (0A), 4A98 = 19096 etabli (01), 3E7A = 15994 en ecoute en IPv6
_proc_exemple() {
  cat <<'PROC'
  sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode
   0: 0100007F:46A0 00000000:0000 0A 00000000:00000000 00:00000000 00000000     0        0 12345 1 0000000000000000 100 0 0 10 0
   1: 0A00000A:4A98 0A00000B:C350 01 00000000:00000000 00:00000000 00000000     0        0 12346 1 0000000000000000 100 0 0 10 0
   2: 00000000000000000000000001000000:3E7A 00000000000000000000000000000000:0000 0A 00000000:00000000 00:00000000 00000000     0        0 12347 1 0000000000000000 100 0 0 10 0
PROC
}

test_port_est_libre_sans_ss_lit_proc_et_voit_un_port_en_ecoute() {
  _exec_ss() { return 127; }
  _lire_proc_net_tcp() { _proc_exemple; }
  source "$NAS_PKG_DIR/lib/ports.sh"
  port_est_libre 18080; a=$?     # IPv4 en ecoute
  port_est_libre 15994; b=$?     # IPv6 en ecoute
  [ "$a" -eq 1 ] && [ "$b" -eq 1 ]
}

test_port_est_libre_sans_ss_ignore_les_connexions_etablies_et_les_ports_absents() {
  _exec_ss() { return 127; }
  _lire_proc_net_tcp() { _proc_exemple; }
  source "$NAS_PKG_DIR/lib/ports.sh"
  port_est_libre 19096; a=$?     # present mais ETABLI (01), pas en ecoute
  port_est_libre 18081; b=$?     # absent
  port_est_libre 15432; c=$?
  [ "$a" -eq 0 ] && [ "$b" -eq 0 ] && [ "$c" -eq 0 ]
}

test_port_est_libre_ne_confond_pas_un_port_avec_un_autre_dont_il_est_le_prefixe() {
  _exec_ss() { return 127; }
  _lire_proc_net_tcp() { _proc_exemple; }
  source "$NAS_PKG_DIR/lib/ports.sh"
  port_est_libre 1808; a=$?      # 18080 est en ecoute, pas 1808
  port_est_libre 180800; b=$?
  [ "$a" -eq 0 ] && [ "$b" -eq 0 ]
}

test_avertir_utilisateur_appelle_le_journal_dsm_avec_le_message() {
  recu=""
  _exec_log_dsm() { recu="$1"; }
  source "$NAS_PKG_DIR/lib/ports.sh"
  avertir_utilisateur "ports non standard : 18080/18081/15432/18090"
  [ "$recu" = "ports non standard : 18080/18081/15432/18090" ]
}

test_avertir_utilisateur_affiche_aussi_le_message_sur_la_sortie() {
  _exec_log_dsm() { :; }
  source "$NAS_PKG_DIR/lib/ports.sh"
  [ "$(avertir_utilisateur "ports non standard : 18080/18081/15432/18090")" = "ports non standard : 18080/18081/15432/18090" ]
}

test_arreter_composants_du_paquet_arrete_agent_et_conteneurs_actifs_puis_les_relance() {
  appels=""
  _exec_systemctl() { appels="$appels|sc:$*"; [ "$1" != "is-active" ] || return 0; }
  _exec_docker() {
    appels="$appels|d:$*"
    if [ "$1" = "ps" ]; then case "$*" in *site-18080*|*site-postgres*) echo abc ;; esac; fi
    return 0
  }
  source "$NAS_PKG_DIR/lib/ports.sh"
  arreter_composants_du_paquet site-18080 site-18081 site-postgres
  [ "$COMPOSANTS_ARRETES" = "agent site-18080 site-postgres" ] || return 1
  relancer_composants_arretes
  [[ "$appels" == *"sc:stop site-agent"* && "$appels" == *"d:stop site-18080"* && "$appels" == *"d:stop site-postgres"* \
     && "$appels" != *"d:stop site-18081"* && "$appels" == *"sc:start site-agent"* \
     && "$appels" == *"d:start site-18080"* && "$appels" == *"d:start site-postgres"* && -z "$COMPOSANTS_ARRETES" ]]
}

test_arreter_composants_du_paquet_ne_fait_rien_si_rien_ne_tourne() {
  appels=""
  _exec_systemctl() { appels="$appels|sc:$*"; return 3; }
  _exec_docker() { appels="$appels|d:$*"; return 0; }
  source "$NAS_PKG_DIR/lib/ports.sh"
  arreter_composants_du_paquet site-18080
  [ -z "$COMPOSANTS_ARRETES" ] && [[ "$appels" != *stop* ]]
}

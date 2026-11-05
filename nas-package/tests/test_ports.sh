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

test_port_est_libre_echoue_si_ss_est_indisponible() {
  _exec_ss() { return 127; }
  source "$NAS_PKG_DIR/lib/ports.sh"
  port_est_libre 18080
  [ "$?" -eq 1 ]
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

NAS_PKG_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

test_maitre_est_joignable_reussit_si_curl_renvoie_0() {
  _exec_curl() { return 0; }
  source "$NAS_PKG_DIR/lib/maitre.sh"
  maitre_est_joignable "https://logipourtous.ts.net"
}

test_maitre_est_joignable_echoue_si_curl_renvoie_un_code_non_nul() {
  _exec_curl() { return 7; }
  source "$NAS_PKG_DIR/lib/maitre.sh"
  maitre_est_joignable "https://logipourtous.ts.net"
  [ "$?" -eq 1 ]
}

test_maitre_est_joignable_interroge_le_bon_chemin_avec_un_timeout() {
  recu=""
  _exec_curl() { recu="$*"; return 0; }
  source "$NAS_PKG_DIR/lib/maitre.sh"
  maitre_est_joignable "https://logipourtous.ts.net"
  [[ "$recu" == *"https://logipourtous.ts.net/actuator/health"* ]] \
    && [[ "$recu" == *"--max-time 5"* ]]
}

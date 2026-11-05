NAS_PKG_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

test_nas_est_maitre_reussit_si_logipourtous() {
  _exec_hostname() { echo "logipourtous"; }
  source "$NAS_PKG_DIR/lib/role.sh"
  nas_est_maitre
}

test_nas_est_maitre_echoue_pour_autre_nom() {
  _exec_hostname() { echo "nas-client-dupont"; }
  source "$NAS_PKG_DIR/lib/role.sh"
  nas_est_maitre
  [ "$?" -eq 1 ]
}

test_nas_est_maitre_ignore_la_casse_et_le_suffixe_domaine() {
  _exec_hostname() { echo "LogiPourtous.local"; }
  source "$NAS_PKG_DIR/lib/role.sh"
  nas_est_maitre
}

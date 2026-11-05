NAS_PKG_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

test_headscale_est_connecte_reussit_quand_tailscale_status_renvoie_0() {
  _exec_tailscale() { return 0; }
  source "$NAS_PKG_DIR/lib/headscale.sh"
  headscale_est_connecte
}

test_avec_reessai_reussit_des_que_la_commande_reussit_sans_attendre_toute_la_fenetre() {
  source "$NAS_PKG_DIR/lib/headscale.sh"
  compteur=0
  commande_test() { compteur=$((compteur+1)); [ "$compteur" -ge 2 ]; }
  avec_reessai 10 1 commande_test
}

test_avec_reessai_echoue_si_la_fenetre_expire_sans_succes() {
  source "$NAS_PKG_DIR/lib/headscale.sh"
  avec_reessai 1 1 false
  [ "$?" -eq 1 ]
}

test_headscale_adresse_ip_affiche_l_adresse_renvoyee_par_tailscale_ip_4() {
  _exec_tailscale() { echo "100.64.0.12"; }
  source "$NAS_PKG_DIR/lib/headscale.sh"
  [ "$(headscale_adresse_ip)" = "100.64.0.12" ]
}

test_headscale_adresse_ip_renvoie_exactement_1_si_tailscale_echoue() {
  _exec_tailscale() { return 42; }
  source "$NAS_PKG_DIR/lib/headscale.sh"
  sortie="$(headscale_adresse_ip)"
  code="$?"
  [ -z "$sortie" ] && [ "$code" -eq 1 ]
}

test_headscale_est_connecte_renvoie_exactement_1_si_tailscale_echoue() {
  _exec_tailscale() { return 42; }
  source "$NAS_PKG_DIR/lib/headscale.sh"
  headscale_est_connecte
  [ "$?" -eq 1 ]
}

test_headscale_connecter_passe_la_cle_par_fichier_jamais_en_argv() {
  recu=""
  _exec_tailscale() { recu="$*"; return 0; }
  source "$NAS_PKG_DIR/lib/headscale.sh"
  HEADSCALE_URL="https://logipourtous.ts.net"
  headscale_connecter "secret-de-test-12345"
  [[ "$recu" != *"secret-de-test-12345"* ]] && [[ "$recu" == *"--authkey=file:"* ]]
}

test_avec_reessai_echoue_immediatement_si_delai_initial_est_zero() {
  source "$NAS_PKG_DIR/lib/headscale.sh"
  avec_reessai 5 0 false
  [ "$?" -eq 1 ]
}

test_assurer_interface_tailscale_ne_fait_rien_si_tailscale0_est_deja_la() {
  appels=0
  _exec_ip() { echo "54: tailscale0    inet 100.64.0.2/32 scope global tailscale0"; }
  _exec_tailscale() { appels=$((appels+1)); }
  _exec_synosystemctl() { appels=$((appels+1)); }
  source "$NAS_PKG_DIR/lib/headscale.sh"
  assurer_interface_tailscale && [ "$appels" -eq 0 ]
}

test_assurer_interface_tailscale_active_le_mode_tun_puis_redemarre_tailscale_dans_cet_ordre() {
  journal=""; present=0
  _exec_ip() { [ "$present" -eq 1 ] && echo "54: tailscale0    inet 100.64.0.2/32 scope global tailscale0"; }
  _exec_tailscale() { journal="$journal tailscale:$*"; }
  _exec_synosystemctl() { journal="$journal syno:$*"; present=1; }
  source "$NAS_PKG_DIR/lib/headscale.sh"
  assurer_interface_tailscale
  [ "$?" -eq 0 ] && [ "$journal" = " tailscale:configure-host syno:restart pkgctl-Tailscale.service" ]
}

test_assurer_interface_tailscale_echoue_ferme_si_l_interface_n_apparait_jamais() {
  _exec_ip() { return 1; }
  _exec_tailscale() { :; }
  _exec_synosystemctl() { :; }
  source "$NAS_PKG_DIR/lib/headscale.sh"
  avec_reessai() { return 1; }   # pas d'attente reelle dans le test
  assurer_interface_tailscale; [ "$?" -eq 1 ]
}

test_assurer_interface_tailscale_n_accepte_pas_une_interface_sans_adresse_headscale() {
  # tailscale0 presente mais sans adresse 100.x (etat transitoire) : pas "prete"
  _exec_ip() { echo "54: tailscale0    inet 192.168.1.5/24 scope global tailscale0"; }
  source "$NAS_PKG_DIR/lib/headscale.sh"
  interface_tailscale_presente; [ "$?" -eq 1 ]
}

test_assurer_interface_tailscale_echoue_si_configure_host_echoue_sans_redemarrer() {
  redemarre=0
  _exec_ip() { return 1; }
  _exec_tailscale() { return 1; }
  _exec_synosystemctl() { redemarre=1; }
  source "$NAS_PKG_DIR/lib/headscale.sh"
  assurer_interface_tailscale; code=$?
  [ "$code" -eq 1 ] && [ "$redemarre" -eq 0 ]
}

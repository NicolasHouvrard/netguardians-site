NAS_PKG_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

test_installer_service_agent_remplace_les_placeholders_et_active_le_service() {
  dir=$(mktemp -d)
  appele=""
  _exec_systemctl() { appele="$appele|$*"; }
  source "$NAS_PKG_DIR/lib/agent_service.sh"
  installer_service_agent "/usr/local/jre/bin/java" "/volume1/site-data/agent.jar" "100.64.0.12" 18090 "$dir/site-agent.service"
  grep -q "/usr/local/jre/bin/java" "$dir/site-agent.service" \
    && grep -q "/volume1/site-data/agent.jar" "$dir/site-agent.service" \
    && grep -q "100.64.0.12" "$dir/site-agent.service" \
    && grep -q "AGENT_PORT=18090" "$dir/site-agent.service" \
    && [ "$appele" = "|daemon-reload|enable site-agent|restart site-agent" ]
}

test_installer_service_agent_n_utilise_jamais_l_option_now_absente_du_systemctl_dsm() {
  dir=$(mktemp -d)
  appele=""
  _exec_systemctl() { appele="$appele|$*"; }
  source "$NAS_PKG_DIR/lib/agent_service.sh"
  installer_service_agent "/j" "/a.jar" "100.64.0.12" 18090 "$dir/site-agent.service"
  [[ "$appele" != *"--now"* ]]
}

test_installer_service_agent_echoue_si_l_activation_echoue() {
  dir=$(mktemp -d)
  _exec_systemctl() { [ "$1" != "enable" ]; }
  source "$NAS_PKG_DIR/lib/agent_service.sh"
  installer_service_agent "/j" "/a.jar" "100.64.0.12" 18090 "$dir/site-agent.service"
  [ "$?" -ne 0 ]
}

test_installer_service_agent_echoue_ferme_si_adresse_ip_vide_ou_hors_tailnet() {
  dir=$(mktemp -d)
  appele=""
  _exec_systemctl() { appele="$*"; }
  source "$NAS_PKG_DIR/lib/agent_service.sh"
  installer_service_agent "/usr/local/jre/bin/java" "/volume1/site-data/agent.jar" "" 18090 "$dir/site-agent.service"
  code_vide="$?"
  installer_service_agent "/usr/local/jre/bin/java" "/volume1/site-data/agent.jar" "203.0.113.5" 18090 "$dir/site-agent.service"
  code_hors_tailnet="$?"
  [ "$code_vide" -eq 1 ] && [ "$code_hors_tailnet" -eq 1 ] \
    && [ ! -e "$dir/site-agent.service" ] && [ -z "$appele" ]
}

test_installer_service_agent_echoue_ferme_si_port_invalide() {
  dir=$(mktemp -d)
  appele=""
  _exec_systemctl() { appele="$*"; }
  source "$NAS_PKG_DIR/lib/agent_service.sh"
  installer_service_agent "/usr/local/jre/bin/java" "/volume1/site-data/agent.jar" "100.64.0.12" "abc" "$dir/site-agent.service"
  code_non_numerique="$?"
  installer_service_agent "/usr/local/jre/bin/java" "/volume1/site-data/agent.jar" "100.64.0.12" 99999 "$dir/site-agent.service"
  code_hors_plage="$?"
  [ "$code_non_numerique" -eq 1 ] && [ "$code_hors_plage" -eq 1 ] \
    && [ ! -e "$dir/site-agent.service" ] && [ -z "$appele" ]
}

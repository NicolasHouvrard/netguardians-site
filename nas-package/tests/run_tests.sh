#!/usr/bin/env bash
set -uo pipefail
fichier="$1"
source "$fichier"
total=0
echecs=0
for fn in $(declare -F | awk '{print $3}' | grep '^test_'); do
  total=$((total+1))
  if "$fn"; then
    echo "ok - $fn"
  else
    echo "FAIL - $fn"
    echecs=$((echecs+1))
  fi
done
echo "$((total-echecs))/$total"
[ "$echecs" -eq 0 ]

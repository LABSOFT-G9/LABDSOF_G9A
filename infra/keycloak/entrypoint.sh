#!/bin/bash
# Starts Keycloak. With BMC_SEED_SYNTHETIC=true (local and dev, never
# production) the synthetic accounts are imported together with the realm.
# Both imports happen only while the realm does not exist yet.
set -euo pipefail

BMC_SYNTHETIC_USER_PASSWORD="${BMC_SYNTHETIC_USER_PASSWORD:-}"

if [ "${BMC_SEED_SYNTHETIC:-false}" = "true" ]; then
  # The accounts are reachable from the internet on dev: never import them
  # with an empty or short password.
  if [ "${#BMC_SYNTHETIC_USER_PASSWORD}" -lt 12 ]; then
    echo "bmc: BMC_SEED_SYNTHETIC=true needs BMC_SYNTHETIC_USER_PASSWORD (12 characters or more)" >&2
    exit 1
  fi
  cp /opt/keycloak/synthetic/bmc-users-0.json /opt/keycloak/data/import/bmc-users-0.json
fi

exec /opt/keycloak/bin/kc.sh "$@"

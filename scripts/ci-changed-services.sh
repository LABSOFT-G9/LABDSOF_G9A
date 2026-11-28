#!/usr/bin/env bash
# Which pull-request jobs a change needs (pr.yml, job "Load services").
#
#   scripts/ci-changed-services.sh <base-sha> <head-sha> [services.json]
#
# Prints GitHub Actions outputs, one per line:
#   list=<JSON array>   the entries of .github/services.json whose folder changed
#   platform=true|false whether the messaging starter (platform/) must be tested
#   sign_in=true|false  whether the sign-in end-to-end test must run
#   reason=<text>       why, for the job log
#
# Safe by default: when the diff cannot be computed, or a file that every
# service build depends on changed, every service is selected. Repo-wide
# checks (gitleaks, CodeQL, buf) are not decided here; they always run.
set -euo pipefail

base="${1:?base commit}"
head="${2:?head commit}"
services_file="${3:-.github/services.json}"

all() {
  echo "list=$(jq -c . "$services_file")"
  echo "platform=true"
  echo "sign_in=true"
  echo "reason=$1"
  exit 0
}

if ! changed="$(git diff --name-only "$base...$head" 2>/dev/null)"; then
  all "the diff between $base and $head could not be computed: everything runs"
fi

# Files that every service build or the pipeline itself depends on.
shared='^(\.github/services\.json|\.github/actions/|\.github/workflows/pr\.yml|scripts/ci-changed-services\.sh)'
if grep -qE "$shared" <<<"$changed"; then
  all "the pipeline or the shared build changed: everything runs"
fi

# A service is selected when a file under its folder changed.
list="$(jq -c --arg changed "$changed" '
  ($changed | split("\n") | map(select(. != ""))) as $files
  | map(select(.dir as $dir | any($files[]; startswith($dir + "/"))))
' "$services_file")"

platform=false
if grep -qE '^platform/' <<<"$changed"; then
  platform=true
fi

# What the sign-in test starts (Traefik, Keycloak, MySQL, Mailpit, Issue, the
# client) and the files it is built from.
sign_in=false
if grep -qE '^(services/(issue|client)/|infra/|local/|compose(\.local)?\.yaml$|\.env\.example$|scripts/e2e-signin\.sh$)' <<<"$changed"; then
  sign_in=true
fi

echo "list=$list"
echo "platform=$platform"
echo "sign_in=$sign_in"
echo "reason=$(jq -r 'if length == 0 then "no service folder changed" else "changed: " + (map(.name) | join(", ")) end' <<<"$list")"

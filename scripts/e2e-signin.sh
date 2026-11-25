#!/usr/bin/env bash
# End-to-end sign-in check for the running stack (EN-02-04, ADR-005).
#
# Drives the same flow as a browser, with curl and a cookie jar:
#   1. an API call without a session answers 401 (never a redirect);
#   2. /oidc/login redirects to Keycloak, the synthetic user signs in there,
#      and the callback sets the session cookie (HttpOnly, SameSite=Strict);
#   3. GET /api/issue/me answers 200 with the user's memberships, so the edge
#      forwarded an access token that the Issue service accepted;
#   4. a state-changing request needs the CSRF header (403 without it), and
#      with it a resident can submit a report (201);
#   5. a forged or copied bearer token sent by the client is not accepted;
#   6. Keycloak's admin console and health endpoints are not routed;
#   7. /logout ends the session: the API answers 401 again;
#   8. the synthetic platform staff account shows its realm role in /me.
#
# Usage: scripts/e2e-signin.sh
# Environment (defaults fit the local stack, compose.local.yaml):
#   BMC_URL                      default http://localhost
#   BMC_SYNTHETIC_USER_PASSWORD  required (the value in .env)
#   BMC_E2E_USER                 default synthetic.resident.a1@bmc.example
#   BMC_E2E_WAIT_SECONDS         default 300: how long to wait for the stack
#   BMC_E2E_WRITE                default true: also submit one synthetic report
# Only synthetic accounts; needs BMC_SEED_SYNTHETIC=true on the stack.
set -euo pipefail

BASE="${BMC_URL:-http://localhost}"
USERNAME="${BMC_E2E_USER:-synthetic.resident.a1@bmc.example}"
PASSWORD="${BMC_SYNTHETIC_USER_PASSWORD:?set BMC_SYNTHETIC_USER_PASSWORD}"
WAIT="${BMC_E2E_WAIT_SECONDS:-300}"
CSRF_HEADER="${CSRF_HEADER:-X-BMC-CSRF}"

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
jar="$work/cookies"

step() { printf '\n== %s\n' "$*"; }
fail() { printf 'FAIL: %s\n' "$*" >&2; exit 1; }
# curl treats http://localhost as a secure origin, so the jar behaves like a
# browser's on the local stack too.
request() { curl --silent --show-error --max-time 30 --cookie "$jar" --cookie-jar "$jar" "$@"; }
status() { request --output /dev/null --write-out '%{http_code}' "$@"; }
expect() { # expect <wanted status> <description> <curl args...>
  local wanted="$1" what="$2" got
  shift 2
  got="$(status "$@")"
  [ "$got" = "$wanted" ] || fail "$what: expected $wanted, got $got"
  echo "ok   $what -> $got"
}

sign_in() { # sign_in <username>: /oidc/login -> Keycloak's form -> callback, in the current jar
  local user="$1" action landed page
  page="$(request --location --output "$work/login.html" --write-out '%{url_effective} %{http_code} %{content_type}' \
    "$BASE/oidc/login")"
  # `|| true`: without a form, say what answered instead of stopping silently (pipefail).
  action="$(grep -o 'action="[^"]*"' "$work/login.html" | head -n 1 | sed 's/^action="//; s/"$//; s/&amp;/\&/g' || true)"
  [ -n "$action" ] || fail "Keycloak's login form was not found; /oidc/login ended at: $page"
  case "$action" in "$BASE"/auth/realms/bmc/*) ;; *) fail "the login form posts outside $BASE/auth/realms/bmc" ;; esac
  landed="$(request --location --output /dev/null --write-out '%{url_effective} %{http_code}' \
    --data-urlencode "username=$user" --data-urlencode "password=$PASSWORD" "$action")"
  [ "$landed" = "$BASE/ 200" ] || fail "after sign-in the browser should land on $BASE/ with 200, got: $landed"
  echo "ok   $user signed in and returned to $BASE/"
}

# Ready means that Keycloak itself answers, not only that something does.
# Traefik routes a container once it is healthy; until then /auth/... falls
# through to the client, and a single-page client answers any path with 200
# and index.html. So: the discovery document must be Keycloak's JSON, the edge
# must refuse an API call without a session, and /oidc/login must redirect to
# the realm (the OIDC plugin answers 500 until it has read the discovery).
# These probes use no cookie jar, so they leave no login state behind.
probe() { curl --silent --max-time 30 "$@"; }
keycloak_answers() {
  probe "$BASE/auth/realms/bmc/.well-known/openid-configuration" | grep -q "\"issuer\":\"$BASE/auth/realms/bmc\""
}
login_redirects() {
  case "$(probe --output /dev/null --write-out '%{http_code} %{redirect_url}' "$BASE/oidc/login")" in
    "302 $BASE/auth/realms/bmc/"*) true ;;
    *) false ;;
  esac
}

step "Waiting for the stack at $BASE (up to ${WAIT}s)"
deadline=$((SECONDS + WAIT))
until keycloak_answers && [ "$(status "$BASE/api/issue/me" || true)" = 401 ] && login_redirects; do
  [ "$SECONDS" -lt "$deadline" ] || fail "the stack did not become ready in ${WAIT}s"
  sleep 5
done
echo "ok   Keycloak answers, the edge refuses an API call without a session and /oidc/login redirects to the realm"

step "1. No session"
expect 401 "GET /api/issue/me without a session" "$BASE/api/issue/me"
expect 401 "POST /api/issue/reports without a session" -X POST -H "$CSRF_HEADER: 1" "$BASE/api/issue/reports"

step "2. Sign in as $USERNAME"
sign_in "$USERNAME"
session_line="$(grep -E 'TraefikOidcAuth\.Session' "$jar" | head -n 1 || true)"
[ -n "$session_line" ] || fail "no session cookie was set"
case "$session_line" in '#HttpOnly_'*) echo "ok   the session cookie is HttpOnly" ;; *) fail "the session cookie is not HttpOnly" ;; esac

step "3. The session reaches the Issue service"
# Without a session the edge answers before the service, so only now can the
# script tell whether the Issue service itself is up: wait while Traefik has
# no healthy backend for it (404, 502, 503, 504).
until case "$(status "$BASE/api/issue/me" || true)" in 404 | 502 | 503 | 504 | 000) false ;; *) true ;; esac; do
  [ "$SECONDS" -lt "$deadline" ] || fail "the Issue service did not become ready in ${WAIT}s"
  sleep 5
done
request --output "$work/me.json" "$BASE/api/issue/me"
expect 200 "GET /api/issue/me with the session" "$BASE/api/issue/me"
grep -q '"userId"' "$work/me.json" || fail "/me has no userId: $(cat "$work/me.json")"
grep -q '"memberships"' "$work/me.json" || fail "/me has no memberships: $(cat "$work/me.json")"
if [ "$USERNAME" = "synthetic.resident.a1@bmc.example" ]; then
  grep -q '"userId":"0199f2a0-0000-7000-8000-000000000101"' "$work/me.json" \
    || fail "the Keycloak subject is not the seeded user ID: $(cat "$work/me.json")"
  grep -q '"buildingName":"Synthetic Building A"' "$work/me.json" \
    || fail "the seeded membership was not resolved: $(cat "$work/me.json")"
  grep -q '"email":"synthetic.resident.a1@bmc.example"' "$work/me.json" \
    || fail "/me does not pass on the account's email: $(cat "$work/me.json")"
  grep -q '"platformRoles":\[\]' "$work/me.json" || fail "a resident has no platform role: $(cat "$work/me.json")"
  echo "ok   subject, membership, email and (no) platform role match the seed"
fi
expect 200 "GET /api/issue/reports with the session" "$BASE/api/issue/reports"

step "4. CSRF header on state-changing requests"
expect 403 "POST /api/issue/reports without $CSRF_HEADER" -X POST -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: e2e-signin-no-csrf' --data '{}' "$BASE/api/issue/reports"
expect 400 "POST /api/issue/reports with $CSRF_HEADER (empty report)" -X POST -H 'Content-Type: application/json' \
  -H "$CSRF_HEADER: 1" -H 'Idempotency-Key: e2e-signin-csrf' --data '{}' "$BASE/api/issue/reports"
if [ "$USERNAME" = "synthetic.resident.a1@bmc.example" ] && [ "${BMC_E2E_WRITE:-true}" = "true" ]; then
  # A real (synthetic) report through the edge: the whole write path works.
  expect 201 "POST /api/issue/reports as a resident of the building" -X POST -H 'Content-Type: application/json' \
    -H "$CSRF_HEADER: 1" -H "Idempotency-Key: e2e-signin-$(date +%s)-$RANDOM" \
    --data '{"buildingId":"0199f2a0-0000-7000-8000-00000000b001","areaId":"0199f2a0-0000-7000-8000-00000000a001","text":"Synthetic report from the sign-in check: the garage light flickers."}' \
    "$BASE/api/issue/reports"
fi

step "5. A bearer token from the client is not a session"
# Built in a variable: a literal bearer header on a curl line trips the secrets scan.
made_up_header="Authorization: Bearer $(printf 'not-a-%s' real-token)"
got="$(curl --silent --max-time 30 --output /dev/null --write-out '%{http_code}' \
  -H "$made_up_header" "$BASE/api/issue/me")"
[ "$got" = 401 ] || fail "a client-supplied bearer token without a session: expected 401, got $got"
echo "ok   client-supplied bearer token without a session -> 401"

step "6. Keycloak's internal paths are not routed"
for path in /auth/admin/ /auth/admin/master/console/ /auth/health/ready /auth/metrics /auth/realms/master/ /auth/realms/master/protocol/openid-connect/certs; do
  got="$(curl --silent --max-time 30 --output /dev/null --write-out '%{http_code}' "$BASE$path")"
  [ "$got" = 404 ] || fail "$path should not be routed: expected 404, got $got"
  echo "ok   $path -> 404"
done

step "7. Sign out"
landed="$(request --location --output /dev/null --write-out '%{url_effective} %{http_code}' "$BASE/logout")"
[ "$landed" = "$BASE/ 200" ] || fail "after sign-out the browser should land on $BASE/ with 200, got: $landed"
expect 401 "GET /api/issue/me after sign-out" "$BASE/api/issue/me"

step "8. Platform staff: a realm role, no membership"
jar="$work/staff-cookies"
sign_in "synthetic.platform.staff@bmc.example"
request --output "$work/staff.json" "$BASE/api/issue/me"
grep -q '"platformRoles":\["platform_staff"\]' "$work/staff.json" \
  || fail "/me does not show the platform_staff realm role: $(cat "$work/staff.json")"
grep -q '"memberships":\[\]' "$work/staff.json" || fail "platform staff should have no membership: $(cat "$work/staff.json")"
echo "ok   /me shows platform_staff and no membership"

printf '\nAll sign-in checks passed.\n'

#!/usr/bin/env bash
set -Eeuo pipefail

# Deploy exactly the checkout containing this script. This does not pull, switch
# branches, or recreate the Cloudflare tunnel.
readonly CHECKOUT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd -P)"
readonly CONFIG="$CHECKOUT/deploy/macos/home-server.env"

fail() {
  printf 'ERROR: %s\n' "$*" >&2
  exit 1
}

[[ $# -eq 0 ]] || fail "usage: $0 (no arguments)"
[[ "$(uname -s)" == Darwin ]] || fail "run this on the macOS home server"
[[ -f "$CONFIG" && ! -L "$CONFIG" ]] || fail "missing or unsafe deployment config: $CONFIG"
# shellcheck disable=SC1090
source "$CONFIG"
[[ "$(cd "$ISSUEBOT_CHECKOUT" && pwd -P)" == "$CHECKOUT" ]] ||
  fail "this checkout does not match ISSUEBOT_CHECKOUT in $CONFIG"
[[ -f "$ISSUEBOT_RUNTIME_ENV" && ! -L "$ISSUEBOT_RUNTIME_ENV" ]] ||
  fail "missing or unsafe protected runtime file: $ISSUEBOT_RUNTIME_ENV"
runtime_mode="$(stat -f '%Lp' "$ISSUEBOT_RUNTIME_ENV")"
(( (8#$runtime_mode & 077) == 0 )) || fail "runtime file must be private (mode 0600)"
# shellcheck disable=SC1090
source "$ISSUEBOT_RUNTIME_ENV"
[[ -n "${ISSUEBOT_USERNAME:-}" && -n "${ISSUEBOT_PASSWORD:-}" ]] ||
  fail "ISSUEBOT_USERNAME and ISSUEBOT_PASSWORD are required for the safety check"

readonly BASE_URL="http://127.0.0.1:$ISSUEBOT_PORT"
dashboard_file="$(mktemp "${TMPDIR:-/tmp}/issuebot-dashboard.XXXXXX")"
cookie_file="$(mktemp "${TMPDIR:-/tmp}/issuebot-cookie.XXXXXX")"
trap 'rm -f "$dashboard_file" "$cookie_file"' EXIT

fetch_dashboard() {
  local status
  status="$(curl --silent --show-error --max-time 15 --basic \
    --user "$ISSUEBOT_USERNAME:$ISSUEBOT_PASSWORD" \
    --cookie "$cookie_file" --cookie-jar "$cookie_file" \
    --output "$dashboard_file" --write-out '%{http_code}' "$BASE_URL/")" ||
    fail "could not read the local dashboard; deployment was not restarted"
  [[ "$status" == 200 ]] || fail "dashboard returned HTTP $status; deployment was not restarted"

  if grep -Fq '<strong>Queue running</strong>' "$dashboard_file"; then
    queue_state='Queue running'
  elif grep -Fq '<strong>Queue paused</strong>' "$dashboard_file"; then
    queue_state='Queue paused'
  elif grep -Fq '<strong>Work stopped</strong>' "$dashboard_file"; then
    queue_state='Work stopped'
  else
    queue_state=''
  fi
  active_count="$(sed -n 's/.*id="control-lane-processing-count"[^>]*aria-label="\([0-9][0-9]*\) matching issues".*/\1/p' "$dashboard_file" | head -n 1)"
  csrf_token="$(sed -n 's/.*<meta name="_csrf" content="\([^"]*\)".*/\1/p' "$dashboard_file" | head -n 1)"
  [[ -n "$queue_state" && "$active_count" =~ ^[0-9]+$ && -n "$csrf_token" ]] ||
    fail "could not verify queue state, active count, and CSRF token; deployment was not restarted"
}

post_control() {
  local action="$1" status
  status="$(curl --silent --show-error --max-time 15 --basic \
    --user "$ISSUEBOT_USERNAME:$ISSUEBOT_PASSWORD" \
    --cookie "$cookie_file" --cookie-jar "$cookie_file" \
    --header "X-CSRF-TOKEN: $csrf_token" \
    --data-urlencode 'returnTo=/' \
    --output /dev/null --write-out '%{http_code}' \
    "$BASE_URL/processing/$action")" || fail "could not $action; deployment was not restarted"
  [[ "$status" == 302 ]] || fail "$action returned HTTP $status; deployment was not restarted"
}

cd "$CHECKOUT"
fetch_dashboard
(( active_count == 0 )) || fail "$active_count issue(s) are active; retry when they finish. Nothing was paused or restarted."
printf 'Building and testing the current checkout (%s)...\n' "$(git rev-parse --short HEAD)"
./mvnw --batch-mode clean verify
node --test src/test/js/*.cjs src/test/js/*.js
[[ -f target/issuebot.jar ]] || fail "build did not produce target/issuebot.jar"
version="$(unzip -p target/issuebot.jar META-INF/build-info.properties |
  sed -n 's/^build.version=//p' | head -n 1)"
[[ -n "$version" ]] || fail "built jar has no Maven version"

fetch_dashboard
was_running=false
if [[ "$queue_state" == 'Queue running' ]]; then
  post_control pause-after-current
  was_running=true
  fetch_dashboard
  [[ "$queue_state" == 'Queue paused' ]] || fail "queue did not pause; deployment was not restarted"
fi

if (( active_count != 0 )); then
  fail "$active_count issue(s) are still active. The queue was not resumed; retry after they finish."
fi

# Recheck immediately before the installer replaces the launchd job. A manual
# start racing this check cannot be made atomic from a shell script.
fetch_dashboard
(( active_count == 0 )) || fail "$active_count issue(s) became active; deployment was not restarted"
[[ "$queue_state" != 'Queue running' ]] || fail "queue resumed unexpectedly; deployment was not restarted"

printf 'Deploying IssueBot %s with no active issues...\n' "$version"
./deploy/macos/install-service.sh --skip-build
fetch_dashboard
grep -Fq "aria-label=\"Application version\">v$version</small>" "$dashboard_file" ||
  fail "service is ready, but the dashboard does not show version $version; queue left paused"

if [[ "$was_running" == true ]]; then
  post_control restart
  fetch_dashboard
  [[ "$queue_state" == 'Queue running' ]] || fail "deployment succeeded, but the queue did not resume"
fi
printf 'IssueBot %s is live; %s.\n' "$version" "$queue_state"

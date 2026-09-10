#!/usr/bin/env bash
set -Eeuo pipefail

readonly SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly DEPLOY_CONFIG="${ISSUEBOT_DEPLOY_CONFIG:-$SCRIPT_DIR/home-server.env}"
readonly PLIST_TEMPLATE="$SCRIPT_DIR/com.baskettecase.issuebot.plist.template"

[[ -f "$DEPLOY_CONFIG" && ! -L "$DEPLOY_CONFIG" ]] || {
  printf 'ERROR: deployment configuration is missing or unsafe: %s\n' "$DEPLOY_CONFIG" >&2
  exit 1
}
# shellcheck disable=SC1090
source "$DEPLOY_CONFIG"

readonly PLIST_TARGET="$ISSUEBOT_SERVICE_HOME/Library/LaunchAgents/$ISSUEBOT_AGENT_LABEL.plist"
readonly HEALTH_URL="http://127.0.0.1:$ISSUEBOT_PORT/actuator/health/readiness"
readonly SERVE_SCRIPT="$ISSUEBOT_CHECKOUT/deploy/macos/serve-issuebot.sh"
readonly SERVICE_PATH="$(dirname "$ISSUEBOT_JAVA"):$ISSUEBOT_SERVICE_HOME/.local/bin:/usr/local/bin:/usr/bin:/bin"
readonly LOG_PATH="$ISSUEBOT_STATE_DIR/logs/issuebot.log"

for value in "$ISSUEBOT_SERVICE_HOME" "$ISSUEBOT_CHECKOUT" "$ISSUEBOT_RUNTIME_ENV" \
  "$ISSUEBOT_STATE_DIR" "$ISSUEBOT_JAVA" "$ISSUEBOT_AGENT_LABEL"; do
  [[ "$value" =~ ^[A-Za-z0-9_./:-]+$ ]] || {
    printf 'ERROR: deployment value contains unsupported characters: %s\n' "$value" >&2
    exit 1
  }
done
[[ "$ISSUEBOT_PORT" =~ ^[0-9]{1,5}$ ]] && (( ISSUEBOT_PORT >= 1 && ISSUEBOT_PORT <= 65535 )) || {
  printf 'ERROR: ISSUEBOT_PORT is invalid: %s\n' "$ISSUEBOT_PORT" >&2
  exit 1
}

skip_build=false
case "${1:-}" in
  '') ;;
  --skip-build) skip_build=true ;;
  *) printf 'Usage: %s [--skip-build]\n' "$0" >&2; exit 2 ;;
esac

[[ "$(uname -s)" == Darwin ]] || {
  printf 'ERROR: this installer is for the macOS home-services host\n' >&2
  exit 1
}
[[ -f "$ISSUEBOT_RUNTIME_ENV" && ! -L "$ISSUEBOT_RUNTIME_ENV" ]] || {
  printf 'ERROR: create the protected runtime file first: %s\n' "$ISSUEBOT_RUNTIME_ENV" >&2
  exit 1
}
runtime_mode="$(stat -f '%Lp' "$ISSUEBOT_RUNTIME_ENV")"
(( (8#$runtime_mode & 077) == 0 )) || {
  printf 'ERROR: runtime file must not be accessible by group or others: %s (%s)\n' "$ISSUEBOT_RUNTIME_ENV" "$runtime_mode" >&2
  exit 1
}

mkdir -p "$ISSUEBOT_STATE_DIR/logs" "$ISSUEBOT_STATE_DIR/repos" "$ISSUEBOT_SERVICE_HOME/Library/LaunchAgents"
cd "$ISSUEBOT_CHECKOUT"
if [[ "$skip_build" == false ]]; then
  ./mvnw --batch-mode clean verify
else
  [[ -f "$ISSUEBOT_CHECKOUT/target/issuebot.jar" ]] || {
    printf 'ERROR: --skip-build requires an existing application jar\n' >&2
    exit 1
  }
fi

# Immutable, content-addressed jars protect the running JVM from later Maven builds.
mkdir -p "$ISSUEBOT_STATE_DIR/releases"
release_digest="$(shasum -a 256 target/issuebot.jar | awk '{print $1}')"
release_jar="$ISSUEBOT_STATE_DIR/releases/issuebot-$release_digest.jar"
[[ -f "$release_jar" ]] || install -m 0600 target/issuebot.jar "$release_jar"
ln -sfn "$release_jar" "$ISSUEBOT_STATE_DIR/releases/current.jar"

rendered_plist="$(mktemp "${TMPDIR:-/tmp}/issuebot-launchd.XXXXXX")"
trap 'rm -f "$rendered_plist"' EXIT
sed \
  -e "s|__AGENT_LABEL__|$ISSUEBOT_AGENT_LABEL|g" \
  -e "s|__SERVE_SCRIPT__|$SERVE_SCRIPT|g" \
  -e "s|__ISSUEBOT_CHECKOUT__|$ISSUEBOT_CHECKOUT|g" \
  -e "s|__SERVICE_HOME__|$ISSUEBOT_SERVICE_HOME|g" \
  -e "s|__SERVICE_PATH__|$SERVICE_PATH|g" \
  -e "s|__LOG_PATH__|$LOG_PATH|g" \
  "$PLIST_TEMPLATE" >"$rendered_plist"
plutil -lint "$rendered_plist"
install -m 0644 "$rendered_plist" "$PLIST_TARGET"

launchctl bootout "gui/$(id -u)/$ISSUEBOT_AGENT_LABEL" 2>/dev/null || true
launchctl bootstrap "gui/$(id -u)" "$PLIST_TARGET"
launchctl kickstart -k "gui/$(id -u)/$ISSUEBOT_AGENT_LABEL"

for _ in $(seq 1 60); do
  if curl --fail --silent --show-error "$HEALTH_URL" >/dev/null 2>&1; then
    printf 'IssueBot is ready at http://127.0.0.1:8090\n'
    exit 0
  fi
  sleep 1
done

printf 'ERROR: IssueBot did not become ready; inspect %s\n' "$LOG_PATH" >&2
exit 1

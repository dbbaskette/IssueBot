#!/usr/bin/env bash
set -Eeuo pipefail

readonly PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
readonly AGENT_LABEL="com.baskettecase.issuebot"
readonly PLIST_SOURCE="$PROJECT_DIR/deploy/macos/$AGENT_LABEL.plist"
readonly PLIST_TARGET="$HOME/Library/LaunchAgents/$AGENT_LABEL.plist"
readonly RUNTIME_ENV="$HOME/.config/issuebot/runtime.env"
readonly HEALTH_URL="http://127.0.0.1:8090/actuator/health/readiness"

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
[[ -f "$RUNTIME_ENV" && ! -L "$RUNTIME_ENV" ]] || {
  printf 'ERROR: create the protected runtime file first: %s\n' "$RUNTIME_ENV" >&2
  exit 1
}
runtime_mode="$(stat -f '%Lp' "$RUNTIME_ENV")"
(( (8#$runtime_mode & 077) == 0 )) || {
  printf 'ERROR: runtime file must not be accessible by group or others: %s (%s)\n' "$RUNTIME_ENV" "$runtime_mode" >&2
  exit 1
}

mkdir -p "$HOME/.issuebot/logs" "$HOME/.issuebot/repos" "$HOME/Library/LaunchAgents"
cd "$PROJECT_DIR"
if [[ "$skip_build" == false ]]; then
  ./mvnw --batch-mode clean verify
else
  [[ -f "$PROJECT_DIR/target/issuebot-0.1.0-SNAPSHOT.jar" ]] || {
    printf 'ERROR: --skip-build requires an existing application jar\n' >&2
    exit 1
  }
fi
plutil -lint "$PLIST_SOURCE"
install -m 0644 "$PLIST_SOURCE" "$PLIST_TARGET"

launchctl bootout "gui/$(id -u)/$AGENT_LABEL" 2>/dev/null || true
launchctl bootstrap "gui/$(id -u)" "$PLIST_TARGET"
launchctl kickstart -k "gui/$(id -u)/$AGENT_LABEL"

for _ in $(seq 1 60); do
  if curl --fail --silent --show-error "$HEALTH_URL" >/dev/null 2>&1; then
    printf 'IssueBot is ready at http://127.0.0.1:8090\n'
    exit 0
  fi
  sleep 1
done

printf 'ERROR: IssueBot did not become ready; inspect %s\n' "$HOME/.issuebot/logs/issuebot.log" >&2
exit 1

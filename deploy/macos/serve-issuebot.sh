#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

readonly SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly DEPLOY_CONFIG="${ISSUEBOT_DEPLOY_CONFIG:-$SCRIPT_DIR/home-server.env}"

[[ -f "$DEPLOY_CONFIG" && ! -L "$DEPLOY_CONFIG" ]] || {
  printf 'ERROR: deployment configuration is missing or unsafe: %s\n' "$DEPLOY_CONFIG" >&2
  exit 1
}
# shellcheck disable=SC1090
source "$DEPLOY_CONFIG"
export ISSUEBOT_CODEX_NETWORK_ALLOWED_REPOSITORIES="${ISSUEBOT_CODEX_NETWORK_ALLOWED_REPOSITORIES:-}"

# Resolve the release link once: subsequent builds/deploys must never replace a running JVM's jar.
readonly ISSUEBOT_JAR="$(readlink "$ISSUEBOT_STATE_DIR/releases/current.jar")"
[[ "$ISSUEBOT_JAR" == "$ISSUEBOT_STATE_DIR/releases/issuebot-"*.jar ]] || {
  printf 'ERROR: install a release with deploy/macos/install-service.sh first\n' >&2
  exit 1
}

[[ -f "$ISSUEBOT_RUNTIME_ENV" && ! -L "$ISSUEBOT_RUNTIME_ENV" ]] || {
  printf 'ERROR: protected runtime environment is missing or unsafe: %s\n' "$ISSUEBOT_RUNTIME_ENV" >&2
  exit 1
}
[[ -f "$ISSUEBOT_JAR" ]] || {
  printf 'ERROR: IssueBot has not been built: %s\n' "$ISSUEBOT_JAR" >&2
  exit 1
}

set -a
# shellcheck disable=SC1090
source "$ISSUEBOT_RUNTIME_ENV"
set +a

export HOME="$ISSUEBOT_SERVICE_HOME"
export PATH="$(dirname "$ISSUEBOT_JAVA"):$ISSUEBOT_SERVICE_HOME/.local/bin:/usr/local/bin:/usr/bin:/bin"
export SPRING_PROFILES_ACTIVE=prod
export SERVER_ADDRESS=0.0.0.0
export SERVER_PORT="$ISSUEBOT_PORT"

cd "$ISSUEBOT_CHECKOUT"
exec "$ISSUEBOT_JAVA" -jar "$ISSUEBOT_JAR"

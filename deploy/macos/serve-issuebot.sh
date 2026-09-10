#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

readonly ISSUEBOT_CHECKOUT="/Users/dbbaskette/Projects/IssueBot"
readonly ISSUEBOT_RUNTIME_ENV="/Users/dbbaskette/.config/issuebot/runtime.env"
readonly ISSUEBOT_JAR="$ISSUEBOT_CHECKOUT/target/issuebot-0.1.0-SNAPSHOT.jar"

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

export HOME="/Users/dbbaskette"
export PATH="$HOME/.sdkman/candidates/java/current/bin:$HOME/.local/bin:/usr/local/bin:/usr/bin:/bin"
export SPRING_PROFILES_ACTIVE=prod

cd "$ISSUEBOT_CHECKOUT"
exec java -jar "$ISSUEBOT_JAR"

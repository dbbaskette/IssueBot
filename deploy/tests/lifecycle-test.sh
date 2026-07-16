#!/usr/bin/env bash
set -euo pipefail

cd "$(git rev-parse --show-toplevel)"
source deploy/tests/test-helper.sh
if [[ ! -f deploy/lib/lifecycle.sh ]]; then
  printf 'FAIL: deploy/lib/lifecycle.sh is absent\n' >&2
  exit 1
fi
source deploy/lib/common.sh
source deploy/lib/lifecycle.sh

eval "$(declare -f stop_native_issuebot | sed '1s/stop_native_issuebot/real_stop_native_issuebot/')"
eval "$(declare -f require_h2_closed | sed '1s/require_h2_closed/real_require_h2_closed/')"
eval "$(declare -f backup_h2 | sed '1s/backup_h2/real_backup_h2/')"
eval "$(declare -f rollback_release | sed '1s/rollback_release/real_rollback_release/')"

export CALL_LOG="$TEST_ROOT/calls"
export ISSUEBOT_HOME="$TEST_ROOT/issuebot-home"
export ISSUEBOT_CHECKOUT="$TEST_ROOT/checkout"
export DEPLOY_ENV="$TEST_ROOT/deploy.env"
export COMPOSE_PROJECT_NAME=issuebot
export CODEX_CLI_PROVIDER_IMAGE='ghcr.io/acme/codex-cli-provider@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa'
export CODEX_CLI_PROVIDER_PROTOCOL_VERSION=1
export ISSUEBOT_GIT_SHA=0123456789abcdef0123456789abcdef01234567
export BUILD_DATE=2026-07-16T12:00:00Z
mkdir -p "$ISSUEBOT_HOME/backups" "$ISSUEBOT_HOME/deployments/failed" "$ISSUEBOT_HOME/repos" "$ISSUEBOT_HOME/logs"
: >"$ISSUEBOT_HOME/issuebot.mv.db"
: >"$DEPLOY_ENV"
chmod 600 "$DEPLOY_ENV"

record() { printf '%s\n' "$1" >>"$CALL_LOG"; }
reset_calls() { : >"$CALL_LOG"; unset FAIL_AT; }
maybe_fail() { record "$1"; [[ "${FAIL_AT:-}" != "$1" ]]; }

acquire_lock() { maybe_fail lock; }
preflight_deploy() { maybe_fail preflight; }
preflight_checkout() { maybe_fail preflight; }
preflight_runner() { maybe_fail runner-contract; }
stop_native_issuebot() { maybe_fail native-stop; }
require_port_free() { maybe_fail port-free; }
require_h2_closed() { maybe_fail h2-closed; }
backup_h2() { maybe_fail backup || return 1; BACKUP_PATH="$ISSUEBOT_HOME/backups/20260716T120000Z"; export BACKUP_PATH; }
verify_liveness() { maybe_fail liveness; }
verify_readiness() { maybe_fail readiness; }
verify_functional() { maybe_fail functional-check; }
capture_diagnostics() { maybe_fail diagnostics; }
rollback_release() { maybe_fail rollback; }
write_release_manifest() { maybe_fail manifest || return 1; mkdir -p "$ISSUEBOT_HOME/deployments"; : >"$ISSUEBOT_HOME/deployments/current.manifest"; }
resolve_release_images() { ISSUEBOT_IMAGE_ID='sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb'; PROVIDER_DIGEST='sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa'; export ISSUEBOT_IMAGE_ID PROVIDER_DIGEST; }

mock_command date 'printf "2026-07-16T12:00:00Z\n"'
# Mock bodies expand their variables only when the generated command executes.
# shellcheck disable=SC2016
mock_command git '
case "$*" in
  *" fetch --prune") printf "fetch\n" >>"$CALL_LOG" ;;
  *" pull --ff-only") printf "pull\n" >>"$CALL_LOG"; test "${FAIL_AT:-}" != pull ;;
  *" rev-parse HEAD") printf "%s\n" "$ISSUEBOT_GIT_SHA" ;;
  *) exit 2 ;;
esac'
# shellcheck disable=SC2016
mock_command docker '
case "$*" in
  "compose --env-file "*" config --quiet") printf "compose-config\n" >>"$CALL_LOG" ;;
  "compose --env-file "*" build --pull issuebot") printf "build\n" >>"$CALL_LOG"; test "${FAIL_AT:-}" != build ;;
  "compose --env-file "*" pull codex-cli-provider") printf "runner-pull\n" >>"$CALL_LOG"; test "${FAIL_AT:-}" != runner-pull ;;
  "compose --env-file "*" up -d --no-build codex-cli-provider issuebot") printf "compose-up\n" >>"$CALL_LOG"; test "${FAIL_AT:-}" != compose-up ;;
  *) printf "unexpected docker call: %s\n" "$*" >&2; exit 2 ;;
esac'

reset_calls
assert_success deploy_release
assert_equals 'lock
preflight
fetch
pull
preflight
compose-config
build
runner-pull
runner-contract
native-stop
port-free
h2-closed
backup
compose-up
liveness
readiness
functional-check
manifest' "$(<"$CALL_LOG")"

for failure in pull build runner-pull runner-contract; do
  reset_calls
  export FAIL_AT="$failure"
  assert_failure deploy_release
  if grep -q '^native-stop$' "$CALL_LOG"; then
    printf 'FAIL: %s failure stopped the native service\n' "$failure" >&2
    exit 1
  fi
done

for failure in native-stop backup; do
  reset_calls
  export FAIL_AT="$failure"
  assert_failure deploy_release
  if grep -q '^compose-up$' "$CALL_LOG"; then
    printf 'FAIL: %s failure started Compose\n' "$failure" >&2
    exit 1
  fi
done

reset_calls
export FAIL_AT=readiness
assert_failure deploy_release
assert_contains $'readiness\ndiagnostics\nrollback' "$(<"$CALL_LOG")"

reset_calls
export FAIL_AT=compose-up
assert_failure deploy_release
assert_contains $'compose-up\ndiagnostics\nrollback' "$(<"$CALL_LOG")"

reset_calls
assert_success deploy_release
test -f "$ISSUEBOT_HOME/deployments/current.manifest"

unset NATIVE_SERVICE_KIND NATIVE_SERVICE_NAME
assert_failure real_stop_native_issuebot
export NATIVE_SERVICE_KIND=unknown NATIVE_SERVICE_NAME=issuebot
assert_failure real_stop_native_issuebot

mock_command lsof 'exit 1'
assert_success real_require_h2_closed
mock_command lsof 'exit 0'
assert_failure real_require_h2_closed

mock_command lsof 'exit 1'
printf 'closed h2 fixture\n' >"$ISSUEBOT_HOME/issuebot.mv.db"
rm -rf "$ISSUEBOT_HOME/backups"
real_backup_h2
test -f "$BACKUP_PATH/issuebot.mv.db"
assert_equals "$(sha256_file "$ISSUEBOT_HOME/issuebot.mv.db")" "$(sha256_file "$BACKUP_PATH/issuebot.mv.db")"
assert_contains 'database_bytes=' "$(<"$BACKUP_PATH/backup.metadata")"

old_sha=1111111111111111111111111111111111111111
old_image=sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc
old_provider='ghcr.io/acme/codex-cli-provider@sha256:dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd'
export old_sha old_image old_provider
previous="$TEST_ROOT/previous.manifest"
write_manifest "$previous" \
  "issuebot_git_sha=$old_sha" \
  "issuebot_image_id=$old_image" \
  "provider_image=$old_provider" \
  provider_digest=sha256:dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd \
  provider_protocol=1 \
  deployed_at=2026-07-15T12:00:00Z \
  "backup_path=$BACKUP_PATH" \
  compose_project=issuebot \
  schema_rollback_compatible=false
# shellcheck disable=SC2016
mock_command git '
case "$*" in
  *" cat-file -e "*) exit 0 ;;
  *" ls-tree -r $ISSUEBOT_GIT_SHA "*) printf "100644 blob new migration.sql\n" ;;
  *" ls-tree -r $old_sha "*) printf "100644 blob old migration.sql\n" ;;
  *) exit 2 ;;
esac'
# shellcheck disable=SC2016
mock_command docker '
case "$*" in
  "image inspect --format {{.Id}} issuebot:$old_sha") printf "%s\n" "$old_image" ;;
  "image inspect $old_provider") exit 0 ;;
  *) printf "%s\n" "$*" >>"$CALL_LOG"; exit 2 ;;
esac'
rm -f "$ISSUEBOT_HOME/deployments/candidate.manifest"
reset_calls
assert_failure real_rollback_release "$previous"
if grep -Eq 'compose (stop|up)' "$CALL_LOG"; then
  printf 'FAIL: schema-unsafe rollback changed Compose state\n' >&2
  exit 1
fi

printf 'lifecycle-test: PASS\n'

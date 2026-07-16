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
eval "$(declare -f stop_issuebot_for_cutover | sed '1s/stop_issuebot_for_cutover/real_stop_issuebot_for_cutover/')"
eval "$(declare -f write_release_manifest | sed '1s/write_release_manifest/real_write_release_manifest/')"
eval "$(declare -f disable_native_autostart | sed '1s/disable_native_autostart/real_disable_native_autostart/')"
eval "$(declare -f ensure_failed_candidate_stopped | sed '1s/ensure_failed_candidate_stopped/real_ensure_failed_candidate_stopped/')"
eval "$(declare -f write_native_recovery_record | sed '1s/write_native_recovery_record/real_write_native_recovery_record/')"

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
reset_calls() { : >"$CALL_LOG"; unset FAIL_AT; DEPLOY_LOCK_HELD=false; }
maybe_fail() { record "$1"; [[ "${FAIL_AT:-}" != "$1" ]]; }

acquire_lock() { maybe_fail lock; }
preflight_deploy() { maybe_fail preflight; }
preflight_checkout() { maybe_fail preflight; }
preflight_runner() { maybe_fail runner-contract; }
stop_native_issuebot() { maybe_fail native-stop; }
stop_issuebot_for_cutover() {
  if [[ "${EXISTING_COMPOSE:-false}" == true ]]; then
    maybe_fail compose-detect || return 1
    maybe_fail compose-stop || return 1
    maybe_fail process-free || return 1
  else
    write_native_recovery_record || return 1
    CUTOVER_WAS_NATIVE=true
    export CUTOVER_WAS_NATIVE
    stop_native_issuebot
  fi
}
write_native_recovery_record() { maybe_fail native-recovery; }
disable_native_autostart() { maybe_fail autostart-disable; }
ensure_failed_candidate_stopped() {
  maybe_fail cleanup-stop || return 1
  maybe_fail cleanup-port-free || return 1
  maybe_fail cleanup-process-free || return 1
  maybe_fail cleanup-h2-closed
}
require_port_free() { maybe_fail port-free; }
require_h2_closed() { maybe_fail h2-closed; }
backup_h2() { maybe_fail backup || return 1; BACKUP_PATH="$ISSUEBOT_HOME/backups/20260716T120000Z"; export BACKUP_PATH; }
verify_liveness() { maybe_fail liveness; }
verify_readiness() { maybe_fail readiness; }
verify_functional() { maybe_fail functional-check; }
capture_diagnostics() { maybe_fail diagnostics; }
rollback_release() { maybe_fail rollback || return 1; [[ -f "${1:-}" ]]; }
write_release_manifest() { maybe_fail manifest || return 1; real_write_release_manifest; }
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
native-recovery
native-stop
port-free
h2-closed
backup
compose-up
liveness
readiness
functional-check
autostart-disable
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
  if [[ "$failure" == native-stop ]] && grep -q '^backup$' "$CALL_LOG"; then
    printf 'FAIL: native-stop failure created a backup\n' >&2
    exit 1
  fi
done

reset_calls
export EXISTING_COMPOSE=true
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
compose-detect
compose-stop
process-free
port-free
h2-closed
backup
compose-up
liveness
readiness
functional-check
manifest' "$(<"$CALL_LOG")"

for failure in compose-stop process-free h2-closed; do
  reset_calls
  export FAIL_AT="$failure"
  assert_failure deploy_release
  if grep -Eq '^(backup|compose-up)$' "$CALL_LOG"; then
    printf 'FAIL: repeat-deploy %s failure allowed backup or restart\n' "$failure" >&2
    exit 1
  fi
done
unset EXISTING_COMPOSE FAIL_AT

reset_calls
export FAIL_AT=readiness
rm -f "$ISSUEBOT_HOME/deployments/current.manifest" "$ISSUEBOT_HOME/deployments/previous.manifest"
assert_failure deploy_release
assert_contains $'readiness\ndiagnostics\ncleanup-stop\ncleanup-port-free\ncleanup-process-free\ncleanup-h2-closed' "$(<"$CALL_LOG")"

reset_calls
export FAIL_AT=compose-up
assert_failure deploy_release
assert_contains $'compose-up\ndiagnostics\ncleanup-stop\ncleanup-port-free\ncleanup-process-free\ncleanup-h2-closed' "$(<"$CALL_LOG")"

for failure in functional-check autostart-disable; do
  reset_calls
  export FAIL_AT="$failure"
  rm -f "$ISSUEBOT_HOME/deployments/current.manifest" "$ISSUEBOT_HOME/deployments/previous.manifest"
  assert_failure deploy_release
  assert_contains $'diagnostics\ncleanup-stop\ncleanup-port-free\ncleanup-process-free\ncleanup-h2-closed' "$(<"$CALL_LOG")"
done

candidate_after_recovery="$ISSUEBOT_HOME/deployments/candidate.manifest"
current_for_recovery="$ISSUEBOT_HOME/deployments/current.manifest"
write_manifest "$candidate_after_recovery" issuebot_git_sha=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa deployed_at=2026-07-16T12:00:00Z
write_manifest "$current_for_recovery" issuebot_git_sha=bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb deployed_at=2026-07-15T12:00:00Z
reset_calls
recover_failed_release "$current_for_recovery"
test ! -e "$candidate_after_recovery"

reset_calls
assert_success deploy_release
test -f "$ISSUEBOT_HOME/deployments/current.manifest"

unset NATIVE_SERVICE_KIND NATIVE_SERVICE_NAME
assert_failure real_stop_native_issuebot
export NATIVE_SERVICE_KIND=unknown NATIVE_SERVICE_NAME=issuebot
assert_failure real_stop_native_issuebot

export NATIVE_SERVICE_KIND=systemd-user NATIVE_SERVICE_NAME=issuebot.service ISSUEBOT_NATIVE_PROCESS_PATTERN=issuebot-native-marker
mock_command systemctl '
case "$*" in
  "--user stop issuebot.service") exit 0 ;;
  "--user is-active --quiet issuebot.service") exit 1 ;;
  *) exit 2 ;;
esac'
mock_command pgrep 'exit 1'
assert_success real_stop_native_issuebot

export NATIVE_SERVICE_KIND=systemd-system
mock_command systemctl '
case "$*" in
  "stop issuebot.service") exit 0 ;;
  "is-active --quiet issuebot.service") exit 1 ;;
  *) exit 2 ;;
esac'
assert_success real_stop_native_issuebot

sleep 30 &
native_pid=$!
pidfile="$TEST_ROOT/native.pid"
printf '%s\n' "$native_pid" >"$pidfile"
export NATIVE_SERVICE_KIND=pidfile NATIVE_SERVICE_NAME="$pidfile"
assert_success real_stop_native_issuebot
wait "$native_pid" 2>/dev/null || true

export NATIVE_SERVICE_KIND=systemd-user NATIVE_SERVICE_NAME=issuebot.service
mock_command pgrep 'exit 0'
assert_failure real_stop_native_issuebot
mock_command pgrep 'exit 2'
assert_failure real_stop_native_issuebot
mock_command pgrep 'exit 127'
assert_failure real_stop_native_issuebot

mock_command pgrep 'exit 1'
assert_success require_no_matching_process issuebot-native-marker
mock_command pgrep 'exit 2'
assert_failure require_no_matching_process issuebot-native-marker

export COMPOSE_STOPPED_MARKER="$TEST_ROOT/compose-stopped"
rm -f "$COMPOSE_STOPPED_MARKER"
mock_command pgrep 'exit 1'
# shellcheck disable=SC2016
mock_command docker '
case "$*" in
  "compose --env-file "*" ps -q issuebot") test -e "$COMPOSE_STOPPED_MARKER" || printf "container-id\n" ;;
  "compose --env-file "*" stop -t 60 issuebot codex-cli-provider") printf "real-compose-stop\n" >>"$CALL_LOG"; : >"$COMPOSE_STOPPED_MARKER" ;;
  *) exit 2 ;;
esac'
reset_calls
assert_success real_stop_issuebot_for_cutover
assert_equals 'real-compose-stop' "$(<"$CALL_LOG")"

github_health_up='{"status":"UP","components":{"gitHub":{"status":"UP"}}}'
github_health_down='{"status":"DEGRADED","components":{"gitHub":{"status":"DOWN"}}}'
unset ALLOW_DEGRADED_GITHUB
assert_success verify_github_health "$github_health_up"
assert_failure verify_github_health "$github_health_down"
export ALLOW_DEGRADED_GITHUB=true
assert_success verify_github_health "$github_health_down"
unset ALLOW_DEGRADED_GITHUB

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

candidate="$ISSUEBOT_HOME/deployments/candidate.manifest"
write_manifest "$candidate" \
  "issuebot_git_sha=$ISSUEBOT_GIT_SHA" \
  issuebot_image_id=sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb \
  "provider_image=$CODEX_CLI_PROVIDER_IMAGE" \
  provider_digest=sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa \
  provider_protocol=1 \
  deployed_at=2026-07-16T12:00:00Z \
  "backup_path=$BACKUP_PATH" \
  compose_project=issuebot \
  schema_rollback_compatible=true
# shellcheck disable=SC2016
mock_command docker '
case "$*" in
  "image inspect --format {{.Id}} issuebot:$old_sha") printf "%s\n" "$old_image" ;;
  "image inspect $old_provider") exit 0 ;;
  "compose --env-file "*" stop issuebot codex-cli-provider") printf "compose-stop\n" >>"$CALL_LOG" ;;
  "compose --env-file "*" up -d --no-build codex-cli-provider issuebot") printf "compose-up\n" >>"$CALL_LOG" ;;
  *) printf "unexpected docker call: %s\n" "$*" >&2; exit 2 ;;
esac'
database_checksum="$(sha256_file "$ISSUEBOT_HOME/issuebot.mv.db")"
reset_calls
real_rollback_release "$previous"
assert_equals "$old_sha" "$ISSUEBOT_GIT_SHA"
assert_equals "$old_image" "$ISSUEBOT_IMAGE_ID"
assert_equals "$old_provider" "$CODEX_CLI_PROVIDER_IMAGE"
assert_equals 'lock
compose-stop
compose-up
liveness
readiness
functional-check
manifest' "$(<"$CALL_LOG")"
assert_equals "$database_checksum" "$(sha256_file "$ISSUEBOT_HOME/issuebot.mv.db")"
assert_contains "issuebot_git_sha=$old_sha" "$(read_manifest "$ISSUEBOT_HOME/deployments/current.manifest")"
assert_contains "issuebot_image_id=$old_image" "$(read_manifest "$ISSUEBOT_HOME/deployments/current.manifest")"
assert_contains "provider_image=$old_provider" "$(read_manifest "$ISSUEBOT_HOME/deployments/current.manifest")"

export NATIVE_SERVICE_KIND=systemd-user NATIVE_SERVICE_NAME=issuebot.service ISSUEBOT_NATIVE_PROCESS_PATTERN=issuebot-native-marker
mock_command systemctl '
case "$*" in
  "--user disable issuebot.service") exit 0 ;;
  "--user is-enabled issuebot.service") printf "disabled\n"; exit 1 ;;
  *) exit 2 ;;
esac'
mock_command pgrep 'exit 1'
assert_success real_disable_native_autostart

export NATIVE_SERVICE_KIND=systemd-system
mock_command systemctl '
case "$*" in
  "disable issuebot.service") exit 0 ;;
  "is-enabled issuebot.service") printf "disabled\n"; exit 1 ;;
  *) exit 2 ;;
esac'
assert_success real_disable_native_autostart

export NATIVE_SERVICE_KIND=pidfile NATIVE_SERVICE_NAME="$TEST_ROOT/issuebot.pid"
assert_failure real_disable_native_autostart

export NATIVE_SERVICE_KIND=systemd-user NATIVE_SERVICE_NAME=issuebot.service ISSUEBOT_NATIVE_PROCESS_PATTERN=issuebot-native-marker
unit_file="$TEST_ROOT/issuebot.service"
printf '[Service]\nExecStart=/usr/bin/java -jar /opt/issuebot.jar\n' >"$unit_file"
export UNIT_FILE="$unit_file"
mock_command systemctl '
case "$*" in
  "--user show --property FragmentPath --value issuebot.service") printf "%s\n" "$UNIT_FILE" ;;
  "--user is-enabled issuebot.service") printf "enabled\n" ;;
  *) exit 2 ;;
esac'
real_write_native_recovery_record
assert_contains 'recovery_type=native' "$(read_manifest "$ISSUEBOT_HOME/deployments/native-recovery.manifest")"
assert_contains 'native_service_kind=systemd-user' "$(read_manifest "$ISSUEBOT_HOME/deployments/native-recovery.manifest")"
assert_contains 'native_process_pattern_b64=' "$(read_manifest "$ISSUEBOT_HOME/deployments/native-recovery.manifest")"
assert_contains "native_unit_fragment=$unit_file" "$(read_manifest "$ISSUEBOT_HOME/deployments/native-recovery.manifest")"
assert_contains 'native_unit_sha256=' "$(read_manifest "$ISSUEBOT_HOME/deployments/native-recovery.manifest")"
assert_contains 'native_autostart_state=enabled' "$(read_manifest "$ISSUEBOT_HOME/deployments/native-recovery.manifest")"

mock_command docker '
case "$*" in
  "compose --env-file "*" stop -t 60 issuebot codex-cli-provider") printf "cleanup-stop\n" >>"$CALL_LOG" ;;
  "compose --env-file "*" rm -f issuebot codex-cli-provider") printf "cleanup-rm\n" >>"$CALL_LOG" ;;
  "compose --env-file "*" ps -q issuebot"|"compose --env-file "*" ps -q codex-cli-provider") exit 0 ;;
  *) exit 2 ;;
esac'
mock_command lsof 'exit 1'
mock_command pgrep 'exit 1'
reset_calls
assert_success real_ensure_failed_candidate_stopped
assert_equals $'cleanup-stop\ncleanup-rm\nport-free\nh2-closed' "$(<"$CALL_LOG")"

current_manifest="$ISSUEBOT_HOME/deployments/current.manifest"
previous_manifest="$ISSUEBOT_HOME/deployments/previous.manifest"
write_manifest "$current_manifest" issuebot_git_sha=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa deployed_at=2026-07-14T12:00:00Z
ISSUEBOT_GIT_SHA=bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb
ISSUEBOT_IMAGE_ID=sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb
CODEX_CLI_PROVIDER_IMAGE='ghcr.io/acme/codex-cli-provider@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa'
PROVIDER_DIGEST=sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
CODEX_CLI_PROVIDER_PROTOCOL_VERSION=1
BACKUP_PATH="$ISSUEBOT_HOME/backups/verified"
real_write_release_manifest
assert_contains 'issuebot_git_sha=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa' "$(read_manifest "$previous_manifest")"
assert_contains 'issuebot_git_sha=bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb' "$(read_manifest "$current_manifest")"

for fatal_log in \
  'org.flywaydb.core.api.FlywayException: migration failed' \
  'JdbcSQLNonTransientConnectionException: Database may be already in use' \
  'java.nio.file.AccessDeniedException: /home/issuebot/.issuebot' \
  'APPLICATION FAILED TO START' \
  'java.lang.OutOfMemoryError: Java heap space' \
  'Web server failed to start. Port 8090 was already in use'; do
  assert_success fatal_startup_log_present "$fatal_log"
done
assert_failure fatal_startup_log_present 'Started IssueBotApplication in 4.2 seconds'

printf 'lifecycle-test: PASS\n'

#!/usr/bin/env bash
set -euo pipefail

cd "$(git rev-parse --show-toplevel)"
source deploy/tests/test-helper.sh
source deploy/lib/common.sh
source deploy/lib/lifecycle.sh

acquire_lock() { printf 'lock\n' >>"$CALL_LOG"; }

export ISSUEBOT_HOME="$TEST_ROOT/issuebot-home"
export ISSUEBOT_CHECKOUT="$TEST_ROOT/checkout"
export DEPLOY_ENV="$TEST_ROOT/deploy.env"
export COMPOSE_PROJECT_NAME=issuebot
export CODEX_CLI_PROVIDER_IMAGE='ghcr.io/acme/codex-cli-provider@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa'
export CODEX_CLI_PROVIDER_PROTOCOL_VERSION=1
export CALL_LOG="$TEST_ROOT/calls"
mkdir -p "$ISSUEBOT_HOME/backups/recovery" "$ISSUEBOT_HOME/deployments" "$ISSUEBOT_HOME/repos" "$ISSUEBOT_HOME/logs"
: >"$DEPLOY_ENV"
chmod 600 "$DEPLOY_ENV"

backup="$ISSUEBOT_HOME/backups/recovery"
printf 'known-good-native-h2\n' >"$backup/issuebot.mv.db"
checksum="$(sha256_file "$backup/issuebot.mv.db")"
bytes="$(file_bytes "$backup/issuebot.mv.db")"
printf 'database_sha256=%s\ndatabase_bytes=%s\ncreated_at=20260716T120000Z\n' "$checksum" "$bytes" >"$backup/backup.metadata"
chmod 600 "$backup/backup.metadata" "$backup/issuebot.mv.db"

unit_file="$TEST_ROOT/issuebot.service"
printf '[Service]\nExecStart=/usr/bin/java -jar /opt/issuebot.jar\n' >"$unit_file"
unit_checksum="$(sha256_file "$unit_file")"
pattern='issuebot-0.1.0-SNAPSHOT.jar'
pattern_b64="$(printf '%s' "$pattern" | base64 | tr -d '\n')"
write_manifest "$backup/native-recovery.manifest" \
  recovery_type=native \
  native_service_kind=systemd-user \
  native_service_name=issuebot.service \
  "native_process_pattern_b64=$pattern_b64" \
  "native_unit_fragment=$unit_file" \
  "native_unit_sha256=$unit_checksum" \
  native_autostart_state=enabled \
  recorded_at=2026-07-16T12:00:00Z

candidate="$ISSUEBOT_HOME/deployments/candidate.manifest"
write_manifest "$candidate" \
  issuebot_git_sha=0123456789abcdef0123456789abcdef01234567 \
  issuebot_image_id=sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb \
  "provider_image=$CODEX_CLI_PROVIDER_IMAGE" \
  provider_digest=sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa \
  provider_protocol=1 \
  deployed_at=2026-07-16T12:00:00Z \
  "backup_path=$backup" \
  compose_project=issuebot \
  schema_rollback_compatible=false

printf 'failed-candidate-h2\n' >"$ISSUEBOT_HOME/issuebot.mv.db"
: >"$CALL_LOG"
export UNIT_FILE="$unit_file" NATIVE_STARTED="$TEST_ROOT/native-started" NATIVE_ENABLED="$TEST_ROOT/native-enabled"

mock_command docker '
case "$*" in
  "compose --env-file "*" stop -t 60 issuebot codex-cli-provider") printf "compose-stop\n" >>"$CALL_LOG" ;;
  "compose --env-file "*" rm -f issuebot codex-cli-provider") printf "compose-rm\n" >>"$CALL_LOG" ;;
  "compose --env-file "*" ps -q issuebot"|"compose --env-file "*" ps -q codex-cli-provider") exit 0 ;;
  *) printf "unexpected docker call: %s\n" "$*" >&2; exit 2 ;;
esac'
mock_command systemctl '
case "$*" in
  "--user show --property FragmentPath --value issuebot.service") printf "%s\n" "$UNIT_FILE" ;;
  "--user is-enabled issuebot.service") if test -e "$NATIVE_ENABLED"; then printf "enabled\n"; else printf "disabled\n"; exit 1; fi ;;
  "--user enable issuebot.service") printf "native-enable\n" >>"$CALL_LOG"; : >"$NATIVE_ENABLED" ;;
  "--user start issuebot.service") printf "native-start\n" >>"$CALL_LOG"; : >"$NATIVE_STARTED" ;;
  "--user is-active --quiet issuebot.service") test -e "$NATIVE_STARTED" ;;
  *) printf "unexpected systemctl call: %s\n" "$*" >&2; exit 2 ;;
esac'
mock_command lsof '
case "$*" in
  *"-iTCP:8090"*) if test -e "$NATIVE_STARTED"; then printf "4242\n"; else exit 1; fi ;;
  *) exit 1 ;;
esac'
mock_command pgrep 'test -e "$NATIVE_STARTED"'
mock_command ps 'printf "/usr/bin/java -jar /opt/issuebot-0.1.0-SNAPSHOT.jar\n"'

assert_success recover_native_cutover "$backup"
assert_equals "$(sha256_file "$backup/issuebot.mv.db")" "$(sha256_file "$ISSUEBOT_HOME/issuebot.mv.db")"
assert_contains 'compose-stop' "$(<"$CALL_LOG")"
assert_contains 'compose-rm' "$(<"$CALL_LOG")"
assert_contains 'native-enable' "$(<"$CALL_LOG")"
assert_contains 'native-start' "$(<"$CALL_LOG")"
find "$ISSUEBOT_HOME/deployments/failed" -type f -name 'issuebot.mv.db.failed-*' | grep -q .

rm -f "$NATIVE_STARTED" "$NATIVE_ENABLED"
printf 'corrupted-backup\n' >>"$backup/issuebot.mv.db"
: >"$CALL_LOG"
assert_failure recover_native_cutover "$backup"
assert_equals '' "$(<"$CALL_LOG")"
printf 'known-good-native-h2\n' >"$backup/issuebot.mv.db"

write_manifest "$backup/native-recovery.manifest" \
  recovery_type=native native_service_kind=pidfile native_service_name=/tmp/issuebot.pid \
  "native_process_pattern_b64=$pattern_b64" native_autostart_state=unmanaged recorded_at=2026-07-16T12:00:00Z
: >"$CALL_LOG"
assert_failure recover_native_cutover "$backup"
assert_equals '' "$(<"$CALL_LOG")"

grep -Fq 'source deploy/lib/common.sh' docs/deployment.md
grep -Fq 'source deploy/lib/lifecycle.sh' docs/deployment.md
grep -Fq 'recover_native_cutover "$backup"' docs/deployment.md

printf 'native-recovery-test: PASS\n'

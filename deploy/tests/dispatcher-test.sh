#!/usr/bin/env bash
set -euo pipefail

cd "$(git rev-parse --show-toplevel)"
# shellcheck disable=SC1091
source deploy/tests/test-helper.sh

if [[ ! -x deploy/issuebot-deploy ]]; then
  printf 'FAIL: deploy/issuebot-deploy is absent or not executable\n' >&2
  exit 1
fi

checkout="$TEST_ROOT/checkout"
config_dir="$TEST_ROOT/config"
dispatcher="$TEST_ROOT/issuebot-deploy"
call_log="$TEST_ROOT/calls"
mkdir -p "$checkout/deploy/lib" "$config_dir"
cp deploy/lib/common.sh "$checkout/deploy/lib/common.sh"

cat >"$checkout/deploy/lib/preflight.sh" <<'EOF'
#!/usr/bin/env bash
preflight_all() { printf 'preflight\n' >>"$CALL_LOG"; printf 'Authorization: Bearer ghp_abcdefghijklmnopqrstuvwxyz0123456789AB\n'; }
EOF
cat >"$checkout/deploy/lib/lifecycle.sh" <<'EOF'
#!/usr/bin/env bash
deploy_release() { printf 'deploy\n' >>"$CALL_LOG"; }
rollback_release() { printf 'rollback\n' >>"$CALL_LOG"; }
EOF

deploy_env="$config_dir/deploy.env"
printf 'ISSUEBOT_CHECKOUT=%s\nISSUEBOT_HOME=%s/state\n' "$checkout" "$TEST_ROOT" >"$deploy_env"
chmod 600 "$deploy_env"
manifest="$TEST_ROOT/state/deployments/current.manifest"
mkdir -p "$(dirname "$manifest")"
write_current_manifest() {
  printf '%s\n' \
    'issuebot_git_sha=0123456789abcdef0123456789abcdef01234567' \
    'deployed_at=2026-07-16T12:00:00Z' >"$manifest"
  chmod 600 "$manifest"
}
write_current_manifest
sed "s|/home/dbbaskette/.config/issuebot/deploy.env|$deploy_env|" deploy/issuebot-deploy >"$dispatcher"
chmod 700 "$dispatcher"

# Mock body expands variables only when the generated command executes.
# shellcheck disable=SC2016
mock_command docker '
: "${ISSUEBOT_GIT_SHA:?ISSUEBOT_GIT_SHA is required}"
: "${BUILD_DATE:?BUILD_DATE is required}"
printf "%s\n" "$*" >>"$CALL_LOG"
printf "context %s %s\n" "$ISSUEBOT_GIT_SHA" "$BUILD_DATE" >>"$CALL_LOG"
case "$*" in
  "compose --env-file "*" logs --no-color --tail "*) printf "password=supersecret\n" ;;
esac'

export CALL_LOG="$call_log"

run_dispatch() {
  env -u ISSUEBOT_GIT_SHA -u BUILD_DATE SSH_ORIGINAL_COMMAND="$1" "$dispatcher"
}

assert_dispatch() {
  local command="$1" expected="$2" output
  : >"$call_log"
  if ! output="$(run_dispatch "$command")"; then
    printf 'FAIL: expected dispatcher success: %s\n' "$command" >&2
    return 1
  fi
  assert_equals "$expected" "$(<"$call_log")"
  printf '%s' "$output"
}

output="$(assert_dispatch preflight preflight)"
assert_contains '[REDACTED]' "$output"
assert_dispatch deploy deploy >/dev/null
assert_dispatch rollback rollback >/dev/null
compose_context='context 0123456789abcdef0123456789abcdef01234567 2026-07-16T12:00:00Z'
assert_dispatch status "compose --env-file $deploy_env ps
$compose_context" >/dev/null
output="$(assert_dispatch 'logs issuebot 1' "compose --env-file $deploy_env logs --no-color --tail 1 issuebot
$compose_context")"
assert_contains 'password=[REDACTED]' "$output"
assert_dispatch 'logs codex-cli-provider 500' "compose --env-file $deploy_env logs --no-color --tail 500 codex-cli-provider
$compose_context" >/dev/null

rm -f "$manifest"
: >"$call_log"
assert_failure run_dispatch status
assert_equals '' "$(<"$call_log")"

printf 'issuebot_git_sha=0123456789abcdef0123456789abcdef01234567\n' >"$manifest"
chmod 600 "$manifest"
: >"$call_log"
assert_failure run_dispatch status
assert_equals '' "$(<"$call_log")"

printf 'deployed_at=2026-07-16T12:00:00Z\n' >"$manifest"
chmod 600 "$manifest"
: >"$call_log"
assert_failure run_dispatch status
assert_equals '' "$(<"$call_log")"

printf 'unapproved_key=value\n' >"$manifest"
chmod 600 "$manifest"
: >"$call_log"
assert_failure run_dispatch status
assert_equals '' "$(<"$call_log")"

write_current_manifest
printf 'deployed_at=not-a-timestamp\n' >>"$manifest"
: >"$call_log"
assert_failure run_dispatch 'logs issuebot 10'
assert_equals '' "$(<"$call_log")"
write_current_manifest

# The command substitution is intentionally literal attack input.
# shellcheck disable=SC2016
for command in \
  '' \
  bash \
  'deploy; id' \
  'logs issuebot 501' \
  'logs issuebot 18446744073709551617' \
  'logs issuebot 0' \
  'logs ../../etc/passwd 10' \
  'status --help' \
  'deploy $(id)' \
  'deploy extra extra'; do
  : >"$call_log"
  assert_failure run_dispatch "$command"
  assert_equals '' "$(<"$call_log")"
done

client_key="$TEST_ROOT/issuebot_deploy_ed25519"
: >"$client_key"
chmod 600 "$client_key"
# shellcheck disable=SC2016
mock_command ssh 'printf "%s\n" "$*" >>"$CALL_LOG"'
: >"$call_log"
assert_failure env \
  DEPLOY_HOST=operator@example.test \
  DEPLOY_KEY="$client_key" \
  bash deploy/deploy-remote.sh logs issuebot 18446744073709551617
assert_equals '' "$(<"$call_log")"

printf 'dispatcher-test: PASS\n'

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
sed "s|/home/dbbaskette/.config/issuebot/deploy.env|$deploy_env|" deploy/issuebot-deploy >"$dispatcher"
chmod 700 "$dispatcher"

# Mock body expands variables only when the generated command executes.
# shellcheck disable=SC2016
mock_command docker '
printf "%s\n" "$*" >>"$CALL_LOG"
case "$*" in
  "compose --env-file "*" logs --no-color --tail "*) printf "password=supersecret\n" ;;
esac'

export CALL_LOG="$call_log"

run_dispatch() {
  SSH_ORIGINAL_COMMAND="$1" "$dispatcher"
}

assert_dispatch() {
  local command="$1" expected="$2" output
  : >"$call_log"
  output="$(run_dispatch "$command")"
  assert_equals "$expected" "$(<"$call_log")"
  printf '%s' "$output"
}

output="$(assert_dispatch preflight preflight)"
assert_contains '[REDACTED]' "$output"
assert_dispatch deploy deploy >/dev/null
assert_dispatch rollback rollback >/dev/null
assert_dispatch status "compose --env-file $deploy_env ps" >/dev/null
output="$(assert_dispatch 'logs issuebot 1' "compose --env-file $deploy_env logs --no-color --tail 1 issuebot")"
assert_contains 'password=[REDACTED]' "$output"
assert_dispatch 'logs codex-cli-provider 500' "compose --env-file $deploy_env logs --no-color --tail 500 codex-cli-provider" >/dev/null

# The command substitution is intentionally literal attack input.
# shellcheck disable=SC2016
for command in \
  '' \
  bash \
  'deploy; id' \
  'logs issuebot 501' \
  'logs issuebot 0' \
  'logs ../../etc/passwd 10' \
  'status --help' \
  'deploy $(id)' \
  'deploy extra extra'; do
  : >"$call_log"
  assert_failure run_dispatch "$command"
  assert_equals '' "$(<"$call_log")"
done

printf 'dispatcher-test: PASS\n'

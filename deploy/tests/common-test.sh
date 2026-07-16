#!/usr/bin/env bash
set -euo pipefail

cd "$(git rev-parse --show-toplevel)"
source deploy/tests/test-helper.sh
if [[ ! -f deploy/lib/common.sh ]]; then
  printf 'FAIL: deploy/lib/common.sh is absent\n' >&2
  exit 1
fi
source deploy/lib/common.sh

assert_success validate_provider_image 'ghcr.io/acme/codex-cli-provider@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa'
assert_failure validate_provider_image 'ghcr.io/acme/codex-cli-provider:latest'
assert_failure validate_provider_image 'registry.example.invalid/providers/codex-cli-provider@sha256:0000000000000000000000000000000000000000000000000000000000000000'

world_readable_env="$TEST_ROOT/world-readable.env"
printf 'APP_UID=1000\n' >"$world_readable_env"
chmod 644 "$world_readable_env"
assert_failure load_deploy_env "$world_readable_env"

deploy_env="$TEST_ROOT/deploy.env"
printf 'APP_UID=1000\nISSUEBOT_BRANCH=main\n' >"$deploy_env"
chmod 600 "$deploy_env"
assert_success load_deploy_env "$deploy_env"
assert_equals '1000:main' "$(load_deploy_env "$deploy_env" && printf '%s:%s' "$APP_UID" "$ISSUEBOT_BRANCH")"

printf 'NATIVE_SERVICE_KIND=systemd-user\nNATIVE_SERVICE_NAME=issuebot.service\n' >"$deploy_env"
chmod 600 "$deploy_env"
assert_success load_deploy_env "$deploy_env"
assert_equals 'systemd-user:issuebot.service' "$(load_deploy_env "$deploy_env" && printf '%s:%s' "$NATIVE_SERVICE_KIND" "$NATIVE_SERVICE_NAME")"

malicious_env="$TEST_ROOT/malicious.env"
printf 'APP_UID=$(touch %s/pwned)\n' "$TEST_ROOT" >"$malicious_env"
chmod 600 "$malicious_env"
assert_success load_deploy_env "$malicious_env"
test ! -e "$TEST_ROOT/pwned"
assert_equals '$(touch '"$TEST_ROOT"'/pwned)' "$(load_deploy_env "$malicious_env" && printf '%s' "$APP_UID")"

assert_contains '[REDACTED]' "$(redact 'Authorization: Bearer ghp_abcdefghijklmnopqrstuvwxyz0123456789AB')"
assert_contains '[REDACTED]' "$(redact 'ANTHROPIC_API_KEY=sk-ant-api03-secretsecretsecret')"
assert_contains '[REDACTED]' "$(redact 'password=hunter2 webhook_secret=topsecret')"

lock="$TEST_ROOT/deploy.lock"
ready="$TEST_ROOT/lock-ready"
export TEST_ROOT
mock_command flock 'mkdir "$TEST_ROOT/flock-held" 2>/dev/null'
bash -c 'source deploy/lib/common.sh; acquire_lock "$1"; : >"$2"; while test ! -e "$3"; do :; done' _ "$lock" "$ready" "$TEST_ROOT/release-lock" &
holder=$!
deadline=$((SECONDS + 5))
while test ! -e "$ready"; do
  (( SECONDS < deadline )) || { printf 'FAIL: lock holder did not become ready\n' >&2; kill "$holder" 2>/dev/null || true; exit 1; }
  sleep 0.01
done
assert_failure acquire_lock "$lock"
: >"$TEST_ROOT/release-lock"
wait "$holder"

manifest="$TEST_ROOT/manifest"
mock_command mv 'printf "%s\n" "$*" >"$TEST_ROOT/mv.args"; /bin/mv "$@"'
assert_success write_manifest "$manifest" \
  issuebot_git_sha=0123456789abcdef \
  provider_protocol=1 \
  compose_project=issuebot
assert_contains '.tmp.' "$(<"$TEST_ROOT/mv.args")"
assert_equals 'issuebot_git_sha=0123456789abcdef
provider_protocol=1
compose_project=issuebot' "$(read_manifest "$manifest")"
assert_failure write_manifest "$manifest" 'not_allowed=value'
printf 'issuebot_git_sha=good\nevil=$(touch pwned)\n' >"$manifest"
chmod 600 "$manifest"
assert_failure read_manifest "$manifest"

printf 'common-test: PASS\n'

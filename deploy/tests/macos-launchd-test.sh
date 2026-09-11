#!/usr/bin/env bash
set -euo pipefail

cd "$(git rev-parse --show-toplevel)"
source deploy/macos/launchd.sh

test_root="$(mktemp -d)"
trap 'rm -rf "$test_root"' EXIT

call_count_file="$test_root/call-count"
printf '0\n' >"$call_count_file"

fake_launchctl="$test_root/launchctl"
printf '%s\n' \
  '#!/usr/bin/env bash' \
  'set -euo pipefail' \
  'count=$(<"$LAUNCHD_TEST_CALL_COUNT")' \
  'count=$((count + 1))' \
  'printf "%s\n" "$count" >"$LAUNCHD_TEST_CALL_COUNT"' \
  '(( count >= 3 )) && exit 113' \
  'exit 0' >"$fake_launchctl"
chmod 700 "$fake_launchctl"

export LAUNCHD_TEST_CALL_COUNT="$call_count_file"
export LAUNCHCTL_BIN="$fake_launchctl"
export LAUNCHD_REMOVAL_TIMEOUT_SECONDS=5
export LAUNCHD_POLL_INTERVAL_SECONDS=0

wait_for_launchd_absent gui/501 com.baskettecase.issuebot

actual_calls="$(<"$call_count_file")"
if [[ "$actual_calls" != 3 ]]; then
  printf 'FAIL: expected three launchd state checks, got %s\n' "$actual_calls" >&2
  exit 1
fi

printf 'macOS launchd tests passed\n'

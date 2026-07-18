#!/usr/bin/env bash
set -euo pipefail

TEST_ROOT="$(mktemp -d)"
export TEST_ROOT
export HOME="$TEST_ROOT/home"
export ISSUEBOT_CHECKOUT="$TEST_ROOT/checkout"
export ISSUEBOT_STATE_DIR="$TEST_ROOT/state"
export MOCK_BIN="$TEST_ROOT/mock-bin"
mkdir -p "$HOME" "$ISSUEBOT_CHECKOUT" "$ISSUEBOT_STATE_DIR" "$MOCK_BIN"
cleanup_test_fixture() {
  local rc="$1"
  trap - EXIT
  rm -rf "$TEST_ROOT"
  exit "$rc"
}
trap 'cleanup_test_fixture $?' EXIT

export PATH="$MOCK_BIN:$PATH"

mock_command() {
  local name="$1"
  shift
  {
    printf '#!/usr/bin/env bash\n'
    printf 'set -euo pipefail\n'
    printf '%s\n' "$*"
  } >"$MOCK_BIN/$name"
  chmod 700 "$MOCK_BIN/$name"
}

assert_success() {
  if ! ( "$@" ); then
    printf 'FAIL: expected success: %s\n' "$*" >&2
    return 1
  fi
}

assert_failure() {
  if ( "$@" ); then
    printf 'FAIL: expected failure: %s\n' "$*" >&2
    return 1
  fi
}

assert_contains() {
  local needle="$1" haystack="$2"
  case "$haystack" in
    *"$needle"*) ;;
    *) printf 'FAIL: expected <%s> in <%s>\n' "$needle" "$haystack" >&2; return 1 ;;
  esac
}

assert_equals() {
  local expected="$1" actual="$2"
  if [[ "$expected" != "$actual" ]]; then
    printf 'FAIL: expected <%s>, got <%s>\n' "$expected" "$actual" >&2
    return 1
  fi
}

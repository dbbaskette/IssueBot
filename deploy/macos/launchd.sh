#!/usr/bin/env bash
set -euo pipefail

wait_for_launchd_absent() {
  local domain="$1" label="$2"
  local launchctl_bin="${LAUNCHCTL_BIN:-launchctl}"
  local timeout_seconds="${LAUNCHD_REMOVAL_TIMEOUT_SECONDS:-30}"
  local poll_interval="${LAUNCHD_POLL_INTERVAL_SECONDS:-1}"
  local deadline=$((SECONDS + timeout_seconds))

  while "$launchctl_bin" print "$domain/$label" >/dev/null 2>&1; do
    if (( SECONDS >= deadline )); then
      printf 'ERROR: launchd did not finish removing %s within %s seconds\n' \
        "$label" "$timeout_seconds" >&2
      return 1
    fi
    sleep "$poll_interval"
  done
}

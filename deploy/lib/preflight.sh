#!/usr/bin/env bash
set -Eeuo pipefail

if ! declare -F die >/dev/null 2>&1; then
  # shellcheck source=deploy/lib/common.sh
  source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/common.sh"
fi

preflight_checkout() {
  local upstream counts ahead behind untracked
  [[ -n "${ISSUEBOT_CHECKOUT:-}" ]] || die 'ISSUEBOT_CHECKOUT is required' || return 1
  [[ -n "${ISSUEBOT_BRANCH:-}" ]] || die 'ISSUEBOT_BRANCH is required' || return 1

  git -C "$ISSUEBOT_CHECKOUT" symbolic-ref -q HEAD >/dev/null || die 'checkout is on a detached HEAD' || return 1
  git -C "$ISSUEBOT_CHECKOUT" diff --quiet || die 'checkout has unstaged changes' || return 1
  git -C "$ISSUEBOT_CHECKOUT" diff --cached --quiet || die 'checkout has staged changes' || return 1
  untracked="$(git -C "$ISSUEBOT_CHECKOUT" ls-files --others --exclude-standard)" || die 'cannot inspect checkout for untracked files' || return 1
  [[ -z "$untracked" ]] || die 'checkout has untracked files' || return 1
  [[ "$(git -C "$ISSUEBOT_CHECKOUT" branch --show-current)" == "$ISSUEBOT_BRANCH" ]] || die "checkout is not on expected branch: $ISSUEBOT_BRANCH" || return 1
  upstream="$(git -C "$ISSUEBOT_CHECKOUT" rev-parse --abbrev-ref '@{upstream}')" || die 'checkout branch has no upstream' || return 1
  [[ -n "$upstream" ]] || die 'checkout branch has no upstream' || return 1
  counts="$(git -C "$ISSUEBOT_CHECKOUT" rev-list --left-right --count 'HEAD...@{upstream}')" || die 'cannot compare checkout with upstream' || return 1
  read -r ahead behind <<<"$counts"
  [[ "$ahead" == 0 && "$behind" == 0 ]] || die "checkout diverges from $upstream (ahead=$ahead behind=$behind)" || return 1
}

preflight_runtime() {
  local compose_version engine_arch
  require_command docker || return 1
  require_command git || return 1
  require_command curl || return 1

  docker info >/dev/null || die 'Docker engine is unavailable' || return 1
  compose_version="$(docker compose version 2>/dev/null)" || die 'Docker Compose v2 is unavailable' || return 1
  [[ "$compose_version" =~ (^|[[:space:]])v?2\. ]] || die "Docker Compose v2 is required: $compose_version" || return 1
  engine_arch="$(docker info --format '{{.Architecture}}')" || die 'cannot determine Docker engine architecture' || return 1
  case "$engine_arch" in
    amd64|arm64) ;;
    *) die "unsupported Docker engine architecture: $engine_arch" || return 1 ;;
  esac
}

preflight_storage() {
  local available_kib pids pid command_line lsof_status
  [[ -n "${ISSUEBOT_HOME:-}" ]] || die 'ISSUEBOT_HOME is required' || return 1
  [[ -n "${ISSUEBOT_SECRET_ENV:-}" ]] || die 'ISSUEBOT_SECRET_ENV is required' || return 1

  require_command df || return 1
  require_command lsof || return 1
  require_command ps || return 1
  [[ -d "$ISSUEBOT_HOME" && -r "$ISSUEBOT_HOME" && -w "$ISSUEBOT_HOME" ]] || die "persistent IssueBot path is not readable and writable: $ISSUEBOT_HOME" || return 1
  [[ -d "$ISSUEBOT_HOME/repos" && -d "$ISSUEBOT_HOME/logs" ]] || die 'persistent repos and logs paths must already exist' || return 1

  available_kib="$(df -Pk "$ISSUEBOT_HOME" | awk 'NR == 2 { print $4 }')" || die 'cannot inspect available storage' || return 1
  [[ "$available_kib" =~ ^[0-9]+$ ]] || die 'cannot determine available storage' || return 1
  (( available_kib >= 5242880 )) || die 'less than 5 GiB is available for deployment' || return 1

  require_private_file "$ISSUEBOT_SECRET_ENV" || return 1
  if [[ -n "${DEPLOY_ENV:-}" ]]; then
    require_private_file "$DEPLOY_ENV" || return 1
  fi

  if pids="$(lsof -nP -iTCP:8090 -sTCP:LISTEN -t 2>/dev/null)"; then
    :
  else
    lsof_status=$?
    [[ "$lsof_status" == 1 ]] || die 'cannot inspect port 8090 ownership' || return 1
    pids=''
  fi
  [[ -z "$pids" ]] && return 0
  [[ -n "${ISSUEBOT_NATIVE_PROCESS_PATTERN:-}" ]] || die 'port 8090 is owned by an unidentified process' || return 1
  while IFS= read -r pid; do
    [[ "$pid" =~ ^[0-9]+$ ]] || die 'port 8090 owner could not be identified' || return 1
    command_line="$(ps -p "$pid" -o command= 2>/dev/null)" || die "cannot inspect port 8090 owner PID $pid" || return 1
    case "$command_line" in
      *"$ISSUEBOT_NATIVE_PROCESS_PATTERN"*) ;;
      *) die "port 8090 is owned by an unexpected process (PID $pid)" || return 1 ;;
    esac
  done <<<"$pids"
}

preflight_runner() {
  local image repo_digests platform expected_platform engine_arch healthcheck protocol doctor
  image="${1:-${CODEX_CLI_PROVIDER_IMAGE:-}}"
  [[ -n "$image" ]] || die 'CODEX_CLI_PROVIDER_IMAGE is required' || return 1
  validate_provider_image "$image" || return 1
  [[ -n "${CODEX_CLI_PROVIDER_PROTOCOL_VERSION:-}" ]] || die 'CODEX_CLI_PROVIDER_PROTOCOL_VERSION is required' || return 1

  docker image inspect "$image" >/dev/null 2>&1 || die 'pinned provider image is not local; pull it in the lifecycle phase before provider contract checks' || return 1
  repo_digests="$(docker image inspect --format '{{json .RepoDigests}}' "$image")" || die 'cannot inspect provider digest' || return 1
  case "$repo_digests" in
    *"$image"*) ;;
    *) die 'local provider image does not record the configured immutable digest' || return 1 ;;
  esac
  engine_arch="$(docker info --format '{{.Architecture}}')" || die 'cannot determine Docker engine architecture' || return 1
  expected_platform="linux/$engine_arch"
  platform="$(docker image inspect --format '{{.Os}}/{{.Architecture}}' "$image")" || die 'cannot inspect provider platform' || return 1
  [[ "$platform" == "$expected_platform" ]] || die "provider platform mismatch: expected $expected_platform, got $platform" || return 1

  healthcheck="$(docker image inspect --format '{{json .Config.Healthcheck.Test}}' "$image")" || die 'cannot inspect provider healthcheck' || return 1
  [[ -n "$healthcheck" && "$healthcheck" != 'null' && "$healthcheck" != '[]' ]] || die 'provider image has no embedded healthcheck' || return 1

  protocol="$(docker image inspect --format '{{index .Config.Labels "com.issuebot.codex-provider.protocol"}}' "$image")" || die 'cannot inspect provider protocol label' || return 1
  [[ "$protocol" == "$CODEX_CLI_PROVIDER_PROTOCOL_VERSION" ]] || die "provider protocol mismatch: expected $CODEX_CLI_PROVIDER_PROTOCOL_VERSION, got $protocol" || return 1

  doctor="$(docker image inspect --format '{{index .Config.Labels "com.issuebot.codex-provider.doctor"}}' "$image")" || die 'cannot inspect provider doctor contract' || return 1
  [[ "$doctor" == 'codex-cli-provider doctor --json --no-billable-work' ]] || die 'provider doctor contract is missing the exact non-billable command' || return 1
}

preflight_all() {
  preflight_checkout || return 1
  preflight_runtime || return 1
  preflight_storage || return 1
  preflight_runner || return 1
}

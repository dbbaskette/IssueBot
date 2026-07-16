#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

die() {
  printf 'ERROR: %s\n' "$(redact "$*")" >&2
  return 1
}

log() {
  printf '%s %s\n' "$(date -u '+%Y-%m-%dT%H:%M:%SZ')" "$(redact "$*")"
}

redact() {
  printf '%s\n' "$*" | sed -E \
    -e 's/(gh[pousr]_[A-Za-z0-9_]{20,})/[REDACTED]/g' \
    -e 's/(github_pat_[A-Za-z0-9_]{20,})/[REDACTED]/g' \
    -e 's/(sk-ant-[A-Za-z0-9_-]{12,})/[REDACTED]/g' \
    -e 's/([Aa]uthorization:[[:space:]]*[Bb]earer)[[:space:]]+[^[:space:]]+/\1 [REDACTED]/g' \
    -e 's/([Bb]earer)[[:space:]]+[^[:space:]]+/\1 [REDACTED]/g' \
    -e 's/([Pp][Aa][Ss][Ss][Ww][Oo][Rr][Dd][[:space:]]*[:=][[:space:]]*)[^[:space:]]+/\1[REDACTED]/g' \
    -e 's/([Ww][Ee][Bb][Hh][Oo][Oo][Kk](_[Ss][Ee][Cc][Rr][Ee][Tt])?[[:space:]]*[:=][[:space:]]*)[^[:space:]]+/\1[REDACTED]/g'
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || die "required command is unavailable: $1"
}

acquire_lock() {
  local lock_path="$1"
  require_command flock || return 1
  [[ -d "$(dirname "$lock_path")" ]] || die "lock directory does not exist: $(dirname "$lock_path")" || return 1
  exec 9>"$lock_path"
  flock -n 9 || die "another deployment operation holds $lock_path" || return 1
}

validate_provider_image() {
  local image="$1" digest
  [[ "$image" =~ ^[^[:space:]@]+@sha256:([0-9a-f]{64})$ ]] || die 'provider image must use an immutable sha256 digest' || return 1
  digest="${BASH_REMATCH[1]}"
  [[ "$digest" != '0000000000000000000000000000000000000000000000000000000000000000' ]] || die 'provider image uses the placeholder digest' || return 1
}

file_mode() {
  local path="$1" mode
  if mode="$(stat -c '%a' "$path" 2>/dev/null)"; then
    printf '%s\n' "$mode"
  else
    stat -f '%Lp' "$path"
  fi
}

require_private_file() {
  local path="$1" mode
  [[ -f "$path" && ! -L "$path" ]] || die "protected file is missing or unsafe: $path" || return 1
  mode="$(file_mode "$path")" || die "cannot inspect protected file mode: $path" || return 1
  (( (8#$mode & 077) == 0 )) || die "protected file has group/other permissions: $path ($mode)" || return 1
}

deploy_env_key_allowed() {
  case "$1" in
    COMPOSE_PROJECT_NAME|APP_UID|APP_GID|ISSUEBOT_HOME|ISSUEBOT_BIND_ADDRESS|ISSUEBOT_SECRET_ENV|ISSUEBOT_CHECKOUT|ISSUEBOT_BRANCH|ISSUEBOT_STATE_DIR|ISSUEBOT_NATIVE_PROCESS_PATTERN|CODEX_CLI_PROVIDER_IMAGE|CODEX_CLI_PROVIDER_PROTOCOL_VERSION) return 0 ;;
    *) return 1 ;;
  esac
}

load_deploy_env() {
  local path="$1" line key value
  require_private_file "$path" || return 1
  while IFS= read -r line || [[ -n "$line" ]]; do
    line="${line%$'\r'}"
    [[ -z "$line" || "$line" =~ ^[[:space:]]*# ]] && continue
    [[ "$line" =~ ^([A-Z][A-Z0-9_]*)=(.*)$ ]] || die "invalid deployment environment line in $path" || return 1
    deploy_env_key_allowed "${BASH_REMATCH[1]}" || die "deployment environment key is not allowed: ${BASH_REMATCH[1]}" || return 1
  done <"$path"
  while IFS= read -r line || [[ -n "$line" ]]; do
    line="${line%$'\r'}"
    [[ -z "$line" || "$line" =~ ^[[:space:]]*# ]] && continue
    [[ "$line" =~ ^([A-Z][A-Z0-9_]*)=(.*)$ ]] || die "invalid deployment environment line in $path" || return 1
    key="${BASH_REMATCH[1]}"
    value="${BASH_REMATCH[2]}"
    deploy_env_key_allowed "$key" || die "deployment environment key is not allowed: $key" || return 1
    printf -v "$key" '%s' "$value"
    export "${key?}"
  done <"$path"
}

manifest_key_allowed() {
  case "$1" in
    issuebot_git_sha|issuebot_image_id|provider_image|provider_digest|provider_protocol|deployed_at|backup_path|compose_project|schema_rollback_compatible) return 0 ;;
    *) return 1 ;;
  esac
}

validate_manifest_record() {
  local record="$1" key value
  [[ "$record" =~ ^([a-z][a-z0-9_]*)=(.*)$ ]] || die 'invalid manifest record' || return 1
  key="${BASH_REMATCH[1]}"
  value="${BASH_REMATCH[2]}"
  manifest_key_allowed "$key" || die "manifest key is not allowed: $key" || return 1
  [[ "$value" =~ ^[A-Za-z0-9._:/@+-]*$ ]] || die "manifest value contains unsafe characters: $key" || return 1
}

write_manifest() {
  local path="$1" record tmp
  shift
  (( $# > 0 )) || die 'manifest requires at least one record' || return 1
  for record in "$@"; do
    validate_manifest_record "$record" || return 1
  done
  [[ -d "$(dirname "$path")" ]] || die "manifest directory does not exist: $(dirname "$path")" || return 1
  tmp="$(mktemp "${path}.tmp.XXXXXX")" || die 'cannot create manifest temporary file' || return 1
  if ! printf '%s\n' "$@" >"$tmp"; then
    rm -f "$tmp"
    die 'cannot write manifest temporary file'
    return 1
  fi
  chmod 600 "$tmp"
  if ! mv -f "$tmp" "$path"; then
    rm -f "$tmp"
    die 'cannot atomically replace manifest'
    return 1
  fi
}

read_manifest() {
  local path="$1" record
  local -a records=()
  require_private_file "$path" || return 1
  while IFS= read -r record || [[ -n "$record" ]]; do
    validate_manifest_record "$record" || return 1
    records[${#records[@]}]="$record"
  done <"$path"
  (( ${#records[@]} > 0 )) || die 'manifest is empty' || return 1
  printf '%s\n' "${records[@]}"
}

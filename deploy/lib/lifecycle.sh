#!/usr/bin/env bash
set -Eeuo pipefail

LIFECYCLE_LIB_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
if ! declare -F die >/dev/null 2>&1; then
  # shellcheck source=deploy/lib/common.sh
  source "$LIFECYCLE_LIB_DIR/common.sh"
fi
if ! declare -F preflight_all >/dev/null 2>&1; then
  # shellcheck source=deploy/lib/preflight.sh
  source "$LIFECYCLE_LIB_DIR/preflight.sh"
fi

compose() {
  docker compose --env-file "$DEPLOY_ENV" "$@"
}

DEPLOY_LOCK_HELD=false

ensure_deployment_lock() {
  if [[ "$DEPLOY_LOCK_HELD" != true ]]; then
    acquire_lock "$ISSUEBOT_HOME/deployments/deploy.lock" || return 1
    DEPLOY_LOCK_HELD=true
  fi
}

preflight_deploy() {
  preflight_checkout || return 1
  preflight_runtime || return 1
  preflight_storage || return 1
}

sha256_file() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print $1}'
  else
    shasum -a 256 "$1" | awk '{print $1}'
  fi
}

file_bytes() {
  if stat -c '%s' "$1" >/dev/null 2>&1; then
    stat -c '%s' "$1"
  else
    stat -f '%z' "$1"
  fi
}

require_port_free() {
  local status
  if lsof -nP -iTCP:8090 -sTCP:LISTEN >/dev/null 2>&1; then
    die 'port 8090 is still owned after native IssueBot shutdown'
    return 1
  else
    status=$?
  fi
  [[ "$status" == 1 ]] || die 'cannot prove port 8090 is free' || return 1
}

require_h2_closed() {
  local database="${ISSUEBOT_HOME:?}/issuebot.mv.db" status
  [[ -f "$database" ]] || die "H2 database is missing: $database" || return 1
  if command -v lsof >/dev/null 2>&1; then
    if lsof "$database" >/dev/null 2>&1; then
      die 'H2 database is still open'
      return 1
    else
      status=$?
    fi
    [[ "$status" == 1 ]] || die 'cannot prove H2 database is closed with lsof' || return 1
  elif command -v fuser >/dev/null 2>&1; then
    if fuser "$database" >/dev/null 2>&1; then
      die 'H2 database is still open'
      return 1
    else
      status=$?
    fi
    [[ "$status" == 1 ]] || die 'cannot prove H2 database is closed with fuser' || return 1
  else
    die 'lsof or fuser is required to prove H2 is closed'
    return 1
  fi
}

wait_for_pid_exit() {
  local pid="$1" deadline=$((SECONDS + 60))
  while kill -0 "$pid" 2>/dev/null; do
    (( SECONDS < deadline )) || die "native IssueBot PID $pid did not stop within 60 seconds" || return 1
    sleep 1
  done
}

wait_for_systemd_inactive() {
  local scope="$1" name="$2" deadline=$((SECONDS + 60))
  while true; do
    if [[ "$scope" == user ]]; then
      systemctl --user is-active --quiet "$name" || return 0
    else
      systemctl is-active --quiet "$name" || return 0
    fi
    (( SECONDS < deadline )) || die "native IssueBot service did not stop within 60 seconds: $name" || return 1
    sleep 1
  done
}

require_no_matching_process() {
  local pattern="$1" status
  [[ -n "$pattern" ]] || die 'IssueBot process pattern is required' || return 1
  if pgrep -f -- "$pattern" >/dev/null 2>&1; then
    die 'matching IssueBot process remains'
    return 1
  else
    status=$?
  fi
  [[ "$status" == 1 ]] || die 'cannot inspect IssueBot process ownership with pgrep' || return 1
}

stop_native_issuebot() {
  local kind="${NATIVE_SERVICE_KIND:-}" name="${NATIVE_SERVICE_NAME:-}" pid
  [[ -n "$kind" && -n "$name" ]] || die 'protected native service ownership is required' || return 1
  case "$kind" in
    systemd-user)
      systemctl --user stop "$name" || die "cannot stop user service: $name" || return 1
      wait_for_systemd_inactive user "$name" || return 1
      ;;
    systemd-system)
      systemctl stop "$name" || die "cannot stop system service: $name" || return 1
      wait_for_systemd_inactive system "$name" || return 1
      ;;
    pidfile)
      [[ -f "$name" && ! -L "$name" ]] || die "native service pidfile is missing or unsafe: $name" || return 1
      IFS= read -r pid <"$name"
      [[ "$pid" =~ ^[0-9]+$ ]] || die 'native service pidfile is invalid' || return 1
      kill -TERM "$pid" || die "cannot stop native IssueBot PID $pid" || return 1
      wait_for_pid_exit "$pid" || return 1
      ;;
    *) die "unknown native service ownership kind: $kind" || return 1 ;;
  esac

  [[ -n "${ISSUEBOT_NATIVE_PROCESS_PATTERN:-}" ]] || die 'native IssueBot process pattern is required' || return 1
  require_no_matching_process "$ISSUEBOT_NATIVE_PROCESS_PATTERN" || return 1
}

write_native_recovery_record() {
  local manifest_dir="$ISSUEBOT_HOME/deployments" recorded_at pattern_b64 fragment='' unit_checksum='' autostart_state=unmanaged
  [[ -n "${NATIVE_SERVICE_KIND:-}" && -n "${NATIVE_SERVICE_NAME:-}" ]] || die 'native recovery identity is incomplete' || return 1
  [[ -n "${ISSUEBOT_NATIVE_PROCESS_PATTERN:-}" ]] || die 'native recovery process pattern is required' || return 1
  pattern_b64="$(printf '%s' "$ISSUEBOT_NATIVE_PROCESS_PATTERN" | base64 | tr -d '\n')" || die 'cannot encode native recovery process pattern' || return 1
  [[ -n "$pattern_b64" ]] || die 'cannot encode empty native recovery process pattern' || return 1
  case "$NATIVE_SERVICE_KIND" in
    systemd-user)
      fragment="$(systemctl --user show --property FragmentPath --value "$NATIVE_SERVICE_NAME")" || die 'cannot inspect native user unit fragment' || return 1
      if autostart_state="$(systemctl --user is-enabled "$NATIVE_SERVICE_NAME" 2>/dev/null)"; then :; else :; fi
      ;;
    systemd-system)
      fragment="$(systemctl show --property FragmentPath --value "$NATIVE_SERVICE_NAME")" || die 'cannot inspect native system unit fragment' || return 1
      if autostart_state="$(systemctl is-enabled "$NATIVE_SERVICE_NAME" 2>/dev/null)"; then :; else :; fi
      ;;
    pidfile) ;;
    *) die "unknown native service ownership kind: $NATIVE_SERVICE_KIND" || return 1 ;;
  esac
  if [[ "$NATIVE_SERVICE_KIND" == systemd-user || "$NATIVE_SERVICE_KIND" == systemd-system ]]; then
    [[ "$fragment" == /* && -f "$fragment" && ! -L "$fragment" ]] || die 'native systemd unit fragment is missing or unsafe' || return 1
    unit_checksum="$(sha256_file "$fragment")" || die 'cannot checksum native systemd unit fragment' || return 1
    [[ "$autostart_state" == enabled || "$autostart_state" == disabled || "$autostart_state" == masked || "$autostart_state" == static ]] || die 'native systemd autostart state is unsupported or ambiguous' || return 1
  fi
  recorded_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)" || die 'cannot create native recovery timestamp' || return 1
  local -a records=(
    recovery_type=native \
    "native_service_kind=$NATIVE_SERVICE_KIND" \
    "native_service_name=$NATIVE_SERVICE_NAME" \
    "native_process_pattern_b64=$pattern_b64" \
    "native_autostart_state=$autostart_state" \
    "recorded_at=$recorded_at"
  )
  if [[ -n "$fragment" ]]; then
    records+=("native_unit_fragment=$fragment" "native_unit_sha256=$unit_checksum")
  fi
  write_manifest "$manifest_dir/native-recovery.manifest" "${records[@]}"
}

disable_native_autostart() {
  local kind="${NATIVE_SERVICE_KIND:-}" name="${NATIVE_SERVICE_NAME:-}" state status
  case "$kind" in
    systemd-user)
      systemctl --user disable "$name" || die "cannot disable user service autostart: $name" || return 1
      if state="$(systemctl --user is-enabled "$name" 2>/dev/null)"; then status=0; else status=$?; fi
      ;;
    systemd-system)
      systemctl disable "$name" || die "cannot disable system service autostart: $name" || return 1
      if state="$(systemctl is-enabled "$name" 2>/dev/null)"; then status=0; else status=$?; fi
      ;;
    pidfile)
      die 'pidfile ownership cannot prove native autostart is disabled; configure a supported systemd service before first-cutover success'
      return 1
      ;;
    *) die "unknown native service ownership kind: $kind" || return 1 ;;
  esac
  [[ "$status" != 0 && ( "$state" == disabled || "$state" == masked ) ]] || die "native service autostart remains enabled or unverifiable: $name" || return 1
  require_no_matching_process "$ISSUEBOT_NATIVE_PROCESS_PATTERN" || return 1
}

stop_issuebot_for_cutover() {
  local container stopped
  container="$(compose ps -q issuebot)" || die 'cannot inspect existing Compose IssueBot ownership' || return 1
  if [[ -z "$container" ]]; then
    write_native_recovery_record || return 1
    CUTOVER_WAS_NATIVE=true
    export CUTOVER_WAS_NATIVE
    stop_native_issuebot
    return
  fi

  CUTOVER_WAS_NATIVE=false
  export CUTOVER_WAS_NATIVE

  compose stop -t 60 issuebot codex-cli-provider || die 'cannot gracefully stop existing Compose release' || return 1
  stopped="$(compose ps -q issuebot)" || die 'cannot verify existing Compose IssueBot shutdown' || return 1
  [[ -z "$stopped" ]] || die 'existing Compose IssueBot remains running after shutdown' || return 1
  [[ -n "${ISSUEBOT_NATIVE_PROCESS_PATTERN:-}" ]] || die 'IssueBot process pattern is required after Compose shutdown' || return 1
  require_no_matching_process "$ISSUEBOT_NATIVE_PROCESS_PATTERN" || return 1
}

backup_h2() {
  local database="${ISSUEBOT_HOME:?}/issuebot.mv.db" timestamp backup_dir backup_file checksum copied_checksum bytes metadata
  require_h2_closed || return 1
  timestamp="$(date -u +%Y%m%dT%H%M%SZ)" || die 'cannot create backup timestamp' || return 1
  backup_dir="$ISSUEBOT_HOME/backups/$timestamp"
  backup_file="$backup_dir/issuebot.mv.db"
  mkdir -p "$backup_dir" || die "cannot create backup directory: $backup_dir" || return 1
  chmod 700 "$ISSUEBOT_HOME/backups" "$backup_dir"
  cp -p "$database" "$backup_file" || die 'cannot copy closed H2 database' || return 1
  chmod 600 "$backup_file"
  checksum="$(sha256_file "$database")" || die 'cannot checksum H2 database' || return 1
  copied_checksum="$(sha256_file "$backup_file")" || die 'cannot checksum H2 backup' || return 1
  [[ "$checksum" == "$copied_checksum" ]] || die 'H2 backup checksum verification failed' || return 1
  bytes="$(file_bytes "$backup_file")" || die 'cannot determine H2 backup size' || return 1
  [[ "$bytes" =~ ^[0-9]+$ ]] || die 'invalid H2 backup size' || return 1
  metadata="$backup_dir/backup.metadata"
  printf 'database_sha256=%s\ndatabase_bytes=%s\ncreated_at=%s\n' "$checksum" "$bytes" "$timestamp" >"$metadata"
  chmod 600 "$metadata"
  if [[ -f "$ISSUEBOT_HOME/deployments/current.manifest" ]]; then
    require_private_file "$ISSUEBOT_HOME/deployments/current.manifest" || return 1
    cp -p "$ISSUEBOT_HOME/deployments/current.manifest" "$backup_dir/previous.manifest" || die 'cannot record current deployment manifest with backup' || return 1
    chmod 600 "$backup_dir/previous.manifest"
  elif [[ -f "$ISSUEBOT_HOME/deployments/native-recovery.manifest" ]]; then
    copy_manifest "$ISSUEBOT_HOME/deployments/native-recovery.manifest" "$backup_dir/native-recovery.manifest" || return 1
  fi
  BACKUP_PATH="$backup_dir"
  export BACKUP_PATH
}

resolve_release_images() {
  local repo_digests
  ISSUEBOT_IMAGE_ID="$(docker image inspect --format '{{.Id}}' "issuebot:$ISSUEBOT_GIT_SHA")" || die 'cannot resolve built IssueBot image ID' || return 1
  PROVIDER_DIGEST="${CODEX_CLI_PROVIDER_IMAGE##*@}"
  [[ "$ISSUEBOT_IMAGE_ID" =~ ^sha256:[0-9a-f]{64}$ ]] || die 'built IssueBot image ID is invalid' || return 1
  [[ "$PROVIDER_DIGEST" =~ ^sha256:[0-9a-f]{64}$ ]] || die 'provider digest is invalid' || return 1
  repo_digests="$(docker image inspect --format '{{json .RepoDigests}}' "$CODEX_CLI_PROVIDER_IMAGE")" || die 'cannot resolve provider image digest' || return 1
  case "$repo_digests" in
    *"$CODEX_CLI_PROVIDER_IMAGE"*) ;;
    *) die 'Docker inspection does not contain the configured provider digest' || return 1 ;;
  esac
  export ISSUEBOT_IMAGE_ID PROVIDER_DIGEST
}

poll_endpoint() {
  local path="$1" deadline=$((SECONDS + 120))
  until curl --fail --silent --show-error "http://${ISSUEBOT_BIND_ADDRESS:-127.0.0.1}:8090$path" >/dev/null; do
    (( SECONDS < deadline )) || die "endpoint did not become healthy within 120 seconds: $path" || return 1
    sleep 2
  done
}

verify_liveness() {
  poll_endpoint /actuator/health/liveness
}

verify_readiness() {
  poll_endpoint /actuator/health/readiness
}

container_is_healthy() {
  local service="$1" container status
  container="$(compose ps -q "$service")" || return 1
  [[ -n "$container" ]] || return 1
  status="$(docker inspect --format '{{.State.Health.Status}}' "$container")" || return 1
  [[ "$status" == healthy ]]
}

verify_github_health() {
  local health="$1" github_status
  github_status="$(printf '%s\n' "$health" | tr -d '\n' | sed -E 's/.*"gitHub"[[:space:]]*:[[:space:]]*\{[^}]*"status"[[:space:]]*:[[:space:]]*"([^"]+)".*/\1/')"
  case "$github_status" in
    UP) return 0 ;;
    DOWN)
      [[ "${ALLOW_DEGRADED_GITHUB:-false}" == true ]] && return 0
      die 'GitHub actuator component is DOWN'
      return 1
      ;;
    *) die 'cannot determine case-correct gitHub actuator component status' || return 1 ;;
  esac
}

fatal_startup_log_present() {
  printf '%s\n' "$1" | grep -Eiq \
    'flyway.*(error|exception|failed)|migration.*(error|exception|failed)|h2.*(error|exception|failed)|database.*(already in use|locked)|accessdeniedexception|permission denied|application (run )?failed|failed to start|outofmemoryerror|exception in thread|port 8090.*already in use'
}

verify_functional() {
  local revision protocol doctor dashboard_status auth_enabled health logs listener_pids pid command_line
  container_is_healthy issuebot || die 'IssueBot container is not healthy' || return 1
  container_is_healthy codex-cli-provider || die 'provider container is not healthy' || return 1
  revision="$(docker image inspect --format '{{index .Config.Labels "org.opencontainers.image.revision"}}' "issuebot:$ISSUEBOT_GIT_SHA")" || die 'cannot inspect IssueBot revision label' || return 1
  [[ "$revision" == "$ISSUEBOT_GIT_SHA" ]] || die "IssueBot revision mismatch: expected $ISSUEBOT_GIT_SHA, got $revision" || return 1

  compose exec -T issuebot sh -c 'test -w /home/issuebot/.issuebot && test -w /home/issuebot/.issuebot/repos && test -w /home/issuebot/.issuebot/logs' || die 'persistent config/repos/log paths are not writable' || return 1
  dashboard_status="$(curl --silent --output /dev/null --write-out '%{http_code}' "http://${ISSUEBOT_BIND_ADDRESS:-127.0.0.1}:8090/")" || die 'cannot verify dashboard authentication response' || return 1
  # Expansion is intentionally performed inside the container shell.
  # shellcheck disable=SC2016
  auth_enabled="$(compose exec -T issuebot sh -c 'test -n "${ISSUEBOT_USERNAME:-}" && printf true || printf false')" || die 'cannot inspect dashboard authentication configuration' || return 1
  if [[ "$auth_enabled" == true ]]; then
    [[ "$dashboard_status" == 401 || "$dashboard_status" == 302 ]] || die "dashboard did not enforce authentication: HTTP $dashboard_status" || return 1
  else
    [[ "$dashboard_status" == 200 ]] || die "dashboard returned unexpected status: HTTP $dashboard_status" || return 1
  fi

  health="$(curl --fail --silent --show-error "http://${ISSUEBOT_BIND_ADDRESS:-127.0.0.1}:8090/actuator/health")" || die 'cannot read aggregate health' || return 1
  verify_github_health "$health" || return 1
  protocol="$(docker image inspect --format '{{index .Config.Labels "com.issuebot.codex-provider.protocol"}}' "$CODEX_CLI_PROVIDER_IMAGE")" || die 'cannot inspect provider protocol' || return 1
  [[ "$protocol" == "$CODEX_CLI_PROVIDER_PROTOCOL_VERSION" ]] || die 'provider protocol changed after startup' || return 1
  doctor="$(compose exec -T codex-cli-provider codex-cli-provider doctor --json --no-billable-work)" || die 'provider non-billable doctor failed' || return 1
  [[ "$doctor" == *'{'*'}'* ]] || die 'provider doctor did not return JSON' || return 1
  if [[ -n "${ISSUEBOT_NATIVE_PROCESS_PATTERN:-}" ]]; then
    require_no_matching_process "$ISSUEBOT_NATIVE_PROCESS_PATTERN" || return 1
  fi
  listener_pids="$(lsof -nP -iTCP:8090 -sTCP:LISTEN -t 2>/dev/null)" || die 'Compose did not create a port 8090 listener' || return 1
  while IFS= read -r pid; do
    command_line="$(ps -p "$pid" -o command= 2>/dev/null)" || die "cannot inspect port owner PID $pid" || return 1
    case "$command_line" in
      *docker*|*Docker*) ;;
      *) die "port 8090 has an unexpected owner (PID $pid)" || return 1 ;;
    esac
  done <<<"$listener_pids"
  logs="$(compose logs --no-color issuebot 2>&1)" || die 'cannot inspect IssueBot startup logs' || return 1
  if fatal_startup_log_present "$logs"; then
    printf '%s\n' "$logs" | redact >&2
    die 'fatal startup pattern found in IssueBot logs'
    return 1
  fi
}

verify_release() {
  verify_liveness || return 1
  verify_readiness || return 1
  verify_functional || return 1
}

write_release_manifest() {
  local rotate_previous="${1:-true}" manifest_dir="$ISSUEBOT_HOME/deployments" deployed_at compatible current previous
  mkdir -p "$manifest_dir" || die 'cannot create deployments directory' || return 1
  chmod 700 "$manifest_dir"
  deployed_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)" || return 1
  compatible="${SCHEMA_ROLLBACK_COMPATIBLE:-false}"
  [[ "$compatible" == true || "$compatible" == false ]] || die 'schema rollback compatibility must be true or false' || return 1
  current="$manifest_dir/current.manifest"
  previous="$manifest_dir/previous.manifest"
  if [[ "$rotate_previous" == true && -f "$current" ]]; then
    copy_manifest "$current" "$previous" || return 1
  fi
  write_manifest "$current" \
    "issuebot_git_sha=$ISSUEBOT_GIT_SHA" \
    "issuebot_image_id=$ISSUEBOT_IMAGE_ID" \
    "provider_image=$CODEX_CLI_PROVIDER_IMAGE" \
    "provider_digest=$PROVIDER_DIGEST" \
    "provider_protocol=$CODEX_CLI_PROVIDER_PROTOCOL_VERSION" \
    "deployed_at=$deployed_at" \
    "backup_path=$BACKUP_PATH" \
    "compose_project=${COMPOSE_PROJECT_NAME:-issuebot}" \
    "schema_rollback_compatible=$compatible"
}

write_candidate_manifest() {
  local manifest_dir="$ISSUEBOT_HOME/deployments" deployed_at compatible
  manifest_dir="$ISSUEBOT_HOME/deployments"
  mkdir -p "$manifest_dir" || die 'cannot create deployments directory' || return 1
  chmod 700 "$manifest_dir"
  deployed_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)" || return 1
  compatible="${SCHEMA_ROLLBACK_COMPATIBLE:-false}"
  [[ "$compatible" == true || "$compatible" == false ]] || die 'schema rollback compatibility must be true or false' || return 1
  write_manifest "$manifest_dir/candidate.manifest" \
    "issuebot_git_sha=$ISSUEBOT_GIT_SHA" \
    "issuebot_image_id=$ISSUEBOT_IMAGE_ID" \
    "provider_image=$CODEX_CLI_PROVIDER_IMAGE" \
    "provider_digest=$PROVIDER_DIGEST" \
    "provider_protocol=$CODEX_CLI_PROVIDER_PROTOCOL_VERSION" \
    "deployed_at=$deployed_at" \
    "backup_path=$BACKUP_PATH" \
    "compose_project=${COMPOSE_PROJECT_NAME:-issuebot}" \
    "schema_rollback_compatible=$compatible"
}

capture_diagnostics() {
  local timestamp failed_dir
  timestamp="$(date -u +%Y%m%dT%H%M%SZ)" || timestamp=unknown
  failed_dir="$ISSUEBOT_HOME/deployments/failed/$timestamp"
  mkdir -p "$failed_dir" || return 1
  chmod 700 "$ISSUEBOT_HOME/deployments/failed" "$failed_dir"
  compose ps --all >"$failed_dir/compose-ps.txt" 2>&1 || true
  compose logs --no-color >"$failed_dir/compose-logs.txt" 2>&1 || true
  redact "$(<"$failed_dir/compose-logs.txt")" >"$failed_dir/compose-logs.sanitized.txt"
  rm -f "$failed_dir/compose-logs.txt"
  if [[ -f "$ISSUEBOT_HOME/deployments/current.manifest" ]]; then
    cp -p "$ISSUEBOT_HOME/deployments/current.manifest" "$failed_dir/previous.manifest" || true
  fi
  FAILED_DIAGNOSTICS_PATH="$failed_dir"
  export FAILED_DIAGNOSTICS_PATH
}

recover_failed_release() {
  local previous_manifest="$1"
  capture_diagnostics || true
  if [[ -f "$previous_manifest" ]] && rollback_release "$previous_manifest"; then
    rm -f "$ISSUEBOT_HOME/deployments/candidate.manifest"
    return 0
  fi
  if ! ensure_failed_candidate_stopped; then
    printf 'CRITICAL: failed candidate could not be proven stopped and closed. Database was not restored. Verified backup: %s\n' "${BACKUP_PATH:-unknown}" >&2
    return 1
  fi
  printf 'Automatic recovery target was unavailable or failed; candidate is stopped and H2 is verified closed. Database was not restored. Verified backup: %s\n' "${BACKUP_PATH:-unknown}" >&2
}

ensure_failed_candidate_stopped() {
  local issuebot_container provider_container
  compose stop -t 60 issuebot codex-cli-provider || die 'cannot stop failed Compose candidate' || return 1
  compose rm -f issuebot codex-cli-provider || die 'cannot remove failed Compose candidate' || return 1
  issuebot_container="$(compose ps -q issuebot)" || die 'cannot verify failed IssueBot candidate removal' || return 1
  provider_container="$(compose ps -q codex-cli-provider)" || die 'cannot verify failed provider candidate removal' || return 1
  [[ -z "$issuebot_container" && -z "$provider_container" ]] || die 'failed Compose candidate remains after cleanup' || return 1
  require_port_free || return 1
  if [[ -n "${ISSUEBOT_NATIVE_PROCESS_PATTERN:-}" ]]; then
    require_no_matching_process "$ISSUEBOT_NATIVE_PROCESS_PATTERN" || return 1
  fi
  require_h2_closed || return 1
}

decode_base64_value() {
  local value="$1"
  if printf '%s' "$value" | base64 --decode 2>/dev/null; then
    return 0
  fi
  printf '%s' "$value" | base64 -D 2>/dev/null
}

load_native_recovery_record() {
  local path="$1" data record key value
  local recovery_type_count=0 kind_count=0 name_count=0 pattern_count=0 fragment_count=0 checksum_count=0 autostart_count=0 recorded_count=0
  NATIVE_RECOVERY_TYPE=''
  NATIVE_RECOVERY_KIND=''
  NATIVE_RECOVERY_NAME=''
  NATIVE_RECOVERY_PATTERN_B64=''
  NATIVE_RECOVERY_FRAGMENT=''
  NATIVE_RECOVERY_UNIT_SHA256=''
  NATIVE_RECOVERY_AUTOSTART=''
  NATIVE_RECOVERY_RECORDED_AT=''
  data="$(read_manifest "$path")" || return 1
  while IFS= read -r record; do
    key="${record%%=*}"
    value="${record#*=}"
    case "$key" in
      recovery_type) (( ++recovery_type_count )); NATIVE_RECOVERY_TYPE="$value" ;;
      native_service_kind) (( ++kind_count )); NATIVE_RECOVERY_KIND="$value" ;;
      native_service_name) (( ++name_count )); NATIVE_RECOVERY_NAME="$value" ;;
      native_process_pattern_b64) (( ++pattern_count )); NATIVE_RECOVERY_PATTERN_B64="$value" ;;
      native_unit_fragment) (( ++fragment_count )); NATIVE_RECOVERY_FRAGMENT="$value" ;;
      native_unit_sha256) (( ++checksum_count )); NATIVE_RECOVERY_UNIT_SHA256="$value" ;;
      native_autostart_state) (( ++autostart_count )); NATIVE_RECOVERY_AUTOSTART="$value" ;;
      recorded_at) (( ++recorded_count )); NATIVE_RECOVERY_RECORDED_AT="$value" ;;
      *) die "native recovery manifest contains an unexpected key: $key" || return 1 ;;
    esac
  done <<<"$data"
  (( recovery_type_count == 1 && kind_count == 1 && name_count == 1 && pattern_count == 1 && autostart_count == 1 && recorded_count == 1 )) || die 'native recovery manifest has missing or duplicate identity fields' || return 1
  [[ "$NATIVE_RECOVERY_TYPE" == native ]] || die 'native recovery manifest has the wrong recovery type' || return 1
  [[ "$NATIVE_RECOVERY_RECORDED_AT" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$ ]] || die 'native recovery manifest has an invalid timestamp' || return 1
  case "$NATIVE_RECOVERY_KIND" in
    systemd-user|systemd-system)
      (( fragment_count == 1 && checksum_count == 1 )) || die 'native systemd recovery identity is incomplete or duplicated' || return 1
      [[ "$NATIVE_RECOVERY_FRAGMENT" == /* ]] || die 'native systemd unit fragment path is invalid' || return 1
      [[ "$NATIVE_RECOVERY_UNIT_SHA256" =~ ^[0-9a-f]{64}$ ]] || die 'native systemd unit checksum is invalid' || return 1
      [[ "$NATIVE_RECOVERY_AUTOSTART" == enabled || "$NATIVE_RECOVERY_AUTOSTART" == disabled || "$NATIVE_RECOVERY_AUTOSTART" == masked || "$NATIVE_RECOVERY_AUTOSTART" == static ]] || die 'native recovery autostart state is invalid' || return 1
      ;;
    pidfile)
      die 'pidfile recovery is unsupported because unit identity and autostart cannot be proven'
      return 1
      ;;
    *) die 'native recovery service kind is unsupported' || return 1 ;;
  esac
}

load_backup_metadata() {
  local path="$1" line key value checksum_count=0 bytes_count=0 created_count=0
  RECOVERY_DATABASE_SHA256=''
  RECOVERY_DATABASE_BYTES=''
  require_private_file "$path" || return 1
  while IFS= read -r line || [[ -n "$line" ]]; do
    [[ "$line" =~ ^([a-z0-9_]+)=([A-Za-z0-9:-]+)$ ]] || die 'backup metadata contains an invalid record' || return 1
    key="${BASH_REMATCH[1]}"
    value="${BASH_REMATCH[2]}"
    case "$key" in
      database_sha256) (( ++checksum_count )); RECOVERY_DATABASE_SHA256="$value" ;;
      database_bytes) (( ++bytes_count )); RECOVERY_DATABASE_BYTES="$value" ;;
      created_at) (( ++created_count )) ;;
      *) die "backup metadata contains an unexpected key: $key" || return 1 ;;
    esac
  done <"$path"
  (( checksum_count == 1 && bytes_count == 1 && created_count == 1 )) || die 'backup metadata has missing or duplicate records' || return 1
  [[ "$RECOVERY_DATABASE_SHA256" =~ ^[0-9a-f]{64}$ && "$RECOVERY_DATABASE_BYTES" =~ ^[0-9]+$ ]] || die 'backup metadata checksum or byte count is invalid' || return 1
}

native_systemctl() {
  case "$NATIVE_SERVICE_KIND" in
    systemd-user) systemctl --user "$@" ;;
    systemd-system) systemctl "$@" ;;
    *) return 1 ;;
  esac
}

wait_for_native_active() {
  local deadline=$((SECONDS + 60))
  while ! native_systemctl is-active --quiet "$NATIVE_SERVICE_NAME"; do
    (( SECONDS < deadline )) || die "restored native service did not become active within 60 seconds: $NATIVE_SERVICE_NAME" || return 1
    sleep 1
  done
}

require_native_port_owner() {
  local pids pid command_line
  pids="$(lsof -nP -iTCP:8090 -sTCP:LISTEN -t 2>/dev/null)" || die 'restored native service did not create the port 8090 listener' || return 1
  [[ -n "$pids" ]] || die 'restored native service has no port 8090 listener' || return 1
  while IFS= read -r pid; do
    [[ "$pid" =~ ^[0-9]+$ ]] || die 'restored native port owner could not be identified' || return 1
    command_line="$(ps -p "$pid" -o command= 2>/dev/null)" || die "cannot inspect restored native port owner PID $pid" || return 1
    case "$command_line" in
      *"$ISSUEBOT_NATIVE_PROCESS_PATTERN"*) ;;
      *) die "restored port 8090 has an unexpected owner (PID $pid)" || return 1 ;;
    esac
  done <<<"$pids"
}

recover_native_cutover() {
  local backup="$1" backups_root backup_real database backup_database metadata recovery_manifest context_manifest
  local pattern fragment checksum bytes timestamp failed_dir restore_tmp current_autostart
  [[ -d "$backup" && ! -L "$backup" ]] || die 'native recovery backup directory is missing or unsafe' || return 1
  backups_root="$(cd "$ISSUEBOT_HOME/backups" && pwd -P)" || die 'cannot resolve IssueBot backups directory' || return 1
  backup_real="$(cd "$backup" && pwd -P)" || die 'cannot resolve native recovery backup directory' || return 1
  case "$backup_real" in "$backups_root"/*) ;; *) die 'native recovery backup must be inside ISSUEBOT_HOME/backups' || return 1 ;; esac
  backup_database="$backup_real/issuebot.mv.db"
  metadata="$backup_real/backup.metadata"
  recovery_manifest="$backup_real/native-recovery.manifest"
  require_private_file "$backup_database" || return 1
  load_backup_metadata "$metadata" || return 1
  load_native_recovery_record "$recovery_manifest" || return 1
  checksum="$(sha256_file "$backup_database")" || die 'cannot checksum native recovery database' || return 1
  bytes="$(file_bytes "$backup_database")" || die 'cannot inspect native recovery database size' || return 1
  [[ "$checksum" == "$RECOVERY_DATABASE_SHA256" && "$bytes" == "$RECOVERY_DATABASE_BYTES" ]] || die 'native recovery database does not match backup metadata' || return 1

  pattern="$(decode_base64_value "$NATIVE_RECOVERY_PATTERN_B64")" || die 'native recovery process pattern is not valid base64' || return 1
  [[ -n "$pattern" && "$pattern" != *$'\n'* ]] || die 'native recovery process pattern is empty or multiline' || return 1
  fragment="$NATIVE_RECOVERY_FRAGMENT"
  [[ -f "$fragment" && ! -L "$fragment" ]] || die 'recorded native systemd unit fragment is missing or unsafe' || return 1
  [[ "$(sha256_file "$fragment")" == "$NATIVE_RECOVERY_UNIT_SHA256" ]] || die 'recorded native systemd unit fragment has changed' || return 1

  NATIVE_SERVICE_KIND="$NATIVE_RECOVERY_KIND"
  NATIVE_SERVICE_NAME="$NATIVE_RECOVERY_NAME"
  ISSUEBOT_NATIVE_PROCESS_PATTERN="$pattern"
  export NATIVE_SERVICE_KIND NATIVE_SERVICE_NAME ISSUEBOT_NATIVE_PROCESS_PATTERN
  fragment="$(native_systemctl show --property FragmentPath --value "$NATIVE_SERVICE_NAME")" || die 'cannot inspect current native systemd unit identity' || return 1
  [[ "$fragment" == "$NATIVE_RECOVERY_FRAGMENT" ]] || die 'native systemd unit fragment identity changed' || return 1

  context_manifest="$ISSUEBOT_HOME/deployments/candidate.manifest"
  [[ -f "$context_manifest" ]] || context_manifest="$ISSUEBOT_HOME/deployments/current.manifest"
  ISSUEBOT_GIT_SHA="$(manifest_value "$context_manifest" issuebot_git_sha)" || die 'cannot load Compose recovery Git context' || return 1
  BUILD_DATE="$(manifest_value "$context_manifest" deployed_at)" || die 'cannot load Compose recovery build context' || return 1
  [[ "$ISSUEBOT_GIT_SHA" =~ ^[0-9a-f]{40}$ ]] || die 'Compose recovery Git context is invalid' || return 1
  export ISSUEBOT_GIT_SHA BUILD_DATE

  ensure_deployment_lock || return 1
  ensure_failed_candidate_stopped || return 1
  database="$ISSUEBOT_HOME/issuebot.mv.db"
  [[ -f "$database" && ! -L "$database" ]] || die 'failed candidate H2 database is missing or unsafe' || return 1
  timestamp="$(date -u +%Y%m%dT%H%M%SZ)" || die 'cannot create native recovery timestamp' || return 1
  failed_dir="$ISSUEBOT_HOME/deployments/failed/$timestamp"
  mkdir -p "$failed_dir" || die 'cannot create failed-database preservation directory' || return 1
  chmod 700 "$ISSUEBOT_HOME/deployments/failed" "$failed_dir"
  mv "$database" "$failed_dir/issuebot.mv.db.failed-$timestamp" || die 'cannot preserve failed candidate H2 database' || return 1
  restore_tmp="$ISSUEBOT_HOME/.issuebot.mv.db.restore-$timestamp"
  cp -p "$backup_database" "$restore_tmp" || die 'cannot stage native recovery H2 database' || return 1
  [[ "$(sha256_file "$restore_tmp")" == "$RECOVERY_DATABASE_SHA256" ]] || { rm -f "$restore_tmp"; die 'staged native recovery H2 checksum mismatch'; return 1; }
  mv "$restore_tmp" "$database" || die 'cannot atomically install native recovery H2 database' || return 1

  if current_autostart="$(native_systemctl is-enabled "$NATIVE_SERVICE_NAME" 2>/dev/null)"; then :; else :; fi
  if [[ "$NATIVE_RECOVERY_AUTOSTART" == enabled && "$current_autostart" != enabled ]]; then
    [[ "$current_autostart" != masked ]] || die 'native unit became masked; refusing to unmask it automatically' || return 1
    native_systemctl enable "$NATIVE_SERVICE_NAME" || die 'cannot re-enable recorded native autostart' || return 1
    current_autostart="$(native_systemctl is-enabled "$NATIVE_SERVICE_NAME" 2>/dev/null)" || true
    [[ "$current_autostart" == enabled ]] || die 'native autostart re-enable could not be verified' || return 1
  fi
  native_systemctl start "$NATIVE_SERVICE_NAME" || die 'cannot start restored native IssueBot service' || return 1
  if ! wait_for_native_active \
    || ! pgrep -f -- "$ISSUEBOT_NATIVE_PROCESS_PATTERN" >/dev/null 2>&1 \
    || ! require_native_port_owner; then
    native_systemctl stop "$NATIVE_SERVICE_NAME" >/dev/null 2>&1 || true
    if [[ "$NATIVE_SERVICE_KIND" == systemd-user ]]; then
      wait_for_systemd_inactive user "$NATIVE_SERVICE_NAME" || true
    else
      wait_for_systemd_inactive system "$NATIVE_SERVICE_NAME" || true
    fi
    require_no_matching_process "$ISSUEBOT_NATIVE_PROCESS_PATTERN" || true
    require_port_free || true
    require_h2_closed || true
    die 'restored native service failed verification and was stopped'
    return 1
  fi
  rm -f "$ISSUEBOT_HOME/deployments/candidate.manifest"
  log "native IssueBot recovery verified from $backup_real; failed database preserved at $failed_dir"
}

load_manifest_map() {
  local path="$1" record key value
  PREVIOUS_issuebot_git_sha=''
  PREVIOUS_issuebot_image_id=''
  PREVIOUS_provider_image=''
  PREVIOUS_provider_digest=''
  PREVIOUS_provider_protocol=''
  PREVIOUS_backup_path=''
  while IFS= read -r record; do
    key="${record%%=*}"
    value="${record#*=}"
    manifest_key_allowed "$key" || die "rollback manifest key is not allowed: $key" || return 1
    printf -v "PREVIOUS_$key" '%s' "$value"
  done < <(read_manifest "$path")
}

migration_set_for_commit() {
  git -C "$ISSUEBOT_CHECKOUT" ls-tree -r "$1" -- src/main/resources/db/migration | awk '{print $3 " " $4}' | sort
}

manifest_value() {
  local path="$1" wanted="$2" record
  while IFS= read -r record; do
    if [[ "${record%%=*}" == "$wanted" ]]; then
      printf '%s\n' "${record#*=}"
      return 0
    fi
  done < <(read_manifest "$path")
  return 1
}

rollback_release() {
  local previous_manifest="${1:-$ISSUEBOT_HOME/deployments/previous.manifest}" new_manifest compatibility=false old_sha old_image old_provider old_digest old_protocol old_backup current_set old_set actual_id rotate_active=false
  ensure_deployment_lock || return 1
  load_manifest_map "$previous_manifest" || return 1
  old_sha="$PREVIOUS_issuebot_git_sha"
  old_image="$PREVIOUS_issuebot_image_id"
  old_provider="$PREVIOUS_provider_image"
  old_digest="$PREVIOUS_provider_digest"
  old_protocol="$PREVIOUS_provider_protocol"
  old_backup="${PREVIOUS_backup_path:-${BACKUP_PATH:-unknown}}"
  if [[ "$previous_manifest" == "$ISSUEBOT_HOME/deployments/previous.manifest" ]]; then
    rotate_active=true
  fi
  [[ -n "$old_sha" && -n "$old_image" && -n "$old_provider" && -n "$old_digest" && -n "$old_protocol" ]] || die 'previous manifest lacks required rollback fields' || return 1
  [[ "$old_provider" == *@"$old_digest" ]] || die 'previous provider image and digest disagree' || return 1
  git -C "$ISSUEBOT_CHECKOUT" cat-file -e "$old_sha^{commit}" || die 'previous IssueBot commit is not local' || return 1
  actual_id="$(docker image inspect --format '{{.Id}}' "issuebot:$old_sha")" || die 'previous IssueBot image is not local' || return 1
  [[ "$actual_id" == "$old_image" ]] || die 'previous IssueBot tag no longer resolves to the recorded image ID' || return 1
  docker image inspect "$old_provider" >/dev/null 2>&1 || die 'previous provider digest is not local' || return 1
  new_manifest="${ROLLBACK_NEW_MANIFEST:-$ISSUEBOT_HOME/deployments/candidate.manifest}"
  if [[ ! -f "$new_manifest" ]]; then
    new_manifest="$ISSUEBOT_HOME/deployments/current.manifest"
  fi
  if [[ -f "$new_manifest" ]]; then
    compatibility="$(manifest_value "$new_manifest" schema_rollback_compatible)" || compatibility=false
  fi
  current_set="$(migration_set_for_commit "$ISSUEBOT_GIT_SHA")" || die 'cannot inspect new Flyway migration set' || return 1
  old_set="$(migration_set_for_commit "$old_sha")" || die 'cannot inspect previous Flyway migration set' || return 1
  if [[ "$current_set" != "$old_set" && "$compatibility" != true ]]; then
    printf 'Automatic rollback refused because Flyway migration sets differ. Database was not restored. Verified backup: %s\n' "$old_backup" >&2
    return 1
  fi

  compose stop issuebot codex-cli-provider || die 'cannot stop failed Compose release' || return 1
  ISSUEBOT_GIT_SHA="$old_sha"
  ISSUEBOT_IMAGE_ID="$old_image"
  CODEX_CLI_PROVIDER_IMAGE="$old_provider"
  PROVIDER_DIGEST="$old_digest"
  CODEX_CLI_PROVIDER_PROTOCOL_VERSION="$old_protocol"
  BACKUP_PATH="$old_backup"
  export ISSUEBOT_GIT_SHA ISSUEBOT_IMAGE_ID CODEX_CLI_PROVIDER_IMAGE PROVIDER_DIGEST CODEX_CLI_PROVIDER_PROTOCOL_VERSION BACKUP_PATH
  compose up -d --no-build codex-cli-provider issuebot || die 'cannot start previous Compose release' || return 1
  verify_release || {
    printf 'Rollback verification failed. Database was not restored. Verified backup: %s\n' "$old_backup" >&2
    return 1
  }
  write_release_manifest "$rotate_active"
}

deploy_release() {
  local previous_manifest="$ISSUEBOT_HOME/deployments/current.manifest"
  mkdir -p "$ISSUEBOT_HOME/deployments" || die 'cannot create deployments directory' || return 1
  chmod 700 "$ISSUEBOT_HOME/deployments"
  ensure_deployment_lock || return 1
  preflight_deploy || return 1
  git -C "$ISSUEBOT_CHECKOUT" fetch --prune || die 'Git fetch failed' || return 1
  git -C "$ISSUEBOT_CHECKOUT" pull --ff-only || die 'Git fast-forward pull failed' || return 1
  preflight_checkout || return 1
  ISSUEBOT_GIT_SHA="$(git -C "$ISSUEBOT_CHECKOUT" rev-parse HEAD)" || die 'cannot resolve deployed Git SHA' || return 1
  BUILD_DATE="$(date -u +%Y-%m-%dT%H:%M:%SZ)" || die 'cannot create build timestamp' || return 1
  export ISSUEBOT_GIT_SHA BUILD_DATE
  compose config --quiet || die 'Compose configuration is invalid' || return 1
  compose build --pull issuebot || die 'IssueBot image build failed' || return 1
  compose pull codex-cli-provider || die 'provider image pull failed' || return 1
  preflight_runner || return 1
  resolve_release_images || return 1
  stop_issuebot_for_cutover || return 1
  require_port_free || return 1
  require_h2_closed || return 1
  backup_h2 || return 1
  write_candidate_manifest || return 1
  if ! compose up -d --no-build codex-cli-provider issuebot; then
    recover_failed_release "$previous_manifest"
    return 1
  fi
  if ! verify_release; then
    recover_failed_release "$previous_manifest"
    return 1
  fi
  if [[ "${CUTOVER_WAS_NATIVE:-false}" == true ]] && ! disable_native_autostart; then
    recover_failed_release "$previous_manifest"
    return 1
  fi
  if ! write_release_manifest; then
    recover_failed_release "$previous_manifest"
    return 1
  fi
  rm -f "$ISSUEBOT_HOME/deployments/candidate.manifest"
}

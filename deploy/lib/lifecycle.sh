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
  if pgrep -f -- "$ISSUEBOT_NATIVE_PROCESS_PATTERN" >/dev/null 2>&1; then
    die 'matching native IssueBot process remains after shutdown'
    return 1
  fi
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
  if [[ "${ALLOW_GITHUB_DEGRADED:-false}" != true ]]; then
    [[ "$health" != *'"githubToken":"missing"'* && "$health" != *'"github"'*'"status":"DOWN"'* ]] || die 'GitHub health is degraded' || return 1
  fi
  protocol="$(docker image inspect --format '{{index .Config.Labels "com.issuebot.codex-provider.protocol"}}' "$CODEX_CLI_PROVIDER_IMAGE")" || die 'cannot inspect provider protocol' || return 1
  [[ "$protocol" == "$CODEX_CLI_PROVIDER_PROTOCOL_VERSION" ]] || die 'provider protocol changed after startup' || return 1
  doctor="$(compose exec -T codex-cli-provider codex-cli-provider doctor --json --no-billable-work)" || die 'provider non-billable doctor failed' || return 1
  [[ "$doctor" == *'{'*'}'* ]] || die 'provider doctor did not return JSON' || return 1
  if [[ -n "${ISSUEBOT_NATIVE_PROCESS_PATTERN:-}" ]] && pgrep -f -- "$ISSUEBOT_NATIVE_PROCESS_PATTERN" >/dev/null 2>&1; then
    die 'native IssueBot process is running alongside Compose'
    return 1
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
  if printf '%s\n' "$logs" | grep -Eiq 'flyway.*(error|failed)|h2.*(error|failed)|application run failed|outofmemoryerror|exception in thread'; then
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
  local manifest_dir="$ISSUEBOT_HOME/deployments" deployed_at compatible
  mkdir -p "$manifest_dir" || die 'cannot create deployments directory' || return 1
  chmod 700 "$manifest_dir"
  deployed_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)" || return 1
  compatible="${SCHEMA_ROLLBACK_COMPATIBLE:-false}"
  [[ "$compatible" == true || "$compatible" == false ]] || die 'schema rollback compatibility must be true or false' || return 1
  write_manifest "$manifest_dir/current.manifest" \
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
  if ! rollback_release "$previous_manifest"; then
    printf 'Automatic recovery did not complete. Database was not restored. Verified backup: %s\n' "${BACKUP_PATH:-unknown}" >&2
  fi
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
  local previous_manifest="${1:-$ISSUEBOT_HOME/deployments/current.manifest}" new_manifest compatibility=false old_sha old_image old_provider old_digest old_protocol old_backup current_set old_set actual_id
  load_manifest_map "$previous_manifest" || return 1
  old_sha="$PREVIOUS_issuebot_git_sha"
  old_image="$PREVIOUS_issuebot_image_id"
  old_provider="$PREVIOUS_provider_image"
  old_digest="$PREVIOUS_provider_digest"
  old_protocol="$PREVIOUS_provider_protocol"
  old_backup="${PREVIOUS_backup_path:-${BACKUP_PATH:-unknown}}"
  [[ -n "$old_sha" && -n "$old_image" && -n "$old_provider" && -n "$old_digest" && -n "$old_protocol" ]] || die 'previous manifest lacks required rollback fields' || return 1
  [[ "$old_provider" == *@"$old_digest" ]] || die 'previous provider image and digest disagree' || return 1
  git -C "$ISSUEBOT_CHECKOUT" cat-file -e "$old_sha^{commit}" || die 'previous IssueBot commit is not local' || return 1
  actual_id="$(docker image inspect --format '{{.Id}}' "issuebot:$old_sha")" || die 'previous IssueBot image is not local' || return 1
  [[ "$actual_id" == "$old_image" ]] || die 'previous IssueBot tag no longer resolves to the recorded image ID' || return 1
  docker image inspect "$old_provider" >/dev/null 2>&1 || die 'previous provider digest is not local' || return 1
  new_manifest="${ROLLBACK_NEW_MANIFEST:-$ISSUEBOT_HOME/deployments/candidate.manifest}"
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
  write_release_manifest
}

deploy_release() {
  local previous_manifest="$ISSUEBOT_HOME/deployments/current.manifest"
  mkdir -p "$ISSUEBOT_HOME/deployments" || die 'cannot create deployments directory' || return 1
  chmod 700 "$ISSUEBOT_HOME/deployments"
  acquire_lock "$ISSUEBOT_HOME/deployments/deploy.lock" || return 1
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
  stop_native_issuebot || return 1
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
  write_release_manifest || return 1
  rm -f "$ISSUEBOT_HOME/deployments/candidate.manifest"
}

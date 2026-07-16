#!/usr/bin/env bash
set -euo pipefail

cd "$(git rev-parse --show-toplevel)"
source deploy/tests/test-helper.sh
if [[ ! -f deploy/lib/common.sh || ! -f deploy/lib/preflight.sh ]]; then
  printf 'FAIL: preflight implementation is absent\n' >&2
  exit 1
fi
source deploy/lib/common.sh
source deploy/lib/preflight.sh

export ISSUEBOT_BRANCH=main
export ISSUEBOT_HOME="$TEST_ROOT/issuebot-home"
export ISSUEBOT_SECRET_ENV="$TEST_ROOT/runtime.env"
export DEPLOY_ENV="$TEST_ROOT/deploy.env"
export ISSUEBOT_BIND_ADDRESS=127.0.0.1
export CODEX_CLI_PROVIDER_IMAGE='ghcr.io/acme/codex-cli-provider@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa'
export CODEX_CLI_PROVIDER_PROTOCOL_VERSION=1
export ISSUEBOT_NATIVE_PROCESS_PATTERN='issuebot-native-marker'
mkdir -p "$ISSUEBOT_HOME" "$ISSUEBOT_HOME/repos" "$ISSUEBOT_HOME/logs"
: >"$ISSUEBOT_SECRET_ENV"
: >"$DEPLOY_ENV"
chmod 600 "$ISSUEBOT_SECRET_ENV" "$DEPLOY_ENV"

mock_git_clean() {
  mock_command git '
case "$*" in
  *"symbolic-ref -q HEAD"*) printf "refs/heads/main\\n" ;;
  *"diff --quiet"*|*"diff --cached --quiet"*) exit 0 ;;
  *"ls-files --others --exclude-standard"*) exit 0 ;;
  *"ls-files --error-unmatch"*) exit 1 ;;
  *"branch --show-current"*) printf "main\\n" ;;
  *"rev-parse --abbrev-ref"*) printf "origin/main\\n" ;;
  *"rev-list --left-right --count"*) printf "0 0\\n" ;;
  *) printf "unexpected git call: %s\\n" "$*" >&2; exit 2 ;;
esac'
}

mock_runtime_clean() {
  mock_command docker '
case "$*" in
  "info --format {{.Architecture}}") printf "amd64\\n" ;;
  "info") exit 0 ;;
  "compose version"*) printf "Docker Compose version v2.30.0\\n" ;;
  *"--format {{json .RepoDigests}}"*) printf "[\\"%s\\"]\\n" "$CODEX_CLI_PROVIDER_IMAGE" ;;
  *"--format {{.Os}}/{{.Architecture}}"*) printf "linux/amd64\\n" ;;
  *"--format {{json .Config.Healthcheck.Test}}"*) printf "[\\"CMD\\",\\"/usr/local/bin/healthcheck\\"]\\n" ;;
  *"--format {{.Config.User}}"*) printf "1000:1000\\n" ;;
  *"codex-provider.protocol"*) printf "1\\n" ;;
  *"codex-provider.doctor"*) printf "codex-cli-provider doctor --json --no-billable-work\\n" ;;
  "image inspect "*) exit 0 ;;
  *) printf "unexpected docker call: %s\\n" "$*" >&2; exit 2 ;;
esac'
  mock_command curl 'exit 0'
  mock_command df 'printf "Filesystem 1024-blocks Used Available Capacity Mounted on\\nmock 99999999 1 6000000 1%% /\\n"'
  mock_command lsof 'exit 1'
  mock_command ps 'exit 1'
}

mock_git_clean
mock_runtime_clean
assert_success preflight_checkout
assert_success preflight_runtime
assert_success preflight_storage
assert_success preflight_runner
assert_success preflight_all

mock_git_clean
mock_command git 'case "$*" in *"diff --quiet"*) exit 1;; *) exec "$TEST_ROOT/git-clean" "$@";; esac'
cp "$MOCK_BIN/git" "$TEST_ROOT/git-dirty"
mock_git_clean
cp "$MOCK_BIN/git" "$TEST_ROOT/git-clean"
cp "$TEST_ROOT/git-dirty" "$MOCK_BIN/git"
assert_failure preflight_checkout

mock_git_clean
mock_command git 'case "$*" in *"ls-files --others --exclude-standard"*) printf "new-file\\n";; *) exec "$TEST_ROOT/git-clean" "$@";; esac'
assert_failure preflight_checkout

mock_git_clean
mock_command git 'case "$*" in *"symbolic-ref -q HEAD"*) exit 1;; *) exec "$TEST_ROOT/git-clean" "$@";; esac'
assert_failure preflight_checkout

mock_git_clean
mock_command git 'case "$*" in *"branch --show-current"*) printf "feature\\n";; *) exec "$TEST_ROOT/git-clean" "$@";; esac'
assert_failure preflight_checkout

mock_git_clean
mock_command git 'case "$*" in *"rev-parse --abbrev-ref"*) exit 1;; *) exec "$TEST_ROOT/git-clean" "$@";; esac'
assert_failure preflight_checkout

mock_git_clean
mock_command git 'case "$*" in *"rev-list --left-right --count"*) printf "1 0\\n";; *) exec "$TEST_ROOT/git-clean" "$@";; esac'
assert_failure preflight_checkout

mock_git_clean
cp "$MOCK_BIN/git" "$TEST_ROOT/git-clean"
mock_command git 'case "$*" in *"rev-list --left-right --count"*) printf "0 1\\n";; *) exec "$TEST_ROOT/git-clean" "$@";; esac'
assert_success preflight_checkout

mock_git_clean
cp "$MOCK_BIN/git" "$TEST_ROOT/git-clean"
mock_command git 'case "$*" in *"rev-list --left-right --count"*) printf "1 1\\n";; *) exec "$TEST_ROOT/git-clean" "$@";; esac'
assert_failure preflight_checkout

mock_git_clean
mock_runtime_clean
mock_command docker 'case "$*" in "compose version"*) printf "Docker Compose version v1.29\\n";; *) exit 0;; esac'
assert_failure preflight_runtime

mock_runtime_clean
cp "$MOCK_BIN/docker" "$TEST_ROOT/docker-clean"
mock_command docker 'case "$*" in "info --format {{.Architecture}}") printf "s390x\\n";; *) exec "$TEST_ROOT/docker-clean" "$@";; esac'
assert_failure preflight_runtime

mock_runtime_clean
mock_command df 'printf "Filesystem 1024-blocks Used Available Capacity Mounted on\\nmock 99999999 1 4000000 1%% /\\n"'
assert_failure preflight_storage

mock_runtime_clean
mock_command lsof 'printf "4242\\n"'
mock_command ps 'printf "unknown-server\\n"'
export ISSUEBOT_FIRST_CUTOVER=true
assert_failure preflight_storage
unset ISSUEBOT_FIRST_CUTOVER

mock_runtime_clean
mock_command lsof 'printf "4242\\n"'
mock_command ps 'printf "/usr/bin/java issuebot-native-marker\\n"'
export ISSUEBOT_FIRST_CUTOVER=true
assert_success preflight_storage
unset ISSUEBOT_FIRST_CUTOVER
assert_failure preflight_storage

export ISSUEBOT_FIRST_CUTOVER=false
assert_failure preflight_storage
unset ISSUEBOT_FIRST_CUTOVER

mock_runtime_clean
secret_outside="$ISSUEBOT_SECRET_ENV"
secret_inside="$ISSUEBOT_CHECKOUT/runtime.env"
: >"$secret_inside"
chmod 600 "$secret_inside"
export ISSUEBOT_SECRET_ENV="$secret_inside"
assert_failure preflight_storage
export ISSUEBOT_SECRET_ENV="$secret_outside"

deploy_outside="$DEPLOY_ENV"
deploy_inside="$ISSUEBOT_CHECKOUT/deploy.env"
: >"$deploy_inside"
chmod 600 "$deploy_inside"
export DEPLOY_ENV="$deploy_inside"
assert_failure preflight_storage
export DEPLOY_ENV="$deploy_outside"

mock_runtime_clean
cp "$MOCK_BIN/git" "$TEST_ROOT/git-clean"
export TRACKED_SECRET="$ISSUEBOT_SECRET_ENV"
mock_command git 'case "$*" in *"ls-files --error-unmatch"*"$TRACKED_SECRET"*) exit 0;; *) exec "$TEST_ROOT/git-clean" "$@";; esac'
assert_failure preflight_storage

mock_runtime_clean
mock_git_clean
cp "$MOCK_BIN/git" "$TEST_ROOT/git-clean"
export TRACKED_SECRET="$DEPLOY_ENV"
mock_command git 'case "$*" in *"ls-files --error-unmatch"*"$TRACKED_SECRET"*) exit 0;; *) exec "$TEST_ROOT/git-clean" "$@";; esac'
assert_failure preflight_storage
unset TRACKED_SECRET
mock_git_clean

chmod 640 "$ISSUEBOT_SECRET_ENV"
assert_failure preflight_storage
chmod 600 "$ISSUEBOT_SECRET_ENV"

mock_runtime_clean
cp "$MOCK_BIN/docker" "$TEST_ROOT/docker-clean"
mock_command docker 'case "$*" in *"RepoDigests"*) printf "[\\"ghcr.io/acme/codex-cli-provider@sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb\\"]\\n";; *) exec "$TEST_ROOT/docker-clean" "$@";; esac'
assert_failure preflight_runner

mock_runtime_clean
cp "$MOCK_BIN/docker" "$TEST_ROOT/docker-clean"
mock_command docker 'case "$*" in *"--format {{.Os}}/{{.Architecture}}"*) printf "linux/arm64\\n";; *) exec "$TEST_ROOT/docker-clean" "$@";; esac'
assert_failure preflight_runner

mock_runtime_clean
cp "$MOCK_BIN/docker" "$TEST_ROOT/docker-clean"
mock_command docker 'case "$*" in *"Healthcheck.Test"*) printf "null\\n";; *) exec "$TEST_ROOT/docker-clean" "$@";; esac'
assert_failure preflight_runner

mock_runtime_clean
cp "$MOCK_BIN/docker" "$TEST_ROOT/docker-clean"
mock_command docker 'case "$*" in *"codex-provider.protocol"*) printf "2\\n";; *) exec "$TEST_ROOT/docker-clean" "$@";; esac'
assert_failure preflight_runner

mock_runtime_clean
cp "$MOCK_BIN/docker" "$TEST_ROOT/docker-clean"
mock_command docker 'case "$*" in *"codex-provider.doctor"*) printf "codex-cli-provider doctor --json\\n";; *) exec "$TEST_ROOT/docker-clean" "$@";; esac'
assert_failure preflight_runner

mock_runtime_clean
assert_failure preflight_runner 'ghcr.io/acme/codex-cli-provider:latest'

for unsafe_user in '' root root:root 0 0:1000 00 000:1000 +0 ' 0' '0 ' 'provider user' 'Provider!' ':1000'; do
  mock_runtime_clean
  export UNSAFE_PROVIDER_USER="$unsafe_user"
  cp "$MOCK_BIN/docker" "$TEST_ROOT/docker-clean"
  mock_command docker 'case "$*" in *"--format {{.Config.User}}"*) printf "%s\n" "$UNSAFE_PROVIDER_USER";; *) exec "$TEST_ROOT/docker-clean" "$@";; esac'
  assert_failure preflight_runner
done
unset UNSAFE_PROVIDER_USER

for safe_user in 1 1000 1000:1000 1000:provider provider provider:provider provider:1000 issuebot_user; do
  mock_runtime_clean
  export SAFE_PROVIDER_USER="$safe_user"
  cp "$MOCK_BIN/docker" "$TEST_ROOT/docker-clean"
  mock_command docker 'case "$*" in *"--format {{.Config.User}}"*) printf "%s\n" "$SAFE_PROVIDER_USER";; *) exec "$TEST_ROOT/docker-clean" "$@";; esac'
  assert_success preflight_runner
done
unset SAFE_PROVIDER_USER

mock_runtime_clean
mock_command lsof 'printf "4242\n"'
mock_command ps 'printf "/usr/bin/docker-proxy -host-port 8090\n"'
cp "$MOCK_BIN/docker" "$TEST_ROOT/docker-clean"
mock_command docker '
case "$*" in
  "ps --filter label=com.docker.compose.project=issuebot --filter label=com.docker.compose.service=issuebot --format {{.ID}}") printf "current-container\n" ;;
  "inspect --format {{json .HostConfig.PortBindings}} current-container") printf "{\"8090/tcp\":[{\"HostIp\":\"127.0.0.1\",\"HostPort\":\"8090\"}]}\n" ;;
  *) exec "$TEST_ROOT/docker-clean" "$@" ;;
esac'
assert_success preflight_storage

mock_runtime_clean
mock_command lsof 'printf "4242\n"'
mock_command ps 'printf "/usr/bin/docker-proxy -host-port 8090\n"'
assert_failure preflight_storage

if grep -Eq '^[[:space:]]*(docker[[:space:]]+(pull|run)|docker[[:space:]]+compose[[:space:]]+(pull|up|down|stop|restart)|git[[:space:]].*[[:space:]](fetch|pull)([[:space:]]|$))' deploy/lib/preflight.sh; then
  printf 'FAIL: preflight contains lifecycle mutation command\n' >&2
  exit 1
fi

printf 'preflight-test: PASS\n'

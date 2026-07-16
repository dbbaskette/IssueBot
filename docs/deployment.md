# Docker deployment runbook

## Scope and cutover gate

IssueBot owns the `issuebot` image, the production Compose project, and the host bind mount containing the file-backed H2 database. The bind mount at `${ISSUEBOT_HOME}` is authoritative; this stack does not use PostgreSQL, SQLite, MinIO, or a Docker volume for application data.

`codex-cli-provider` is an independent product and release boundary. Its project owns its image build, credentials, authentication, state, and API. This repository only pulls an externally released image by immutable digest. Provider credentials do not belong in IssueBot's `.env`, runtime file, deploy file, image, or `~/.issuebot` tree.

> **Production cutover is forbidden** until both a compatible `codex-cli-provider` image has been published and IssueBot provider-integration work has removed direct local `claude` execution. Tasks 1–6 prepare and verify deployment assets only. They do not satisfy this gate.

The supported remote commands are exactly:

```text
preflight
deploy
rollback
status
logs issuebot 1-500
logs codex-cli-provider 1-500
```

## Prerequisites

On `home-services.local`, install Java-independent Docker Engine support, Docker Compose v2, Git, curl, `lsof` (or `fuser` for the closed-H2 check), `flock`, and enough build storage. The Docker engine architecture must be `amd64` or `arm64`. The `dbbaskette` account must be able to use Docker without an interactive privilege prompt.

Before proceeding, require:

- a clean IssueBot checkout on its expected attached branch with an upstream and no local commits;
- outbound Git, registry, GitHub, and provider connectivity;
- at least 5 GiB free and at least twice the H2 database size available for a backup and failed copy;
- `${ISSUEBOT_HOME}`, `${ISSUEBOT_HOME}/repos`, and `${ISSUEBOT_HOME}/logs` owned and writable by the configured UID/GID;
- a compatible provider image with an explicit non-root `Config.User`, an embedded healthcheck, label `com.issuebot.codex-provider.protocol`, and doctor label containing the exact non-billable command `codex-cli-provider doctor --json --no-billable-work`.

The provider joins the internal `backend` network for IssueBot traffic and a separate non-internal `egress` network for outbound provider calls. It publishes no host port. IssueBot is the only service that publishes `8090`.

Confirm tools and architecture on the host:

```bash
ssh dbbaskette@home-services.local 'docker info --format "{{.Architecture}}"; docker compose version; git --version; curl --version; command -v lsof || command -v fuser; command -v flock'
```

Expected: architecture `amd64` or `arm64`, Compose v2, and a path/version for every required command.

## Dedicated restricted key

Create a key on the operator workstation. Do not overwrite an existing key without first confirming its ownership.

```bash
ssh-keygen -t ed25519 -f ~/.ssh/issuebot_deploy_ed25519 -C issuebot-deploy
chmod 0600 ~/.ssh/issuebot_deploy_ed25519
```

Install the forced-command wrapper using an existing unrestricted login:

```bash
./deploy/install-remote-wrapper.sh ~/.ssh/issuebot_deploy_ed25519.pub
```

Expected: `Installed restricted deployment wrapper and key on dbbaskette@home-services.local`. The authorized-key entry uses `restrict` and allows only the commands listed above. Verify rejection without changing state:

```bash
ssh -i ~/.ssh/issuebot_deploy_ed25519 -o IdentitiesOnly=yes dbbaskette@home-services.local uname
```

Expected: `ERROR: remote deployment command rejected`.

## Protected configuration

Create the provider's credentials and state only according to the independently released provider's documentation. Do not add them to either IssueBot file below.

Create the IssueBot runtime-secret file on the host outside the checkout:

```bash
ssh dbbaskette@home-services.local 'umask 077; mkdir -p ~/.config/issuebot; install -m 0600 /dev/null ~/.config/issuebot/runtime.env'
```

Populate only secrets IssueBot itself supports, as needed:

```dotenv
GITHUB_TOKEN=replace_me
ISSUEBOT_USERNAME=admin
ISSUEBOT_PASSWORD=replace_me
ISSUEBOT_WEBHOOK_SECRET=replace_me
```

Create `/home/dbbaskette/.config/issuebot/deploy.env` with literal `KEY=value` records (no shell interpolation):

```dotenv
COMPOSE_PROJECT_NAME=issuebot
APP_UID=1000
APP_GID=1000
ISSUEBOT_HOME=/home/dbbaskette/.issuebot
ISSUEBOT_BIND_ADDRESS=127.0.0.1
ISSUEBOT_SECRET_ENV=/home/dbbaskette/.config/issuebot/runtime.env
ISSUEBOT_CHECKOUT=/home/dbbaskette/IssueBot
ISSUEBOT_BRANCH=main
ISSUEBOT_NATIVE_PROCESS_PATTERN=issuebot-0.1.0-SNAPSHOT.jar
ISSUEBOT_FIRST_CUTOVER=true
NATIVE_SERVICE_KIND=systemd-user
NATIVE_SERVICE_NAME=issuebot.service
CODEX_CLI_PROVIDER_IMAGE=registry.example.com/providers/codex-cli-provider@sha256:replace_with_64_lowercase_hex_characters
CODEX_CLI_PROVIDER_PROTOCOL_VERSION=1
```

Use the host's actual numeric identity and lock down both files:

```bash
ssh dbbaskette@home-services.local 'id -u; id -g; chmod 0600 ~/.config/issuebot/deploy.env ~/.config/issuebot/runtime.env; stat -c "%a %n" ~/.config/issuebot/deploy.env ~/.config/issuebot/runtime.env'
```

Expected: the UID/GID match `APP_UID`/`APP_GID`, and both modes are `600`. Neither file may be a symlink or live inside the checkout.

## Select and authenticate the provider image

Authenticate using the registry's supported credential helper or an interactive login on the host; never put the registry password in either environment file:

```bash
ssh -t dbbaskette@home-services.local 'docker login registry.example.com'
```

Resolve an approved release tag to a digest on the host architecture, then pin the resulting `repository@sha256:...` value in `CODEX_CLI_PROVIDER_IMAGE`:

```bash
ssh dbbaskette@home-services.local '
  docker pull registry.example.com/providers/codex-cli-provider:APPROVED_TAG
  docker image inspect --format "{{index .RepoDigests 0}}" registry.example.com/providers/codex-cli-provider:APPROVED_TAG
'
```

Expected: a non-placeholder digest with 64 lowercase hexadecimal characters. The deployment rejects mutable tags, the example zero digest, a platform mismatch, an empty/root/numeric-zero runtime user, a missing healthcheck, a protocol mismatch, or a missing exact doctor contract. An accepted provider must declare a named or numeric non-root `Config.User`; IssueBot never builds this image.

## Discover native service ownership

Do not guess how the old process is managed. With IssueBot still running, inspect the listener and process:

```bash
ssh dbbaskette@home-services.local '
  pids=$(lsof -nP -iTCP:8090 -sTCP:LISTEN -t)
  printf "listener pids: %s\n" "$pids"
  ps -fp $pids
  systemctl --user status issuebot.service --no-pager || true
  systemctl status issuebot.service --no-pager || true
'
```

Record one verified ownership mode in `deploy.env`:

- `NATIVE_SERVICE_KIND=systemd-user` and the user unit name;
- `NATIVE_SERVICE_KIND=systemd-system` and the system unit name (the deployment account must already have narrowly scoped permission to stop it); or
- `NATIVE_SERVICE_KIND=pidfile` and `NATIVE_SERVICE_NAME` set to the absolute, non-symlink pidfile path.

The deployer can stop all three kinds safely. It can disable and verify autostart only for `systemd-user` and `systemd-system`. A pidfile does not identify an autostart mechanism, so a pidfile-owned first cutover fails closed after verification and removes the candidate instead of marking it known-good. Convert the native launch to one of the documented systemd kinds before a successful first cutover.

Set `ISSUEBOT_NATIVE_PROCESS_PATTERN` to a distinctive portion of the verified Java command. During the first cutover only, `ISSUEBOT_FIRST_CUTOVER=true` lets preflight accept port 8090 when every listener matches this pattern. An unknown owner is always rejected.

## Verify the pre-cutover backup conditions

Read the current database size and available space, but do not copy the live database:

```bash
ssh dbbaskette@home-services.local '
  db=$HOME/.issuebot/issuebot.mv.db
  test -f "$db"
  stat -c "database bytes=%s owner=%U:%G mode=%a" "$db"
  df -Pk "$HOME/.issuebot"
  lsof "$db" || true
'
```

Expected: the database exists, ownership matches `APP_UID`/`APP_GID`, and the live native process may be shown by `lsof`. The deploy lifecycle first stops the known native service, proves port 8090 is free and H2 is closed, then copies it to `${ISSUEBOT_HOME}/backups/<UTC timestamp>/`, writes `backup.metadata`, and verifies the SHA-256 checksum before starting Compose.

## First cutover

Run the read-only gate first:

```bash
./deploy/deploy-remote.sh preflight
```

Expected: success with a clean branch, Docker/Compose v2, supported architecture, writable storage, protected-file modes, identified native listener, and the pinned provider contract. A missing provider image must first be pulled by an unrestricted operator; `deploy` also pulls it before its post-pull contract check.

Then run:

```bash
./deploy/deploy-remote.sh deploy
```

Expected: `git fetch --prune`, `git pull --ff-only`, a test-bearing IssueBot image build tagged with the full SHA, provider pull by digest, a durable `${ISSUEBOT_HOME}/deployments/native-recovery.manifest` written before graceful native shutdown, a verified closed-H2 backup, both services healthy, successful liveness/readiness and functional checks, native systemd autostart disabled and verified, and `${ISSUEBOT_HOME}/deployments/current.manifest` updated atomically. The deployment is not successful and does not write a known-good manifest if autostart disablement cannot be proved.

If candidate startup, readiness, migration/functional verification, autostart disablement, or manifest publication fails without an older Compose release, the deployer stops and removes both candidate containers and proves port `8090`, the native process pattern, and H2 are closed. It never leaves an `unless-stopped` candidate owning H2.

Finally change `ISSUEBOT_FIRST_CUTOVER=false` in the mode-`0600` deploy file. Later preflights accept port `8090` only when Docker reports exactly one container with the configured Compose project and `issuebot` service labels and the expected `8090` binding. The lifecycle then performs a controlled Compose stop and requires the port to be free before H2 backup or restart. Any other listener is rejected.

## Routine operation

```bash
./deploy/deploy-remote.sh status
./deploy/deploy-remote.sh logs issuebot 200
./deploy/deploy-remote.sh logs codex-cli-provider 200
ssh dbbaskette@home-services.local 'curl --fail --silent --show-error http://127.0.0.1:8090/actuator/health/liveness'
ssh dbbaskette@home-services.local 'curl --fail --silent --show-error http://127.0.0.1:8090/actuator/health/readiness'
```

Expected: both Compose services are healthy; liveness and readiness return HTTP 200 with `UP`. The default `ISSUEBOT_BIND_ADDRESS=127.0.0.1` is loopback on `home-services.local`, so operator-workstation checks must SSH to that host and run curl there. If direct LAN access is intentionally required, set `ISSUEBOT_BIND_ADDRESS` to the host's specific LAN address only after protecting port 8090 with the approved firewall/reverse-proxy and TLS policy; this exposes the dashboard beyond host loopback, so dashboard authentication remains mandatory. The aggregate health check additionally validates GitHub during deployment unless degraded GitHub operation was explicitly approved in code. Logs are redacted by the wrapper and limited to 1–500 lines.

For later releases, first update the approved provider digest if required, then run `preflight` and `deploy`. A build or provider-pull failure happens before shutdown and leaves the old process running. Failures after shutdown capture sanitized diagnostics under `${ISSUEBOT_HOME}/deployments/failed/<UTC timestamp>/` and attempt only a schema-safe application rollback. Each successful deployment atomically rotates the old `current.manifest` to durable `previous.manifest` before publishing the new current manifest.

## Application rollback

```bash
./deploy/deploy-remote.sh rollback
```

Expected: the dispatcher loads `current.manifest` only to render and compare the active release, acquires the same deployment lock used by deploy, and targets durable `previous.manifest`. The recorded previous IssueBot image and provider digest are reused locally and fully verified. This command never restores H2. If Flyway migration sets differ and the active/candidate release was not explicitly recorded as compatible, rollback refuses to start the old application, stops/removes the unsafe candidate, proves closure, and reports the verified backup path.

## Operator-approved native first-cutover recovery

Use this only when the first Compose cutover failed and no previous Compose manifest exists. This is a deliberate data restore and is therefore unavailable through the restricted deployment key. The original cutover records the systemd scope/name, exact unit-fragment path and SHA-256, original autostart state, and an encoded exact process pattern before native shutdown; the closed-H2 backup retains that `native-recovery.manifest` beside `backup.metadata`.

Open an unrestricted host shell, select the exact verified backup reported by the failed deployment, and invoke the repository-owned recovery function:

```bash
ssh -t dbbaskette@home-services.local
cd /home/dbbaskette/IssueBot
export DEPLOY_ENV=/home/dbbaskette/.config/issuebot/deploy.env
source deploy/lib/common.sh
load_deploy_env "$DEPLOY_ENV"
source deploy/lib/preflight.sh
source deploy/lib/lifecycle.sh
backup="$ISSUEBOT_HOME/backups/REPLACE_WITH_REPORTED_UTC_DIRECTORY"
recover_native_cutover "$backup"
```

The function requires the backup to be under `${ISSUEBOT_HOME}/backups`, validates protected file modes, strict/unique recovery records, H2 checksum and byte count, exact systemd unit path/checksum, and the recorded process pattern before mutation. It then acquires the deployment lock, stops/removes both candidate Compose services, proves the port/process/H2 are closed, preserves the failed database under `deployments/failed/<UTC timestamp>/`, restores H2 through a checksum-verified temporary file, and starts the exact recorded systemd unit. If the unit was originally enabled but the failed cutover disabled it, recovery re-enables and verifies it; it never automatically unmasks a unit. Success requires the unit active, the recorded process present, and every port `8090` owner matching that process.

`pidfile` recovery is intentionally rejected before Compose or H2 mutation because a pidfile cannot prove the original executable/unit identity or autostart behavior. Convert the native owner to a documented systemd user/system unit or perform a separately reviewed manual recovery; do not claim automated native recovery from pidfile metadata.

## Operator-approved H2 restore

Use this only after an application rollback is schema-unsafe or the new database is known to be corrupt, and only with explicit operator approval. The restricted deploy key cannot perform this procedure. The commands intentionally stop both native and Compose ownership, preserve the failed database, strictly validate the selected backup's own `backup.metadata` and `previous.manifest`, restore ownership/mode, and start only the IssueBot/provider versions stored with that backup. Never select the application release from `deployments/current.manifest` during an H2 restore: the database and release must remain one backup set.

Open an unrestricted host shell, change to the checkout, and load the protected deployment file with the allowlisting parser:

```bash
ssh -t dbbaskette@home-services.local
cd /home/dbbaskette/IssueBot
source deploy/lib/common.sh
DEPLOY_ENV=/home/dbbaskette/.config/issuebot/deploy.env
export DEPLOY_ENV
load_deploy_env "$DEPLOY_ENV"
source deploy/lib/lifecycle.sh
```

Stop Compose and the recorded native service (run the matching native command even if it is already inactive), then prove no process owns the database or port:

```bash
docker compose --env-file "$DEPLOY_ENV" stop -t 60 issuebot codex-cli-provider
case "$NATIVE_SERVICE_KIND" in
  systemd-user) systemctl --user stop "$NATIVE_SERVICE_NAME" ;;
  systemd-system) sudo systemctl stop "$NATIVE_SERVICE_NAME" ;;
  pidfile)
    if test -f "$NATIVE_SERVICE_NAME"; then kill -TERM "$(cat "$NATIVE_SERVICE_NAME")"; fi
    ;;
  *) printf 'Unknown native service kind\n' >&2; false ;;
esac
! pgrep -f -- "$ISSUEBOT_NATIVE_PROCESS_PATTERN"
! lsof -nP -iTCP:8090 -sTCP:LISTEN
require_h2_closed
```

Select the exact verified backup directory reported by the failed deployment. Before moving the failed database, require and strictly load the `previous.manifest` copied into that backup by `backup_h2`; `require_private_file`, `read_manifest`, and `load_manifest_map` reject symlinks, unsafe modes, malformed records, and non-allowlisted keys. Then validate all required release identities and locally available images, verify the backup checksum and size, restore through a temporary file, and verify the restored checksum:

```bash
backup=/home/dbbaskette/.issuebot/backups/YYYYMMDDTHHMMSSZ
restore_manifest="$backup/previous.manifest"
db="$ISSUEBOT_HOME/issuebot.mv.db"
stamp=$(date -u +%Y%m%dT%H%M%SZ)
failed="$ISSUEBOT_HOME/issuebot.failed.$stamp.mv.db"
require_private_file "$restore_manifest"
load_manifest_map "$restore_manifest"
[[ "$PREVIOUS_issuebot_git_sha" =~ ^[0-9a-f]{40}$ ]]
[[ "$PREVIOUS_issuebot_image_id" =~ ^sha256:[0-9a-f]{64}$ ]]
validate_provider_image "$PREVIOUS_provider_image"
[[ "$PREVIOUS_provider_digest" =~ ^sha256:[0-9a-f]{64}$ ]]
test "$PREVIOUS_provider_digest" = "${PREVIOUS_provider_image##*@}"
[[ "$PREVIOUS_provider_protocol" =~ ^[0-9]+$ ]]
git cat-file -e "$PREVIOUS_issuebot_git_sha^{commit}"
test "$(docker image inspect --format '{{.Id}}' "issuebot:$PREVIOUS_issuebot_git_sha")" = "$PREVIOUS_issuebot_image_id"
docker image inspect "$PREVIOUS_provider_image" >/dev/null
expected_sha=$(awk -F= '$1=="database_sha256" {print $2}' "$backup/backup.metadata")
expected_bytes=$(awk -F= '$1=="database_bytes" {print $2}' "$backup/backup.metadata")
[[ "$expected_sha" =~ ^[0-9a-f]{64}$ ]]
[[ "$expected_bytes" =~ ^[0-9]+$ ]]
test "$(sha256_file "$backup/issuebot.mv.db")" = "$expected_sha"
test "$(file_bytes "$backup/issuebot.mv.db")" = "$expected_bytes"
mv "$db" "$failed"
cp -p "$backup/issuebot.mv.db" "$db.restore"
chown "$APP_UID:$APP_GID" "$db.restore"
chmod 0600 "$db.restore"
test "$(sha256_file "$db.restore")" = "$expected_sha"
mv "$db.restore" "$db"
require_h2_closed
```

Use only the already-validated release variables loaded from `$backup/previous.manifest`, then start those already-local images without a build or pull:

```bash
ISSUEBOT_GIT_SHA=$PREVIOUS_issuebot_git_sha
ISSUEBOT_IMAGE_ID=$PREVIOUS_issuebot_image_id
CODEX_CLI_PROVIDER_IMAGE=$PREVIOUS_provider_image
PROVIDER_DIGEST=$PREVIOUS_provider_digest
CODEX_CLI_PROVIDER_PROTOCOL_VERSION=$PREVIOUS_provider_protocol
BACKUP_PATH=$backup
BUILD_DATE=$(date -u +%Y-%m-%dT%H:%M:%SZ)
export ISSUEBOT_GIT_SHA ISSUEBOT_IMAGE_ID CODEX_CLI_PROVIDER_IMAGE PROVIDER_DIGEST
export CODEX_CLI_PROVIDER_PROTOCOL_VERSION BACKUP_PATH BUILD_DATE
docker compose --env-file "$DEPLOY_ENV" up -d --no-build codex-cli-provider issuebot
verify_release
```

Expected: verification succeeds against the restored H2 database. Keep `issuebot.failed.<timestamp>.mv.db`, the selected backup, and failed diagnostics until the incident is closed. If any checksum, ownership, image identity, or health check fails, stop and do not try a different version speculatively.

## Reboot verification

After a successful cutover and native-service disablement, reboot only in an approved maintenance window:

```bash
./deploy/deploy-remote.sh status
ssh dbbaskette@home-services.local 'systemctl --user is-enabled issuebot.service || true'
ssh dbbaskette@home-services.local 'sudo reboot'
```

After the host returns:

```bash
./deploy/deploy-remote.sh status
ssh dbbaskette@home-services.local 'curl --fail --silent --show-error http://127.0.0.1:8090/actuator/health/liveness'
ssh dbbaskette@home-services.local 'curl --fail --silent --show-error http://127.0.0.1:8090/actuator/health/readiness'
ssh dbbaskette@home-services.local 'pgrep -af issuebot-0.1.0-SNAPSHOT.jar || true; lsof -nP -iTCP:8090 -sTCP:LISTEN'
```

Expected: Compose restarts both healthy services, no native Java process returns, and only Docker owns the port 8090 listener. Record the reboot timestamp, Git SHA, provider digest/protocol, health output, manifest path, and verified backup path in deployment history; never copy secret files into that history.

## Retention

Retain at least the current known-good backup and manifest, every backup referenced by a retained manifest or unresolved incident, the failed database from an open incident, and locally referenced IssueBot/provider images. Apply the organization's approved time/count policy only after listing references:

```bash
find "$ISSUEBOT_HOME/backups" -mindepth 1 -maxdepth 1 -type d -print | sort
find "$ISSUEBOT_HOME/deployments" -type f \( -name '*.manifest' -o -name 'backup.metadata' \)
docker image ls --digests issuebot
```

Never use `docker compose down -v`; the H2 bind mount is authoritative. Delete backups, diagnostics, failed databases, or images only after confirming they are not referenced by `current.manifest`, `previous.manifest`, `native-recovery.manifest`, a candidate/failed manifest, or an open incident.

## Troubleshooting

| Symptom | Boundary and response |
|---|---|
| Dirty, untracked, detached, wrong, upstream-less, or divergent checkout | Pre-shutdown refusal. Clean the checkout and reconcile it through reviewed Git history; never force-reset production. |
| `git pull --ff-only` fails | Pre-shutdown refusal; the old process remains running. Correct the remote/branch history before retrying. |
| Maven/image build fails | Pre-shutdown refusal; inspect build output and fix/test in source. |
| Provider tag, zero digest, wrong platform, root/empty user, healthcheck, protocol, or doctor label | Pre-shutdown refusal. Select a compatible externally published non-root digest; do not add a provider build here. |
| Provider doctor fails | Post-start verification failure. Inspect `logs codex-cli-provider 200` and failed diagnostics; the doctor command must remain `codex-cli-provider doctor --json --no-billable-work`. |
| Port 8090 occupied | Before first cutover, identify the exact native owner and pattern. Later, only the exact configured Compose project/service is accepted before its controlled stop; stop any other owner through its own manager and never kill an unidentified PID. |
| Native autostart cannot be disabled | The candidate is stopped/removed and closure is verified. Use a supported systemd user/system service; pidfile ownership alone cannot prove reboot safety. |
| `H2 database is still open` | Stop all native/Compose IssueBot processes and identify the holder with `lsof "$ISSUEBOT_HOME/issuebot.mv.db"`; never copy it while open. |
| Liveness/readiness timeout | Post-shutdown diagnostics are under `deployments/failed/`; inspect bounded logs, persistent-path ownership, database/Flyway messages, and resource limits. |
| Flyway migration sets differ | Automatic rollback refuses data restoration and prints the verified backup. Obtain operator approval and use the manual H2 restore procedure with the previous known-good version only. |
| Protected file rejected | Ensure it is a regular non-symlink outside the checkout with mode `0600`, contains only allowlisted literal records, and contains no provider credentials. |
| Reboot starts two IssueBot owners | Stop the native service, confirm Compose health, disable the verified native autostart unit, and repeat the port/process checks. |

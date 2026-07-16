# Docker Deployment Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a secure Docker Compose deployment for IssueBot on `dbbaskette@home-services.local` that fast-forward-updates source, preserves its H2 data, consumes a separately released pinned `codex-cli-provider` image, verifies health, and supports schema-safe rollback.

**Architecture:** The IssueBot repository owns a multi-stage application image, production Compose file, persistent H2 bind mount, host deployment library, restricted SSH wrapper, and operator client. IssueBot is built on the remote host from a verified Git SHA; `codex-cli-provider` is built only by its separate project and pulled here by immutable digest. Existing `~/.issuebot` state remains a host bind mount, and production cutover is blocked until separate provider integration removes IssueBot's direct local `claude` dependency.

**Tech Stack:** Java 21, Spring Boot 3.4.2 Actuator, Maven Wrapper, Docker Engine, Docker Compose v2, Bash, H2, Flyway, JUnit 5, Mockito, MockMvc

## Global Constraints

- Target host is exactly `dbbaskette@home-services.local`.
- Every deployment runs `git fetch --prune` followed by `git pull --ff-only` before build or restart.
- Deployment refuses a dirty, detached, divergent, unexpected-branch, or upstream-less checkout.
- IssueBot is built from the updated checkout and tagged with its full Git SHA.
- `CODEX_CLI_PROVIDER_IMAGE` must match the immutable-image rule `^.+@sha256:[0-9a-f]{64}$`.
- `codex-cli-provider` build logic, internals, authentication, and credential formats remain out of scope.
- PostgreSQL, SQLite, and MinIO are not part of this stack; IssueBot keeps its current file-backed H2 database on the host bind mount.
- Production cutover is forbidden until a compatible `codex-cli-provider` release and IssueBot provider integration exist.
- `${HOME}/.issuebot` remains authoritative; H2 is copied only while every IssueBot process is stopped.
- Automation never invokes `docker compose down -v` and never automatically restores H2.
- Secrets remain outside Git and image layers.
- A dedicated SSH key is restricted to `preflight`, `deploy`, `rollback`, `status`, and `logs`.
- Only IssueBot port `8090` is published; the runner is private to the Compose network.

## File Map

- `src/main/java/com/dbbaskette/issuebot/observability/IssueBotLivenessHealthIndicator.java` — process-only liveness.
- `src/main/java/com/dbbaskette/issuebot/observability/PersistentStorageHealthIndicator.java` — persistent-path readiness.
- `src/test/java/com/dbbaskette/issuebot/observability/*HealthIndicatorTest.java` — health contracts.
- `src/test/java/com/dbbaskette/issuebot/security/SecurityConfigHealthEndpointTest.java` — unauthenticated probe access.
- `src/main/resources/application.yml`, `pom.xml`, `SecurityConfig.java` — probe groups and build metadata.
- `Dockerfile`, `.dockerignore` — reproducible non-root application image.
- `compose.yaml`, `deploy/production.env.example` — production topology and variable contract.
- `deploy/lib/common.sh` — logging, redaction, locking, validation, and manifests.
- `deploy/lib/preflight.sh` — host, checkout, storage, secrets, image, and port checks.
- `deploy/lib/lifecycle.sh` — update, build, backup, cutover, verification, and rollback.
- `deploy/issuebot-deploy` — forced-command dispatcher.
- `deploy/deploy-remote.sh` — local SSH operator client.
- `deploy/install-remote-wrapper.sh` — one-time restricted-key installation helper.
- `deploy/tests/*.sh` — isolated image, Compose, guard, lifecycle, and dispatcher tests.
- `docs/deployment.md`, `README.md` — operator runbook and entry point.

---

### Task 1: Separate liveness, readiness, and build identity

**Files:**
- Create: `src/main/java/com/dbbaskette/issuebot/observability/IssueBotLivenessHealthIndicator.java`
- Create: `src/main/java/com/dbbaskette/issuebot/observability/PersistentStorageHealthIndicator.java`
- Create: `src/test/java/com/dbbaskette/issuebot/observability/IssueBotLivenessHealthIndicatorTest.java`
- Create: `src/test/java/com/dbbaskette/issuebot/observability/PersistentStorageHealthIndicatorTest.java`
- Create: `src/test/java/com/dbbaskette/issuebot/security/SecurityConfigHealthEndpointTest.java`
- Modify: `src/main/resources/application.yml`
- Modify: `src/main/java/com/dbbaskette/issuebot/security/SecurityConfig.java`
- Modify: `pom.xml`

**Interfaces:**
- Produces: public `GET /actuator/health/liveness` containing no external dependency.
- Produces: public `GET /actuator/health/readiness` covering database, disk, and persistent paths. Runner and GitHub checks remain explicit post-deploy integration checks so temporary external failure cannot make the JVM appear dead.
- Produces: Spring Boot build metadata at authenticated `/actuator/info`.

- [ ] **Step 1: Write failing indicator tests**

```java
@Test
void livenessDoesNotDependOnExternalServices() {
    Health health = new IssueBotLivenessHealthIndicator().health();
    assertThat(health.getStatus()).isEqualTo(Status.UP);
    assertThat(health.getDetails()).containsEntry("application", "responsive");
}

@Test
void storageIsDownWhenApplicationHomeDoesNotExist() {
    Path home = tempDir.resolve("missing").resolve(".issuebot");
    Health health = new PersistentStorageHealthIndicator(home, home.resolve("repos")).health();
    assertThat(health.getStatus()).isEqualTo(Status.DOWN);
    assertThat(health.getDetails()).containsKey("applicationHome");
}
```

Use `@TempDir`. Add positive writable-directory coverage and separate failures for missing `repos` and `logs`.

- [ ] **Step 2: Verify the new tests fail to compile**

Run: `./mvnw -q -Dtest=IssueBotLivenessHealthIndicatorTest,PersistentStorageHealthIndicatorTest test`

Expected: FAIL because both classes are missing.

- [ ] **Step 3: Implement the indicators**

```java
@Component("issueBotLiveness")
public final class IssueBotLivenessHealthIndicator implements HealthIndicator {
    @Override
    public Health health() {
        return Health.up().withDetail("application", "responsive").build();
    }
}
```

`PersistentStorageHealthIndicator` has a production constructor using `${user.home}/.issuebot` and `IssueBotProperties#getWorkDirectory`, plus a package-private `(Path applicationHome, Path workDirectory)` constructor. It checks that application home, work directory, and `logs` exist, are directories, and are writable; any failure returns `DOWN` with path/status details but no secret values.

- [ ] **Step 4: Configure health groups and build info**

Add:

```yaml
management:
  endpoint:
    health:
      show-details: always
      probes:
        enabled: true
      group:
        liveness:
          include: livenessState,issueBotLiveness
        readiness:
          include: readinessState,db,diskSpace,persistentStorage
  info:
    build:
      enabled: true
```

Add the `build-info` goal to the existing `spring-boot-maven-plugin`; add no dependency.

- [ ] **Step 5: Permit grouped health URLs with authentication enabled**

Replace the exact health matcher with:

```java
.requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
```

In `SecurityConfigHealthEndpointTest`, use `@SpringBootTest`, `@AutoConfigureMockMvc`, configured dashboard credentials, and assert health groups are public while `/actuator/info` requires authentication.

- [ ] **Step 6: Run focused and full tests**

Run: `./mvnw -q -Dtest=IssueBotLivenessHealthIndicatorTest,PersistentStorageHealthIndicatorTest,SecurityConfigHealthEndpointTest test`

Expected: PASS.

Run: `./mvnw test`

Expected: BUILD SUCCESS.

- [ ] **Step 7: Commit**

```bash
git add pom.xml src/main/resources/application.yml \
  src/main/java/com/dbbaskette/issuebot/observability \
  src/main/java/com/dbbaskette/issuebot/security/SecurityConfig.java \
  src/test/java/com/dbbaskette/issuebot/observability \
  src/test/java/com/dbbaskette/issuebot/security/SecurityConfigHealthEndpointTest.java
git commit -m "feat: add deployment health probes"
```

### Task 2: Build the hardened IssueBot image and Compose topology

**Files:**
- Create: `Dockerfile`
- Create: `.dockerignore`
- Create: `compose.yaml`
- Create: `deploy/production.env.example`
- Create: `deploy/tests/image-test.sh`
- Create: `deploy/tests/compose-test.sh`
- Modify: `.gitignore`

**Interfaces:**
- Consumes: Task 1 probe endpoints.
- Produces build arguments: `APP_UID`, `APP_GID`, `VCS_REF`, `BUILD_DATE`.
- Produces services `issuebot` and `codex-cli-provider` on private network `backend`.
- Consumes provider contract: immutable image, embedded OCI healthcheck, label `com.issuebot.codex-provider.protocol`, and non-billable `codex-cli-provider doctor --json --no-billable-work`.

- [ ] **Step 1: Write failing image and Compose contract tests**

`image-test.sh` asserts:

```bash
grep -Eq '^FROM .*@sha256:[0-9a-f]{64} AS build$' Dockerfile
grep -Eq '^FROM .*@sha256:[0-9a-f]{64}$' Dockerfile
grep -Fq 'USER issuebot' Dockerfile
grep -Fq '/actuator/health/liveness' Dockerfile
grep -Fq '.env' .dockerignore
grep -Fq '.git' .dockerignore
grep -Fq '.issuebot' .dockerignore
```

`compose-test.sh` renders config with a temporary fixture and asserts exactly two services, only IssueBot publishes `127.0.0.1:8090:8090`, both services use `read_only`, `no-new-privileges`, dropped capabilities, bounded resources, and rotated logs, the provider image contains `@sha256:`, the provider has no `build:` section, and neither service mounts Docker socket or SSH paths. It also asserts there are no PostgreSQL or MinIO services and that `${ISSUEBOT_HOME}` maps to `/home/issuebot/.issuebot`.

- [ ] **Step 2: Verify both tests fail**

Run: `bash deploy/tests/image-test.sh; bash deploy/tests/compose-test.sh`

Expected: both fail because deployment assets are absent.

- [ ] **Step 3: Create the multi-stage Dockerfile**

Use implementation-time verified official Maven 3.9/Temurin 21 and Temurin 21 JRE multi-architecture digests. The builder runs `./mvnw --batch-mode clean verify`. The runtime image creates UID/GID-configurable `issuebot`, copies only the JAR, runs `USER issuebot`, exposes `8090`, includes OCI source/revision/created labels, and checks `http://127.0.0.1:8090/actuator/health/liveness`. Use a pinned runtime containing the health client or copy a pinned static client; never install unpinned packages.

- [ ] **Step 4: Create `.dockerignore`**

```text
.git
.github
.idea
.vscode
.env
.env.*
.issuebot
target
*.db
*.trace.db
*.lock.db
docs
deploy/tests
```

- [ ] **Step 5: Create `compose.yaml`**

The IssueBot service includes:

```yaml
build:
  context: .
  args:
    APP_UID: ${APP_UID:?APP_UID is required}
    APP_GID: ${APP_GID:?APP_GID is required}
    VCS_REF: ${ISSUEBOT_GIT_SHA:?ISSUEBOT_GIT_SHA is required}
    BUILD_DATE: ${BUILD_DATE:?BUILD_DATE is required}
image: issuebot:${ISSUEBOT_GIT_SHA:?ISSUEBOT_GIT_SHA is required}
user: "${APP_UID}:${APP_GID}"
ports:
  - "${ISSUEBOT_BIND_ADDRESS:-127.0.0.1}:8090:8090"
env_file:
  - ${ISSUEBOT_SECRET_ENV:?ISSUEBOT_SECRET_ENV is required}
environment:
  HOME: /home/issuebot
  SPRING_PROFILES_ACTIVE: prod
volumes:
  - ${ISSUEBOT_HOME:?ISSUEBOT_HOME is required}:/home/issuebot/.issuebot
```

Provider image is `${CODEX_CLI_PROVIDER_IMAGE:?CODEX_CLI_PROVIDER_IMAGE is required}` and must not have a Compose `build:` section. Provider credentials/state use separate protected mounts defined by the provider contract. Both services use `restart: unless-stopped`, read-only roots, `tmpfs: [/tmp]`, all capabilities dropped, `no-new-privileges`, PID/CPU/memory limits, JSON log rotation (`10m`, five files), and `backend`. Provider has no `ports`; IssueBot readiness uses `/actuator/health/readiness`.

- [ ] **Step 6: Define non-secret production variables**

```dotenv
COMPOSE_PROJECT_NAME=issuebot
APP_UID=1000
APP_GID=1000
ISSUEBOT_HOME=/home/dbbaskette/.issuebot
ISSUEBOT_BIND_ADDRESS=127.0.0.1
ISSUEBOT_SECRET_ENV=/home/dbbaskette/.config/issuebot/runtime.env
CODEX_CLI_PROVIDER_IMAGE=registry.example.invalid/providers/codex-cli-provider@sha256:0000000000000000000000000000000000000000000000000000000000000000
CODEX_CLI_PROVIDER_PROTOCOL_VERSION=1
```

The invalid registry/zero digest is intentionally rejected by preflight. Ignore `deploy/production.env` and `deploy/*.local.env`.

- [ ] **Step 7: Verify assets**

Run: `bash deploy/tests/image-test.sh && bash deploy/tests/compose-test.sh`

Expected: both print PASS.

Run: `docker compose --env-file deploy/production.env.example config --quiet`

Expected: exit 0.

Build with current UID/GID, SHA, and UTC timestamp; expected Maven BUILD SUCCESS and an image configured as user `issuebot` with the full SHA label.

- [ ] **Step 8: Commit**

```bash
git add Dockerfile .dockerignore compose.yaml .gitignore \
  deploy/production.env.example deploy/tests/image-test.sh deploy/tests/compose-test.sh
git commit -m "build: add production Compose stack"
```

### Task 3: Implement common guards and read-only preflight

**Files:**
- Create: `deploy/lib/common.sh`
- Create: `deploy/lib/preflight.sh`
- Create: `deploy/tests/test-helper.sh`
- Create: `deploy/tests/common-test.sh`
- Create: `deploy/tests/preflight-test.sh`

**Interfaces:**
- Produces `die`, `log`, `redact`, `require_command`, `acquire_lock`, `validate_provider_image`, `load_deploy_env`, and atomic manifest read/write.
- Produces `preflight_all`, `preflight_checkout`, `preflight_runtime`, `preflight_storage`, and `preflight_runner`.
- Every failure returns before lifecycle mutation.

- [ ] **Step 1: Create an isolated shell-test fixture**

`test-helper.sh` creates temporary `HOME`, checkout, state, and `mock-bin`, provides `mock_command`, success/failure/output assertions, and cleans through `trap`. Tests run in subshells; no test invokes real Docker or changes the real checkout.

- [ ] **Step 2: Write failing common tests**

```bash
assert_success validate_provider_image 'ghcr.io/acme/codex-cli-provider@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa'
assert_failure validate_provider_image 'ghcr.io/acme/codex-cli-provider:latest'
assert_failure validate_provider_image 'registry.example.invalid/providers/codex-cli-provider@sha256:0000000000000000000000000000000000000000000000000000000000000000'
assert_failure load_deploy_env "$world_readable_env"
assert_contains '[REDACTED]' "$(redact 'Authorization: Bearer ghp_abcdefghijklmnopqrstuvwxyz0123456789AB')"
```

Prove two lock holders cannot succeed concurrently and manifest writes use temporary file plus atomic `mv`.

- [ ] **Step 3: Verify common tests fail**

Run: `bash deploy/tests/common-test.sh`

Expected: FAIL because functions are absent.

- [ ] **Step 4: Implement `common.sh`**

Use `set -Eeuo pipefail`, `flock -n`, `umask 077`, UTC timestamps, `printf`, environment mode rejection for any group/other bits, and redaction for GitHub/Anthropic/bearer/password/webhook patterns. Manifests are allowlisted `key=value` data and are never sourced.

- [ ] **Step 5: Write failing preflight tests**

Cover clean attached expected branch, dirty files, untracked files, detached `HEAD`, unexpected branch, absent upstream, divergence, missing Compose v2, less than 5 GiB free, unknown owner on `8090`, allowed identified native process during first cutover, secret-file mode, provider digest/platform/healthcheck/protocol, and non-billable doctor contract.

- [ ] **Step 6: Verify preflight tests fail**

Run: `bash deploy/tests/preflight-test.sh`

Expected: FAIL because preflight functions are absent.

- [ ] **Step 7: Implement `preflight.sh`**

Checkout checks execute:

```bash
git -C "$ISSUEBOT_CHECKOUT" symbolic-ref -q HEAD
git -C "$ISSUEBOT_CHECKOUT" diff --quiet
git -C "$ISSUEBOT_CHECKOUT" diff --cached --quiet
test -z "$(git -C "$ISSUEBOT_CHECKOUT" ls-files --others --exclude-standard)"
test "$(git -C "$ISSUEBOT_CHECKOUT" branch --show-current)" = "$ISSUEBOT_BRANCH"
git -C "$ISSUEBOT_CHECKOUT" rev-parse --abbrev-ref '@{upstream}'
```

Preflight does not fetch or pull. Runtime checks Docker/Compose/Git/curl, target architecture, disk, secrets, port ownership, native service identity, and persistent paths. Runner checks use manifest/image inspection for immutable digest, platform, embedded healthcheck, and exact protocol label; doctor runs only after pull.

- [ ] **Step 8: Run and commit**

Run: `bash deploy/tests/common-test.sh && bash deploy/tests/preflight-test.sh`

Expected: both PASS.

```bash
git add deploy/lib/common.sh deploy/lib/preflight.sh deploy/tests
git commit -m "feat: add deployment preflight guards"
```

### Task 4: Implement deployment lifecycle and schema-safe rollback

**Files:**
- Create: `deploy/lib/lifecycle.sh`
- Create: `deploy/tests/lifecycle-test.sh`

**Interfaces:**
- Consumes Task 3 guards.
- Produces `deploy_release`, `rollback_release`, `verify_release`, `backup_h2`, and `stop_native_issuebot`.
- Manifest keys: `issuebot_git_sha`, `issuebot_image_id`, `provider_image`, `provider_digest`, `provider_protocol`, `deployed_at`, `backup_path`, `compose_project`.

- [ ] **Step 1: Write failing ordering and failure-safety tests**

Mock external commands into `$CALL_LOG`. Successful ordering must begin:

```text
lock preflight fetch pull preflight compose-config build runner-pull runner-contract native-stop port-free h2-closed backup compose-up liveness readiness functional-check manifest
```

Prove pull/build/runner failures never call native-stop; native-stop failure never backs up or starts Compose; backup failure never starts Compose; failed verification captures diagnostics before rollback.

- [ ] **Step 2: Verify lifecycle tests fail**

Run: `bash deploy/tests/lifecycle-test.sh`

Expected: FAIL because lifecycle functions are absent.

- [ ] **Step 3: Implement update and build before shutdown**

```bash
git -C "$ISSUEBOT_CHECKOUT" fetch --prune
git -C "$ISSUEBOT_CHECKOUT" pull --ff-only
ISSUEBOT_GIT_SHA="$(git -C "$ISSUEBOT_CHECKOUT" rev-parse HEAD)"
BUILD_DATE="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
export ISSUEBOT_GIT_SHA BUILD_DATE
docker compose --env-file "$DEPLOY_ENV" config --quiet
docker compose --env-file "$DEPLOY_ENV" build --pull issuebot
docker compose --env-file "$DEPLOY_ENV" pull codex-cli-provider
```

Rerun checkout/runner preflight after pull. Resolve image IDs and digests from Docker inspection, never tags alone.

- [ ] **Step 4: Implement first-cutover shutdown and closed-H2 backup**

Support protected `NATIVE_SERVICE_KIND` values `systemd-user`, `systemd-system`, or `pidfile`, plus `NATIVE_SERVICE_NAME`. Gracefully stop, wait up to 60 seconds, require no matching Java process/listener, then use `lsof` or `fuser` to prove H2 closed. Unknown ownership aborts.

Create `${ISSUEBOT_HOME}/backups/<UTC timestamp>/`, copy the closed `issuebot.mv.db`, record SHA-256/bytes and current manifest, then verify checksum before continuing.

- [ ] **Step 5: Implement start and verification**

```bash
docker compose --env-file "$DEPLOY_ENV" up -d --no-build codex-cli-provider issuebot
```

Poll liveness/readiness for 120 seconds. Verify both services healthy; expected Git SHA; no H2/Flyway failure; writable config/repos/log paths; expected dashboard auth response; GitHub UP unless explicitly allowed degraded; exact provider protocol; successful `codex-cli-provider doctor --json --no-billable-work`; no native process; correct port owner; and no sanitized fatal startup pattern.

- [ ] **Step 6: Implement rollback without automatic data restore**

Load only allowlisted previous-manifest keys. Refuse automatic rollback when Flyway migration sets differ unless the new manifest records a tested `schema_rollback_compatible=true`. Never restore H2. Reuse only a locally present prior commit/image and recorded provider digest, then run full verification. Store diagnostics under `${ISSUEBOT_HOME}/deployments/failed/<timestamp>/` and print the verified backup path when manual restoration is required.

- [ ] **Step 7: Run all shell tests and commit**

```bash
for test_file in deploy/tests/*-test.sh; do bash "$test_file"; done
```

Expected: every test PASS; none invokes real Docker or modifies the real checkout.

```bash
git add deploy/lib/lifecycle.sh deploy/tests/lifecycle-test.sh
git commit -m "feat: add safe deployment lifecycle"
```

### Task 5: Restrict SSH access and provide operator commands

**Files:**
- Create: `deploy/issuebot-deploy`
- Create: `deploy/deploy-remote.sh`
- Create: `deploy/install-remote-wrapper.sh`
- Create: `deploy/tests/dispatcher-test.sh`

**Interfaces:**
- Produces remote commands `preflight`, `deploy`, `rollback`, `status`, and `logs [issuebot|codex-cli-provider] [1-500]`.
- Consumes forced-command variable `SSH_ORIGINAL_COMMAND`.

- [ ] **Step 1: Write failing dispatcher tests**

Accept the exact allowlist and reject `bash`, `deploy; id`, `logs issuebot 501`, `logs ../../etc/passwd 10`, `status --help`, and `deploy $(id)`. Rejected input must never reach mocked lifecycle functions; output passes through redaction.

- [ ] **Step 2: Verify dispatcher tests fail**

Run: `bash deploy/tests/dispatcher-test.sh`

Expected: FAIL because dispatcher is missing.

- [ ] **Step 3: Implement forced-command dispatcher**

Reject characters outside `[A-Za-z0-9._/-]` plus whitespace, parse at most three tokens, enforce exact arity with `case`, load `/home/dbbaskette/.config/issuebot/deploy.env`, enter `ISSUEBOT_CHECKOUT`, source repository-owned libraries, and dispatch. Logs run:

```bash
docker compose --env-file "$DEPLOY_ENV" logs --no-color --tail "$line_count" "$service"
```

- [ ] **Step 4: Implement local client and installer**

Client defaults:

```bash
DEPLOY_HOST=dbbaskette@home-services.local
DEPLOY_KEY=${HOME}/.ssh/issuebot_deploy_ed25519
```

Use `IdentitiesOnly=yes`, `BatchMode=yes`, `ForwardAgent=no`, 10-second timeout, and strict host-key checking. Never accept arbitrary remote strings.

Installer requires an explicit Ed25519 public-key file and existing interactive SSH access, installs wrapper at `/home/dbbaskette/.local/libexec/issuebot-deploy` mode `0755`, rejects duplicate fingerprints, and installs:

Generate the authorized-key line without a textual placeholder:

```bash
key_data="$(awk '$1 == "ssh-ed25519" { print $2; exit }' "$PUBLIC_KEY_FILE")"
printf 'restrict,command="/home/dbbaskette/.local/libexec/issuebot-deploy" ssh-ed25519 %s issuebot-deploy\n' "$key_data"
```

It never generates, overwrites, or transmits a private key.

- [ ] **Step 5: Run tests and commit**

```bash
bash deploy/tests/dispatcher-test.sh
bash -n deploy/issuebot-deploy deploy/deploy-remote.sh deploy/install-remote-wrapper.sh deploy/lib/*.sh
```

Expected: dispatcher PASS and no syntax errors.

```bash
git add deploy/issuebot-deploy deploy/deploy-remote.sh \
  deploy/install-remote-wrapper.sh deploy/tests/dispatcher-test.sh
git commit -m "feat: restrict remote deployment access"
```

### Task 6: Write the runbook and perform local acceptance

**Files:**
- Create: `docs/deployment.md`
- Modify: `README.md`
- Modify: `.env.example`
- Modify: only Task 1–5 files when acceptance exposes defects.

**Interfaces:**
- Documents Tasks 2–5.
- Produces verified local evidence without production deployment.

- [ ] **Step 1: Write the complete runbook**

Include executable commands and expected outcomes for architecture/scope, prerequisites, `ssh-keygen -t ed25519 -f ~/.ssh/issuebot_deploy_ed25519 -C issuebot-deploy`, wrapper installation, mode-`0600` deploy/runtime files, registry login, digest selection, native service discovery, backup verification, first cutover, status/health/logs, rollback, operator-approved H2 restore, reboot verification, retention, and troubleshooting.

The H2 restore procedure stops native and Compose processes, preserves the failed DB with timestamp, verifies backup checksum, restores ownership, and starts only the previous known-good version.

- [ ] **Step 2: Update README and `.env.example`**

Add a Docker deployment link, independent-provider boundary, and production-cutover gate. Add only IssueBot-supported runtime secrets; never provider credentials.

- [ ] **Step 3: Run complete local verification**

```bash
./mvnw clean verify
for test_file in deploy/tests/*-test.sh; do bash "$test_file"; done
bash -n deploy/*.sh deploy/lib/*.sh deploy/tests/*.sh
docker compose --env-file deploy/production.env.example config --quiet
```

Expected: Maven BUILD SUCCESS, all shell tests PASS, no syntax errors, Compose valid.

- [ ] **Step 4: Build and inspect the real image**

Build with current UID/GID, full SHA, and UTC timestamp. Inspect user, labels, healthcheck, exposed ports, entrypoint, and filesystem.

Expected: non-root `issuebot`, correct SHA, liveness healthcheck, only `8090`, Java entrypoint, and no `.env`, Git metadata, Maven executable, source, or `.issuebot` data.

- [ ] **Step 5: Exercise disposable persistence and failure fixtures**

Use only temporary IssueBot home/secret files and a test H2 DB. Start/stop a disposable native instance, prove H2 closed, back up, start Compose on the same mount, recreate, and verify data/ownership. Inject dirty checkout, non-fast-forward pull, Maven failure, invalid runner, doctor failure, occupied port, open H2, readiness timeout, and migration incompatibility.

Expected: pre-shutdown failures leave the old process running; post-shutdown failures capture diagnostics; schema-unsafe rollback refuses data restoration and reports the backup.

- [ ] **Step 6: Commit documentation and any acceptance fixes**

```bash
git add docs/deployment.md README.md .env.example
git commit -m "docs: add IssueBot deployment runbook"
```

If acceptance changed code, commit those fixes separately as `fix: satisfy deployment acceptance checks`; never create an empty commit.

### Task 7: Perform the gated first production cutover

**Files:**
- Remote protected configuration and deployment history only.

**Interfaces:**
- Consumes all completed tasks, dedicated key, compatible pinned `codex-cli-provider`, and merged IssueBot provider integration.
- Produces verified Compose deployment and disables native autostart.

- [ ] **Step 1: Confirm external gates**

Record the exact provider digest/architecture/protocol, successful non-billable doctor result, proof IssueBot no longer needs local `claude`, mode-`0600` configuration, known native service identity, and at least twice the H2 database size available for backup.

- [ ] **Step 2: Run preflight**

Run: `./deploy/deploy-remote.sh preflight`

Expected: PASS with branch/SHA, Docker/Compose, architecture, disk, port owner, secret modes, provider contract, and native-service identity.

- [ ] **Step 3: Deploy**

Run: `./deploy/deploy-remote.sh deploy`

Expected: fast-forward pull, test-bearing build, provider pull, graceful native stop, verified H2 backup, healthy services, functional checks, known-good manifest, and disabled native autostart.

- [ ] **Step 4: Independently verify**

```bash
./deploy/deploy-remote.sh status
./deploy/deploy-remote.sh logs issuebot 200
./deploy/deploy-remote.sh logs codex-cli-provider 200
curl --fail --silent http://home-services.local:8090/actuator/health/liveness
curl --fail --silent http://home-services.local:8090/actuator/health/readiness
```

Expected: healthy services, expected SHA/protocol, no native process, H2 lock, permission error, missing executable, startup exception, or exposed secret.

- [ ] **Step 5: Verify reboot recovery in an approved maintenance window**

Reboot through the normal administrative channel, then rerun status and health checks.

Expected: services recover, persistent data is intact, only containerized IssueBot owns `8090`, and native autostart remains disabled.

- [ ] **Step 6: Record evidence**

Store manifest, backup checksum/path, health output, provider digest/protocol, Git SHA, and reboot timestamp in deployment history. Never copy secret environment files.

---

## External Dependency Gate

Tasks 1–6 are safe without touching production. Task 7 must not begin until the separate `codex-cli-provider` project publishes a compatible immutable image and separate IssueBot provider-integration work removes direct local CLI execution. This plan defines only the provider deployment contract: immutable digest, embedded healthcheck, protocol label, and non-billable doctor command.

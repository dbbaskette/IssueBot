# Final whole-branch review fix report

## Status

DONE. Commit `5810562` (`fix: harden deployment recovery and rollback`) addresses the critical finding, all six important findings, and the requested practical minors. No production deployment was performed. No PostgreSQL, MinIO, or SQLite assets were added; H2 remains authoritative. The provider remains an externally built, digest-pinned image.

## Implemented findings

### Critical: first-cutover failure safety

- Before stopping a native first-cutover owner, deployment atomically writes `deployments/native-recovery.manifest` with the protected service identity and timestamp. The closed-H2 backup also retains this record when no Compose manifest exists.
- Candidate startup, liveness/readiness, functional/migration-log, autostart-disable, and release-manifest failures invoke one recovery boundary.
- When no valid automated Compose rollback target exists, that boundary runs `compose stop -t 60`, `compose rm -f`, verifies both candidate containers are absent, proves port `8090` free, proves the configured native process absent, and proves H2 closed.
- Tests cover first-cutover startup, readiness, functional/migration-like verification, and autostart failures and assert every cleanup/closure stage.

### Important findings

1. Repeat preflight now accepts a listener only when Docker reports exactly one running container labeled with the configured Compose project and `issuebot` service and its `8090/tcp` host binding. Unknown Docker/native listeners remain rejected. Controlled cutover stop is still followed by mandatory free-port proof.
2. Successful releases atomically rotate `current.manifest` to `previous.manifest`. The forced-command dispatcher loads current only for active Compose context, then standalone rollback targets durable previous. Standalone rollback rotates the former active release back to previous after success.
3. Deploy and rollback share the same `flock`. An in-process ownership marker prevents deploy's recovery rollback from trying to reacquire its own lock; standalone rollback acquires it normally. Existing contention coverage remains, and its busy wait is now bounded.
4. `codex-cli-provider` joins internal `backend` plus non-internal `egress`, publishes no port, and retains all hardening controls. Rendered Compose and live temporary Docker-network probes verify the topology without a real provider.
5. First-cutover success disables and verifies native autostart before publishing current known-good. `systemd-user` and `systemd-system` are supported. `pidfile` ownership can be stopped safely but cannot prove reboot autostart state, so first cutover fails closed and cleans the candidate.
6. Provider preflight requires an explicit non-root `.Config.User`; empty, `root`, `root:*`, `0`, and `0:*` are rejected, while explicit named/numeric non-root users are accepted by the baseline fixture. The runbook documents this external image contract.

### Minor findings

- Updated stale `LocalVerificationService` reader-thread comments to describe the bounded drain/forced-close behavior.
- Replaced the brittle preflight mutation grep expression with a line-anchored command check.
- Bounded the common lock-test readiness wait at five seconds.
- Added real test branches for systemd-user, systemd-system, and pidfile shutdown.
- Broadened fatal startup detection and tests for Flyway/migration failures, H2/database-use failures, access/permission failures, failed startup, OOM, and port conflicts.

## TDD evidence

The new regressions were run before implementation and failed for the intended missing behavior:

- `compose-test.sh`: provider lacked non-internal egress.
- `preflight-test.sh`: unsafe provider users were accepted and the exact current Compose listener was rejected.
- `dispatcher-test.sh`: rollback did not load active context or target durable previous.
- `lifecycle-test.sh`: no native recovery record/autostart stage existed and first-cutover candidate failures did not prove cleanup closure.
- The focused fatal-log test failed with `fatal_startup_log_present: command not found` before the detector was added.

After implementation, each focused suite passed before the full verification run.

## Fresh verification evidence

All commands ran from `/Users/dbbaskette/Projects/IssueBot/.worktrees/docker-deployment`.

### Deployment shell suites

```bash
for test_file in deploy/tests/*-test.sh; do bash "$test_file"; done
```

Exit `0`. PASS markers:

- `common-test: PASS`
- `compose-test: PASS`
- `dispatcher-test: PASS`
- `image-test: PASS`
- `lifecycle-test: PASS`
- `preflight-test: PASS`

Expected negative-fixture `ERROR:` lines were emitted while the suites asserted rejection paths.

### Syntax, static analysis, Compose, whitespace

```bash
bash -n deploy/*.sh deploy/lib/*.sh deploy/tests/*.sh
shellcheck -x -e SC2016 deploy/*.sh deploy/lib/*.sh deploy/tests/*.sh
docker compose --env-file deploy/production.env.example config --quiet
git diff --check
```

Every command exited `0` with no output.

### Maven clean verification with isolated IssueBot home

The first sandboxed run reached all 763 tests with zero failures but produced two `Operation not permitted` errors when timeout tests called `ProcessHandle.descendants()`. Per the acceptance instruction, it was rerun with host process permission and a temporary `user.home`:

```bash
test_home="$(mktemp -d /private/tmp/issuebot-mvn-home.XXXXXX)"
trap 'rm -rf "$test_home"' EXIT
./mvnw -Duser.home="$test_home" clean verify
```

Result: `BUILD SUCCESS`; `Tests run: 763, Failures: 0, Errors: 0, Skipped: 0`. The temporary home was removed by the trap, and the real `~/.issuebot` was not used by this final acceptance run.

### Focused live Docker topology

Two temporary networks were created and removed under a cleanup trap; no service/provider container was started:

```text
docker-topology: PASS backend_internal=true egress_internal=false
```

The rendered Compose suite additionally proves the provider attaches to both networks and has no published ports.

## Concerns and gates

- Production cutover remains forbidden until the independently released provider image and IssueBot provider integration satisfy the documented external gate.
- A `pidfile` identifies a process but not its reboot/autostart mechanism. This is intentionally fail-closed; operators must use the documented systemd user/system ownership for a successful first cutover.
- The live Docker topology check required local Docker API permission; its first sandboxed attempt was denied, then the scoped temporary-network check passed with permission.

## Commits

- `5810562 fix: harden deployment recovery and rollback`
- The report itself is committed separately after this file is added.

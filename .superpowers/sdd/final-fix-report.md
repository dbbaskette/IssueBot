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

---

## Final re-review follow-up

### Status

DONE. This focused follow-up closes the numeric UID canonicalization, native first-cutover restore, persisted native-process identity, and stale-candidate findings. No production deployment or real IssueBot home mutation was performed.

### Changes

- Provider `.Config.User` parsing now treats every all-digit user component as a numeric UID and rejects it when it consists only of zeroes. Regressions cover `0`, `0:1000`, `00`, `000:1000`, `+0`, leading/trailing whitespace, empty/extra-colon, and invalid named forms. Positive coverage includes UID `1`/`1000`, numeric and named groups, and valid named non-root users.
- The first-cutover recovery record now persists the exact systemd scope/name, unit fragment path and SHA-256, original autostart state, timestamp, and the required exact process pattern as strict base64 data. The unit checksum binds the recorded unit to its `ExecStart`/binary command without inventing provider or native runtime internals.
- Added operator-only `recover_native_cutover <backup>` for failed first cutovers with no previous Compose release. It strictly validates backup location/modes/metadata/checksum/bytes, unique allowlisted native records, process-pattern encoding, current unit path, and unit checksum before mutation. It shares the deployment lock; stops/removes both candidate services; proves port/process/H2 closure; preserves the failed H2 file; restores through a checksum-verified temporary file; conditionally re-enables originally-enabled autostart without unmasking; and verifies the recorded systemd unit, process, and port owner.
- Pidfile native restore is rejected before Compose or H2 mutation because executable/unit identity and autostart cannot be proven.
- Successful automated Compose recovery now removes `candidate.manifest`, preventing stale schema compatibility metadata from influencing later standalone rollback.
- The runbook contains the exact unrestricted-shell recovery invocation and its safety boundary; `native-recovery-test.sh` asserts those commands remain documented.

### TDD evidence

Before implementation:

- `preflight-test.sh` failed because zero-padded UID `00` was accepted.
- `lifecycle-test.sh` failed when the stale `candidate.manifest` remained after successful automated recovery and when the native record lacked the new identity fields.
- `native-recovery-test.sh` failed because the recovery schema/function did not exist.

After implementation, the focused preflight, lifecycle, native-recovery, and dispatcher suites all exited `0`.

### Fresh verification

```bash
for test_file in deploy/tests/*-test.sh; do bash "$test_file"; done
bash -n deploy/*.sh deploy/lib/*.sh deploy/tests/*.sh
shellcheck -x -e SC2016 deploy/*.sh deploy/lib/*.sh deploy/tests/*.sh
docker compose --env-file deploy/production.env.example config --quiet
git diff --check
```

All commands exited `0`. PASS markers were printed for the original six deploy suites plus the new `native-recovery-test: PASS`. Negative-fixture errors for invalid users, corrupted backup data, pidfile recovery, and other rejection paths were expected assertions.

The documentation command checks are part of `native-recovery-test.sh` and require the runbook to contain the common/lifecycle sources and exact `recover_native_cutover "$backup"` invocation.

Maven was intentionally omitted in this focused follow-up because no Java source, Java test, `pom.xml`, or application resource changed after the previously recorded isolated-home `./mvnw clean verify` result (763 tests, zero failures/errors). Every changed executable is Bash or Compose and is covered by the full shell, syntax, ShellCheck, and Compose gates above.

### Remaining gate

Production cutover remains blocked on the independent provider release and IssueBot provider integration. Native automatic restore remains intentionally systemd-only; pidfile recovery requires a separately reviewed manual procedure.

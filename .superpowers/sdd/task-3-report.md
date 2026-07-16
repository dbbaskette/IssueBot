# Task 3 report: common guards and read-only preflight

## Status

Complete. Added common deployment guards, allowlisted non-sourced environment and manifest parsing, atomic manifest replacement, non-blocking lock acquisition, and read-only checkout/runtime/storage/provider preflight functions.

## RED evidence

- `bash deploy/tests/common-test.sh` exited 1 with `FAIL: deploy/lib/common.sh is absent` before `common.sh` was created.
- `bash deploy/tests/preflight-test.sh` exited 1 with `FAIL: preflight implementation is absent` before `preflight.sh` was created.

## GREEN evidence

- `bash deploy/tests/common-test.sh && bash deploy/tests/preflight-test.sh` exited 0 and printed `common-test: PASS` and `preflight-test: PASS`.
- `bash -n deploy/lib/common.sh deploy/lib/preflight.sh deploy/tests/test-helper.sh deploy/tests/common-test.sh deploy/tests/preflight-test.sh` exited 0.
- `shellcheck -x -e SC2016 deploy/lib/common.sh deploy/lib/preflight.sh deploy/tests/test-helper.sh deploy/tests/common-test.sh deploy/tests/preflight-test.sh` exited 0. SC2016 is excluded because mock command bodies are intentionally single-quoted for runtime expansion inside generated mock executables.
- `git diff --check` exited 0.

## Coverage and review

- Common: immutable non-placeholder provider digest, protected environment modes, literal/non-sourced environment loading, GitHub/Anthropic/bearer/password/webhook redaction, contended `flock -n`, allowlisted manifest data, temporary-file plus atomic `mv` replacement.
- Checkout: attached expected branch, unstaged/untracked changes, detached HEAD, unexpected branch, absent upstream, and local divergence without fetch/pull.
- Runtime/storage: required tools and Compose v2, supported engine architecture, 5 GiB floor, protected secret files, persistent paths, unknown port owner rejection, and identified native first-cutover owner acceptance.
- Provider: configured and recorded immutable digest, engine platform, embedded healthcheck, exact `com.issuebot.codex-provider.protocol` label, and exact non-billable doctor metadata.
- Static mutation guard rejects pull/run/Compose lifecycle/fetch/pull commands in preflight. No real Docker invocation or real-checkout mutation occurs in tests.

## Concerns

- `preflight_runner` intentionally requires the pinned provider image to be local. It never pulls or runs the image; lifecycle code must pull it before invoking provider contract/doctor validation.
- The exact non-billable doctor command is validated through `com.issuebot.codex-provider.doctor` image metadata and is not executed by this read-only preflight.

## Important-review fix evidence

Regression RED: after adding the behind-only ancestry contract, `bash deploy/tests/preflight-test.sh` exited 1 with the exact new failure:

```text
ERROR: checkout diverges from origin/main (ahead=0 behind=1)
FAIL: expected success: preflight_checkout
```

Final covering command:

```text
$ bash deploy/tests/common-test.sh && bash deploy/tests/preflight-test.sh
ERROR: provider image must use an immutable sha256 digest
ERROR: provider image uses the placeholder digest
ERROR: protected file has group/other permissions: <temporary fixture>/world-readable.env (644)
ERROR: another deployment operation holds <temporary fixture>/deploy.lock
ERROR: manifest key is not allowed: not_allowed
ERROR: manifest key is not allowed: evil
common-test: PASS
ERROR: checkout has unstaged changes
ERROR: checkout has untracked files
ERROR: checkout is on a detached HEAD
ERROR: checkout is not on expected branch: main
ERROR: checkout branch has no upstream
ERROR: checkout cannot fast-forward from origin/main (ahead=1 behind=0)
ERROR: checkout cannot fast-forward from origin/main (ahead=1 behind=1)
ERROR: Docker Compose v2 is required: Docker Compose version v1.29
ERROR: unsupported Docker engine architecture: s390x
ERROR: less than 5 GiB is available for deployment
ERROR: port 8090 is owned by an unexpected process (PID 4242)
ERROR: port 8090 must be free outside explicit first-cutover preflight
ERROR: port 8090 must be free outside explicit first-cutover preflight
ERROR: protected file must not be inside ISSUEBOT_CHECKOUT: <temporary fixture>/checkout/runtime.env
ERROR: protected file must not be inside ISSUEBOT_CHECKOUT: <temporary fixture>/checkout/deploy.env
ERROR: protected file must not be tracked by Git: <temporary fixture>/runtime.env
ERROR: protected file must not be tracked by Git: <temporary fixture>/deploy.env
ERROR: protected file has group/other permissions: <temporary fixture>/runtime.env (640)
ERROR: local provider image does not record the configured immutable digest
ERROR: provider platform mismatch: expected linux/amd64, got linux/arm64
ERROR: provider image has no embedded healthcheck
ERROR: provider protocol mismatch: expected 1, got 2
ERROR: provider doctor contract is missing the exact non-billable command
ERROR: provider image must use an immutable sha256 digest
preflight-test: PASS
```

The combined command exited `0`. Temporary fixture prefixes vary per run and are normalized above; every other output string is exact.

Additional exact verification results:

```text
$ bash -n deploy/lib/common.sh deploy/lib/preflight.sh deploy/tests/test-helper.sh deploy/tests/common-test.sh deploy/tests/preflight-test.sh
<no output; exit 0>
$ shellcheck -x -e SC2016 deploy/lib/common.sh deploy/lib/preflight.sh deploy/tests/test-helper.sh deploy/tests/common-test.sh deploy/tests/preflight-test.sh
<no output; exit 0>
$ git diff --check
<no output; exit 0>
```

Fix coverage now proves protected runtime and deploy environment files are rejected both when located inside the checkout and when Git reports them tracked; behind-only `0 1` ancestry is accepted while `1 0` and `1 1` are rejected; and a matching native listener is accepted only with `ISSUEBOT_FIRST_CUTOVER=true`, then rejected with the flag absent or false.

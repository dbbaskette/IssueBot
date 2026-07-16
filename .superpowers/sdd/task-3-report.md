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

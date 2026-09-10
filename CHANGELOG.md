# Changelog

## 0.2.0

- Reset failed, cooldown, or blocked issues and pause automatic dispatch without deleting history, approved plans, or dependency constraints.
- Start one chosen issue manually while the automatic queue remains paused; allow its stage approvals to continue.
- Save Codex reasoning effort alongside repository, issue start/retry, and stage model choices. Validate supported levels and show stage effort in history.
- Display the deployed build version in the sidebar.
- Deploy immutable macOS release jars so subsequent builds cannot overwrite a running application's classes.

## Release process

The project version in `pom.xml` is the source of truth. Increment it for every release (patch for fixes, minor for features) and add an entry here. Maven build-info supplies the UI version; no separate UI constant is needed.

Run `./mvnw verify` and `node --test src/test/js/*.cjs src/test/js/*.js`, open a PR, and merge after checks/review. The artifact name stays `target/issuebot.jar` across versions.

Before restarting the home server, pause automatic processing and let active work finish. Run `./deploy/macos/install-service.sh --skip-build` after building the merged commit. The installer retains content-addressed jars under the configured state directory's `releases/` directory and selects the current release; it does not remove older releases.

## Recovering an out-of-order issue

1. Open the failed issue and select **Reset and pause**. This queues the issue and pauses new automatic work; other running work may finish.
2. Open its prerequisite in the issue queue. Select **Start manually while paused**, optionally choosing models and reasoning levels.
3. Complete the prerequisite and any requested approvals. Repeat for other prerequisites if needed.
4. Start the original issue manually, or resume automatic processing using the global controls.

Reset does not close existing pull requests, discard repository changes, release decomposition ownership, mark dependencies complete, or bypass guided plan-conformance recovery. Those existing safety checks still apply.

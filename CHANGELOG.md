# Changelog

## 0.3.0

- Add a persistent, full-repository dependency and scheduling map to the issue queue, with cycle detection, missing-dependency visibility, explicit reservation edges, and task selection.
- Add manual recovery that pauses automatic processing and reversibly suspends decomposition reservations without abandoning groups or losing child progress; active work must stop safely first.
- Manual plan approval respects explicit dependencies and active checkpoints, but no longer imposes numeric issue order or deletes unrelated later plans.
- Replace misleading queue readiness text with the current dependency/reservation explanation.

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

## Recovering a reservation deadlock (0.3.0)

On the Issue Queue, use **Stop everything** if a workflow is active, then wait for its safe checkpoint. Choose **Enter manual recovery — release group reservations**. This pauses automatic dispatch and suspends all idle decomposition reservations without cancelling children or abandoning groups.

Use the visible dependency map to select an eligible task. This opens its detail page so you can choose models/reasoning before **Start manually while paused**. Failed tasks still require their existing reset/guided-retry action; approval checkpoints and open PRs are not silently discarded. The map labels real dependencies separately from child order and scheduling reservations, and detects cycles.

When ready, use **Resume group reservation** in the map to restore ordered ownership. This does not start work or resume the global queue. Global **Restart processing** remains a separate, deliberate action.

## Recovering an out-of-order issue

1. Open the failed issue and select **Reset and pause**. This queues the issue and pauses new automatic work; other running work may finish.
2. Open its prerequisite in the issue queue. Select **Start manually while paused**, optionally choosing models and reasoning levels.
3. Complete the prerequisite and any requested approvals. Repeat for other prerequisites if needed.
4. Start the original issue manually, or resume automatic processing using the global controls.

Reset does not close existing pull requests, discard repository changes, release decomposition ownership, mark dependencies complete, or bypass guided plan-conformance recovery. Those existing safety checks still apply.

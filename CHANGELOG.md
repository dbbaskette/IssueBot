# Changelog

## 0.7.1 — 2026-09-12

- Consolidate planning, implementation, and review guidance with explicit verification ownership, proportional skill use, and shared frontend design guidance for UI work.
- Preserve trusted local/CI gates and approval boundaries while removing redundant full-suite instructions and the assumption that independent review always uses a different model.

## 0.7.0 — 2026-09-12

- Unify operator decisions and live-state presentation, with durable actor/action history and comparable review changes that preserve missing-data and attempt-identity boundaries.
- Add contextual recovery guidance and explicit cached prerequisite checks, grouped searchable notification history with watermark-safe read/mute controls, and bounded return-to-results navigation.
- Close the original dispatch, processing-commit, and workflow-stepper coverage gaps; expand synthetic MVC/browser fixtures without changing deployment or the stable `target/issuebot.jar` artifact contract.

## 0.6.2

- Apply processing mode changes and stop-request cancellations only after the database transaction commits, preventing rolled-back controls from publishing stale state or cancelling active work.

## 0.6.1

- Wait for launchd to finish removing the prior IssueBot job before bootstrapping a replacement, preventing transient macOS error 5 during redeploys.

## 0.6.0

- Route Claude Code and Codex through a shared coding harness registry with model and reasoning capabilities.
- Pair every saved model with compatible reasoning, including Claude effort, and drive settings and run selectors from adapter capabilities.
- Migrate persisted harness identities to stable `claude` and `codex` IDs while preserving legacy settings, sessions, and active workflow history.
- Resolve missing legacy Claude reasoning from the selected model and preserve approved stage checkpoints when catalog or authentication checks fail.

## 0.5.1

- Replace the sidebar's decorative blue dot with the IssueBot ticket-bot icon and use it as the browser favicon.
- Add the matching transparent IssueBot wordmark to the repository README, with reusable branding assets and generation notes.

## 0.5.0

- Promote issue splitting out of Advanced settings and default new repositories to Off.
- Require high complexity, at least 20 substantive changed files and two independently deliverable capabilities before proposing or automatically splitting, including after failures.
- Keep cohesive features and their tests together; timeouts and multi-layer changes alone no longer qualify.
- Always require split approval in Propose mode, including managed workflows. Existing groups and saved repository choices are preserved.

## 0.4.0

- Make the issue queue the primary view; move the duplicate dependency table into an expandable section below it.
- Use consistent task-versus-queue language: Start issue, Pause queue, Resume queue. Put stop and recovery actions under More controls with explicit consequences.
- Replace competing one-click row actions with one Open/Review action, keeping model and reasoning choices on the issue detail page.
- Reduce queue columns and explanatory text while retaining dependency, scheduling, and recovery details.

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

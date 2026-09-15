# Changelog

## 0.21.0 — 2026-09-15

- Hold and release selected waiting issues without deleting plans; explicit Start releases a hold after dependency checks.
- Apply repository autostart to every workflow, leaving current work running while new issues wait for manual start.
- Show retained blocked implementation results and readable agent-reported checks instead of misleading Not started status.
- Require safe environment diagnosis and recovery before a harness reports an infrastructure blocker; preserve permission and test-integrity boundaries.

## 0.20.0 — 2026-09-15

- Use the durable coding-harness completion loop for every implementation path, preserving sessions after failed resumes instead of silently restarting.
- Retain handoff-limit and resumable-timeout attempts with explicit Extend and continue controls, bounded extensions, and workspace freshness checks.
- Require distinct implementation/review models, preserve the implementation provider on each attempt, and restrict review to read-only capabilities.
- Capture reported test-tree/environment claims separately from observed workspace identity; reject changed handoff trees before review and return focused verification requests to the coding session.
- Clarify managed capability profiles in Setup and remove conflicting local-verification ownership instructions.

## 0.19.0 — 2026-09-14

- Make local testing the coding harness's responsibility: IssueBot no longer requires configured commands or reruns tests. Existing commands become optional harness guidance.
- Save exact agent-reported commands, results, and limitations for the issue UI and independent reviewer. Missing evidence is never counted as passing; review findings drive focused correction in the harness.
- Preserve test evidence across recovery and allow reviewed merge retries without another coding/test run. GitHub CI and independent review gates remain in place.

## 0.18.0 — 2026-09-14

- Browse issue output by clicking a workflow stage, following the current stage by default while preserving a manually selected stage during updates.
- Keep issue status and stage evidence refreshing while waiting for input, clarify manual-retry states, and align review headers consistently.
- Capture medium, low, and minor review suggestions in the repository's rolling backlog on passed and failed reviews, retaining suggested fixes and deduplicating repeated findings.

## 0.17.0 — 2026-09-14

- Add a one-command macOS home-server deploy for the current checkout, with Java/JavaScript verification, queue and active-work safety checks, running-version confirmation, and preservation of the prior queue mode.

## 0.16.0 — 2026-09-14

- Run Codex as a single agent by default. Repositories can allow subagents, and each issue start, retry, or implementation approval can override that policy.
- Pass the delegation choice directly to Codex and show the effective mode on the issue page.
- Ask the coding harness to form its own test-backed judgment about the independent checks without reflexive full-suite runs.
- On a failed independent review, feed focused findings back to the existing coding work and preserve passing behavior instead of treating the correction as a fresh implementation.

## 0.15.0 — 2026-09-14

- Wait up to the repository's CI timeout for pending GitHub checks before merging reviewed work; keep a guarded merge-only recovery path instead of requiring another coding attempt.
- Put compact progress at the top of every issue, keep Plan, Implementation, Checks, Review, and Outcome in stable positions, and refresh new review evidence without reopening the page.
- Remove duplicate Goal and Timeline cards from the main issue view while retaining attempt and activity history; clarify CI and current-stage wording.

## 0.14.0 — 2026-09-14

- Accept longer exact verification commands in coding handoffs and identify the specific invalid check when a handoff is malformed.
- Close blocked coding attempts on the authoritative iteration row so saved turns, session details, and output survive the failure path.
- Offer a guarded way to reuse an older rejected `COMPLETE` handoff and proceed directly to trusted checks and independent review without rerunning coding.

## 0.13.0 — 2026-09-12

- Capture only durable, repository-wide lessons: ask for transferable rules, allow verified documentation references, reject obvious issue-specific details, and skip duplicates.
- Keep older one-off lessons visible for review but out of future coding prompts; treat all reusable lessons as advisory behind the current issue, approved plan, repository instructions, and actual code.
- Show each lesson's source issue and date, and let operators rewrite existing lessons into reusable guidance.

## 0.12.0 — 2026-09-12

- Start the issue terminal on direct page loads, refreshes, and HTMX navigation instead of relying on an inline script that could run before deferred JavaScript; avoid reconnecting on unrelated polls.
- Make the planning contract collapsible, open it by default only when approval is needed, and collapse the legacy plan, Goal, and Guide panels during active work.

## 0.11.0 — 2026-09-12

- Put issue state, next action, latest activity, and the last agent output together at the top of the issue page; collapse detailed progress and history by default.
- Replay recent per-issue terminal output after refresh or reconnect, show connection and last-output status, and display Codex command starts and captured results.

## 0.10.0 — 2026-09-12

- Keep bundled Superpowers provenance in Setup but omit its unrelated upstream commit from coding prompts, so agents use the task checkout as their source of truth.
- Permit outbound dependency downloads for explicitly allowlisted Codex implementation repositories, while keeping other stages restricted and removing inherited service credentials from coding subprocesses.

## 0.9.0 — 2026-09-12

- Give approved-plan implementation to one resumable coding-harness run with explicit complete, continue, and blocked handoffs, durable turn and cost checkpoints, and a bounded turn limit.
- Return trusted local-check failures to the same coding session for repair without consuming an independent-review attempt; keep IssueBot's final verification and review gates.
- Let an explicit Reset & pause start a fresh attempt after repeated conformance misses, preserving plan and review history while clearing the old session and attempt counters.
- Allow an operator to save a repository's trusted verification command without round-tripping unrelated repository settings.

## 0.8.1 — 2026-09-12

- Stop approved-plan implementation before coding when no trusted local verification command is configured, and stop before publication if that command is removed mid-run. Show the requirement on issue and repository screens.
- Record the proposed harness-owned implementation design separately from the current workflow.

## 0.8.0 — 2026-09-12

- Bundle integrity-pinned Superpowers Custom stage guidance for Codex and Claude, validate it before dispatch, and show provenance in Setup. No personal plugin installation or startup downloads are required.

- Show implementation agents the actual configured local verification commands, including resumed and cold-fallback runs, so they can choose focused checks without guessing or bypassing trusted gates.

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

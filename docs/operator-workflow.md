# Operator workflow and testing ownership

This describes the implemented 0.19.0 workflow. The [harness-owned implementation design](superpowers/specs/2026-09-12-harness-owned-implementation-design.md) records the broader target and remaining limits. Saved repository settings, rather than defaults alone, determine a particular run.

## From `agent-ready` to completion

An open labeled issue in a watched repository is discovered by polling or the labeling webhook. Qualification excludes pull requests and already-tracked work, resolves dependencies, and respects processing state and global capacity. A transactional dispatch claim reserves the repository and admits one runnable issue. Waiting for approval or an open IssueBot PR can hold that repository slot; labeling several issues does not make them run concurrently there.

| Policy | Planning and operator gates |
| --- | --- |
| LEGACY, Plan First enabled | Generate a versioned design and implementation plan; one operator approval accepts both, followed by a separate start. Legacy completion/auto-merge settings apply. |
| LEGACY, Plan First disabled | Skip versioned planning; retain ordinary verification, review, and completion gates. |
| AUTOMATED | Require Plan First; system-accept the generated version and progress automatically when prerequisites and actual gates pass. |
| STAGED | Require Plan First; pause before each configured stage, progressing automatically through other stages. A PLANNING approval precedes generation, not a second review of finished artifacts. |

Planning uses a protected workspace before feature-branch setup, requests assumptions, meaningful alternatives, a simplest sufficient design, and coherent implementation milestones, and verifies the source was unchanged. Both artifacts are generated in one call. Revisions preserve previous versions and supply operator feedback. This is not a multi-turn design conversation or a durable per-plan-task execution ledger.

The implementation provider receives the whole issue and approved artifacts, repository custom instructions, optional lessons, and correction feedback. For approved plans it owns focused implement/test/fix work inside one resumable coding run. Each CLI handoff must say `COMPLETE`, `CONTINUE`, or `BLOCKED` in a versioned response; ending a CLI process alone is not completion. IssueBot checkpoints each turn and its cost before another turn begins, and resumes the same session after `CONTINUE` or restart. The default limit is eight handoff turns per implementation attempt. The normal outer order is:

`harness implements and tests → save evidence → commit/push → CI if enabled → create/reuse PR → independent review → focused correction or completion`

The coding harness owns local verification: discover the build system, add appropriate regression tests, run relevant checks, repair failures, and report exact commands, results, tested tree/environment, and limitations. IssueBot does not execute or rerun local commands. The verification checkpoint saves the latest handoff evidence for the UI and independent reviewer. Reported results are labeled `REPORTED`, not an independent `PASSED`. No structured checks means `NOT_RUN`, with the reason or missing-evidence warning shown explicitly.

Repository settings expose optional suggested test commands for the harness. Existing saved commands are preserved as guidance, not executed by IssueBot. Empty settings never block implementation. The authenticated `POST /repositories/{id}/verification-commands` endpoint accepts empty `verificationCommands` to clear suggestions without changing other settings.

The reviewer evaluates the code, coverage, reported commands/results, and limitations. Relevant test failures, missing necessary tests, or stale evidence should become focused correction findings. The coding harness fixes and tests those findings; IssueBot does not start a second local test loop. A justified lack of runnable checks (such as prose-only changes) is visible for review, not manufactured test success. CI remains a separate configured GitHub gate. PR creation precedes independent review; only legacy APPROVAL_GATED work creates a draft PR.

Review is a fresh model invocation with the issue, approved contract, diff, changed files, repository requirements, and local/CI summaries. Parsed scores and blockers determine the verdict locally. A real review failure returns findings for correction, which goes through normal verification again. Plan First permits one dedicated correction before a second conformance miss requires human guidance. A crashed, empty, or unparseable review retries review itself up to five times before operational escalation; it does not automatically imply the code needs reimplementation. Recovery checkpoints can reuse completed CI/review outcomes.

Managed merge requires a passed review, the reviewed commit still matching the PR head, current successful/skipped GitHub checks, and a SHA-conditional squash merge. This freshness check does not launch another test suite. Legacy auto-merge-off can complete the IssueBot workflow with an unmerged PR. Ordinary issue closure relies on the PR's `Resolves #N` link and GitHub's merge behavior; workflow completion is not universally issue closure.

## Current testing ownership and limitations

| Check | Actual owner and boundary |
| --- | --- |
| Focused implementation tests | Coding harness owns the inner loop and reports exact command/result claims in its structured handoff; IssueBot does not trust those claims as final verification. |
| Suggested local commands | Optional operator guidance to the harness; IssueBot never executes them. |
| CI | GitHub, observed by IssueBot; clean-environment or platform coverage may justify command overlap. |
| Independent review | Fresh model reasoning. It receives result summaries, not a command/tree/environment evidence ledger. Review is not technically read-only today, and anti-rerun behavior is not enforced. |
| Merge freshness | IssueBot checks reviewed SHA and remote check status without rerunning tests. |
| Correction | Changed code passes through the applicable gates again; prior success is not proof for a new tree. |

The shared prompt bundle assigns local testing to the coding harness and evidence assessment to independent review. Evidence is persisted on the iteration and restored for stage/review recovery; no test command is executed from a model response. Native approved-plan sessions remain resumable. A model's bare success claim is not proof of correctness: the reviewer must assess coverage against the actual code and approved plan. Review is prompt-directed not to run another test suite; that is not an OS-level read-only enforcement boundary.

For repository development, use coherent increments and focused checks at milestones, then one combined relevant suite before release. Reviewers should consume supplied evidence and request only a justified focused check for a specific doubt. Evidence reuse requires an unchanged tree and relevant environment; changed corrections still go through applicable gates.

The original, provider-neutral resources in `src/main/resources/prompts/guidance/` are bundled with the application. Each of planning, implementation, and review receives common guidance, its own role guidance, and conditional frontend guidance. UI work consults the existing design system, consistent controls/statuses, accessible mobile layouts, and state preservation during refresh. Backend-only changes do not trigger a browser pass. No third-party skill text is copied into this bundle.

## Managed stage skill bundle

IssueBot 0.8.0 bundles a pinned Superpowers Custom stage subset with manifest/file integrity checks. The common harness boundary projects only the selected stage guidance for Codex and Claude; utility calls remain unmodified. Startup fails if bundled resources are missing or corrupt. Setup shows validated provenance. See [managed skills](managed-skills.md) for the upgrade and rollback procedure.

This is explicit stage-prompt emulation, not native plugin loading: native-skill capability flags remain false. Claude still omits personal settings/hooks and Codex isolates user config/rules. No personal cache path or startup download is required. The MIT-licensed vendored subset includes attribution. Native projection and task-ledger execution from the [cross-harness design](superpowers/specs/2026-09-11-cross-harness-superpowers-orchestration-design.md) remain future work.

## Operator surfaces and safety

The issue page follows the current workflow stage by default. Select a stage to inspect
its saved output; live refresh preserves that choice until **Follow current stage** is
selected. Status and evidence keep refreshing while waiting for input as well as during
execution. An earlier saved review is labeled as such while the new attempt awaits review.
Recovery options and history are secondary disclosures. **Needs attention** requires an
operator retry; it is not an automatic cooldown countdown.

For repositories using **Rolling backlog**, every completed independent review contributes
medium, low, and minor findings plus their suggested fixes, including when the review
fails. Blocking defects still go through correction. Entries use the existing per-repository
backlog and deduplication keys. Other follow-up modes retain their configured behavior.

Decision history is append-only structured audit data, separate from notification delivery. It labels actor kind, not an authenticated person's identity. Reviews compare only compatible persisted attempt identities and distinguish absent evidence from empty results. Recovery links do not authenticate or change settings: ordinary GETs use cached observations, and explicit Setup re-checks establish fresh prerequisite state.

The notification bell counts unread actionable groups; reading a notification does not resolve its underlying Needs You decision. Read watermarks preserve newer arrivals. Shared-instance progress/completion mutes affect informational delivery, not stored searchable history or critical attention. Return context is bounded, tab-local, expiring navigation state, never authorization.

Before any deployment restart, pause automatic dispatch and check for active issues; do not interrupt active workflows without permission. Builds retain `target/issuebot.jar`, while the macOS installer uses immutable release jars so a build cannot overwrite classes used by a running JVM. The UI version comes from Maven build-info.

An explicit **Reset & pause queue** starts a new workflow run for a failed, cooldown, or blocked issue, including a repeated plan-conformance miss. It clears the old coding session, PR pointer, and attempt counters but retains approved artifacts and old iteration/review history. A new run receives a distinct branch name; close any stale open PR before manually starting it.

# Operator workflow and testing ownership

This describes the implemented runtime including the 0.8.0 managed skill bundle and verification-context refinement, not the full future cross-harness methodology design. Saved repository settings, rather than defaults alone, determine a particular run.

## From `agent-ready` to completion

An open labeled issue in a watched repository is discovered by polling or the labeling webhook. Qualification excludes pull requests and already-tracked work, resolves dependencies, and respects processing state and global capacity. A transactional dispatch claim reserves the repository and admits one runnable issue. Waiting for approval or an open IssueBot PR can hold that repository slot; labeling several issues does not make them run concurrently there.

| Policy | Planning and operator gates |
| --- | --- |
| LEGACY, Plan First enabled | Generate a versioned design and implementation plan; one operator approval accepts both, followed by a separate start. Legacy completion/auto-merge settings apply. |
| LEGACY, Plan First disabled | Skip versioned planning; retain ordinary verification, review, and completion gates. |
| AUTOMATED | Require Plan First; system-accept the generated version and progress automatically when prerequisites and actual gates pass. |
| STAGED | Require Plan First; pause before each configured stage, progressing automatically through other stages. A PLANNING approval precedes generation, not a second review of finished artifacts. |

Planning uses a protected workspace before feature-branch setup, requests assumptions, meaningful alternatives, a simplest sufficient design, and coherent implementation milestones, and verifies the source was unchanged. Both artifacts are generated in one call. Revisions preserve previous versions and supply operator feedback. This is not a multi-turn design conversation or a durable per-plan-task execution ledger.

The implementation provider receives the whole issue and approved artifacts, repository custom instructions, optional lessons, and correction feedback. It implements one issue-level iteration. The normal order is:

`implementation → configured local commands → commit/push → CI if enabled → create/reuse PR → independent review → correction or completion`

Local verification commands run sequentially, ignoring blank/comment lines and stopping at the first failure, with a ten-minute timeout per command. If none are configured, that gate does not run. A local failure returns its command/log context to implementation without proceeding to CI. CI-disabled work still commits and pushes, with CI recorded as skipped. PR creation precedes independent review; only legacy APPROVAL_GATED work creates a draft PR.

Review is a fresh model invocation with the issue, approved contract, diff, changed files, repository requirements, and local/CI summaries. Parsed scores and blockers determine the verdict locally. A real review failure returns findings for correction, which goes through normal verification again. Plan First permits one dedicated correction before a second conformance miss requires human guidance. A crashed, empty, or unparseable review retries review itself up to five times before operational escalation; it does not automatically imply the code needs reimplementation. Recovery checkpoints can reuse completed CI/review outcomes.

Managed merge requires a passed review, the reviewed commit still matching the PR head, current successful/skipped GitHub checks, and a SHA-conditional squash merge. This freshness check does not launch another test suite. Legacy auto-merge-off can complete the IssueBot workflow with an unmerged PR. Ordinary issue closure relies on the PR's `Resolves #N` link and GitHub's merge behavior; workflow completion is not universally issue closure.

## Current testing ownership and limitations

| Check | Actual owner and boundary |
| --- | --- |
| Focused implementation tests | Coding harness under prompt/repository instructions; IssueBot does not enforce cadence. |
| Configured final local commands | IssueBot's trusted local gate. Duplicate configured lines run verbatim. |
| CI | GitHub, observed by IssueBot; clean-environment or platform coverage may justify command overlap. |
| Independent review | Fresh model reasoning. It receives result summaries, not a command/tree/environment evidence ledger. Review is not technically read-only today, and anti-rerun behavior is not enforced. |
| Merge freshness | IssueBot checks reviewed SHA and remote check status without rerunning tests. |
| Correction | Changed code passes through the applicable gates again; prior success is not proof for a new tree. |

The shared prompt bundle assigns focused development checks to implementation and configured final verification to IssueBot. Implementation receives the effective configured command list on initial/resumed runs and cold fallback, or an explicit notice that none are configured. Review is instructed to consume supplied evidence and avoid automatic full-suite reruns. A harness can still choose overlapping checks: prompts are not enforcement. Review summaries are not an exact-tree/command/environment ledger. A model's bare `PASSED` is never grounds to skip IssueBot's trusted gate.

For repository development, use coherent increments and focused checks at milestones, then one combined relevant suite before release. Reviewers should consume supplied evidence and request only a justified focused check for a specific doubt. Evidence reuse requires an unchanged tree and relevant environment; changed corrections still go through applicable gates.

The original, provider-neutral resources in `src/main/resources/prompts/guidance/` are bundled with the application. Each of planning, implementation, and review receives common guidance, its own role guidance, and conditional frontend guidance. UI work consults the existing design system, consistent controls/statuses, accessible mobile layouts, and state preservation during refresh. Backend-only changes do not trigger a browser pass. No third-party skill text is copied into this bundle.

## Managed stage skill bundle

IssueBot 0.8.0 bundles a pinned Superpowers Custom stage subset with manifest/file integrity checks. The common harness boundary projects only the selected stage guidance for Codex and Claude; utility calls remain unmodified. Startup fails if bundled resources are missing or corrupt. Setup shows validated provenance. See [managed skills](managed-skills.md) for the upgrade and rollback procedure.

This is explicit stage-prompt emulation, not native plugin loading: native-skill capability flags remain false. Claude still omits personal settings/hooks and Codex isolates user config/rules. No personal cache path or startup download is required. The MIT-licensed vendored subset includes attribution. Native projection and task-ledger execution from the [cross-harness design](superpowers/specs/2026-09-11-cross-harness-superpowers-orchestration-design.md) remain future work.

## Operator surfaces and safety

Decision history is append-only structured audit data, separate from notification delivery. It labels actor kind, not an authenticated person's identity. Reviews compare only compatible persisted attempt identities and distinguish absent evidence from empty results. Recovery links do not authenticate or change settings: ordinary GETs use cached observations, and explicit Setup re-checks establish fresh prerequisite state.

The notification bell counts unread actionable groups; reading a notification does not resolve its underlying Needs You decision. Read watermarks preserve newer arrivals. Shared-instance progress/completion mutes affect informational delivery, not stored searchable history or critical attention. Return context is bounded, tab-local, expiring navigation state, never authorization.

Before any deployment restart, pause automatic dispatch and check for active issues; do not interrupt active workflows without permission. Builds retain `target/issuebot.jar`, while the macOS installer uses immutable release jars so a build cannot overwrite classes used by a running JVM. The UI version comes from Maven build-info.

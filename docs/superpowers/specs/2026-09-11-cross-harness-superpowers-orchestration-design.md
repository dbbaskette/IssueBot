# Cross-Harness Superpowers Orchestration Design

## Summary

IssueBot will become the durable, provider-neutral coordinator for a consistent Superpowers development methodology across Codex, Claude, OpenCode, Cursor, and future coding harnesses. IssueBot—not an individual harness—will select required skills, own dependencies and scheduling, provision isolated Git worktrees, manage questions and deadlines, execute plan tasks, enforce test and review gates, control pull requests and merges, and preserve a complete recovery ledger.

One canonical, pinned skill bundle will define the methodology. Harness adapters will expose that bundle through each harness's native skill mechanism where supported and will use a tested, visible prompt fallback only where native loading is unavailable. Harnesses reason and edit within one assigned task; they never control the surrounding workflow.

This design supersedes the non-interactive planning assumptions and the explicit exclusion of native skill integration in `2026-07-17-versioned-plan-first-approval-design.md`. Existing stage-policy safety, immutable planning history, dependency ordering, cancellation, verification, review, PR, and merge guarantees remain in force unless this design explicitly replaces them.

## Goals

- Produce consistent engineering behavior across supported coding harnesses by using one canonical, versioned skill bundle.
- Keep IssueBot authoritative for workflow state, skill selection, workspaces, concurrency, recovery, verification, review, PRs, and merges.
- Replace provider-specific branching with a capability-driven coding-harness adapter contract.
- Pair every model selection with a compatible reasoning-level selection anywhere IssueBot chooses a model.
- Support adaptive Spike, Bounded, and Architectural issue classification.
- Let incomplete issues enter a natural, one-question-at-a-time design conversation without indefinitely blocking other work.
- Apply one repository-level question timeout independently to every question, with an explicit model-recommended default.
- Create one durable, IssueBot-owned worktree per active issue and preserve it through retries, pauses, reviews, and restarts.
- Execute implementation plans as independently verified tasks with durable checkpoint commits.
- Enforce root-cause debugging, real red-green-refactor evidence, per-task review, final review, and fresh completion verification.
- Add OpenCode and Cursor without weakening the workflow contract established for Codex and Claude.
- Keep the interface simple: policy-oriented settings, one active question, and one primary action derived from state.

## Non-goals

- Allowing a coding harness to schedule issues, select workflow skills, create unmanaged worktrees, spawn untracked workers or reviewers, push, merge, or delete branches.
- Allowing harnesses to add optional skills silently.
- Maintaining different semantic versions of the core methodology for each harness.
- Guaranteeing identical code or prose from different models; the guarantee is consistent process and evidence.
- Re-enabling aggressive GitHub issue decomposition. Decomposition remains separate and off by default.
- Adding local merge as an integration path. Pull requests remain IssueBot's only integration boundary.
- Automatically switching to an unconfigured harness or model.
- Treating repository-authored skills as replacements for protected IssueBot core skills.
- Requiring native skill support from every harness when a tested and disclosed prompt fallback can satisfy the same stage contract.

## Architectural Boundary

The system has four responsibility layers:

1. **Repository policy** defines stage approvals, harness/model/reasoning tuples, question timeout, parallelism, review limits, verification commands, CI use, and merge behavior.
2. **IssueBot orchestration** owns the workflow state machine, dependency scheduler, worktree leases, deterministic skill selection, interaction deadlines, task ledger, verification gates, review loops, and all external side effects.
3. **Canonical methodology** supplies pinned skill content, semantic triggers, enforcement requirements, compatibility metadata, and tested prompt fallbacks.
4. **Harness adapters** translate IssueBot requests into harness-specific commands, authentication, native skill exposure, model catalogs, reasoning controls, events, session continuation, and cancellation.

A harness receives one bounded execution request containing its role, task brief, approved constraints, exact workspace, selected model and reasoning level, required skills, tool policy, session reference when applicable, and timeout. It returns structured events and a result. It does not choose the next workflow state.

## Harness Adapter Contract

Replace the current `ClaudeCodeService` facade and its direct Claude/Codex switch with a neutral `CodingHarnessRegistry` and adapters such as `CodexHarnessAdapter`, `ClaudeHarnessAdapter`, `OpenCodeHarnessAdapter`, and `CursorHarnessAdapter`.

Persist stable string harness identifiers rather than growing the current `AgentProvider` enum through the core domain. Migration maps `CLAUDE_CODE` and `CODEX` to their stable identifiers without changing active work. Legacy `claudeSessionId` data remains readable while new sessions are stored on provider-neutral harness-run records.

Every adapter must provide:

- identity, installed version, availability, and authentication status;
- a model catalog, display metadata, capability tier, and allowed reasoning values per model;
- supported execution roles and read-only/read-write policies;
- native skill locations, allowlisting and explicit-invocation behavior;
- structured event and result parsing;
- continuation, cancellation, timeout, and process-cleanup behavior;
- token and cost usage when the harness exposes them;
- proof of the exact production command used during preflight.

The shared execution roles are Analysis and Classification, Design and Planning, Implementation, Debugging and Corrections, Task Review, and Final Review. Every role resolves one immutable tuple of harness identifier, model identifier, and reasoning level before dispatch. Start and retry dialogs may override the full tuple for that run but never mutate repository defaults. Unsupported combinations are rejected before work begins; IssueBot never silently substitutes a model, reasoning level, harness, or billing path.

An optional advanced fallback tuple may be configured per role. It is used only at a clean task boundary after an availability or authentication failure and is recorded as a new harness run. It cannot take over a live session or bypass stage approval.

## Canonical Skill Bundle

IssueBot owns a canonical methodology bundle with a semantic version, manifest, content checksum, skill definitions, harness projections, fallback prompts, and conformance scenarios. A workflow run snapshots the exact bundle version and checksum so later upgrades cannot change an active issue.

The adapter provisions an execution-scoped native skill environment without changing tracked repository content. Harnesses that support a common `.agents/skills` layout use that projection. Claude receives its native projection. A harness-specific projection may change discovery metadata or invocation syntax but must reference the same canonical skill content and behavioral contract.

Repository skills are additive. They may provide domain knowledge or extra techniques, but they cannot shadow, replace, disable, or weaken a protected core skill. IssueBot records core and repository skills separately and rejects path traversal, unsafe symlinks, duplicate protected names, and checksum mismatches.

For every harness run, IssueBot records skills required, made discoverable, requested, reported as loaded, completed, or emulated. A tested prompt fallback is visibly labeled `EMULATED`; it is never reported as native skill execution. If neither native loading nor an approved fallback is available, the run fails before repository mutation.

## Deterministic Skill Selection

IssueBot translates the Superpowers suite into enforceable workflow responsibilities:

| Skill | IssueBot responsibility |
|---|---|
| Using Superpowers | Deterministically select required skills and prevent silent harness additions or omissions. |
| Brainstorming | Classify work, gather missing decisions, present design, and enforce the pre-implementation design boundary. |
| Writing Plans | Produce an executable task plan for Architectural work after its design is settled. |
| Using Git Worktrees | Provision and verify IssueBot-owned isolated workspaces before implementation. |
| Executing Plans | Track task order, dispatch one bounded task, verify it, checkpoint it, and resume durably. |
| Subagent-Driven Development | Use fresh task contexts, separate reviewers, correction loops, a durable ledger, and a broad final review. |
| Dispatching Parallel Agents | Apply conservative dependency and file-ownership checks before any concurrency. |
| Test-Driven Development | Enforce a real failing test before production changes and preserve red-green-refactor evidence. |
| Systematic Debugging | Require root-cause evidence and a confirmed hypothesis before a corrective change. |
| Requesting Code Review | Dispatch clean, read-only task and whole-branch reviews with precise artifacts. |
| Receiving Code Review | Verify every finding, implement valid feedback, and make evidence-backed technical pushback. |
| Verification Before Completion | Bind fresh command evidence to the exact commit before any completion claim or external progression. |
| Finishing a Development Branch | Verify, push, create/update the PR, follow merge policy, and clean up only after confirmed integration. |
| Writing Skills | Use behavioral pressure tests when the issue creates or changes a skill. |

Harnesses may perform the reasoning described by a skill, but IssueBot enforces workflow gates and persistence. A harness's claim that it followed TDD, verified a task, or completed review is evidence to inspect, not authority to advance.

## Adaptive Classification and Planning

Before code modification, the Analysis and Classification role classifies an issue as:

- **Spike** — a read-only feasibility investigation whose retained result is a report, not production code. Keeping experimental code requires reclassification and a new design decision.
- **Bounded** — a well-scoped change to an existing flow. IssueBot produces a short design and normally one implementation task. There is no separate long-form plan artifact.
- **Architectural** — a new subsystem or interface-changing design. IssueBot conducts the full design conversation, persists a versioned specification, and generates a detailed implementation plan.

Classification includes evidence and is visible in the issue timeline. Uncertainty selects the heavier classification. Hidden complexity may upgrade Bounded work to Architectural; the run returns to design rather than continuing under an undersized contract.

Architectural work separates design from plan generation. Once questions are resolved, IssueBot presents the design under the repository's Planning approval policy. It then produces the implementation plan. There is no second plan-approval action: when Implementation is staged, its approval card presents the completed design and plan and authorizes execution. Automated repositories proceed according to their previously selected stage policy.

Minor plan changes that preserve approved behavior, scope, architecture, and acceptance criteria are accepted automatically, appended to plan history, and explained in the timeline. Any material change returns to Brainstorming and the applicable approval boundary.

## Natural User Interaction and Deadlines

Design and recovery questions appear as conversational entries on the issue page, not a separate wizard. Only one unanswered question may be active for an issue. A question contains:

- why the decision matters;
- one concise question;
- two or three mutually exclusive options when appropriate;
- free-form response support;
- a model-recommended answer and short rationale;
- an absolute deadline and live countdown;
- the exact action IssueBot will take at expiry.

One repository-level duration, defaulting to ten minutes, applies independently to every new question. The recommended answer is persisted before waiting, so a restart cannot change it. Updating repository policy affects future questions only. At expiry, IssueBot atomically records the timeout decision, applies the persisted recommendation, and continues.

When the model cannot identify a defensible answer within the approved scope, the recommendation is `Pause this issue`. Pausing releases processing capacity and any repository-wide scheduling reservation while retaining the issue worktree lease, evidence, and recovery state. Other dependency-eligible work continues. A later answer creates a new timeline decision and resumes from the recorded checkpoint.

Question deadlines apply to requests for information or judgment. Stage approvals are policy gates, not questions; their behavior remains Automated or Staged and a question timeout never converts a staged merge approval into an automatic merge.

Notifications may link to the active question, and a future GitHub bridge may accept issue-comment answers, but the IssueBot issue timeline remains authoritative.

## Issue-Owned Worktrees

IssueBot will maintain one base clone per repository and create a separate, durable worktree and `issuebot/issue-*` branch for each active implementation run. Planning may continue using a read-only snapshot before an implementation workspace is needed.

`WorkspaceManager` records the repository, issue, execution run, absolute path, branch, base commit, current commit, lease owner, lease state, creation time, and cleanup state. It validates that the path remains inside the configured worktree root and that Git's common directory belongs to the expected base clone. Native Git worktree commands manage creation, inspection, removal, and pruning; existing JGit operations may continue within the selected worktree where appropriate.

The same workspace survives task retries, review corrections, pauses, IssueBot restarts, and PR feedback. It is passed explicitly to every harness request. Harness instructions state that the workspace is already isolated, and nested or alternative worktree creation is prohibited.

After GitHub confirms merge, IssueBot verifies the worktree is clean before removal. Uncommitted or untracked data blocks cleanup and produces a visible recovery action; IssueBot never force-removes it automatically. Failed, paused, open-PR, or unmerged work remains available. Explicit discard requires a dedicated destructive confirmation and is outside automatic timeout decisions.

## Task Execution and Durable Ledger

Each plan task is a persisted unit with dependencies, brief, global constraints, file/interface ownership, status, base and head commits, harness runs, test evidence, reviews, rulings, and correction round. A fresh implementation context receives only the task brief, approved constraints, required prior interfaces, workspace path, report contract, selected skills, and relevant ledger pointers. It does not receive accumulated conversation history.

For every task, IssueBot:

1. verifies task dependencies and workspace lease;
2. performs the TDD RED gate where applicable;
3. dispatches implementation and refactoring work;
4. runs task-specific verification;
5. creates a local checkpoint commit;
6. dispatches an independent read-only task review;
7. resolves review findings through the correction loop;
8. records task completion before dispatching the next task.

Completed task records and commits are authoritative after restart. An interrupted live process becomes an interrupted harness run; IssueBot reconciles the worktree and resumes from the first incomplete boundary rather than replaying completed tasks.

Correction loops default to five rounds, and repositories may lower the cap. Rounds one through three resume the original implementer when supported, or create a fresh session carrying the persisted brief and report. Rounds four and five use a fresh session with a more capable configured model/reasoning combination. At the cap, IssueBot may park a contestable or non-load-bearing finding with an explicit ruling. A real load-bearing defect pauses the issue when no safe path remains.

Every ruling records the decision, evidence, reason, and cost if wrong. Rulings are shown together before merge and remain in permanent issue history.

## Conservative Parallelism

Repository parallelism is `OFF`, `CONSERVATIVE`, or `ENABLED`, with `CONSERVATIVE` as the default.

- `OFF` executes all work serially.
- `CONSERVATIVE` keeps code-changing implementation tasks serial while allowing safe read-only analysis and independent review to overlap.
- `ENABLED` may parallelize implementation tasks only when the persisted task graph proves independence and declared file/interface ownership does not overlap.

Enabled parallel code tasks receive separate IssueBot-owned child worktrees. IssueBot integrates their checkpoint commits in dependency order and reruns combined verification. Ambiguous dependency, shared files, shared generated artifacts, database migrations, or cross-cutting interfaces force serial execution. Parallel plan tasks remain within one tracked issue and one PR; IssueBot does not create extra GitHub issues.

## Enforced Test-Driven Development

For behavior changes, IssueBot splits a task's implementation into observable gates:

1. **RED** — the harness may add or modify tests but may not change production files. IssueBot inspects the diff, runs the named focused test itself, and confirms that it fails for the expected missing behavior rather than a syntax or setup error.
2. **GREEN** — only after accepted RED evidence may the harness change production code. IssueBot runs the focused test and relevant neighboring tests and requires success.
3. **REFACTOR** — cleanup may proceed only while the focused and neighboring tests remain green.

This split makes TDD enforceable across harnesses without trusting narration or requiring identical command-event formats. Test-file classification is adapter-independent and repository-aware; uncertain paths require an explicit decision before production editing.

Throwaway Spike code, generated output, and pure configuration changes may receive a recorded exception only through an IssueBot policy decision. The exception names the replacement validation. A harness cannot silently exempt itself.

When writing or changing tests, the task brief requires observable behavior, hand-derived expectations, real components where practical, narrow external mocking, and a statement of the production defect each test catches.

## Systematic Debugging

Any bug, test failure, build failure, CI failure, integration problem, performance problem, harness anomaly, or unexpected behavior enters a structured debugging subflow before a fix is proposed:

1. capture complete errors, environment, recent changes, and component-boundary evidence;
2. reproduce or explicitly document why reproduction is not yet possible;
3. compare with working code and references;
4. record one root-cause hypothesis and its evidence;
5. test that hypothesis with one minimal experiment;
6. after confirmation, create the failing regression test;
7. implement one root-cause fix and verify it.

The issue timeline displays the active debugging phase and evidence summary. After three unsuccessful root-cause fixes, IssueBot returns to Brainstorming because the architecture may be wrong. If the interaction deadline expires at this boundary, the safe default pauses this issue, preserves its workspace and evidence, releases processing capacity, and continues other eligible work.

## Independent Review

Every completed plan task receives an independent read-only review before a dependent task begins. The reviewer gets the exact task brief, approved constraints, implementer report, verification evidence, and complete base-to-head diff package. It receives no implementation conversation history and cannot mutate the worktree or dispatch another reviewer.

The task review returns separate specification-compliance and code-quality verdicts. Critical and Important findings block progression; Minor findings are recorded for final triage. Accepted findings are implemented one at a time and receive scoped rereview. Implementer self-review never replaces the independent review.

After all tasks, a fresh Final Review role evaluates the entire branch against the approved design, acceptance criteria, plan, deferred minors, parked findings, and rulings. It uses the repository's configured high-capability harness/model/reasoning tuple. Final findings receive one consolidated fix wave and one scoped rereview before residual findings are adjudicated visibly.

Receiving feedback follows a durable `UNDERSTAND → VERIFY → ACCEPT / REJECT / ASK` record. Human feedback is trusted as intent but still checked for scope and technical consequences. Automated or external findings are verified against the actual code. IssueBot may reject an incorrect, unnecessary, incompatible, or design-conflicting finding with evidence-backed reasoning and a concise reply in the original GitHub review thread. It never silently drops a finding.

## Verification and PR-Only Completion

Verification evidence is bound to an exact commit and contains command, working directory, time, exit status, sanitized output, evidence type, and provenance. Any later code change invalidates completion use of earlier evidence.

IssueBot requires:

- focused verification for each task and review correction;
- configured local verification on the completed branch head before push;
- GitHub CI for the same commit when repository CI policy is enabled;
- a head-SHA check proving that the reviewed and verified commit remains the PR head before merge;
- configured post-merge or deployment health checks when present.

If GitHub CI is disabled, successful local verification is sufficient for that gate and the UI states that CI was not required. IssueBot discovers conventional verification commands and may propose additions, but repository policy stores the authoritative command set.

Finishing is PR-only. After fresh local verification, IssueBot pushes the feature branch and creates or updates the pull request. The worktree remains through CI, review, corrections, and human approval. Merge follows only the repository's Automated or Staged policy. Cleanup begins only after GitHub confirms integration and the workspace passes the clean-data check. Harnesses cannot choose merge, push, cleanup, or discard behavior.

## Skill-Authoring Conformance

The Writing Skills methodology is selected only when an issue creates or changes a skill. It requires baseline pressure scenarios without the skill, observed failure behavior, the minimal skill change, repeated scenarios with the skill, and refactoring against discovered loopholes.

Changes to IssueBot's canonical core bundle must pass behavioral compatibility scenarios against every supported harness adapter. Repository-specific skills run conformance only against harnesses enabled for that repository. Text-presence assertions do not qualify; tests must exercise the consuming harness's behavior.

## Persistence and Recovery

At first workflow entry, IssueBot creates an immutable execution-policy snapshot containing classification policy, stage approvals, every harness/model/reasoning tuple, bundle version and checksum, question timeout, parallelism, correction limits, verification commands, CI policy, and merge policy.

New durable records cover:

- harness runs and their sessions, events, usage, and outcome;
- workspace leases and Git provenance;
- classified design conversations, questions, recommended defaults, answers, deadlines, and timeout decisions;
- versioned design and plan artifacts;
- implementation tasks, dependencies, attempts, checkpoint commits, and reports;
- skill application and fallback evidence;
- verification evidence;
- review findings, decisions, fix rounds, and rulings;
- idempotency keys and remote identifiers for GitHub effects.

On startup, recovery reconciles database state with process status, Git worktrees, branch commits, deadlines, and GitHub objects. Expired unanswered questions apply their already-persisted default once. Completed tasks are never re-dispatched. Interrupted work resumes from the last proven boundary with a new harness run if the old process cannot continue.

The issue page derives one primary action from durable state, such as `Answer and continue`, `Approve implementation`, `Review PR`, or `Retry authentication`. Diagnostic and destructive actions remain in an Advanced menu and never compete with the primary recovery action.

## Backward Compatibility and Migration

Existing active issues retain their current policy snapshot and complete through the legacy workflow. The migration never creates new questions, worktrees, task records, approvals, or skill requirements for a run that has already begun. A paused or failed legacy issue remains legacy when resumed unless the operator explicitly starts a new execution under the new policy.

For not-yet-started work, an enabled legacy Plan First setting maps to `Superpowers — IssueBot managed`; an explicit Plan First opt-out maps to the compatibility `Standard` method so upgrade does not revoke an existing operator choice. New repositories default to the managed Superpowers method. The compatibility method remains available during migration but is not the recommended default.

Existing provider and model settings map as follows:

- utility provider/model becomes Analysis and Classification;
- implementation provider/model becomes Design and Planning, Implementation, and Debugging and Corrections;
- review provider/model becomes Task Review and Final Review.

Existing persisted reasoning choices map with their corresponding model role. When an old role has no reasoning value, the adapter's documented default is resolved once and stored; the UI shows that resolved value rather than leaving it implicit. Existing stage approval policies, verification commands, GitHub CI choice, merge mode, decomposition choice, concurrency limits, branches, PR identifiers, planning versions, and review history remain unchanged.

The original repository clone remains the base clone. IssueBot creates a worktree only when a new-policy implementation run first enters its workspace setup stage. Legacy `claudeSessionId` values remain usable by compatible legacy recovery paths but are never copied into a different harness.

## Error Handling

Normalize adapter and workflow failures into stable categories: unavailable executable, authentication, unsupported model/reasoning, missing skill capability, malformed output, harness timeout, cancellation, workspace invariant, test failure, review block, CI failure, Git/GitHub failure, security policy, and internal error.

Installation, authentication, selection, skill, workspace, and read-only capability checks occur before mutation. Native-skill failure uses a tested prompt fallback only when the adapter and bundle declare it compatible. Transient process failures may retry without consuming a code-correction round; code, test, and review failures use their own bounded loops.

Cancellation stops the live process, records the interruption, preserves commits and worktree contents, releases processing capacity, and pauses the issue. External actions use persisted idempotency keys and identifiers so restart recovery cannot create duplicate branches, PRs, reviews, comments, or merges. Every failure state supplies one plain-language explanation and one recommended next action.

## User Interface

Repository settings use policy language rather than a per-skill switchboard:

- Development method: `Superpowers — IssueBot managed`.
- Question timeout: one duration applied to every question.
- Parallelism: Off, Conservative, or Enabled.
- Role rows pairing Harness, Model, and Reasoning for Analysis and Classification, Design and Planning, Implementation, Debugging and Corrections, Task Review, and Final Review.
- Advanced settings for correction rounds, fallback tuples, GitHub CI, verification commands, and capacity.

Harness choices display installed version, authentication, native or emulated skill support, and readiness. Only adapter-supported model/reasoning combinations appear. Global settings supply defaults, repositories override them, and run-level selections snapshot the complete tuple.

The issue page uses a single timeline for questions, answers, defaults, plans, task checkpoints, debugging evidence, reviews, rulings, and PR progression. It shows why each skill was selected and whether execution was native or emulated. Only one unanswered question and one primary action are present at a time.

## Security

- IssueBot core skills are trusted, pinned, checksummed release assets.
- Repository skills are untrusted additive inputs and cannot override core names or policies.
- Skill projection and worktree paths are canonicalized and confined to configured roots.
- Symlink escapes, traversal, writable core-skill content, and checksum mismatches fail preflight.
- Secrets and raw authentication data are never written to prompts, ledgers, reports, or UI events.
- Review sessions are read-only and implementation sessions are confined to their leased worktree.
- Harnesses cannot perform push, merge, publish, deployment, or destructive cleanup; IssueBot owns these side effects.

## Testing Strategy

### Adapter conformance

Every adapter runs the same contract suite for availability, authentication, model and reasoning validation, exact command construction, read-only and read-write execution, skill projection, native or emulated evidence, structured events, continuation, cancellation, timeout, malformed output, and cleanup. Fixture CLIs make these tests deterministic. Optional live smoke tests validate installed harness versions without becoming normal CI requirements.

### Workflow and persistence

Integration tests cover policy snapshotting, each classification, material and minor plan changes, one-question exclusivity, persisted recommendations, virtual-clock expiry, restart recovery, stale answers, task dependencies, worktree leases, checkpoint commits, correction escalation, rulings, cancellation, and GitHub idempotency.

### Methodology gates

Tests prove RED rejects production changes and incorrect failures; GREEN and REFACTOR require real passing commands; debugging cannot implement before a confirmed hypothesis; every task receives independent review; invalid findings can be rejected only with evidence; fresh commit-bound verification is required before PR progression; and skill-authoring scenarios measure behavior rather than source text.

### Concurrency and worktrees

Tests cover path confinement, ownership, overlapping leases, restart reconciliation, preserved failed work, clean merged cleanup, dirty cleanup refusal, Conservative read-only overlap, Enabled task independence, conflicting file scopes, integration order, and combined verification.

### UI and migration

Controller, template, and browser tests cover paired model/reasoning selectors, capability filtering, countdowns, recommended defaults, timeout provenance, role overrides, harness readiness, one primary action, compact recovery, skill evidence, task/review history, and mobile behavior. Migration tests preserve existing Claude/Codex settings, stage approvals, active issues, legacy sessions, plans, branches, and PRs.

## Release Decomposition

This program is too large for one safe release. It will be delivered as seven independently releasable projects, each with its own specification, implementation plan, feature-version increment, changelog entry, tests, PR, merge, deployment, and post-deployment verification:

1. **Harness foundation** — adapter SPI, registry, capability model, provider-neutral persistence, and dynamic model/reasoning UI; migrate Claude and Codex without workflow changes.
2. **Workspace and ledger foundation** — IssueBot-owned worktrees, leases, task records, checkpoint commits, and restart reconciliation.
3. **Conversational planning** — adaptive classification, natural Brainstorming questions, persisted recommendations, repository deadline, and simplified issue actions.
4. **Canonical methodology runtime** — versioned bundle, deterministic selection, native projections, skill evidence, prompt fallbacks, and repository-skill protection.
5. **Disciplined execution** — task dispatcher, enforced TDD, systematic debugging, task reviews, five-round correction breaker, final review, verification, rulings, and PR-only finish.
6. **OpenCode adapter** — production adapter and full conformance proof.
7. **Cursor adapter** — production adapter and full conformance proof after its unattended CLI, authentication, event, cancellation, and skill capabilities pass a feasibility spike.

The implementation order is binding because later projects depend on the provider-neutral execution, workspace, interaction, and methodology foundations. Each project must leave IssueBot deployable and existing active workflows recoverable.

## Acceptance Criteria

1. IssueBot, not a harness, deterministically selects workflow skills and controls every workflow transition and external side effect.
2. One versioned canonical skill bundle operates natively or through a tested, visibly emulated fallback across supported harnesses.
3. Repository skills may supplement but cannot replace or weaken protected core skills.
4. Every model-driven role selects and snapshots harness, model, and compatible reasoning level together.
5. Existing Claude and Codex settings and active runs migrate without unsafe replay or provider substitution.
6. Spike, Bounded, and Architectural classifications produce appropriately scaled design and planning flows.
7. Architectural implementation approval presents the completed design and plan; there is no redundant plan-approval action.
8. Questions appear one at a time with rationale, recommendation, countdown, exact default action, and free-form response.
9. The repository timeout applies independently to every question, survives restart, and never auto-approves a staged gate.
10. An unsafe unanswered decision pauses only that issue and allows other eligible work to continue.
11. Every implementation run uses an IssueBot-owned leased worktree that survives retries, pauses, reviews, and restarts.
12. Conservative parallelism is the default and never overlaps code-changing plan tasks.
13. Every plan task has a fresh bounded implementer context, real RED evidence where applicable, targeted verification, a checkpoint commit, and an independent task review.
14. Review correction defaults to five rounds with late escalation, while repositories may lower the cap.
15. Three failed root-cause fixes return the issue to Brainstorming and safely pause on an unanswered architectural decision.
16. Every review finding has an evidence-backed Accept, Reject, or Ask decision and no finding disappears silently.
17. Final whole-branch review and fresh commit-bound local verification pass before PR progression; enabled GitHub CI passes for the same head before merge.
18. IssueBot uses PR-only integration and never automatically force-removes dirty or unmerged work.
19. Restart recovery applies expired defaults once, preserves completed tasks, reconciles workspaces and GitHub objects, and presents one safe next action.
20. OpenCode and Cursor pass the same adapter and methodology conformance suites before they are advertised as Ready.

## References

- Codex skill discovery and progressive loading: <https://developers.openai.com/codex/build-skills>
- Claude Agent SDK skills and allowlisting: <https://code.claude.com/docs/en/agent-sdk/skills>
- OpenCode skill locations and permissions: <https://opencode.ai/docs/skills>
- Cursor Agent Skills and compatible project locations: <https://prod.cursor.com/docs/skills>

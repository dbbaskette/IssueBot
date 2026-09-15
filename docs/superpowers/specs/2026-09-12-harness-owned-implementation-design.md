# Harness-owned implementation, IssueBot-owned final gates

Historical design: local gate ownership was superseded in 0.19.0. See
[the 0.20.0 capability and recovery contract](../../harness-capabilities.md) and
[the approved completion spec](2026-09-15-harness-ownership-completion.md) for current
behavior. The historical local-command requirements below are not current instructions.

Status: core implementation loop shipped in 0.9.0; exact-tree verification evidence,
strict read-only independent review, and the broader UI migration remain follow-up work.
This changes the execution-ownership decision in the
[2026-09-11 cross-harness design](2026-09-11-cross-harness-superpowers-orchestration-design.md):
IssueBot should not dispatch each implementation-plan task or use independent review as
the primary mechanism for finishing an incomplete first coding pass.

## Decision

An approved issue-level plan is one coding-harness assignment. Codex, Claude Code, or a
future harness owns the *inner* loop: read the plan and code, implement coherent slices,
run focused checks, diagnose failures, revise, and repeat until the contract is complete
or a real blocker requires operator input. The harness may use its own skills, native
planning, agents, and worktree mechanisms within the assigned repository boundary.
IssueBot owns the *outer* loop: queue/dependency scheduling, approval and workspace
isolation, budgets and cancellation, trusted final verification, PR publication,
independent final review, and final disposition. IssueBot does not prescribe a task
ledger or decide how many coding steps the harness takes.

The coding harness's exit code or `turn.completed` event means only that an invocation
ended. It does **not** mean the approved plan was implemented. A successful handoff
requires a provider-neutral structured outcome: `COMPLETE`, `CONTINUE`, or `BLOCKED`,
plus a concise plan-coverage summary, checks with exact commands/results, changed-tree
identifier, and remaining limitations. Missing or malformed outcome fails closed. The
summary is a claim for independent validation, not proof that gates passed.

## One implementation run

1. Before dispatch, IssueBot verifies an immutable approved contract, a usable coding
   harness, an isolated checkout, and at least one operator-authorized local verification
   command. Plan text may suggest commands, but never silently becomes an executable
   command list. The operator explicitly configures or approves executable commands.
   The harness workspace must also be able to resolve the project's dependencies via an
   allowed network route or a prepared local cache; otherwise the run pauses with an
   environment blocker rather than claiming the plan is implemented.
2. IssueBot gives the full contract and relevant context to one harness session. The
   harness owns plan execution and focused test/fix cycles. A natural question or external
   blocker pauses for operator guidance; the work and session are retained.
3. `CONTINUE` resumes the *same* session as part of the same implementation run, not a
   new IssueBot review iteration. IssueBot persists turn summaries, token/cost totals,
   checkpoints and heartbeat; it enforces a configurable overall time/turn/budget limit.
   Timeouts and process restarts resume safely or stop with an explicit incomplete state.
4. `BLOCKED` stops before commit/push and presents the blocker with the attempted steps
   and safest next action. A sandbox or dependency-resolution failure is not converted
   into `COMPLETE` and does not trigger blind reimplementation.
5. `COMPLETE` proceeds to the trusted local commands on the resulting tree. Failure
   returns the exact command/output to the same harness session for repair; it does not
   consume an independent-review attempt. IssueBot reruns the trusted gate on every
   changed tree. If CI is enabled, IssueBot publishes only after the local gate, then
   checks CI as a separate final gate.
6. An independent reviewer (a distinct configured model where available) receives the
   approved plan, exact diff, changed-tree identity and gate evidence. It does not edit
   code or rerun broad tests by default. A genuine conformance finding becomes one
   bounded correction request to the same coding session, followed by all final gates
   again. Repeated disagreement or an unresolvable finding becomes operator guidance,
   not an endless implementation/review ping-pong.

## Cross-harness contract and safety

- Adapters translate native session/resume and output formats into the shared outcome;
  IssueBot does not assume Codex JSONL or Claude output is universal. Native harness
  skills can be used without making IssueBot emulate their internal steps.
- A model statement that tests passed cannot waive the trusted local gate, CI, or
  independent review. Verification evidence records command, exit code, environment,
  tree/commit identity and timestamp so stale successes are not reused after changes.
- Host-side dependency bootstrap, if needed, is a separate operator-authorized setup
  action. Do not give an implementation agent unrestricted network/host access merely
  to hide a sandbox limitation.
- The harness can edit only its assigned checkout. It cannot approve its own plan,
  change trusted verification settings, merge, deploy, or spend outside the run budget.
- Operator cancellation ends the current process and retains the checkpoint and
  partial diff; resumption is an explicit action. No automatic split follows merely
  from a long or blocked implementation.

## Delivery slices

1. **Fail-closed local gate (0.8.1):** approved plans without executable repository
   verification stop before coding; removal during a run stops before publication.
   Display the configuration requirement and preserve a repairable failure reason.
2. **Structured harness outcome:** add a versioned, provider-neutral response schema;
   distinguish process completion from contract completion. Capture block reasons and
   exact check claims. Add parser, adapter, and failure/restart tests.
3. **Harness-owned continuation:** persist a durable implementation-run identity and
   resume the same session for `CONTINUE` and trusted-check repair, with cancellation,
   budget, timeout and orphan recovery. Keep IssueBot's review-attempt counter separate.
4. **Final evidence and review:** persist trusted command/tree evidence, make review
   read-only, enforce or clearly disclose model separation, and limit review correction
   to an outer conformance loop. Exercise failure, correction, and restart end-to-end.
5. **UI migration:** show one implementation run with its internal turns and checks,
   followed by final gates. Replace confusing per-plan-task/iteration controls with
   Continue, Pause, and Needs guidance when those actions are actually valid. Preserve
   old run history without recasting it as the new outcome type.

The first slice is compatible with the current runtime. The remaining slices are a
behavioral migration and should ship together only after end-to-end recovery and
cross-harness tests pass; merely strengthening a prompt would not establish the new
ownership boundary.

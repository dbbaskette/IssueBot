# Complete harness ownership (#190–194)

Status: approved by the operator; implemented and verified locally for 0.20.0.

## Outcome

An issue is one assignment to the configured implementation harness. It owns code
inspection, implementation, regression coverage, local checks, and repair until it
judges the requirements satisfied. IssueBot owns scheduling, approvals, durable
state, resource authorization, cancellation, publication, CI observation, independent
review, and disposition. A completed process is not a completed assignment.

## Current evidence

- `IssueWorkflowService` selects its durable implementation loop only when an
  approved plan and checkpoint service are present.
- `phaseHarnessOwnedImplementation` persists COMPLETE/CONTINUE/BLOCKED turns but
  treats invocation failures as blockers and caps handoffs with a global limit.
- `HarnessVerificationEvidence` saves command/result summaries, not a structured
  exact-tree/environment evidence record.
- `CodexCliService` isolates personal configuration/rules and explicitly controls
  network and multi-agent capabilities. These flags are not to be removed blindly.
- `HarnessCapabilities` describes native skills and continuation only.
- Model selection is stage-specific but does not require different models.
- Review guidance forbids another suite categorically; review isolation needs an
  explicit supported boundary rather than reliance on prose.

## Contract

### Universal implementation (#190)

All new implementation paths use the same durable handoff protocol, including
non-Plan-First, legacy-compatible, restart, and correction paths. Supply issue
requirements without inventing an approved plan when none exists. CONTINUE resumes
the retained session and is not a review attempt. Invalid output cannot advance to
publication. Preserve historical records without claiming old runs used this protocol.

### Limits and recovery (#191)

Distinguish native tool turns, handoff turns, invocation timeouts, overall limits,
cancellation, and budgets. Store an explicit stop reason and effective limits.
Resource exhaustion is incomplete work, not a code review failure. An authenticated,
CSRF-protected action may extend an authorized limit and resume the same attempt;
never silently increase limits. Retain workspace and session, reject stale or active
resume requests, and atomically claim dispatch to prevent duplicate agents. Do not
blindly resume broken authentication or missing workspaces/sessions. Preserve an
operator recovery route if native timeout recovery is unsupported.

### Capability profile (#192)

Inventory and validate actual provider behavior before promising native parity.
Use an explicit managed implementation profile: project instructions, maintained
skill guidance, local testing, and optional authorized network/delegation. Load
native guidance only through a verified supported mechanism; otherwise report
prompt emulation clearly. A capability must have one authoritative guidance path.
Setup displays native/emulated/disabled/unavailable capabilities and effective
restrictions. Preserve subscription login and exclude arbitrary personal hooks,
service secrets, unrestricted host access, and automatic API billing. Profile
changes must not silently alter an already-approved run.

### Different review model (#193)

Resolve effective provider-qualified canonical model identities before a new run.
Require different implementation/review model identities; a fresh session with the
same model is insufficient. Validate defaults, overrides, and saved approvals.
Never silently select a paid model/provider or rewrite an approved tuple. Existing
active runs retain their approved choices; if they violate the new requirement,
pause at review for explicit model selection rather than discarding implementation.
Record and display actual selections in history.

### Evidence and correction (#194)

Store structured reported checks, results, claimed tested tree, relevant environment,
limitations, and timestamps separately from IssueBot-observed handoff tree identity.
Do not relabel a handoff tree observation as proof that tests ran against that tree.
Compare reviewed/published state to the captured tree, including relevant untracked
source. Missing or mismatched claims remain visibly unverified.

Review gets requirements, exact diff, identities, CI, and reported evidence in a
fresh session. It must not edit implementation or publish. Default to reading code
and reusing applicable evidence. For a justified verification gap, return a focused
check request to the implementation harness; this is the initial safe fallback
instead of building a second test executor. Record the reason and subsequent result.
Corrections return to the retained coding session, then refresh evidence and review.
Keep operational review errors separate from bounded conformance correction counts.

## Non-goals

Parallel issue scheduling (#159), Docker deployment, unrestricted native user config,
IssueBot-owned local test execution, unlimited retries, automatic model substitution,
publication/deployment of this change without authorization.

## Acceptance and risks

All five linked issue acceptance criteria remain the scope. Verify both adapters,
restart and duplicate-resume races, old persisted data, model alias handling, stale
evidence, and denied capabilities. UI disclosures and recovery controls must retain
expansion on refresh, keyboard access, and mobile readability. CLI capability support
is a discovery dependency, not a promise to invent unsupported flags.

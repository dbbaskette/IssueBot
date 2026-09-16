# Implementation plan: native permissions and operator input

## Sequence

1. **Transport feasibility:** inspect native Codex schemas and Claude CLI/control documentation.
   Establish subscription-compatible request/reply transport before changing production launchers.
2. **Shared durable model:** repository policy and run snapshot; request records, validated
   atomic decisions, process generation and wait-budget accounting. Owner: main agent.
3. **Adapters:** Codex app-server and Claude native bridge; maintain billing sanitization,
   cancellation, session continuity and output/evidence compatibility. No blanket fallback.
4. **Workflow/UI:** waiting input, dependency reservation, restart recovery, Needs You and
   issue forms; capability-aware repository permission selection using existing visual tokens.
5. **Verification/release:** targeted milestones, one full relevant suite at integration,
   JavaScript and rendered UI checks; version/changelog. No running-service restart.

## Progress

- Approved requirements captured. Existing launchers close stdin; Codex disables approvals and
  Claude implementation bypasses them. A flags-only change cannot satisfy pause/question handling.
- Installed versions observed: Codex 0.153.4; Claude Code 2.1.197. Documentation describes newer
  Claude transport options too, so capability checks must not assume those exist locally.
- Both installed CLIs completed a native initialization handshake without a model request.
- Implemented native transports, durable request/decision records, workflow-pinned policy,
  same-process waits, timeout exclusion, cancelled-request handling and no-replay recovery.
- Implemented repository controls and refreshed issue/Needs You panels. New host-permission
  mutations require dashboard authentication as well as CSRF. Full access requires confirmation.
- Verification: full Java suite passed; JavaScript 61 ordinary + 8
  localhost fixture tests passed. Focused rechecks cover subsequent native bridge, cancellation,
  security and rendering refinements and also passed. Packaging succeeded as `target/issuebot.jar`,
  with manifest version 0.22.0. No running deployment was restarted or changes published.

## Implementation boundaries

- Grants are per request, or explicitly labelled per turn for Codex permission-profile requests;
  persistent and session-wide permission rules are not offered.
- Native process loss is recovery-needed, not transparent reconnection. The operator may Stop
  the retained run; approvals are never replayed into a replacement native connection.
- Codex app-server reads native local configuration. Explicit policy/provider/sandbox overrides
  replace the old exec-only ignore-user-config behavior for implementation; review stays protected.
- Native initialized transports and fake model-turn exchanges were verified. No live model
  implementation or paid-service test was run as part of verification.

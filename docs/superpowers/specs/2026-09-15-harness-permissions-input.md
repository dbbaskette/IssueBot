# Harness permissions and durable operator input

Status: implemented locally and verified; native live-model execution remains an operator smoke test.

## Outcome

Repository owners select Ask for approval, Approve for me, or Full access independently
of workflow stage approvals. Both Codex and Claude Code use their native permission
mechanisms; automatic review is not a permission bypass. Keep subscription-only billing.
An assistant asking a question or requesting permission pauses the current run, not a retry.

## Shared contract

- A run snapshots the repository permission policy. Active runs do not silently inherit edits.
- Adapters expose supported modes, input types, grant scopes and resume capabilities.
  Unsupported combinations produce a setup explanation, never a more permissive fallback.
- A request is bound to issue, workflow run, implementation attempt, provider session,
  transport generation and native request ID. Persist before showing or answering it.
- Responses are authenticated, CSRF-protected, schema-validated and atomically claimed.
  Reject stale, duplicate-conflicting or cross-issue replies. Do not replay approvals after
  uncertain delivery to a dead transport.
- Waiting input preserves the workspace, approved plan and repository/dependency reservation.
  It consumes no coding timeout or retry attempt. Global stop/cancel must still work.
- Questions require real user answers even in automatic permission review mode. Automatic
  denials return to the assistant for alternatives; genuine user-input needs become waiting.
- An issue detail card and Needs You expose the same pending request. Permission cards show
  the action, reason, requested access and available scopes; question cards show native choices
  and supported free text. Retain decision history and disclosure state during refreshes.
- Restart recovery preserves pending records and the session identity. If the transport cannot
  reconnect/resume the request safely, display recovery required without resetting the attempt
  or silently executing a new coding run.

## Provider boundaries

Codex: app-server bidirectional JSON-RPC, native approval reviewer, command/file/permission
requests and tool user input. Validate against schemas exported by the installed CLI.

Claude: native default/auto/bypassPermissions modes; validate the supported streaming control
or permission-prompt integration using its installed CLI. Do not adopt API-billed execution or
assume SDK authentication is interchangeable with Claude Code subscription authentication.

Planning and independent review remain non-mutating. Full access is explicit opt-in, not a
way to override organization policy, OS credentials or external authorization.

## Acceptance

Both providers must pass fake-transport tests for permission and question wait/reply, native
policy mapping, retained identity, cancellation, excluded wait time, stale replies and process
loss. MVC tests cover repository forms, pending cards, Needs You and authenticated actions.
No live production workflow or provider tokens are needed for these tests. Document any
installed-version limitation and do not label an unverified transport as supported.

# Harness permissions and operator input — implementation report

Implemented locally on `codex/harness-permissions-input`, version 0.22.0.

## Delivered

- Separate repository controls for Ask for approval, native Approve for me, and confirmed
  Full access. A durable workflow policy snapshot prevents mid-run settings changes.
- Codex app-server and Claude Code bidirectional streaming adapters for implementation and
  correction. Independent review retains its existing protected launcher.
- Durable issue/run/attempt/session/request identities, atomic operator replies, stale and
  conflicting-response rejection, and no arbitrary browser-supplied permission payloads.
- Inline issue request panels, questions and suggested answers, permission scope labels,
  Needs You integration, and preserved response forms/disclosure state on refresh.
- Same-process waiting without consuming coding time or another attempt. Native withdrawals
  are handled; connection loss retains a recovery checkpoint instead of silently replaying.
- Live cancellation and explicit stopping of disconnected waits preserve existing work.
- Authenticated, CSRF-protected permission mutations. Existing anonymous dashboard access
  remains unchanged, but cannot grant permissions or answer native input requests.
- Guidance now tells the coding assistant to use native permission/question channels before
  treating an authority or input dependency as a blocker.

## Verification

- Installed Codex 0.153.4 and Claude Code 2.1.197 completed native initialization handshakes
  without a model request.
- `./mvnw -q test` passed. After review refinements, affected harness, workflow/control,
  security, prompt and render tests were rerun successfully.
- All 69 JavaScript tests passed. Eight fixture-server tests needed a sandbox escalation
  solely to bind a disposable localhost port; the other 61 passed in the sandbox.
- Fake native process exchanges cover Codex approval and Claude question continuation,
  session identity, excluded wait time, interruption and subscription cost handling.
- Database tests cover pinned policy, cross-issue/old-run/duplicate responses, withdrawal and
  disconnected requests. Recovery tests confirm pending input is not automatically requeued.
- Render checks cover escaping, draft preservation markup and disconnected controls. A
  synthetic browser preview was inspected; no running IssueBot workflow was used for UI testing.
- Packaging passed. `target/issuebot.jar` reports implementation version 0.22.0.
- `git diff --check` passed.

## Operating notes and limits

Enable dashboard login using `ISSUEBOT_USERNAME` and `ISSUEBOT_PASSWORD` before using the
new controls. CLI subscription login remains the authentication source; this implementation
does not introduce API-key billing, copy credentials, or install an SDK runtime.

Codex app-server reads native installation configuration. IssueBot explicitly pins its
provider, login method, permission policy/reviewer, sandbox, network and delegation controls.
This is different from the old implementation exec command's ignored user configuration;
protected review still ignores user configuration and rules.

Permission grants are request-scoped, or explicitly turn-scoped for Codex permission-profile
requests. Persistent/session-wide approval rules are not offered. A dead native request cannot
be transparently reconnected; its identity and work are retained, with no automatic replay.
Native provider model/tool execution was not run against a real issue during verification.

The running service was not restarted. Changes have not been pushed, opened as a PR or merged.

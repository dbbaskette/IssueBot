# Codex CLI Provider Implementation Plan

**Goal:** Add Codex CLI as a selectable IssueBot execution provider alongside Claude Code, using the operator's existing ChatGPT subscription login and showing the models available to that Codex CLI installation.

## Design

- Keep `ClaudeCodeService` as the workflow-facing facade for backward compatibility, but route every execution, availability, authentication, and session operation to the selected provider.
- Add a Codex executor that runs `codex exec --json` in the repository with `workspace-write` sandboxing and `never` approvals. It consumes the existing `codex login` session; no OpenAI API key is accepted or stored.
- Parse Codex JSONL events into the existing result shape, including final answer, token usage, session/thread id, changed files, failures, and streamed events.
- Discover visible model choices with `codex debug models`, cache the compact catalog, and fall back to a curated list when discovery is unavailable.
- Persist provider-specific model defaults independently so switching providers does not overwrite the other provider's choices.
- Make Settings, Setup, health, startup validation, and user-facing execution labels provider-aware.

## Verification

1. Add parser, command construction, model discovery, provider routing, resolver, settings persistence, and template rendering tests.
2. Run focused tests while iterating.
3. Run the full Maven suite.
4. Restart the native IssueBot process on port 8090 and verify provider/model controls in the live UI.

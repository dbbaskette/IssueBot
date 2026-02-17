# IssueBot — Product Requirements Document v1.0

**Local Dev Agent for Autonomous GitHub Issue Implementation**

|           |                                                      |
|-----------|------------------------------------------------------|
|**Version**|1.0                                                   |
|**Date**   |February 2026                                         |
|**Author** |Dan                                                   |
|**Status** |DRAFT                                                 |
|**Stack**  |Spring AI 1.1.0 GA · Claude Code CLI · MCP Integration|

-----

## 1. Executive Summary

**IssueBot** is a locally-running, Spring AI-powered dev agent that watches configured GitHub repositories for issues labeled `agent-ready`, autonomously addresses them using the Claude Code CLI in headless mode, and iterates on the implementation until a self-assessment pass and CI checks confirm completion. It handles any well-scoped issue — bug fixes, feature enhancements, refactors, documentation updates, test additions, dependency upgrades — and delivers a pull request as its sole output. It runs as a background process on the developer’s machine, exposing both a local web dashboard (Spring Boot) and a YAML configuration file for setup and monitoring.

The system uses Spring AI 1.1.0 GA with full Model Context Protocol (MCP) integration, treating Claude Code CLI invocations, GitHub API interactions, Git operations, and CI status checks as MCP tools orchestrated by a central Spring AI agent. Authentication leverages the `ANTHROPIC_API_KEY` environment variable consumed by Claude Code’s headless (`-p`) mode, not the Anthropic REST API directly.

**Key differentiator:** IssueBot doesn’t just delegate to Claude Code and hope for the best. It wraps each issue in a structured loop: plan, implement, self-assess, check CI, and iterate—with configurable autonomy levels per repository and a hard max-iterations guardrail to prevent runaway sessions.

**Definition of Done:** The sole deliverable of every successful IssueBot run is a **pull request**. The agent creates a dedicated branch (`issuebot/issue-{number}-{slug}`), implements the work described in the issue — whether that’s a bug fix, feature enhancement, refactor, documentation update, test addition, or dependency upgrade — and opens a PR against the repo’s default branch. IssueBot never commits to `main` directly. The PR is the handoff point — humans review and merge.

-----

## 2. Problem Statement

Development teams accumulate GitHub issues faster than they can address them. Many issues — bug fixes, feature enhancements, refactors, dependency updates, documentation gaps, test coverage improvements — are well-scoped enough for an AI coding agent to handle autonomously. Yet today’s workflow requires a human to:

1. Triage the issue and decide if it’s agent-appropriate
2. Manually invoke Claude Code or similar tools in the right repo context
3. Monitor the output, iterate on failures, and verify CI passes
4. Create a branch, commit, push, and open a PR

This is repetitive, context-switch-heavy work that a well-designed agent can handle end-to-end for the right class of issues.

-----

## 3. Goals and Non-Goals

### 3.1 Goals

- **Autonomous issue implementation:** Watch multiple GitHub repos, detect new issues labeled `agent-ready`, and deliver a PR that addresses the issue — whether it’s a bug fix, enhancement, refactor, or documentation update — without human intervention (when configured for autonomous mode).
- **Structured iteration loop:** Plan → implement → self-assess → CI check → iterate, with Claude Code performing its own quality review before declaring completion.
- **Configurable autonomy:** Per-repo setting for fully autonomous (branch → implement → PR) or approval-gated (propose changes, wait for human approval before PR).
- **Local-first architecture:** Runs as a process on the developer’s machine with H2 embedded database. No cloud infrastructure required.
- **Spring AI MCP integration:** All external interactions (GitHub API, Git operations, Claude Code CLI, CI status) modeled as MCP tools for consistent orchestration.
- **Dual configuration:** Web dashboard UI at localhost for real-time monitoring plus YAML config file for version-controllable settings.
- **Desktop notifications:** Native OS notifications when issues are addressed, PRs are opened, or intervention is needed.

### 3.2 Non-Goals

- Multi-user or team-shared deployment (this is a local dev tool)
- Support for GitLab, Bitbucket, or other Git hosting providers (GitHub only for v1.0)
- Direct Anthropic API integration (all AI coding goes through Claude Code CLI)
- Replacement for comprehensive CI/CD pipelines
- Code review automation (IssueBot opens PRs; humans review them)

-----

## 4. Architecture Overview

### 4.1 High-Level Architecture

IssueBot is a Spring Boot 3.x application running on the developer’s local machine. It is structured around Spring AI 1.1.0 GA’s MCP client capabilities, where the central orchestration agent connects to multiple MCP tool providers:

```
┌─────────────────────────────────────────────────────────────────┐
│          IssueBot Agent Core (Spring AI ChatClient)           │
│                    + MCP Client Orchestrator                    │
├─────────────────────────────────────────────────────────────────┤
│              Spring AI MCP Tool Callback Layer                  │
├───────────┬───────────┬───────────┬──────────┬────────┬────────┤
│  GitHub   │  Git Ops  │  Claude   │    CI    │ Notif- │ Config │
│  Issues   │   (JGit)  │  Code CLI │  Status  │ ication│  Mgmt  │
│   MCP     │    MCP    │    MCP    │   MCP    │  MCP   │  MCP   │
└───────────┴───────────┴───────────┴──────────┴────────┴────────┘
```

### 4.2 Technology Stack

|Component          |Technology                                                                      |
|-------------------|--------------------------------------------------------------------------------|
|**Runtime**        |Java 21+, Spring Boot 3.4.x                                                     |
|**AI Framework**   |Spring AI 1.1.0 GA                                                              |
|**MCP Integration**|Spring AI MCP Client (STDIO transport for Claude Code; internal for other tools)|
|**AI Engine**      |Claude Code CLI (headless mode via `-p` flag)                                   |
|**Authentication** |`ANTHROPIC_API_KEY` environment variable                                        |
|**Persistence**    |H2 embedded database (file-backed for durability across restarts)               |
|**Git Operations** |JGit (Eclipse) for branch, commit, push operations                              |
|**GitHub API**     |GitHub REST API v3 via Spring WebClient (issues, PRs, CI status)                |
|**Web UI**         |Spring Boot + Thymeleaf + HTMX (dashboard)                                      |
|**Notifications**  |`java.awt.SystemTray` (cross-platform desktop notifications)                    |
|**Configuration**  |Spring Boot YAML + web UI overlay                                               |
|**Build**          |Gradle with Spring Boot plugin                                                  |
|**Observability**  |Spring Boot Actuator + Micrometer                                               |

### 4.3 MCP Tool Architecture

Each external capability is implemented as a Spring AI MCP tool, registered via the `@McpTool` annotation pattern. The central orchestration agent (Spring AI ChatClient) selects and invokes tools based on the current phase of issue implementation. This provides clean separation of concerns, testability, and the ability to swap implementations without touching orchestration logic.

The following MCP tool servers are embedded within the IssueBot process:

- **GitHubIssueTool:** `list-issues`, `get-issue`, `add-comment`, `assign-issue`, `add-labels`, `close-issue`
- **GitHubPrTool:** `create-pr`, `get-pr-status`, `get-pr-checks`
- **GitOpsTool:** `clone-repo`, `create-branch`, `checkout`, `commit`, `push`, `diff`, `status`
- **ClaudeCodeTool:** `execute-task` (wraps `claude -p` invocation with working directory, allowed tools, and output parsing)
- **CiStatusTool:** `get-check-runs`, `wait-for-checks` (polls GitHub Checks API until completion)
- **NotificationTool:** `send-desktop-notification`, `send-dashboard-event`
- **ConfigTool:** `get-repo-config`, `get-global-config` (reads per-repo settings like autonomy mode and max iterations)

-----

## 5. Detailed Requirements

### 5.1 Issue Detection and Filtering

IssueBot continuously monitors configured GitHub repositories for new issues. The polling interval is configurable per-repo (default: 60 seconds). Webhook support is a future enhancement.

#### 5.1.1 Issue Qualification

Not every issue should be processed. IssueBot uses the `agent-ready` label as its sole opt-in trigger — this is a hardcoded convention in v1.0, not configurable per-repo. A human triages the issue, decides it’s scoped enough for the agent, and applies the label. This keeps the human-in-the-loop for triage, provides a natural cost gate, and ensures predictable behavior across all watched repos.

Additional filters applied after label detection:

- **`agent-ready` label (required, hardcoded):** Only issues carrying this label are picked up. IssueBot ignores all other issues regardless of content or assignee. This is the primary safety mechanism — the agent never acts on an issue a human hasn’t explicitly approved for automation.
- **Assignee filter (optional):** Only unassigned issues, or issues assigned to a bot account.
- **State filter:** Only open issues. Closed or in-progress issues are skipped.
- **Duplicate detection:** IssueBot tracks issue IDs in H2 to avoid reprocessing. Issues already in-progress or completed are skipped.
- **Cooldown:** If an issue was attempted and failed (hit max iterations), it enters a configurable cooldown period before retry (default: 24 hours).

### 5.2 Issue Implementation Workflow

When a qualifying issue is detected, IssueBot executes a structured implementation loop. Each iteration is tracked in H2 with full state.

#### 5.2.1 Phase 1 — Setup

1. **Clone/pull:** Ensure a fresh local copy of the repository at the default branch (main/master).
2. **Create branch:** Create a working branch named `issuebot/issue-{number}-{slug}` from the default branch.
3. **Context assembly:** Build a structured prompt containing the issue title, body, labels, any linked PRs or issues, and relevant repo metadata (language, framework hints from package files).

#### 5.2.2 Phase 2 — Implementation (Claude Code CLI)

IssueBot invokes Claude Code in headless mode to perform the actual coding work:

```bash
claude -p "<structured-prompt>" \
  --output-format stream-json \
  --allowedTools "Read,Write,Edit,Bash(git diff:*),Bash(npm test:*),Bash(gradle test:*),Bash(mvn test:*)" \
  --max-turns 30
```

Key aspects of the Claude Code invocation:

- **Working directory:** Set to the local repo clone root. Claude Code gets full codebase context.
- **Allowed tools:** Scoped to read, write, edit, and test commands. No arbitrary bash execution.
- **Structured prompt:** Includes the issue description, repo conventions (from `.issuebot.yml` if present), and explicit instructions to implement the requested changes, write tests where appropriate, and verify the work.
- **Output parsing:** `stream-json` output is parsed for tool invocations, file changes, and final result assessment.
- **Session management:** Each iteration gets a fresh Claude Code session. State is not carried between iterations to avoid context pollution.

#### 5.2.3 Phase 3 — Self-Assessment

After Claude Code completes its implementation pass, IssueBot invokes a **separate** Claude Code session for self-assessment:

- **Review prompt:** “Review the changes in this branch against the original issue requirements. Check for: completeness (does the implementation fully address what the issue asked for?), correctness, test coverage, code style consistency, potential regressions, and edge cases. Output a JSON assessment with pass/fail and specific issues found.”
- **Diff-based:** The assessment prompt includes the `git diff` of all changes, not the full codebase, to keep context focused.
- **Structured output:** Claude Code returns a JSON assessment that IssueBot parses to determine next steps.

#### 5.2.4 Phase 4 — CI Verification

If self-assessment passes, IssueBot pushes the branch and monitors CI:

1. **Push branch:** Push to origin with the `issuebot/` prefix.
2. **Wait for CI:** Poll GitHub Checks API for the branch until all required checks complete (configurable timeout, default: 15 minutes).
3. **Evaluate results:** If all checks pass, proceed to Phase 5. If checks fail, feed the failure logs back into Claude Code for the next iteration.

#### 5.2.5 Phase 5 — Completion (Branch → PR)

When both self-assessment and CI pass, IssueBot’s final action is always to open a pull request. The PR is the sole deliverable — IssueBot never merges, never commits to the default branch directly, and never closes the issue without a PR.

- **Autonomous mode:** Automatically create a pull request against the default branch with a structured description including: the issue reference (`Closes #42`), a change summary generated by Claude Code, test results, iteration count, and total token cost. Comment on the original issue with a link to the PR. Remove the `agent-ready` label and add `issuebot-complete`.
- **Approval-gated mode:** Create a **draft** pull request with the same structured description, send a desktop notification and dashboard alert (“Awaiting your approval”), and wait. The human reviews the diff in the dashboard or on GitHub, then either approves (converting the draft to a ready PR) or rejects with feedback (which triggers another iteration if under max-iterations).

#### 5.2.6 Iteration and Guardrails

If either self-assessment or CI fails, IssueBot loops back to Phase 2 with enhanced context:

- **Failure context injection:** The structured prompt for the next iteration includes the previous attempt’s diff, the self-assessment feedback, and/or CI failure logs.
- **Max iterations guardrail:** Configurable per-repo (default: 5). When reached, IssueBot marks the issue as `needs-human` with a comment summarizing all attempts, labels it accordingly, and sends a desktop notification.
- **Escalation:** Failed issues are visible in the dashboard with full iteration history, diffs, and logs for human review.

### 5.3 Configuration System

#### 5.3.1 YAML Configuration File

Primary configuration lives in `~/.issuebot/config.yml`:

```yaml
global:
  poll-interval-seconds: 60
  max-concurrent-issues: 3
  work-directory: ~/.issuebot/repos
  # The agent-ready label is hardcoded in v1.0 — no label config needed
  claude-code:
    max-turns-per-invocation: 30
    model: claude-sonnet-4-5-20250929
    timeout-minutes: 10
  github:
    token: ${GITHUB_TOKEN}
  notifications:
    desktop: true
    dashboard: true

repositories:
  - owner: my-org
    name: my-app
    branch: main
    mode: autonomous          # or 'approval-gated'
    max-iterations: 5
    ci-timeout-minutes: 15
    allowed-paths:
      - src/
      - test/

  - owner: my-org
    name: my-lib
    branch: main
    mode: approval-gated
    max-iterations: 3
```

#### 5.3.2 Web Dashboard UI

The Spring Boot dashboard at `localhost:8090` (configurable) provides:

- **Repository management:** Add, edit, remove watched repositories with all configuration options available in YAML.
- **Issue queue:** Real-time view of pending, in-progress, completed, and failed issues across all repos.
- **Issue detail view:** Full iteration history with diffs, self-assessment results, CI logs, and Claude Code output for each attempt.
- **Approval workflow:** For approval-gated repos, one-click approve/reject with optional feedback that gets injected into the next iteration.
- **Global controls:** Pause/resume agent, view active Claude Code sessions, kill stuck sessions.
- **Configuration editor:** Edit YAML config with validation, changes apply without restart.

The dashboard uses HTMX for reactive updates without a heavy JavaScript framework, keeping the tech stack clean and Spring-native.

### 5.4 Claude Code CLI Integration

#### 5.4.1 Authentication

IssueBot uses the `ANTHROPIC_API_KEY` environment variable for Claude Code CLI authentication. This is the recommended approach for non-interactive/headless usage. The key must be set in the environment where IssueBot runs. The agent validates key presence at startup and fails fast with a clear error message if missing.

Key management details:

- **Source:** Anthropic Console API key (not a Claude.ai subscription).
- **Mechanism:** Passed to Claude Code CLI process via environment variable inheritance. IssueBot never stores or logs the key.
- **Validation:** At startup, IssueBot invokes `claude --version` to verify the CLI is installed and `claude -p "hello"` to verify authentication works.
- **Model selection:** Configurable via `ANTHROPIC_MODEL` or the `--model` flag per invocation. Default: `claude-sonnet-4-5-20250929` for cost-effective iteration.

#### 5.4.2 Headless Invocation Pattern

Each Claude Code invocation uses the `-p` (print/headless) flag for non-interactive execution:

- **Command construction:** IssueBot builds the `claude -p` command with: structured prompt, `--output-format stream-json`, `--allowedTools` whitelist, `--max-turns` limit, and `--append-system-prompt` for repo-specific conventions.
- **Process management:** Java `ProcessBuilder` launches `claude` as a child process. Stdout is consumed as newline-delimited JSON. Stderr is captured for error logging.
- **Timeout enforcement:** If Claude Code exceeds the configured timeout (default: 10 minutes), the process is destroyed and the iteration is marked as timed out.
- **Output parsing:** Stream-JSON output is parsed for: tool invocations (files read/written), final text result, and session metadata including token usage for cost tracking.

### 5.5 Notification System

IssueBot sends desktop notifications for key events:

- **Issue picked up:** “IssueBot: Starting work on my-org/my-app #42 — Add pagination to /api/users endpoint”
- **PR opened:** “IssueBot: PR #87 opened for my-org/my-app #42 (3 iterations)”
- **Approval needed:** “IssueBot: Awaiting your approval for my-org/my-app #42 → Dashboard”
- **Max iterations hit:** “IssueBot: Gave up on my-org/my-app #42 after 5 attempts. Needs human.”
- **Error:** “IssueBot: Error processing my-org/my-app #42 — GitHub API rate limit exceeded”

Notifications are implemented via `java.awt.SystemTray` for cross-platform support (macOS, Linux, Windows). The dashboard also shows a live event feed via SSE (Server-Sent Events).

-----

## 6. Data Model

H2 embedded database with file-backed storage at `~/.issuebot/issuebot.db`. Schema managed by Flyway migrations.

### 6.1 Core Tables

|Table           |Purpose              |Key Fields                                                              |Notes                                                                                   |
|----------------|---------------------|------------------------------------------------------------------------|----------------------------------------------------------------------------------------|
|`watched_repos` |Repository config    |owner, name, mode, max_iterations                                       |Synced from YAML + UI edits. Label is always `agent-ready` (hardcoded).                 |
|`tracked_issues`|Issue lifecycle state|repo_id, issue_number, status, current_iteration, branch_name           |Status: `PENDING`, `IN_PROGRESS`, `AWAITING_APPROVAL`, `COMPLETED`, `FAILED`, `COOLDOWN`|
|`iterations`    |Per-attempt history  |issue_id, iteration_num, claude_output, self_assessment, ci_result, diff|Full audit trail of each attempt                                                        |
|`events`        |Activity log         |timestamp, type, repo_id, issue_id, message                             |Powers dashboard feed and notification history                                          |
|`cost_tracking` |Token/cost usage     |issue_id, iteration_num, input_tokens, output_tokens, estimated_cost    |Parsed from Claude Code stream-json output                                              |

-----

## 7. Spring AI MCP Implementation Details

IssueBot uses Spring AI 1.1.0 GA’s MCP framework for all tool orchestration. The architecture follows the MCP client pattern where IssueBot itself is the MCP client, and tool implementations are registered as embedded MCP servers within the same process.

### 7.1 Orchestration Agent

The central orchestration agent uses Spring AI’s ChatClient with the Anthropic model provider (for the planning/reasoning layer, separate from Claude Code CLI invocations) configured via Spring AI’s auto-configuration. The orchestration agent’s responsibilities:

- **Issue triage:** Given a new issue, determine if it’s a good candidate for autonomous handling (code change, enhancement, docs update vs. open-ended discussion, scope assessment).
- **Plan generation:** Create a structured plan for Claude Code including which files to examine, what tests to run, and what the success criteria are.
- **Self-assessment coordination:** After Claude Code completes, assemble the self-assessment prompt and parse results.
- **Iteration decisions:** Based on self-assessment and CI results, decide whether to iterate, escalate, or complete.

### 7.2 MCP Tool Registration

All tools are registered using Spring AI’s `@McpTool` annotations and auto-discovered via component scanning:

```java
@Service
public class ClaudeCodeTool {

    @McpTool(
        name = "execute-claude-code",
        description = "Execute a coding task using Claude Code CLI"
    )
    public ClaudeCodeResult executeTask(
        @McpToolParam(description = "Task prompt", required = true)
        String prompt,
        @McpToolParam(description = "Working directory", required = true)
        String workingDirectory,
        @McpToolParam(description = "Allowed tools whitelist")
        String allowedTools,
        @McpProgressToken String progressToken,
        McpSyncServerExchange exchange
    ) {
        // Build and execute claude -p command
        // Stream progress via exchange.progressNotification()
        // Parse stream-json output
        // Return structured result
    }
}
```

### 7.3 Recursive Advisor Pattern

IssueBot leverages Spring AI 1.1.0’s new recursive advisor feature for the self-assessment loop. The recursive advisor enables the agent to evaluate its own output and decide whether to iterate, creating the self-improving behavior required for reliable issue implementation. This is the core mechanism that implements the “iterate until complete” requirement.

-----

## 8. Security Considerations

- **API key handling:** `ANTHROPIC_API_KEY` and `GITHUB_TOKEN` are read from environment variables only, never stored in config files or the database. The dashboard UI never displays these values.
- **Claude Code sandboxing:** The `--allowedTools` flag restricts Claude Code to read, write, edit, and specific test commands. No arbitrary shell access.
- **Filesystem scope:** Claude Code operates within the cloned repo directory only. The `allowed-paths` config further restricts which directories Claude Code can modify.
- **Branch isolation:** All work happens on `issuebot/` prefixed branches. The default branch is never modified directly.
- **Network scope:** IssueBot only communicates with GitHub API (`api.github.com`) and spawns local Claude Code CLI processes. No other outbound network access.
- **Dashboard access:** Bound to localhost only by default. Optional basic auth can be enabled for environments where other users share the machine.

-----

## 9. Observability

IssueBot exposes comprehensive metrics and health information via Spring Boot Actuator and Micrometer:

- **Metrics:** Issues processed, iterations per issue, success/failure rates, Claude Code invocation duration, token usage, CI wait times, GitHub API call counts.
- **Health checks:** GitHub API connectivity, Claude Code CLI availability, H2 database health, disk space for repo clones.
- **Structured logging:** JSON-formatted logs with correlation IDs per issue/iteration for easy debugging. Log files at `~/.issuebot/logs/`.
- **Dashboard metrics:** The web UI includes a summary view with key metrics: total issues completed, average iterations, cost per issue, and success rate trends.

-----

## 10. Development Phases

### Phase 1: Foundation (Weeks 1–3)

1. Spring Boot project scaffolding with Spring AI 1.1.0 GA dependencies
2. H2 database setup with Flyway migrations for core schema
3. YAML configuration loading and validation
4. GitHub API client (issue listing, filtering, commenting)
5. JGit integration (clone, branch, commit, push)
6. Claude Code CLI wrapper with ProcessBuilder and stream-json parsing

### Phase 2: Core Agent Loop (Weeks 4–6)

1. MCP tool implementations for all seven tool servers
2. Spring AI ChatClient orchestration agent with recursive advisor
3. Issue implementation workflow (all 5 phases)
4. Self-assessment loop with structured JSON output parsing
5. CI status polling and failure log extraction
6. Max iterations guardrail and cooldown logic

### Phase 3: User Interface (Weeks 7–8)

1. Spring Boot + Thymeleaf + HTMX dashboard
2. Repository management CRUD via web UI
3. Issue queue with real-time SSE updates
4. Approval workflow UI for approval-gated repos
5. Desktop notification integration (SystemTray)
6. Configuration editor with YAML validation

### Phase 4: Polish & Hardening (Weeks 9–10)

1. Observability (Actuator endpoints, Micrometer metrics)
2. Error handling and graceful degradation
3. Cost tracking and reporting
4. Integration tests with mock GitHub API and Claude Code
5. Documentation and first-run setup wizard

-----

## 11. Success Metrics

|Metric                                                               |Target                      |Measurement                          |
|---------------------------------------------------------------------|----------------------------|-------------------------------------|
|Issue completion rate (% of `agent-ready` issues that result in a PR)|> 60% for well-scoped issues|Tracked in H2                        |
|Average iterations to completion                                     |< 3 iterations              |Tracked in H2                        |
|Time from issue detection to PR                                      |< 30 minutes (excluding CI) |Tracked in events table              |
|False positive rate (PRs rejected by humans)                         |< 20%                       |Manual tracking initially            |
|Cost per completed issue                                             |< $2.00 average (Sonnet 4.5)|Token tracking in cost_tracking table|

-----

## 12. Future Enhancements (v2.0+)

- **Custom trigger labels:** Allow per-repo override of the hardcoded `agent-ready` label for teams that prefer their own naming conventions.
- **GitHub webhook support:** Replace polling with real-time webhook triggers for instant issue detection.
- **GitLab and Bitbucket support:** Extend beyond GitHub to other major Git hosting providers.
- **Slack/Teams notifications:** In addition to desktop notifications, support team chat integrations.
- **Multi-agent parallelism:** Process multiple issues concurrently with isolated working directories and separate Claude Code sessions.
- **Learning from outcomes:** Track which types of issues (bugs vs. enhancements vs. refactors) complete successfully and use this data to improve issue triage and prompt engineering.
- **Custom MCP tool plugins:** Allow users to register custom MCP tools (e.g., database migration tools, deployment scripts) that the agent can invoke during implementation.
- **Claude Code session continuity:** Use `--continue`/`--resume` flags to maintain context across iterations instead of fresh sessions.
- **PR auto-merge:** For high-confidence completions with passing CI, automatically merge PRs after a configurable delay.

-----

## 13. Open Questions

1. **Concurrent issue limit:** Should the v1.0 `max-concurrent-issues` default be 1 (serial) or 3 (parallel)? Parallel requires isolated working directories per issue.
2. **Cost alerting:** Should there be a per-issue or daily cost cap that pauses the agent if exceeded?
3. **Issue complexity scoring:** Should the orchestration agent pre-score issue complexity and skip issues that are likely too complex for autonomous handling (e.g., large architectural changes, cross-repo dependencies)?
4. **PR template customization:** Should IssueBot support a `.github/ISSUEBOT_PR_TEMPLATE.md` to let teams customize the auto-generated PR description format?
5. **Repo conventions file:** Should IssueBot look for a `.issuebot.yml` in the repo root for per-repo conventions (coding style, test framework, etc.) to inject into Claude Code prompts?
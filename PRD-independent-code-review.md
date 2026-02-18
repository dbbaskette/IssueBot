# PRD: Independent Code Review Phase

## Overview

Add an independent code review phase to IssueBot's workflow using Claude Sonnet 4.6 via the Claude CLI. After IssueBot (Opus) implements code and CI passes, Sonnet 4.6 reviews the changes against the original issue specification. Review findings feed back into the implementation loop, creating a dual-model quality gate: Opus writes, Sonnet reviews.

This also introduces GitHub Actions CI (compile + test) and an optional security review toggle.

## Motivation

- **Independent validation**: The implementing model (Opus) should not be the only judge of its own work. A separate model provides genuinely independent review with different strengths.
- **Spec compliance**: Ensure the code actually implements what the issue specifies — no more, no less.
- **Quality gate before merge**: Catch issues before PRs are merged, especially in autonomous mode where no human reviews.
- **Security**: Optional deep security review on changed files before code ships.

## New Workflow (6 Phases)

The existing 5-phase workflow is restructured. Phase 3 (Opus self-assessment) is **removed** and replaced by CI + independent Sonnet review.

```
Phase 1: Setup ─────────────── Clone/branch (unchanged)
    │
Phase 2: Implementation ────── Opus 4.6 writes code (unchanged)
    │
Phase 3: CI Verification ───── GitHub Actions: compile + test (was Phase 4)
    │  ↑ (CI failure → retry Phase 2 with failure logs, impl budget)
    │
Phase 4: PR Creation ───────── Create DRAFT PR on GitHub (new)
    │
Phase 5: Independent Review ── Sonnet 4.6 reviews via CLI (new)
    │  ↑ (Review failure → retry Phase 2 with findings, review budget)
    │
Phase 6: Completion ─────────── Mark PR ready, auto-merge if configured
```

### Key Changes from Current Workflow

| Aspect | Current | New |
|--------|---------|-----|
| Phase 3 | Opus self-assessment | **Removed** |
| Phase 4 | CI verification (if enabled) | CI verification (GitHub Actions) |
| Phase 5 | PR creation + completion | Split into Phase 4 (draft PR) + Phase 6 (finalize) |
| New Phase 5 | N/A | **Independent Sonnet 4.6 review** |
| Implementation model | claude-sonnet-4-5-20250929 | **claude-opus-4-6** |
| Review model | N/A | **claude-sonnet-4-6** |

## Phase Details

### Phase 3: CI Verification (GitHub Actions)

**New**: Add a GitHub Actions workflow to target repositories.

**Workflow file** (`.github/workflows/issuebot-ci.yml`):
```yaml
name: IssueBot CI
on:
  push:
    branches: ['issuebot/**']
  pull_request:
    branches: [main]

jobs:
  build-and-test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          java-version: '21'
          distribution: 'temurin'
      - run: mvn compile -B
      - run: mvn test -B
```

**Behavior**:
- Triggers on pushes to `issuebot/**` branches
- IssueBot polls GitHub check runs (existing `CiStatusTool` logic)
- On failure: extract logs, feed back to Phase 2 as implementation feedback
- On success: proceed to Phase 4

### Phase 4: PR Creation (Draft)

- Create a **draft** PR on GitHub after CI passes
- PR title: `IssueBot: {issue_title} (#{issue_number})`
- PR body: issue summary + metadata (iterations, model, cost)
- Draft status allows Sonnet to post review comments before the PR is "ready"
- If this is a review retry iteration, the PR already exists — just push new commits to the same branch

### Phase 5: Independent Code Review (Sonnet 4.6)

**Core behavior**: Invoke Claude CLI with Sonnet 4.6 in the cloned repo directory, scoped to files touched by the implementation.

#### CLI Invocation

```
claude -p "<review_prompt>" \
  --output-format stream-json \
  --max-turns 15 \
  --model claude-sonnet-4-6 \
  --verbose \
  --dangerously-skip-permissions
```

Working directory: the cloned repository (same as implementation).

#### Review Prompt Construction

The review prompt includes:
1. **Issue specification** — full issue title + body (the "spec")
2. **File list** — files changed in the implementation (from git diff)
3. **Diff** — the full diff vs. base branch
4. **Review instructions** — structured evaluation criteria (see below)
5. **Security review instructions** — included only if security review is enabled

#### Review Dimensions

Sonnet evaluates against these dimensions:

| Dimension | Description |
|-----------|-------------|
| **Spec Compliance** | Does the code implement exactly what the issue specifies? Missing requirements? Over-engineering? |
| **Correctness** | Logic errors, edge cases, null handling, off-by-one errors |
| **Code Quality** | Readability, naming, structure, adherence to project conventions |
| **Test Coverage** | Are the changes adequately tested? Missing test cases? |
| **Architecture Fit** | Do the changes fit the existing codebase patterns and architecture? |
| **Regressions** | Could changes break existing functionality? |
| **Security** *(optional)* | Injection vulnerabilities, auth issues, data exposure, OWASP top 10 (only when security review is enabled) |

#### Review Output Format

Sonnet returns structured JSON:

```json
{
  "passed": false,
  "summary": "Implementation covers the main requirement but misses the edge case described in acceptance criteria #3.",
  "specComplianceScore": 0.7,
  "correctnessScore": 0.9,
  "codeQualityScore": 0.85,
  "testCoverageScore": 0.6,
  "architectureFitScore": 0.95,
  "regressionsScore": 0.9,
  "securityScore": 0.8,
  "findings": [
    {
      "severity": "high",
      "category": "spec_compliance",
      "file": "src/main/java/com/example/Service.java",
      "line": 42,
      "finding": "Missing handling for the bulk import case described in issue acceptance criteria #3",
      "suggestion": "Add a batch processing method that handles collections as specified in the issue"
    },
    {
      "severity": "medium",
      "category": "test_coverage",
      "file": "src/test/java/com/example/ServiceTest.java",
      "finding": "No test for error case when input is empty",
      "suggestion": "Add test case for empty input that verifies the expected IllegalArgumentException"
    }
  ],
  "advice": "The core implementation is solid. Focus on adding the bulk import path and its corresponding tests. The existing single-item flow looks correct."
}
```

#### Scoping to Touched Files

Sonnet's review prompt instructs it to focus on:
- Files changed in the diff (primary review targets)
- Direct imports/dependencies of changed files (for context only)
- Test files corresponding to changed source files

This keeps review cost proportional to change size while still allowing Sonnet to read surrounding context via CLI when needed.

#### Review Failure → Feedback Loop

When `passed: false`:
1. Store the full review JSON in the Iteration record
2. Post a **summary comment** on the draft PR with key findings
3. Post **inline PR comments** on specific files/lines for high-severity findings
4. Feed the review `summary`, `findings`, and `advice` back to Phase 2 as implementation feedback
5. Opus receives the feedback in its next iteration prompt, along with:
   - The current diff
   - The specific findings to address
   - Sonnet's advice on how to fix

When `passed: true`:
1. Store the review JSON in the Iteration record
2. Post an **approval comment** on the PR
3. Proceed to Phase 6

#### Security Review Toggle

When enabled for a repository, the review prompt includes additional security-focused instructions:
- OWASP Top 10 checks against changed code
- Input validation and sanitization review
- Authentication/authorization review (if applicable)
- Data exposure and secrets detection
- Dependency vulnerability awareness

Security findings are included in the standard findings array with `category: "security"` and are flagged with appropriate severity.

### Phase 6: Completion

- Mark draft PR as **ready for review** (remove draft status)
- If mode is `AUTONOMOUS` and `autoMerge` is enabled: squash merge
- If mode is `APPROVAL_GATED`: leave as ready PR, set status to `AWAITING_APPROVAL`
- Update issue labels: add `issuebot-pr-created`, remove `agent-ready`
- Post final comment on issue with metadata (iterations, review score, cost)

## Iteration Budget

### Separate Review Budget

Two independent iteration counters:

| Counter | Config Key | Default | Purpose |
|---------|-----------|---------|---------|
| Implementation iterations | `maxIterations` | 5 | Phase 2-3 retries (CI failures, implementation issues) |
| Review iterations | `maxReviewIterations` | 2 | Phase 5 failures feeding back to Phase 2 |

**Behavior**:
- Implementation failures (CI fails) decrement `maxIterations`
- Review failures decrement `maxReviewIterations`
- When review budget exhausted: mark issue `FAILED`, add `needs-human` label, enter cooldown
- Each review iteration still runs through Phase 2 (Opus) → Phase 3 (CI) → Phase 5 (Sonnet)

### Escalation Path

```
Review iteration 1 fails → Opus fixes with Sonnet's feedback → CI → Sonnet re-reviews
Review iteration 2 fails → Escalate: needs-human label, cooldown
```

## Configuration Changes

### Per-Repository Settings (WatchedRepo)

New fields:

```yaml
securityReviewEnabled: false    # Toggle for security review dimension
maxReviewIterations: 2          # Max review-triggered re-implementation cycles
reviewModel: "claude-sonnet-4-6" # Model for independent review (default)
```

### Global Configuration (application.yml)

```yaml
issuebot:
  claude-code:
    implementation-model: "claude-opus-4-6"       # Model for Phase 2
    review-model: "claude-sonnet-4-6"             # Model for Phase 5
    review-max-turns: 15                          # Max turns for review session
    review-timeout-minutes: 5                     # Timeout for review invocation
```

## Data Model Changes

### Iteration Table

New columns:

| Column | Type | Description |
|--------|------|-------------|
| `review_json` | TEXT | Full Sonnet review JSON response |
| `review_passed` | BOOLEAN | Whether the independent review passed |
| `review_model` | VARCHAR | Model used for review (e.g., claude-sonnet-4-6) |

### CostTracking Table

Review invocations tracked separately:

| Column | Type | Description |
|--------|------|-------------|
| `phase` | VARCHAR | `IMPLEMENTATION` or `REVIEW` — which phase incurred the cost |

### WatchedRepo Table

New columns:

| Column | Type | Description |
|--------|------|-------------|
| `security_review_enabled` | BOOLEAN | Default false |
| `max_review_iterations` | INTEGER | Default 2 |

### Database Migration

New Flyway migration: `V6__add_independent_review.sql`

## Dashboard Updates

### Issue Detail View

- Show review results alongside implementation iterations
- Display review scores (radar chart or score bars for each dimension)
- Show review findings with severity indicators
- Link to PR review comments

### Repository Settings

- New toggle: "Security Review" (enabled/disabled)
- New field: "Max Review Iterations" (numeric, default 2)
- Display review model selection

### Cost Tracking

- Separate cost breakdown: Implementation (Opus) vs. Review (Sonnet)
- Per-issue total cost includes both models

## GitHub Actions CI Setup

IssueBot should be able to generate and commit a basic CI workflow file for watched repositories that don't have one. This is a one-time setup step.

### Setup Flow

1. When adding a new watched repo in the dashboard, check if `.github/workflows/` contains a CI workflow
2. If not present, offer to generate one based on the detected project type:
   - **Maven/Java**: `mvn compile && mvn test`
   - **Gradle/Java**: `./gradlew build`
   - **Node.js**: `npm ci && npm test`
   - **Python**: `pip install -r requirements.txt && pytest`
3. Commit the workflow file to the default branch (requires user confirmation)
4. Enable `ciEnabled: true` on the watched repo config

## Token Cost Estimates

Per-issue cost estimate (assuming ~500 line diff):

| Phase | Model | Est. Input Tokens | Est. Output Tokens | Est. Cost |
|-------|-------|-------------------|--------------------|----|
| Implementation | Opus 4.6 | ~50,000 | ~10,000 | ~$1.25 |
| Review | Sonnet 4.6 | ~20,000 | ~3,000 | ~$0.11 |
| Review retry (if needed) | Opus + Sonnet | ~70,000 | ~13,000 | ~$1.36 |

The independent review adds ~$0.11 per issue when it passes on the first attempt — a small cost for significant quality improvement.

## Implementation Phases

### Phase A: CI Foundation
1. Add GitHub Actions workflow template generation
2. Update `WatchedRepo` model with new fields
3. Database migration `V6__add_independent_review.sql`
4. Update repository settings UI

### Phase B: Workflow Restructure
1. Remove Phase 3 (Opus self-assessment)
2. Renumber phases: Setup → Implementation → CI → PR Creation → Review → Completion
3. Update `IssueWorkflowService` phase logic
4. Update `TrackedIssue.currentPhase` to reflect new phase names
5. Change implementation model to Opus 4.6

### Phase C: Independent Review Phase
1. Build review prompt constructor (issue spec + diff + touched files + review instructions)
2. Add Sonnet 4.6 CLI invocation in `ClaudeCodeService` (configurable model)
3. Parse review JSON response
4. Post review findings as PR comments (summary + inline)
5. Implement review failure → Phase 2 feedback loop
6. Add `maxReviewIterations` counter and escalation logic

### Phase D: Security Review & Polish
1. Add security review prompt extension (toggled by `securityReviewEnabled`)
2. Update dashboard: review scores, findings display, cost breakdown
3. Update cost tracking to separate implementation vs. review costs
4. End-to-end testing of full workflow

## Success Criteria

- [ ] Code that passes review on first attempt ≥ 70% of issues
- [ ] Review catches spec compliance gaps that self-assessment missed
- [ ] Security review (when enabled) identifies at least OWASP top 5 vulnerability categories
- [ ] Review adds < 3 minutes to the per-issue pipeline (excluding retries)
- [ ] Total cost per issue increases by < 15% compared to current workflow
- [ ] No increase in `needs-human` escalation rate (review should help, not hurt)

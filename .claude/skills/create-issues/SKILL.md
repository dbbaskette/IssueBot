---
name: create-issues
description: Break down a design document into GitHub issues with labels, milestones, phases, and dependency chains. Creates issues in the correct order so IssueBot can process them autonomously.
argument-hint: <owner/repo> <design-doc-path>
allowed-tools: Read, Grep, Glob, Bash(gh *), Task
---

# Create GitHub Issues from Design Document

You are breaking down a design document into well-structured GitHub issues on the repository **$0**.

## Step 1: Read the Design Document

Read the design document at `$1`. If no path is provided, ask the user to specify one.

Thoroughly understand:
- The overall goal and architecture
- Individual components, features, or tasks
- Dependencies between tasks (what must be built before what)
- Any phasing or milestone groupings

## Step 2: Plan the Issue Breakdown

Break the design into **atomic, implementable issues** — each one should be completable by an AI coding agent in a single session (roughly 1-2 files of changes, a focused scope).

Organize issues into **phases** (milestones). Common phasing:
- **Phase 1: Foundation** — models, schemas, migrations, core interfaces
- **Phase 2: Core Logic** — services, business logic, algorithms
- **Phase 3: Integration** — API endpoints, controllers, wiring
- **Phase 4: UI** — templates, frontend components, styling
- **Phase 5: Testing & Polish** — integration tests, edge cases, docs

For each issue, determine:
- **Title**: Clear, imperative (e.g. "Implement UserService with CRUD operations")
- **Body**: Detailed requirements, acceptance criteria, relevant code locations
- **Labels**: Phase label + any relevant labels
- **Milestone**: Which phase/milestone it belongs to
- **Dependencies**: Which other issues must be completed first

## Step 3: Create Milestones

Check existing milestones first, then create any needed ones:

```bash
gh milestone list --repo $0
```

Create milestones for each phase:
```bash
gh milestone create --repo $0 --title "Phase N: Name"
```

## Step 4: Ensure Labels Exist

Create phase labels if they don't exist:
```bash
gh label create "phase-1" --repo $0 --color "0E8A16" --description "Phase 1: Foundation" --force
gh label create "phase-2" --repo $0 --color "1D76DB" --description "Phase 2: Core Logic" --force
gh label create "phase-3" --repo $0 --color "D93F0B" --description "Phase 3: Integration" --force
gh label create "phase-4" --repo $0 --color "FBCA04" --description "Phase 4: UI" --force
gh label create "phase-5" --repo $0 --color "5319E7" --description "Phase 5: Testing & Polish" --force
gh label create "agent-ready" --repo $0 --color "F9A825" --description "Ready for IssueBot processing" --force
```

## Step 5: Create Issues in Dependency Order

**CRITICAL**: Create issues bottom-up — leaf tasks first (no dependencies), then tasks that depend on them. This ensures `#N` references in `**Blocked by:**` lines point to real issue numbers.

Track every created issue number. For each issue:

```bash
gh issue create --repo $0 \
  --title "Issue title" \
  --body "$(cat <<'BODY'
## Description
What needs to be done...

## Requirements
- Requirement 1
- Requirement 2

## Acceptance Criteria
- [ ] Criterion 1
- [ ] Criterion 2

## Implementation Notes
Relevant files, patterns to follow, etc.

**Blocked by:** #earlier_issue_1, #earlier_issue_2
BODY
)" \
  --label "phase-N" \
  --milestone "Phase N: Name"
```

**Dependency rules:**
- Only include `**Blocked by:** #N` if the issue genuinely cannot start until #N is done
- Don't over-constrain — if two issues are independent, don't add false dependencies
- The `**Blocked by:**` line must be the exact format IssueBot parses: `**Blocked by:** #5, #6`

## Step 6: Label Leaf Issues as agent-ready

After ALL issues are created, add the `agent-ready` label ONLY to the leaf issues (those that have no unresolved blockers — the first issues in the chain):

```bash
gh issue edit NUMBER --repo $0 --add-label "agent-ready"
```

IssueBot will then auto-label upstream issues as it discovers dependency chains.

## Step 7: Summary

Print a summary table:

| # | Title | Phase | Blocked By | Labels |
|---|-------|-------|------------|--------|

And a dependency graph showing the processing order.

## Guidelines

- Keep issues focused — one logical unit of work per issue
- Write issue bodies as if an AI agent will read them — be explicit about file paths, function signatures, expected behavior
- Include code snippets or examples in issue bodies when helpful
- Reference existing code patterns the agent should follow
- Don't create issues for trivial tasks (updating a config value, adding an import)
- If the design doc is ambiguous, make reasonable choices and note assumptions in the issue body

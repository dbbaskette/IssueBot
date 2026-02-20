# IssueBot — Dependency-Aware Issue Processor

## Overview

A GitHub Action that triggers when an issue is labeled `agent-ready`. It resolves the full dependency chain by parsing `**Blocked by:**` lines in issue bodies, tags all prerequisite issues, and determines the correct processing order.

## Problem

When tagging an issue for work, its dependencies may not be complete yet. Manually tracing the `Blocked by:` chain across multiple issues is tedious and error-prone.

## Solution

Automate dependency resolution: tag one issue, and the bot walks the entire chain, labels everything that needs doing, and identifies where to start.

---

## Trigger

```yaml
on:
  issues:
    types: [labeled]
# Only runs when the label is "agent-ready"
```

## Convention

Dependencies can be declared two ways:

**1. GitHub native dependencies (recommended)** — Use GitHub's "Mark as blocked by" feature in the issue sidebar. IssueBot reads these via the GraphQL `blockedBy` field.

**2. Body text (legacy fallback)** — Each issue body may contain a dependency line:

```markdown
**Blocked by:** #14, #15
```

Completed dependencies use strikethrough:

```markdown
**Blocked by:** ~~#4~~ ✅, #14
```

The bot checks native GitHub dependencies first. If none are found, it falls back to parsing the body text. Strikethrough references are ignored, and actual issue state is always verified via the GitHub API.

---

## Algorithm

### 1. Parse `Blocked by:`

Extract issue numbers from the `**Blocked by:**` line in the issue body.

- Skip strikethrough references (`~~#N~~`) — these are already completed
- Extract plain `#N` references as open blockers
- Verify each blocker's actual state via GitHub API (closed = done)

### 2. Recursive Chain Resolution

```
function resolveChain(issueNumber, visited):
    if issueNumber in visited → return (cycle protection)
    add issueNumber to visited

    issue = fetch from GitHub API
    if issue is closed → return (already done)

    blockers = parseBlockedBy(issue.body)

    for each blocker:
        if blocker is open:
            resolveChain(blocker, visited)  // recurse

    add issue to chain
```

This produces the full dependency tree — all open issues that must be completed before the originally tagged issue can proceed.

### 3. Topological Sort (Processing Order)

```
function determineOrder(chain):
    order = []
    done = set()

    while chain has remaining issues:
        ready = issues whose blockers are ALL either:
            - closed on GitHub, OR
            - already in the "done" set

        sort ready by issue number ascending
        add ready to order
        mark ready as done

    return order
```

This produces a valid execution sequence respecting all dependencies.

### 4. Label and Report

- Add `agent-ready` label to all issues in the chain that don't already have it
- Post a comment on the originally tagged issue with the full processing order
- Skip re-processing if triggered by the bot's own labeling (prevent infinite loops)

---

## Example

User labels **#20** with `agent-ready`.

Issue bodies:
- **#20**: `**Blocked by:** #15`
- **#15**: `**Blocked by:** #10`
- **#10**: `**Blocked by:** #5`
- **#5**: no blockers

Bot resolves:
```
#20 → blocked by #15 (open)
  #15 → blocked by #10 (open)
    #10 → blocked by #5 (open)
      #5 → no open blockers ✅
```

**Result:**
- Labels `#5`, `#10`, `#15` with `agent-ready` (if not already labeled)
- Posts comment on #20:

```markdown
## Dependency Chain Resolved

Processing order:
1. #5 — Set up multi-module Maven project ← **start here**
2. #10 — React frontend — API Explorer
3. #15 — Implement tool generation REST APIs
4. #20 — Implement ScaffoldInjectionService

4 issues tagged. Starting with #5 (lowest unblocked).
```

## Branching Dependencies (DAG)

The chain can branch. If **#15** is blocked by both **#10** and **#12**:

```
#20 → #15 → #10 → #5
              ↘ #12 → #7
```

The bot collects the full DAG: `#5, #7, #10, #12, #15, #20`

Processing order respects all edges:
1. #5 (no blockers)
2. #7 (no blockers)
3. #10 (blocked by #5 — done)
4. #12 (blocked by #7 — done)
5. #15 (blocked by #10, #12 — both done)
6. #20 (blocked by #15 — done)

---

## Edge Cases

| Case | Behavior |
|------|----------|
| Blocker is already closed | Skip — not added to chain |
| Circular dependency | `visited` set prevents infinite recursion; log warning |
| Issue has no `Blocked by:` line | Treat as unblocked — ready to start |
| Bot's own labeling triggers workflow | Check `github.actor === 'github-actions[bot]'` — skip |
| Issue already has `agent-ready` label | Don't re-label, but still include in chain for ordering |
| Strikethrough refs with stale state | Always verify via API — don't trust markdown formatting alone |

---

## File Structure

```
.github/
  workflows/
    issue-bot.yml           # Workflow definition
  scripts/
    resolve-dependencies.js # Dependency resolution logic
```

## Workflow Definition

```yaml
name: Issue Bot — Dependency Resolution

on:
  issues:
    types: [labeled]

jobs:
  resolve-dependencies:
    if: >
      github.event.label.name == 'agent-ready' &&
      github.actor != 'github-actions[bot]'
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/github-script@v7
        with:
          script: |
            const script = require('./.github/scripts/resolve-dependencies.js')
            await script({ github, context, core })
```

## Script: `resolve-dependencies.js`

```javascript
module.exports = async ({ github, context, core }) => {
  const owner = context.repo.owner;
  const repo = context.repo.repo;
  const issueNumber = context.payload.issue.number;

  core.info(`Resolving dependency chain for #${issueNumber}`);

  // --- Parse Blocked By ---
  function parseBlockedBy(body) {
    if (!body) return [];
    const match = body.match(/\*\*Blocked by:\*\*\s*(.+)/);
    if (!match) return [];
    const line = match[1];

    // Remove strikethrough sections (completed deps)
    const withoutStrikethrough = line.replace(/~~[^~]+~~/g, "");

    // Extract remaining #N references
    const refs = [...withoutStrikethrough.matchAll(/#(\d+)/g)];
    return refs.map((r) => parseInt(r[1]));
  }

  // --- Recursive Resolution ---
  const visited = new Set();
  const chain = [];

  async function resolve(num) {
    if (visited.has(num)) return;
    visited.add(num);

    const { data: issue } = await github.rest.issues.get({
      owner,
      repo,
      issue_number: num,
    });

    if (issue.state === "closed") return;

    const blockers = parseBlockedBy(issue.body);

    for (const blocker of blockers) {
      await resolve(blocker);
    }

    chain.push({
      number: issue.number,
      title: issue.title,
      labels: issue.labels.map((l) => l.name),
      blockers: blockers,
    });
  }

  await resolve(issueNumber);

  if (chain.length === 0) {
    core.info("No open issues in chain — nothing to do");
    return;
  }

  // --- Topological Sort ---
  const order = [];
  const done = new Set();
  const remaining = [...chain];

  while (remaining.length > 0) {
    const ready = remaining.filter((issue) =>
      issue.blockers.every((b) => {
        const inChain = chain.find((c) => c.number === b);
        return !inChain || done.has(b);
      })
    );

    if (ready.length === 0) {
      // Circular dependency — take lowest number to break cycle
      core.warning(
        `Circular dependency detected among: ${remaining.map((i) => "#" + i.number).join(", ")}`
      );
      ready.push(remaining.sort((a, b) => a.number - b.number)[0]);
    }

    ready.sort((a, b) => a.number - b.number);

    for (const issue of ready) {
      order.push(issue);
      done.add(issue.number);
      remaining.splice(remaining.indexOf(issue), 1);
    }
  }

  // --- Label unlabeled issues ---
  let labeled = 0;
  for (const issue of order) {
    if (!issue.labels.includes("agent-ready")) {
      await github.rest.issues.addLabels({
        owner,
        repo,
        issue_number: issue.number,
        labels: ["agent-ready"],
      });
      labeled++;
      core.info(`Labeled #${issue.number} with agent-ready`);
    }
  }

  // --- Post comment on original issue ---
  const lines = order.map((issue, i) => {
    const marker = i === 0 ? " **start here**" : "";
    return `${i + 1}. #${issue.number} — ${issue.title}${marker}`;
  });

  const body = [
    "## Dependency Chain Resolved",
    "",
    "Processing order:",
    ...lines,
    "",
    `${order.length} issue(s) in chain. ${labeled} newly labeled.`,
  ].join("\n");

  await github.rest.issues.createComment({
    owner,
    repo,
    issue_number: issueNumber,
    body: body,
  });

  core.info(`Done. Processing order: ${order.map((i) => "#" + i.number).join(" → ")}`);
};
```

---

## Future Enhancements

- **Auto-close chain:** When an issue is closed, check if downstream issues are now unblocked and post a comment notifying
- **Progress tracking:** Update the original issue's comment as each dependency is completed
- **Mermaid graph:** Generate a visual dependency graph in the comment
- **Slack/webhook notification:** Alert when an issue becomes unblocked

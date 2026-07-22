# Next Actions and Operator Control Room Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give every tracked issue one consistent next-action summary and turn the dashboard into a three-lane operator control room for decisions, active processing, and upcoming work.

**Architecture:** A stateless `IssueNextActionResolver` owns all workflow-state wording and deep links. `IssueController` supplies its output to issue detail and queue templates, while a focused `DashboardControlRoomAssembler` owns dashboard queries, ordering, five-card limits, and running details; `DashboardController` only publishes the assembled model.

**Tech Stack:** Java 21, Spring Boot MVC, Spring Data JPA, Thymeleaf, HTMX morph swaps, CSS custom properties, JUnit 5, Mockito, AssertJ, Maven Wrapper.

## Global Constraints

- A visible next-action summary must exist for every issue state, including terminal states.
- Dashboard cards, issue detail, and issue queue must render output from the same `IssueNextActionResolver`; templates must not duplicate status-to-copy branching.
- Dashboard lanes are exactly **Needs your decision**, **Currently processing**, and **Up next**, in that order.
- Each lane renders zero to five cards, reports the full matching count, and shows `View all` only when more than five issues match.
- Needs-decision membership is `AWAITING_APPROVAL`, `AWAITING_PLAN_APPROVAL`, `AWAITING_DECOMPOSITION`, `FAILED`, and `COOLDOWN` in that priority order, then ascending database ID.
- Currently-processing membership is `IN_PROGRESS`, ordered by ascending non-null start time, null start times last, then ascending database ID.
- Up-next membership is `QUEUED`, `PENDING`, and `BLOCKED` in that priority order, then ascending database ID.
- Dashboard and queue links are GET deep links only; no dashboard card may execute approve, reject, retry, cancel, or start mutations.
- The existing `/dashboard/live` ten-second refresh must update all three lanes.
- The legacy Now Running strip must not remain alongside the Currently processing lane.
- Existing metrics and recent events remain beneath the control room.
- No database migration, new workflow status, or workflow transition change is permitted.
- State must be communicated by text and structure, never by color alone.
- Control-room cards must not overflow horizontally at a 320-pixel viewport.
- All implementation work follows red-green-refactor TDD and is reviewed before the next task.

---

### Task 1: Shared next-action resolver

**Files:**
- Create: `src/main/java/com/dbbaskette/issuebot/service/ui/IssueNextAction.java`
- Create: `src/main/java/com/dbbaskette/issuebot/service/ui/IssueNextActionResolver.java`
- Create: `src/test/java/com/dbbaskette/issuebot/service/ui/IssueNextActionResolverTest.java`

**Interfaces:**
- Consumes: `TrackedIssue`, `IssueStatus`, `TrackedIssue#getBlockerNumbers()`, and `Humanize.phase(String)`.
- Produces: `IssueNextActionResolver#resolve(TrackedIssue)` returning `IssueNextAction(String summary, String ctaLabel, String href, Tone tone, boolean actionRequired)` and `IssueNextAction#hasAction()`.

- [ ] **Step 1: Write the failing resolver tests**

Create parameterized coverage for all statuses plus optional-data and null fallbacks. Use exact assertions so copy and links cannot drift:

```java
package com.dbbaskette.issuebot.service.ui;

import com.dbbaskette.issuebot.model.IssueStatus;
import com.dbbaskette.issuebot.model.TrackedIssue;
import com.dbbaskette.issuebot.model.WatchedRepo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class IssueNextActionResolverTest {
    private final IssueNextActionResolver resolver = new IssueNextActionResolver();

    static Stream<Arguments> statuses() {
        return Stream.of(
                Arguments.of(IssueStatus.AWAITING_APPROVAL, "Review and decide the pull request.", "Review approval", "/issues/7#approval-decision", IssueNextAction.Tone.ACTION, true),
                Arguments.of(IssueStatus.AWAITING_PLAN_APPROVAL, "Review and approve the current plan.", "Review plan", "/issues/7#plan-review", IssueNextAction.Tone.ACTION, true),
                Arguments.of(IssueStatus.AWAITING_DECOMPOSITION, "Review the proposed issue split.", "Review split", "/issues/7#status-actions", IssueNextAction.Tone.ACTION, true),
                Arguments.of(IssueStatus.FAILED, "Review the failure, add guidance, or retry.", "Resolve failure", "/issues/7#recovery", IssueNextAction.Tone.ACTION, true),
                Arguments.of(IssueStatus.COOLDOWN, "Review the failed attempt before retrying.", "Review recovery", "/issues/7#recovery", IssueNextAction.Tone.ACTION, true),
                Arguments.of(IssueStatus.IN_PROGRESS, "IssueBot is processing this issue.", "View progress", "/issues/7#live-status", IssueNextAction.Tone.ACTIVE, false),
                Arguments.of(IssueStatus.QUEUED, "Queued and ready when processing capacity is available.", "View issue", "/issues/7", IssueNextAction.Tone.WAITING, false),
                Arguments.of(IssueStatus.PENDING, "Ready to start manually or enter the processing queue.", "Review and start", "/issues/7#status-actions", IssueNextAction.Tone.WAITING, false),
                Arguments.of(IssueStatus.BLOCKED, "Waiting for blocking issues to complete.", "View blockers", "/issues/7#status-actions", IssueNextAction.Tone.WAITING, false),
                Arguments.of(IssueStatus.COMPLETED, "No action needed — completed.", null, null, IssueNextAction.Tone.SUCCESS, false),
                Arguments.of(IssueStatus.DECOMPOSED, "No action needed — work continues in the split issues.", null, null, IssueNextAction.Tone.NEUTRAL, false));
    }

    @ParameterizedTest
    @MethodSource("statuses")
    void resolvesEveryStatus(IssueStatus status, String summary, String label, String href,
                             IssueNextAction.Tone tone, boolean required) {
        TrackedIssue issue = issue(status);
        IssueNextAction action = resolver.resolve(issue);
        assertThat(action).extracting(IssueNextAction::summary, IssueNextAction::ctaLabel,
                        IssueNextAction::href, IssueNextAction::tone, IssueNextAction::actionRequired)
                .containsExactly(summary, label, href, tone, required);
        assertThat(action.hasAction()).isEqualTo(label != null && href != null);
    }

    @Test
    void usesPrPhaseAndBlockerDetailsWhenPresent() {
        TrackedIssue approval = issue(IssueStatus.AWAITING_APPROVAL);
        approval.setPrNumber(158);
        assertThat(resolver.resolve(approval).summary()).isEqualTo("Review and decide PR #158.");

        TrackedIssue running = issue(IssueStatus.IN_PROGRESS);
        running.setCurrentPhase("CI_VERIFICATION");
        assertThat(resolver.resolve(running).summary()).isEqualTo("IssueBot is CI Verification.");

        TrackedIssue oneBlocker = issue(IssueStatus.BLOCKED);
        oneBlocker.setBlockedByIssues("12");
        assertThat(resolver.resolve(oneBlocker).summary()).isEqualTo("Waiting for issue #12.");

        TrackedIssue manyBlockers = issue(IssueStatus.BLOCKED);
        manyBlockers.setBlockedByIssues("12, 19");
        assertThat(resolver.resolve(manyBlockers).summary()).isEqualTo("Waiting for issues #12, #19.");
    }

    @Test
    void nullStatusAndMissingIdUseSafeFallbacks() {
        TrackedIssue unknown = issue(IssueStatus.PENDING);
        unknown.setStatus(null);
        assertThat(resolver.resolve(unknown)).isEqualTo(new IssueNextAction(
                "Review the current issue state.", "View issue", "/issues/7",
                IssueNextAction.Tone.NEUTRAL, false));

        TrackedIssue unsaved = issue(IssueStatus.FAILED);
        unsaved.setId(null);
        IssueNextAction action = resolver.resolve(unsaved);
        assertThat(action.summary()).isEqualTo("Review the failure, add guidance, or retry.");
        assertThat(action.ctaLabel()).isNull();
        assertThat(action.href()).isNull();
        assertThat(action.hasAction()).isFalse();
    }

    private static TrackedIssue issue(IssueStatus status) {
        TrackedIssue issue = new TrackedIssue(new WatchedRepo("acme", "widgets"), 42, "Title");
        issue.setId(7L);
        issue.setStatus(status);
        return issue;
    }
}
```

- [ ] **Step 2: Run the resolver test and confirm red**

Run: `./mvnw -q -Dtest=IssueNextActionResolverTest test`

Expected: compilation failure because `IssueNextAction` and `IssueNextActionResolver` do not exist.

- [ ] **Step 3: Implement the immutable model and resolver**

```java
package com.dbbaskette.issuebot.service.ui;

public record IssueNextAction(String summary, String ctaLabel, String href,
                              Tone tone, boolean actionRequired) {
    public enum Tone { ACTION, ACTIVE, WAITING, SUCCESS, NEUTRAL }

    public boolean hasAction() {
        return ctaLabel != null && href != null;
    }
}
```

```java
package com.dbbaskette.issuebot.service.ui;

import com.dbbaskette.issuebot.model.TrackedIssue;
import com.dbbaskette.issuebot.util.Humanize;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Component
public class IssueNextActionResolver {
    public IssueNextAction resolve(TrackedIssue issue) {
        if (issue == null || issue.getStatus() == null) {
            return action("Review the current issue state.", "View issue", baseHref(issue),
                    IssueNextAction.Tone.NEUTRAL, false);
        }
        return switch (issue.getStatus()) {
            case AWAITING_APPROVAL -> action(
                    issue.getPrNumber() == null ? "Review and decide the pull request."
                            : "Review and decide PR #" + issue.getPrNumber() + ".",
                    "Review approval", anchored(issue, "approval-decision"), IssueNextAction.Tone.ACTION, true);
            case AWAITING_PLAN_APPROVAL -> action("Review and approve the current plan.",
                    "Review plan", anchored(issue, "plan-review"), IssueNextAction.Tone.ACTION, true);
            case AWAITING_DECOMPOSITION -> action("Review the proposed issue split.",
                    "Review split", anchored(issue, "status-actions"), IssueNextAction.Tone.ACTION, true);
            case FAILED -> action("Review the failure, add guidance, or retry.",
                    "Resolve failure", anchored(issue, "recovery"), IssueNextAction.Tone.ACTION, true);
            case COOLDOWN -> action("Review the failed attempt before retrying.",
                    "Review recovery", anchored(issue, "recovery"), IssueNextAction.Tone.ACTION, true);
            case IN_PROGRESS -> action(inProgressSummary(issue), "View progress",
                    anchored(issue, "live-status"), IssueNextAction.Tone.ACTIVE, false);
            case QUEUED -> action("Queued and ready when processing capacity is available.",
                    "View issue", baseHref(issue), IssueNextAction.Tone.WAITING, false);
            case PENDING -> action("Ready to start manually or enter the processing queue.",
                    "Review and start", anchored(issue, "status-actions"), IssueNextAction.Tone.WAITING, false);
            case BLOCKED -> action(blockedSummary(issue), "View blockers",
                    anchored(issue, "status-actions"), IssueNextAction.Tone.WAITING, false);
            case COMPLETED -> action("No action needed — completed.", null, null,
                    IssueNextAction.Tone.SUCCESS, false);
            case DECOMPOSED -> action("No action needed — work continues in the split issues.", null, null,
                    IssueNextAction.Tone.NEUTRAL, false);
        };
    }

    private static String inProgressSummary(TrackedIssue issue) {
        String phase = Humanize.phase(issue.getCurrentPhase());
        return phase == null || phase.isBlank()
                ? "IssueBot is processing this issue." : "IssueBot is " + phase + ".";
    }

    private static String blockedSummary(TrackedIssue issue) {
        List<Integer> blockers = issue.getBlockerNumbers();
        if (blockers.isEmpty()) return "Waiting for blocking issues to complete.";
        if (blockers.size() == 1) return "Waiting for issue #" + blockers.getFirst() + ".";
        return "Waiting for issues " + blockers.stream().map(n -> "#" + n)
                .collect(Collectors.joining(", ")) + ".";
    }

    private static IssueNextAction action(String summary, String label, String href,
                                          IssueNextAction.Tone tone, boolean required) {
        if (href == null) label = null;
        return new IssueNextAction(summary, label, href, tone, required);
    }

    private static String anchored(TrackedIssue issue, String anchor) {
        String base = baseHref(issue);
        return base == null ? null : base + "#" + anchor;
    }

    private static String baseHref(TrackedIssue issue) {
        return issue == null || issue.getId() == null ? null : "/issues/" + issue.getId();
    }
}
```

- [ ] **Step 4: Run the resolver test and confirm green**

Run: `./mvnw -q -Dtest=IssueNextActionResolverTest test`

Expected: exit code `0`, with all resolver cases passing.

- [ ] **Step 5: Commit Task 1**

```bash
git add src/main/java/com/dbbaskette/issuebot/service/ui/IssueNextAction.java src/main/java/com/dbbaskette/issuebot/service/ui/IssueNextActionResolver.java src/test/java/com/dbbaskette/issuebot/service/ui/IssueNextActionResolverTest.java
git commit -m "feat: resolve issue next actions"
```

---

### Task 2: Issue detail and queue next-action surfaces

**Files:**
- Modify: `src/main/java/com/dbbaskette/issuebot/controller/IssueController.java`
- Modify: `src/main/resources/templates/issue-detail.html`
- Modify: `src/main/resources/templates/issues.html`
- Modify: `src/main/resources/static/css/style.css`
- Modify: `src/test/java/com/dbbaskette/issuebot/controller/IssueControllerTest.java`
- Modify: `src/test/java/com/dbbaskette/issuebot/service/workflow/IntegrationWorkflowTest.java`
- Modify: `src/test/java/com/dbbaskette/issuebot/controller/IssuesQueueRenderTest.java`
- Modify: `src/test/java/com/dbbaskette/issuebot/controller/IssuesQueueUpgradeRenderTest.java`
- Modify: `src/test/java/com/dbbaskette/issuebot/controller/IssueDetailLivePollRenderTest.java`
- Create: `src/test/java/com/dbbaskette/issuebot/controller/IssueNextActionRenderTest.java`

**Interfaces:**
- Consumes: `IssueNextActionResolver#resolve(TrackedIssue)` from Task 1.
- Produces: model attribute `nextAction` on detail and live-status requests; model attribute `nextActions` as `Map<Long, IssueNextAction>` on queue and table requests; Thymeleaf fragment `issue-detail :: next-action-callout(oob)`.

- [ ] **Step 1: Write failing controller and rendering assertions**

Add controller assertions that both `list` and `table` publish a next-action map for the page contents and that `detail` and `liveStatus` publish `nextAction`. Construct `IssueController` with a real `IssueNextActionResolver` in shared test helpers.

Create `IssueNextActionRenderTest` using the repository's existing Thymeleaf `WebContext` pattern. Render `issue-detail :: next-action-callout` for `AWAITING_PLAN_APPROVAL`, `IN_PROGRESS`, and `COMPLETED`, asserting:

```java
assertThat(planHtml).contains("Next action", "Review and approve the current plan.",
        "href=\"/issues/7#plan-review\"")
        .contains("next-action--action");
assertThat(activeHtml).contains("IssueBot is Implementation.", "View progress")
        .contains("next-action--active");
assertThat(completedHtml).contains("No action needed — completed.")
        .doesNotContain("next-action-cta");
```

Extend `IssuesQueueRenderTest` so its render helper supplies `nextActions` and asserts that the title cell contains `Next:` plus resolver output for pending, failed, and completed issues. Extend `IssueDetailLivePollRenderTest` to assert the `live-status-poll` fragment contains an OOB replacement for `next-action-callout`.

- [ ] **Step 2: Run focused tests and confirm red**

Run: `./mvnw -q -Dtest=IssueControllerTest,IssuesQueueRenderTest,IssueDetailLivePollRenderTest,IssueNextActionRenderTest test`

Expected: failures for missing `nextAction`/`nextActions` model attributes and absent callout/queue markup.

- [ ] **Step 3: Wire resolver output into `IssueController`**

Add `IssueNextActionResolver` as the final required constructor dependency and field. Update all eight direct constructor call sites in tests. Add this helper:

```java
private Map<Long, IssueNextAction> resolveNextActions(List<TrackedIssue> issues) {
    return issues.stream()
            .filter(issue -> issue.getId() != null)
            .collect(java.util.stream.Collectors.toUnmodifiableMap(
                    TrackedIssue::getId, nextActionResolver::resolve));
}
```

In `list`, keep `List<TrackedIssue> pageIssues = issuePage.getContent()`, set `issues` to that list, and add `nextActions`. In `table`, perform the same two additions from the fragment page contents. In `populateDetailModel`, add:

```java
model.addAttribute("nextAction", nextActionResolver.resolve(issue));
```

- [ ] **Step 4: Add the issue-detail callout and live OOB update**

Render this fragment immediately after `.page-header` in the left detail column:

```html
<section th:fragment="next-action-callout(oob)" id="next-action-callout"
         th:if="${nextAction != null}"
         class="next-action-callout mb-3"
         th:classappend="${' next-action--' + nextAction.tone().name().toLowerCase()}"
         th:attr="hx-swap-oob=${oob != null and oob ? 'true' : null}"
         aria-labelledby="next-action-heading">
    <div>
        <span class="eyebrow">Next action</span>
        <p id="next-action-heading" th:text="${nextAction.summary()}">Review the current issue state.</p>
    </div>
    <a th:if="${nextAction.hasAction()}" class="btn btn-outline btn-sm next-action-cta"
       th:href="${nextAction.href()}" th:text="${nextAction.ctaLabel()}">View issue</a>
</section>
```

Add this sibling to `live-status-poll`:

```html
<div th:replace="~{issue-detail :: next-action-callout(true)}"></div>
```

- [ ] **Step 5: Add queue summary markup and mobile card behavior**

Give queue cells stable classes. Replace the title cell with:

```html
<td class="queue-title-cell">
    <span class="queue-title" th:text="${issue.issueTitle}">Issue title</span>
    <span class="queue-next-action" th:if="${nextActions != null and nextActions[issue.id] != null}">
        <strong>Next:</strong>
        <span th:text="${nextActions[issue.id].summary()}">Review the issue.</span>
    </span>
</td>
```

Add `queue-repo-cell`, `queue-number-cell`, `queue-phase-cell`, `queue-iteration-cell`, `queue-updated-cell`, and `queue-actions-cell` to the remaining cells. Add CSS for the callout tones and queue summary, then convert issue rows to stacked cards below 768 pixels:

```css
.next-action-callout {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 1rem;
    padding: .9rem 1rem;
    border: 1px solid var(--hairline);
    border-left: 4px solid var(--text-tertiary);
    border-radius: var(--radius-lg);
    background: var(--surface-raised);
}
.next-action-callout p { margin: .15rem 0 0; color: var(--text-primary); font-weight: 600; }
.next-action--action { border-left-color: var(--danger); background: color-mix(in srgb, var(--danger-soft) 55%, var(--surface)); }
.next-action--active { border-left-color: var(--ok); background: color-mix(in srgb, var(--ok-soft) 55%, var(--surface)); }
.next-action--waiting { border-left-color: var(--accent); }
.next-action--success { border-left-color: var(--ok); }
.queue-title-cell { min-width: 15rem; }
.queue-title { display: block; color: var(--text-primary); font-weight: 600; }
.queue-next-action { display: block; margin-top: .3rem; color: var(--text-tertiary); font-size: .76rem; line-height: 1.4; white-space: normal; }

@media (max-width: 768px) {
    .next-action-callout { align-items: stretch; flex-direction: column; }
    .next-action-cta { width: 100%; justify-content: center; }
    .queue-table-panel { overflow: visible; background: transparent; border: 0; box-shadow: none; }
    .queue-table-panel .data-table { min-width: 0; }
    .queue-table-panel thead { position: absolute; width: 1px; height: 1px; overflow: hidden; clip: rect(0 0 0 0); }
    .queue-table-panel tbody { display: grid; gap: .75rem; }
    .queue-table-panel tbody tr { position: relative; display: grid; grid-template-columns: minmax(0, 1fr) auto; gap: .4rem .75rem; padding: 1rem; border: 1px solid var(--hairline); border-radius: var(--radius-lg); background: var(--surface); }
    .queue-table-panel tbody td { display: block; padding: 0; border: 0; min-width: 0; }
    .queue-table-panel .checkbox-col { position: absolute; top: 1rem; right: 1rem; }
    .queue-repo-cell { grid-column: 1; padding-right: 2.5rem !important; color: var(--text-tertiary); font-size: .76rem; overflow-wrap: anywhere; }
    .queue-number-cell { grid-column: 2; padding-right: 2.5rem !important; font-weight: 700; }
    .queue-title-cell, .issue-status-cell, .queue-actions-cell { grid-column: 1 / -1; }
    .queue-phase-cell, .queue-iteration-cell, .queue-updated-cell { display: none !important; }
    .queue-actions-cell .btn { width: 100%; justify-content: center; }
}
```

- [ ] **Step 6: Run focused tests and confirm green**

Run: `./mvnw -q -Dtest=IssueControllerTest,IssuesQueueRenderTest,IssuesQueueUpgradeRenderTest,IssueDetailLivePollRenderTest,IssueNextActionRenderTest,IntegrationWorkflowTest test`

Expected: exit code `0`; controller construction, queue rendering, detail rendering, and live OOB assertions all pass.

- [ ] **Step 7: Commit Task 2**

```bash
git add src/main/java/com/dbbaskette/issuebot/controller/IssueController.java src/main/resources/templates/issue-detail.html src/main/resources/templates/issues.html src/main/resources/static/css/style.css src/test/java/com/dbbaskette/issuebot/controller/IssueControllerTest.java src/test/java/com/dbbaskette/issuebot/service/workflow/IntegrationWorkflowTest.java src/test/java/com/dbbaskette/issuebot/controller/IssuesQueueRenderTest.java src/test/java/com/dbbaskette/issuebot/controller/IssuesQueueUpgradeRenderTest.java src/test/java/com/dbbaskette/issuebot/controller/IssueDetailLivePollRenderTest.java src/test/java/com/dbbaskette/issuebot/controller/IssueNextActionRenderTest.java
git commit -m "feat: show issue next actions"
```

---

### Task 3: Dashboard control-room assembler

**Files:**
- Create: `src/main/java/com/dbbaskette/issuebot/service/ui/DashboardControlRoomAssembler.java`
- Create: `src/main/java/com/dbbaskette/issuebot/util/BudgetProgress.java`
- Modify: `src/main/java/com/dbbaskette/issuebot/controller/IssueController.java`
- Create: `src/test/java/com/dbbaskette/issuebot/service/ui/DashboardControlRoomAssemblerTest.java`
- Create: `src/test/java/com/dbbaskette/issuebot/util/BudgetProgressTest.java`

**Interfaces:**
- Consumes: `TrackedIssueRepository#findByStatusIn`, `TrackedIssueRepository#findByStatus`, `CostTrackingRepository#totalCostForIssue`, `IssueNextActionResolver#resolve`, `BudgetProgress.percent`, `ElapsedFormatter.format`, and `Humanize`.
- Produces: `DashboardControlRoomAssembler#assemble()` returning `ControlRoom`; public nested records `ControlRoom`, `Lane`, `Card`, and `RunDetails`; package-visible `assemble(LocalDateTime now)` for deterministic tests.

- [ ] **Step 1: Write failing assembler tests**

Cover exact membership, cross-status priority, ascending IDs, start-time ordering with nulls last, five-card truncation, total counts, `hasMore`, empty lanes, next-action reuse, and running detail enrichment. The core assertions are:

```java
ControlRoom room = assembler.assemble(LocalDateTime.of(2026, 7, 22, 12, 0));
assertThat(room.needsDecision().cards()).extracting(card -> card.issue().getStatus())
        .containsExactly(AWAITING_APPROVAL, AWAITING_PLAN_APPROVAL,
                AWAITING_DECOMPOSITION, FAILED, COOLDOWN);
assertThat(room.processing().cards()).extracting(card -> card.issue().getStartedAt())
        .containsExactly(oldestStart, newestStart, null);
assertThat(room.upNext().cards()).extracting(card -> card.issue().getStatus())
        .containsExactly(QUEUED, PENDING, BLOCKED);
assertThat(room.upNext().cards()).hasSize(5);
assertThat(room.upNext().total()).isEqualTo(7);
assertThat(room.upNext().hasMore()).isTrue();
```

For a running issue with `$2.50` spend and `$10.00` budget started five minutes before `now`, assert `RunDetails(2.50, 10.00, 25, "5m")`. Verify `nextActionResolver.resolve(issue)` is called once for every visible card and that no cost lookup occurs for non-running cards. Add issues with a blank title and absent repository object; assert cards use `Untitled issue` and `Unknown repository` rather than rendering `null` or throwing.

Move the existing budget-percentage cases into `BudgetProgressTest`: null budget, null/zero spend, zero budget with positive spend, round-down below 80%, and clamp above 100%.

- [ ] **Step 2: Run the assembler test and confirm red**

Run: `./mvnw -q -Dtest=DashboardControlRoomAssemblerTest test`

Expected: compilation failure because `DashboardControlRoomAssembler` and `BudgetProgress` do not exist.

- [ ] **Step 3: Implement assembler records, queries, order, and limit**

Create a Spring `@Component` with constructor dependencies for issue repository, cost repository, and resolver. Use these record contracts:

```java
public record ControlRoom(Lane needsDecision, Lane processing, Lane upNext) {}

public record Lane(String key, String eyebrow, String title, String emptyMessage,
                   String viewAllHref, int total, List<Card> cards) {
    public Lane {
        cards = List.copyOf(cards);
    }
    public boolean hasMore() { return total > cards.size(); }
}

public record Card(TrackedIssue issue, IssueNextAction nextAction,
                   String repositoryLabel, String issueLabel,
                   String stateLabel, RunDetails runDetails) {}

public record RunDetails(BigDecimal spend, BigDecimal effectiveBudget,
                         int budgetPct, String elapsed) {}
```

Use immutable status lists in the specified priority order, an integer rank map for cross-status sorting, `Comparator.nullsLast` for IDs and start times, and `stream().limit(5)`. Build lanes with these exact descriptors:

```java
new Lane("needs-decision", "Intervention", "Needs your decision",
        "No decisions need you right now.", "/inbox", total, cards);
new Lane("processing", "Execution", "Currently processing",
        "IssueBot is not processing an issue.", "/issues?status=IN_PROGRESS", total, cards);
new Lane("up-next", "Queue", "Up next",
        "No issues are waiting to run.", "/issues", total, cards);
```

For state labels, use the humanized current phase or `Starting` for processing cards and `Humanize.status(issue.getStatus().name())` for all other cards. Only processing cards receive `RunDetails`.

Build `repositoryLabel` as `Unknown repository` when `issue.getRepo()` is null or its owner or name is null/blank; otherwise use `fullName()`. Build `issueLabel` as `Untitled issue` when `issue.getIssueTitle()` is null or blank. Templates must render these fields instead of dereferencing optional display data.

Move the existing `IssueController.budgetPct` implementation unchanged into this focused utility and call it from both `IssueController` and the assembler:

```java
package com.dbbaskette.issuebot.util;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class BudgetProgress {
    private BudgetProgress() {}

    public static int percent(BigDecimal spent, BigDecimal budget) {
        if (budget == null) return 0;
        if (spent == null || spent.signum() <= 0) return 0;
        if (budget.signum() <= 0) return 100;
        BigDecimal pct = spent.multiply(BigDecimal.valueOf(100))
                .divide(budget, 0, RoundingMode.DOWN);
        return pct.compareTo(BigDecimal.valueOf(100)) >= 0 ? 100 : pct.intValue();
    }
}
```

- [ ] **Step 4: Run the assembler test and confirm green**

Run: `./mvnw -q -Dtest=DashboardControlRoomAssemblerTest,BudgetProgressTest,IssueControllerTest test`

Expected: exit code `0`, with exact membership, order, limit, totals, and running details passing.

- [ ] **Step 5: Commit Task 3**

```bash
git add src/main/java/com/dbbaskette/issuebot/service/ui/DashboardControlRoomAssembler.java src/main/java/com/dbbaskette/issuebot/util/BudgetProgress.java src/main/java/com/dbbaskette/issuebot/controller/IssueController.java src/test/java/com/dbbaskette/issuebot/service/ui/DashboardControlRoomAssemblerTest.java src/test/java/com/dbbaskette/issuebot/util/BudgetProgressTest.java src/test/java/com/dbbaskette/issuebot/controller/IssueControllerTest.java
git commit -m "feat: assemble dashboard control room"
```

---

### Task 4: Dashboard controller and responsive control-room UI

**Files:**
- Modify: `src/main/java/com/dbbaskette/issuebot/controller/DashboardController.java`
- Modify: `src/main/resources/templates/dashboard.html`
- Modify: `src/main/resources/static/css/style.css`
- Modify: `src/test/java/com/dbbaskette/issuebot/controller/DashboardControllerTest.java`
- Delete: `src/test/java/com/dbbaskette/issuebot/controller/DashboardRunningStripRenderTest.java`
- Create: `src/test/java/com/dbbaskette/issuebot/controller/DashboardControlRoomRenderTest.java`
- Modify: `src/test/java/com/dbbaskette/issuebot/controller/DashboardTileRenderTest.java`

**Interfaces:**
- Consumes: `DashboardControlRoomAssembler#assemble()` and its nested records from Task 3.
- Produces: dashboard model attribute `controlRoom`; `dashboard :: control-room-lane` Thymeleaf fragment; three live-refreshing dashboard lanes.

- [ ] **Step 1: Rewrite controller and template tests to describe the control room**

Update `DashboardControllerTest` so mocked `DashboardControlRoomAssembler#assemble()` returns a known `ControlRoom`, and assert both `dashboard` and `live` publish that exact instance as `controlRoom`. Preserve the existing assertion that `/dashboard/live` does not query unread notification count.

Replace the legacy strip render test with `DashboardControlRoomRenderTest`. Its context supplies a `ControlRoom` containing one action-required card, one processing card with `RunDetails`, and one queued card. Assert:

```java
assertThat(html).contains("Needs your decision", "Currently processing", "Up next");
assertThat(html).contains("Review and approve the current plan.", "Review plan");
assertThat(html).contains("IssueBot is Implementation.", "View progress", "Run details");
assertThat(html).contains("Queued and ready when processing capacity is available.");
assertThat(html).contains("$2.50 of $10.00", "width:25%", "12m", "2/5");
assertThat(html).doesNotContain("Now Running", "data-modal-open=\"stop-modal-");
```

Add separate cases for all three empty messages, total count badges, `View all` hidden at five-or-fewer and shown at six-or-more, valid non-nested links, and the ten-second `hx-get="/dashboard/live"` live-region attributes.

- [ ] **Step 2: Run focused dashboard tests and confirm red**

Run: `./mvnw -q -Dtest=DashboardControllerTest,DashboardControlRoomRenderTest,DashboardTileRenderTest test`

Expected: failures for missing `controlRoom`, missing lane markup, and legacy Now Running output.

- [ ] **Step 3: Simplify `DashboardController` to publish the assembler result**

Inject `DashboardControlRoomAssembler`, remove `buildRunningIssues()` and `RunningIssueView`, and replace the final metrics-model addition with:

```java
model.addAttribute("controlRoom", controlRoomAssembler.assemble());
```

Keep every existing count, total cost, event, processing-state, notification, and route behavior unchanged.

- [ ] **Step 4: Replace the Now Running template with reusable lane/card markup**

At the top of `dashboard :: live`, render:

```html
<section class="control-room mb-3" aria-labelledby="control-room-title">
    <header class="control-room-heading">
        <div>
            <span class="eyebrow">Operator control room</span>
            <h3 id="control-room-title">What needs attention now</h3>
        </div>
        <span class="text-sm text-muted">Updates every 10 seconds</span>
    </header>
    <div class="control-room-grid">
        <section th:replace="~{dashboard :: control-room-lane(${controlRoom.needsDecision()})}"></section>
        <section th:replace="~{dashboard :: control-room-lane(${controlRoom.processing()})}"></section>
        <section th:replace="~{dashboard :: control-room-lane(${controlRoom.upNext()})}"></section>
    </div>
</section>
```

The lane fragment uses semantic section/list markup, renders total counts, and only shows `View all` when `lane.hasMore()`. Card title and CTA are separate links. Use a `<details class="control-room-run-details">` only when `card.runDetails() != null`; put elapsed time, iteration/model, spend, budget bar, and unlimited-budget copy inside it. Remove the outer stop-modal loop because dashboard cards no longer execute cancellation.

```html
<section th:fragment="control-room-lane(lane)" class="control-lane"
         th:classappend="${' control-lane--' + lane.key()}"
         th:attr="aria-labelledby=${'control-lane-' + lane.key()}">
    <header class="control-lane-heading">
        <div>
            <span class="eyebrow" th:text="${lane.eyebrow()}">Lane</span>
            <h4 th:id="${'control-lane-' + lane.key()}" th:text="${lane.title()}">Lane title</h4>
        </div>
        <span class="control-lane-count" th:text="${lane.total()}"
              th:attr="aria-label=${lane.total() + ' matching issues'}">0</span>
    </header>

    <ul class="control-card-list" th:if="${not #lists.isEmpty(lane.cards())}">
        <li class="control-card" th:each="card : ${lane.cards()}">
            <header class="control-card-header">
                <div class="control-card-identity">
                    <span class="control-card-repo" th:text="${card.repositoryLabel()}">owner/repo</span>
                    <a th:if="${card.issue().id != null}" class="control-card-title"
                       th:href="${'/issues/' + card.issue().id}"
                       th:text="${'#' + card.issue().issueNumber + ' — ' + card.issueLabel()}">#1 — Title</a>
                    <strong th:unless="${card.issue().id != null}" class="control-card-title"
                            th:text="${'#' + card.issue().issueNumber + ' — ' + card.issueLabel()}">#1 — Title</strong>
                </div>
                <span class="status"
                      th:classappend="${card.nextAction().tone() == T(com.dbbaskette.issuebot.service.ui.IssueNextAction.Tone).ACTION ? ' status-failed' : (card.nextAction().tone() == T(com.dbbaskette.issuebot.service.ui.IssueNextAction.Tone).ACTIVE ? ' status-in_progress' : ' status-pending')}"
                      th:text="${card.stateLabel()}">State</span>
            </header>

            <p class="control-card-summary" th:text="${card.nextAction().summary()}">Next action</p>

            <div class="control-card-actions">
                <a th:if="${card.nextAction().hasAction()}" class="btn btn-outline btn-sm"
                   th:href="${card.nextAction().href()}" th:text="${card.nextAction().ctaLabel()}">View issue</a>
            </div>

            <details class="control-room-run-details" th:if="${card.runDetails() != null}">
                <summary>Run details</summary>
                <div class="control-room-run-body">
                    <div class="running-meta text-sm text-muted">
                        <span><i class="ti ti-clock" aria-hidden="true"></i>
                            <span th:text="${card.runDetails().elapsed()}">0m</span></span>
                        <span th:if="${card.issue().repo != null}"><i class="ti ti-repeat" aria-hidden="true"></i>
                            <span th:text="${card.issue().currentIteration + '/' + card.issue().repo.maxIterations}">0/3</span></span>
                        <span th:if="${card.issue().resolvedImplModel != null}"><i class="ti ti-cpu" aria-hidden="true"></i>
                            <span th:text="${card.issue().resolvedImplModel}">model</span></span>
                    </div>
                    <div th:if="${card.runDetails().effectiveBudget() != null}">
                        <div class="flex-between mb-1">
                            <span class="text-sm">Spend</span>
                            <span class="text-sm" th:text="${'$' + #numbers.formatDecimal(card.runDetails().spend(), 1, 2) + ' of $' + #numbers.formatDecimal(card.runDetails().effectiveBudget(), 1, 2)}">$0.00 of $0.00</span>
                        </div>
                        <div class="budget-bar-track">
                            <div class="budget-bar-fill"
                                 th:classappend="${card.runDetails().budgetPct() >= 100 ? 'over' : (card.runDetails().budgetPct() >= 80 ? 'warning' : '')}"
                                 th:style="${'width:' + card.runDetails().budgetPct() + '%'}"></div>
                        </div>
                    </div>
                    <span th:unless="${card.runDetails().effectiveBudget() != null}" class="text-sm text-muted"
                          th:text="${'$' + #numbers.formatDecimal(card.runDetails().spend(), 1, 2) + ' spent · unlimited budget'}">$0.00 spent</span>
                </div>
            </details>
        </li>
    </ul>

    <p class="control-lane-empty" th:if="${#lists.isEmpty(lane.cards())}"
       th:text="${lane.emptyMessage()}">No matching issues.</p>
    <a class="control-lane-view-all text-sm" th:if="${lane.hasMore()}"
       th:href="${lane.viewAllHref()}">View all</a>
</section>
```

- [ ] **Step 5: Add responsive control-room styling and remove legacy strip rules**

Replace `.running-*` CSS with:

```css
.control-room { min-width: 0; }
.control-room-heading, .control-lane-heading, .control-card-header {
    display: flex;
    align-items: flex-start;
    justify-content: space-between;
    gap: .75rem;
}
.control-room-heading { align-items: end; margin-bottom: .9rem; }
.control-room-heading h3, .control-lane-heading h4 { margin: .1rem 0 0; }
.control-room-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 1rem; }
.control-lane { min-width: 0; padding: .9rem; border: 1px solid var(--hairline); border-radius: var(--radius-lg); background: var(--surface); }
.control-lane--needs-decision { border-top: 3px solid var(--danger); }
.control-lane--processing { border-top: 3px solid var(--ok); }
.control-lane--up-next { border-top: 3px solid var(--accent); }
.control-lane-count { min-width: 1.7rem; padding: .12rem .45rem; border-radius: var(--radius-pill); background: var(--surface-raised); text-align: center; font-size: .72rem; font-weight: 700; }
.control-card-list { display: grid; gap: .65rem; margin: .8rem 0 0; padding: 0; list-style: none; }
.control-card { min-width: 0; padding: .85rem; border: 1px solid var(--hairline); border-radius: var(--radius-md); background: var(--surface-raised); }
.control-card-identity { min-width: 0; }
.control-card-repo { display: block; color: var(--text-tertiary); font-size: .7rem; overflow-wrap: anywhere; }
.control-card-title { display: block; margin-top: .15rem; color: var(--text-primary); font-weight: 650; line-height: 1.3; text-decoration: none; overflow-wrap: anywhere; }
.control-card-title:hover { color: var(--accent); }
.control-card-summary { margin: .65rem 0; color: var(--text-secondary); font-size: .82rem; line-height: 1.45; }
.control-card-actions { display: flex; align-items: center; justify-content: space-between; gap: .5rem; }
.control-room-run-details { margin-top: .65rem; border-top: 1px solid var(--hairline); padding-top: .55rem; }
.control-room-run-details > summary { cursor: pointer; color: var(--text-secondary); font-size: .76rem; font-weight: 600; }
.control-room-run-body { display: grid; gap: .55rem; margin-top: .55rem; }
.control-lane-empty { margin: .8rem 0 0; padding: 1rem .75rem; border-radius: var(--radius-md); background: var(--surface-raised); color: var(--text-tertiary); font-size: .8rem; text-align: center; }

@media (max-width: 1100px) {
    .control-room-grid { grid-template-columns: 1fr; }
}
@media (max-width: 768px) {
    .control-room-heading { align-items: flex-start; flex-direction: column; }
    .control-card-header { flex-direction: column; }
    .control-card-header .status { align-self: flex-start; }
    .control-card-actions { align-items: stretch; flex-direction: column; }
    .control-card-actions .btn { width: 100%; justify-content: center; }
}
```

- [ ] **Step 6: Run focused dashboard tests and confirm green**

Run: `./mvnw -q -Dtest=DashboardControllerTest,DashboardControlRoomRenderTest,DashboardTileRenderTest test`

Expected: exit code `0`; controller model, lane content, empty states, limits, processing details, accessibility markup, and legacy-strip removal assertions pass.

- [ ] **Step 7: Commit Task 4**

```bash
git add src/main/java/com/dbbaskette/issuebot/controller/DashboardController.java src/main/resources/templates/dashboard.html src/main/resources/static/css/style.css src/test/java/com/dbbaskette/issuebot/controller/DashboardControllerTest.java src/test/java/com/dbbaskette/issuebot/controller/DashboardControlRoomRenderTest.java src/test/java/com/dbbaskette/issuebot/controller/DashboardTileRenderTest.java
git add -u src/test/java/com/dbbaskette/issuebot/controller/DashboardRunningStripRenderTest.java
git commit -m "feat: add operator control room dashboard"
```

---

### Task 5: Whole-feature verification, PR, merge, and local deployment

**Files:**
- Modify only if verification exposes a spec violation or regression.

**Interfaces:**
- Consumes: completed Tasks 1–4 and the approved design specification.
- Produces: verified feature branch, GitHub PR closing #130 and #138, merged `main`, and healthy local runtime on port 8090.

- [ ] **Step 1: Run static and focused verification**

Run:

```bash
git diff --check origin/main...HEAD
./mvnw -q -Dtest=IssueNextActionResolverTest,DashboardControlRoomAssemblerTest,BudgetProgressTest,IssueControllerTest,IssuesQueueRenderTest,IssuesQueueUpgradeRenderTest,IssueDetailLivePollRenderTest,IssueNextActionRenderTest,DashboardControllerTest,DashboardControlRoomRenderTest,DashboardTileRenderTest test
```

Expected: no whitespace errors and exit code `0` for all focused tests.

- [ ] **Step 2: Run the complete build**

Run: `./mvnw clean verify`

Expected: `BUILD SUCCESS`, zero test failures, and a packaged JAR under `target/`.

- [ ] **Step 3: Perform browser smoke testing**

Start the packaged branch build in a dedicated terminal with an isolated in-memory database:

```bash
set -a
source .env
set +a
SERVER_PORT=18090 SPRING_DATASOURCE_URL=jdbc:h2:mem:issuebot_smoke java -jar target/issuebot-0.1.0-SNAPSHOT.jar
```

Wait for startup, then verify `curl --fail --silent http://localhost:18090/actuator/health` contains `"status":"UP"`. Open `http://localhost:18090/` with the browser-control skill and inspect desktop and 320-pixel-wide views. Verify:

- dashboard shows all three lanes before metrics;
- live refresh preserves all lanes;
- actionable card links land at the matching issue-detail section;
- processing details expand and show spend/budget/elapsed metadata;
- queue rows show the same summary and become stacked mobile cards;
- issue detail shows the same summary directly below the header;
- terminal issues show explicit no-action-needed copy;
- no horizontal overflow occurs in control-room cards at 320 pixels.

Send `Ctrl-C` to the dedicated terminal after the smoke test and confirm `curl --fail --silent http://localhost:18090/actuator/health` no longer connects.

- [ ] **Step 4: Run whole-branch review and resolve findings**

Create a review package from `git merge-base origin/main HEAD` through `HEAD`. Dispatch one final reviewer with the approved spec, this plan, package path, and all Global Constraints. Fix every Critical or Important finding, rerun its covering tests, regenerate the review package, and obtain a clean re-review before continuing. Record Minor findings in the task ledger and either fix them or state why they remain non-blocking in the PR.

- [ ] **Step 5: Push and open the PR**

Push branch `codex/next-actions-control-room` and create a PR titled `Add issue next actions and operator control room`. The body must include:

```markdown
## Summary
- add one shared next-action resolver across issue detail, queue, and dashboard
- replace the Now Running strip with decision, processing, and up-next control-room lanes
- retain live refresh and running cost/budget details with responsive accessible cards

## Verification
- focused next-action, controller, assembler, and template tests
- full `./mvnw clean verify`
- desktop and 320px browser smoke test

Closes #130
Closes #138
```

- [ ] **Step 6: Verify GitHub checks and merge**

Run `gh pr checks --watch` for the new PR. Expected: every required check passes. Merge with a merge commit so the locally committed design and plan remain ancestors of remote `main`; do not force-push.

- [ ] **Step 7: Update local main and deploy port 8090**

Fast-forward the main checkout from remote, run `./mvnw clean package`, then restart the existing native IssueBot launch service with:

```bash
launchctl remove com.dbbaskette.issuebot
launchctl submit -l com.dbbaskette.issuebot -- /bin/zsh -lc 'cd /Users/dbbaskette/Projects/IssueBot; set -a; source .env; set +a; export PATH=/Applications/ChatGPT.app/Contents/Resources:/Users/dbbaskette/.local/share/mise/installs/java/21.0.2/bin:/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin; exec /Users/dbbaskette/.local/share/mise/installs/java/21.0.2/bin/java -jar target/issuebot-0.1.0-SNAPSHOT.jar >/Users/dbbaskette/.issuebot/logs/issuebot-launchd.log 2>&1'
```

Verify:

```bash
curl --fail --silent http://localhost:8090/actuator/health
```

Expected response contains `"status":"UP"`. Open the running dashboard and confirm its rendered source contains `Operator control room`, `Needs your decision`, `Currently processing`, and `Up next`.

- [ ] **Step 8: Close out issue and goal evidence**

Confirm GitHub issues #130 and #138 are closed by the merged PR, the other eight UX issues remain open, the PR reports `MERGED`, local `main` contains the merge, the worktree is clean, and the 8090 health endpoint is `UP`. Only then mark the `/goal` complete.

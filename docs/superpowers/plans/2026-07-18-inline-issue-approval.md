# Inline Issue Approval Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a phone-friendly approval decision card to issue detail that reuses the existing approval/rejection actions and returns the operator to the same issue.

**Architecture:** `ApprovalController` remains the single mutation entry point and gains safe, status-guarded issue-detail return routing. `IssueController` asks the existing `ApprovalCardAssembler` for one issue's CI/review/PR view data only while the issue awaits approval. `issue-detail.html` renders a compact decision card and its modal/forms; existing Approvals and Inbox behavior remains unchanged.

**Tech Stack:** Java 21, Spring Boot MVC, Thymeleaf, HTMX-compatible server forms, JUnit 5, Mockito, AssertJ, CSS.

## Global Constraints

- Render the decision card only when the issue status is exactly `AWAITING_APPROVAL`.
- Approve defaults to squash-merging a recorded PR, but the operator can clear the checkbox to complete IssueBot while leaving the PR open.
- `View PR` is a secondary option; the centralized Approvals page remains available and unchanged in purpose.
- Every issue-detail decision returns to `/issues/{id}` with an accurate flash message.
- Review scores are percentages on every surface; a valid `0%` must render.
- `reviewPassed` remains authoritative; operational/unavailable reviews must never render as code failures.
- The server must reject stale approval/rejection submissions without merging, completing, saving, or starting rejection recovery.
- Return routing accepts only server-defined literals and must not create an open redirect.
- At 390 CSS pixels, evidence and actions must not cause horizontal scrolling; Approve, Reject, and View PR become full-width tap targets in that order.
- Do not add a database migration, chart dependency, or new workflow state.

---

### Task 1: Safe shared approval actions and issue return routing

**Files:**
- Modify: `src/main/java/com/dbbaskette/issuebot/controller/ApprovalController.java`
- Modify: `src/main/java/com/dbbaskette/issuebot/controller/ViewResolver.java`
- Test: `src/test/java/com/dbbaskette/issuebot/controller/ApprovalControllerTest.java`
- Test: `src/test/java/com/dbbaskette/issuebot/controller/ViewResolverTest.java`

**Interfaces:**
- Consumes: existing POST `/approvals/{id}/approve`, POST `/approvals/{id}/reject`, and `IssueStatus.AWAITING_APPROVAL`.
- Produces: `ViewResolver.approvalRedirect(String returnTo, Long issueId, String fallback)` returning only `/inbox`, `/issues/{id}`, or the supplied server-owned fallback.
- Produces: status-guarded approve/reject behavior used unchanged by Approvals, Inbox, and issue detail.

- [ ] **Step 1: Write failing safe-routing tests**

Add focused tests equivalent to:

```java
@Test
void approvalRedirectReturnsToCurrentIssueOnlyForLiteralIssue() {
    assertThat(ViewResolver.approvalRedirect("issue", 42L, "redirect:/approvals"))
            .isEqualTo("redirect:/issues/42");
    assertThat(ViewResolver.approvalRedirect("https://evil.example", 42L, "redirect:/approvals"))
            .isEqualTo("redirect:/approvals");
}
```

Run: `./mvnw -Dtest=ViewResolverTest test`
Expected: FAIL because `approvalRedirect` does not exist.

- [ ] **Step 2: Implement bounded routing**

Add this server-owned resolver shape without accepting a path or URL from the request:

```java
public static String approvalRedirect(String returnTo, Long issueId, String fallback) {
    if ("inbox".equals(returnTo)) return "redirect:/inbox";
    if ("issue".equals(returnTo) && issueId != null) return "redirect:/issues/" + issueId;
    return fallback;
}
```

Retain `redirectTarget` for unrelated decomposition/plan callers, or delegate it without changing its existing contract.

Run: `./mvnw -Dtest=ViewResolverTest test`
Expected: PASS.

- [ ] **Step 3: Write failing controller tests for issue returns and stale submissions**

Add tests covering all of these exact outcomes:

```java
assertThat(controller.approve(model, 1L, false, "issue", null, redirects))
        .isEqualTo("redirect:/issues/1");
assertThat(controller.reject(model, 1L, "needs work", "issue", null, redirects))
        .isEqualTo("redirect:/issues/1");
```

For both approve and reject, set the persisted issue status to `COMPLETED` (and separately `IN_PROGRESS` where useful), invoke the endpoint, and verify:

```java
verify(gitHubApi, never()).mergePullRequest(any(), any(), anyInt(), any(), any());
verify(issues, never()).save(any());
verify(iterationManager, never()).handleHumanRejection(any(), anyString());
verify(redirects).addFlashAttribute(eq("error"), contains("no longer awaiting approval"));
```

Also assert merge failure with `returnTo=issue` redirects back to the issue and preserves `AWAITING_APPROVAL`; arbitrary `returnTo` still falls back to `/approvals`.

Run: `./mvnw -Dtest=ApprovalControllerTest test`
Expected: FAIL because issue routing and stale-state guards are absent.

- [ ] **Step 4: Implement status guards and shared redirect use**

Immediately after loading the issue in both actions, require:

```java
if (issue.getStatus() != IssueStatus.AWAITING_APPROVAL) {
    redirectAttributes.addFlashAttribute("error",
            "This issue is no longer awaiting approval. Refresh to see its current state.");
    return ViewResolver.approvalRedirect(returnTo, id, "redirect:/approvals");
}
```

Use `approvalRedirect(returnTo, id, "redirect:/approvals")` for every approve/reject success and error exit. Preserve the existing merge checkbox semantics, mutation order, event logging, and Inbox behavior. Add a nonblank feedback guard before `handleHumanRejection`; return an actionable error without changing state when blank.

Run: `./mvnw -Dtest=ApprovalControllerTest,ViewResolverTest test`
Expected: PASS.

- [ ] **Step 5: Commit Task 1**

```bash
git add src/main/java/com/dbbaskette/issuebot/controller/ApprovalController.java \
        src/main/java/com/dbbaskette/issuebot/controller/ViewResolver.java \
        src/test/java/com/dbbaskette/issuebot/controller/ApprovalControllerTest.java \
        src/test/java/com/dbbaskette/issuebot/controller/ViewResolverTest.java
git commit -m "fix: guard shared approval actions"
```

---

### Task 2: Issue-detail approval decision card

**Files:**
- Modify: `src/main/java/com/dbbaskette/issuebot/controller/IssueController.java`
- Modify: `src/main/resources/templates/issue-detail.html`
- Modify: `src/main/resources/static/css/style.css`
- Test: `src/test/java/com/dbbaskette/issuebot/controller/IssueControllerTest.java`
- Test: `src/test/java/com/dbbaskette/issuebot/controller/IssueDetailPlanReviewRenderTest.java`

**Interfaces:**
- Consumes: `ApprovalCardAssembler.assemble(List<TrackedIssue>)` and its `Cards` maps keyed by issue ID.
- Consumes: Task 1 POST actions with hidden `returnTo=issue`.
- Produces: issue-detail model attributes `approvalReviewScore`, `approvalCiStatus`, and `approvalPrUrl`, present only for `AWAITING_APPROVAL`.
- Produces: `#approval-decision` card plus `#issue-approve-modal` and `#issue-reject-form`.

- [ ] **Step 1: Write failing controller model tests**

Inject `ApprovalCardAssembler` into `IssueController`. In the fixture, mock it and assert an awaiting issue calls:

```java
when(approvalCardAssembler.assemble(List.of(issue))).thenReturn(cards);
controller.detail(model, issue.getId(), null, null, null);
assertThat(model.getAttribute("approvalReviewScore")).isSameAs(score);
assertThat(model.getAttribute("approvalCiStatus")).isEqualTo("passed");
assertThat(model.getAttribute("approvalPrUrl")).isEqualTo("https://github.com/acme/widgets/pull/55");
```

For a non-awaiting issue, verify the assembler is not called and the three attributes are absent.

Run: `./mvnw -Dtest=IssueControllerTest test`
Expected: FAIL because the controller has no approval-card dependency or attributes.

- [ ] **Step 2: Populate one shared approval card**

Add a constructor-injected `ApprovalCardAssembler`. In `populateDetailModel`, only for `AWAITING_APPROVAL`, assemble `List.of(issue)` once and extract nullable values:

```java
ApprovalCardAssembler.Cards cards = approvalCardAssembler.assemble(List.of(issue));
model.addAttribute("approvalReviewScore", cards.reviewScores().get(issue.getId()));
model.addAttribute("approvalCiStatus", cards.ciStatuses().get(issue.getId()));
model.addAttribute("approvalPrUrl", cards.prUrls().get(issue.getId()));
```

Update all direct `IssueController` construction in tests with the new dependency.

Run: `./mvnw -Dtest=IssueControllerTest test`
Expected: PASS.

- [ ] **Step 3: Write failing issue-detail render tests**

Extend the render helper to supply approval attributes. Add tests that assert:

- `id="approval-decision"` exists only for `AWAITING_APPROVAL`;
- `CI passed`, `Review passed`, `90%`, and `0%` use percentage semantics;
- unavailable review copy is `Review unavailable`, never `Review failed`;
- the PR link opens externally and reads `View PR #55`;
- Approve, Reject, and View PR appear in that DOM order;
- the approve form posts to `/approvals/42/approve`, contains `returnTo=issue`, and its merge checkbox is checked by default;
- the reject form posts to `/approvals/42/reject`, contains required feedback and `returnTo=issue`;
- no-PR rendering omits the merge checkbox and says approval will complete IssueBot without merging;
- modal IDs are outside `#live-status` so polling cannot replace them.

Run: `./mvnw -Dtest=IssueDetailPlanReviewRenderTest test`
Expected: FAIL because the decision card is absent.

- [ ] **Step 4: Render the compact decision card and forms**

Place this semantic structure immediately after the review-history section and before plan/recovery decision content:

```html
<section th:if="${issue.status.name() == 'AWAITING_APPROVAL'}"
         id="approval-decision" class="panel mb-3 approval-decision-card"
         aria-labelledby="approval-decision-title">
  <header class="panel-header">
    <div><span class="eyebrow">Ready for your decision</span>
      <h3 id="approval-decision-title">Approval decision</h3></div>
    <span class="status status-awaiting_approval">Awaiting approval</span>
  </header>
  <!-- compact CI/review/score evidence -->
  <!-- actions in DOM order: Approve, Reject, View PR -->
</section>
```

Use the existing Tabler icon vocabulary and explicit text for all states. `Approve` opens `issue-approve-modal`; `Reject` reveals `issue-reject-form`. Forms use normal POST submission and hidden `<input name="returnTo" value="issue">` so the complete page reload displays flash messages.

The merge checkbox renders only for a positive PR number and is `checked`. The View PR link uses `approvalPrUrl`, `target="_blank"`, and `rel="noopener"`.

Run: `./mvnw -Dtest=IssueDetailPlanReviewRenderTest test`
Expected: PASS.

- [ ] **Step 5: Write and satisfy a focused responsive-style regression**

Add a static CSS assertion to the render test (or a focused stylesheet test if one exists) that checks the mobile rule includes the approval action container and full-width direct children. Implement scoped styles:

```css
.approval-decision-evidence { display:flex; flex-wrap:wrap; gap:.65rem; }
.approval-decision-actions { display:flex; flex-wrap:wrap; gap:.65rem; }
.approval-decision-card { min-width:0; overflow-wrap:anywhere; }

@media (max-width: 600px) {
  .approval-decision-actions { flex-direction:column; }
  .approval-decision-actions > .btn,
  .approval-decision-actions > form,
  .approval-decision-actions > a { width:100%; justify-content:center; }
}
```

Keep the card consistent with the existing restrained IssueBot panel system; the signature element is the compact evidence-to-decision flow, not new decoration or motion.

Run: `./mvnw -Dtest=IssueDetailPlanReviewRenderTest test`
Expected: PASS.

- [ ] **Step 6: Run affected regression suites**

Run:

```bash
./mvnw -Dtest=ApprovalControllerTest,ViewResolverTest,IssueControllerTest,IssueDetailPlanReviewRenderTest,ApprovalsDiffViewerRenderTest,InboxPageRenderTest test
```

Expected: all selected tests pass with zero failures and existing Approvals/Inbox output preserved.

- [ ] **Step 7: Commit Task 2**

```bash
git add src/main/java/com/dbbaskette/issuebot/controller/IssueController.java \
        src/main/resources/templates/issue-detail.html \
        src/main/resources/static/css/style.css \
        src/test/java/com/dbbaskette/issuebot/controller/IssueControllerTest.java \
        src/test/java/com/dbbaskette/issuebot/controller/IssueDetailPlanReviewRenderTest.java
git commit -m "feat: approve work from issue detail"
```

---

### Task 3: Complete verification and live acceptance

**Files:**
- Modify: `docs/superpowers/plans/2026-07-18-inline-issue-approval.md` (mark completed checkboxes)
- Create: `.superpowers/sdd/inline-approval-verification.md` (ignored scratch evidence)

**Interfaces:**
- Consumes: completed Tasks 1–2.
- Produces: merge-ready build and recorded automated/live evidence.

- [ ] **Step 1: Run the full suite from a clean final HEAD**

Run: `./mvnw test`
Expected: `BUILD SUCCESS`, zero failures/errors/skips, and at least the 1,011 tests present before this feature.

- [ ] **Step 2: Build the deployable artifact and inspect the diff**

Run:

```bash
./mvnw -q -DskipTests package
git diff --check $(git merge-base main HEAD)..HEAD
git status --short
```

Expected: package exit 0, diff check exit 0, clean worktree.

- [ ] **Step 3: Verify the phone layout and actions against a real awaiting issue**

Deploy the branch artifact temporarily with the existing local launch configuration on port 8090. On a real `AWAITING_APPROVAL` issue, verify at a 390-by-844 viewport:

- score is a percentage, not `/10`;
- the card has no horizontal overflow;
- Approve, Reject, and View PR are full-width and in order;
- the approval modal defaults merge on and can be canceled without mutation;
- the PR link opens the exact PR;
- no action is actually confirmed during visual verification unless it targets a disposable test issue.

Record URL, viewport, rendered states, health response, artifact SHA-256, and any test issue used in `.superpowers/sdd/inline-approval-verification.md`.

- [ ] **Step 4: Mark plan traceability and commit**

Mark completed steps only after their evidence exists, then commit the plan update:

```bash
git add docs/superpowers/plans/2026-07-18-inline-issue-approval.md
git commit -m "docs: record inline approval verification"
```

- [ ] **Step 5: Whole-branch review, PR, merge, and final deployment**

Generate a review package from `git merge-base main HEAD` through final `HEAD` and obtain an independent Critical/Important/Minor review. Resolve all Critical/Important findings test-first in one fix wave and re-review.

After a clean review:

1. push `feat/review-score-history`;
2. create a GitHub pull request whose body summarizes score history, corrected score semantics, neutral operational failures, and inline issue approval with verification;
3. wait for required checks and merge the PR;
4. update the main checkout to the merged remote `main` without discarding local work;
5. rebuild from `main` and point the existing `com.dbbaskette.issuebot` launch service back to the main JAR;
6. verify port 8090 health and the deployed commit/build metadata;
7. confirm the merged UI on a real issue without performing an unintended approval.

Expected: PR merged, local/remote main aligned, launch service running the main-checkout artifact, and health `UP`.

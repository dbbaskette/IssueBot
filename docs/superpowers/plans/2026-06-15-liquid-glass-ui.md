# Liquid Glass UI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Reskin the IssueBot dashboard to a lighter/airier liquid-glass aesthetic (with dark/light toggle), fix 10 UX gaps and 3 latent backend bugs, all on the existing Thymeleaf + HTMX + SSE stack.

**Architecture:** Server-rendered Thymeleaf pages composed via `layout.html` + a `contentTemplate :: content` fragment. A new `HX-Request`-aware return helper makes every endpoint return the bare content fragment for HTMX swaps and the full layout for direct navigation (fixing the page-nesting bug uniformly). A rewritten `style.css` provides a CSS-variable design system themed by `data-theme` on `<html>`. Charts via Chart.js CDN.

**Tech Stack:** Java 21, Spring Boot 3.4.2, Thymeleaf, HTMX 2.0.4 + sse + idiomorph, JGit, H2/Flyway, Chart.js (CDN), JUnit 5 / Mockito.

**Branch:** `liquid-glass-ui` (already created; cleanup + spec already committed).

**Global rule:** after every task, `./mvnw -o test` must pass (113+ tests). Commit after each task.

---

## File map

**Backend (Phase 0)**
- Create `controller/ViewResolver.java` — static helper: HTMX-aware view name + flash-to-model bridge.
- Modify all controllers (`DashboardController`, `IssueController`, `ApprovalController`, `SettingsController`, `RepositoryController`, `CostController`, `SetupController`) — use the helper; convert action endpoints to flash + fragment.
- Modify `IssueController.table` — honor filter params.
- Modify `controller/GlobalExceptionHandler.java` — stop leaking exception text.
- Modify `service/workflow/IssueWorkflowService.java` — stream-json key, iteration-comment gate, review-budget bug.
- Modify `service/claude/StreamJsonParser.java` — align tool key.
- Tests: `IssueControllerTest`, `IssueWorkflowServiceTest`, `StreamJsonParserTest`.

**Frontend (Phases 1–3)**
- Rewrite `static/css/style.css` — design system + both themes.
- Create `static/js/app.js` — theme toggle, terminal controls, diff render, modal, delegated handlers.
- Modify every template in `templates/` — glass components, fold in UX fixes.

---

## PHASE 0 — Backend prerequisites + bug fixes

### Task 1: HTMX-aware view resolution helper

**Files:**
- Create: `src/main/java/com/dbbaskette/issuebot/controller/ViewResolver.java`
- Test: `src/test/java/com/dbbaskette/issuebot/controller/ViewResolverTest.java`

**Context:** Today every controller returns `"layout"`, which renders the full shell. When HTMX swaps that into `#content` it nests a second shell. The helper returns `"<template> :: content"` for HTMX requests (bare fragment) and `"layout"` otherwise. It reads the `HX-Request` header.

- [ ] **Step 1: Write the failing test**

```java
package com.dbbaskette.issuebot.controller;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ViewResolverTest {

    @Test
    void returnsFragmentForHtmxRequest() {
        assertThat(ViewResolver.view("issues", true)).isEqualTo("issues :: content");
    }

    @Test
    void returnsLayoutForFullPageRequest() {
        assertThat(ViewResolver.view("issues", false)).isEqualTo("layout");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -o test -Dtest=ViewResolverTest`
Expected: compile failure / FAIL — `ViewResolver` does not exist.

- [ ] **Step 3: Implement**

```java
package com.dbbaskette.issuebot.controller;

/** Resolves a Thymeleaf view name based on whether the request came from HTMX. */
public final class ViewResolver {

    private ViewResolver() {}

    /**
     * @param contentTemplate the page template name (also the value set as {@code contentTemplate})
     * @param htmxRequest      true when the {@code HX-Request} header is present
     * @return the bare content fragment for HTMX swaps, or the full {@code layout} otherwise
     */
    public static String view(String contentTemplate, boolean htmxRequest) {
        return htmxRequest ? contentTemplate + " :: content" : "layout";
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw -o test -Dtest=ViewResolverTest`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/dbbaskette/issuebot/controller/ViewResolver.java \
        src/test/java/com/dbbaskette/issuebot/controller/ViewResolverTest.java
git commit -m "Add HTMX-aware view resolver to avoid nested layout swaps"
```

### Task 2: Adopt the helper across GET endpoints + add HX-Request param

**Files:**
- Modify: every `@GetMapping` returning `"layout"` in `DashboardController`, `IssueController` (`list`, `detail`), `ApprovalController.list`, `SettingsController.settings`, `RepositoryController.list`, `CostController`, `SetupController`.

**Pattern for each handler** — add `@RequestHeader(value = "HX-Request", required = false) String hx` and return `ViewResolver.view("<template>", hx != null)`. The `contentTemplate` model attribute stays (layout uses it for full-page).

- [ ] **Step 1: Modify `IssueController.list`** — change signature to add the header param and return `ViewResolver.view("issues", hx != null)`:

```java
@GetMapping
public String list(Model model,
                   @RequestParam(required = false) String status,
                   @RequestParam(required = false) Long repoId,
                   @RequestHeader(value = "HX-Request", required = false) String hx) {
    // ... existing body unchanged up to the return ...
    return ViewResolver.view("issues", hx != null);
}
```

- [ ] **Step 2: Repeat** the same edit for `IssueController.detail`, `DashboardController` index, `ApprovalController.list`, `SettingsController.settings`, `RepositoryController.list`, `CostController` index, `SetupController` index. Each returns `ViewResolver.view("<its contentTemplate>", hx != null)`.

- [ ] **Step 3: Verify each content template defines `th:fragment="content"`** — they already do (layout uses `~{${contentTemplate} :: content}`). No template change needed for this task.

- [ ] **Step 4: Compile + full test run**

Run: `./mvnw -o test`
Expected: BUILD SUCCESS, 115 tests.

- [ ] **Step 5: Manual check**

Run the app (`run` skill), click each nav item, confirm no nested sidebar appears inside the content area and direct URL loads still render the full shell.

- [ ] **Step 6: Commit**

```bash
git add -A && git commit -m "Return content fragment for HTMX nav, full layout for direct loads"
```

### Task 3: Action endpoints — flash + fragment instead of full layout

**Files:**
- Modify: `ApprovalController.approve/reject`, `SettingsController.pause/resume/quickSettings/saveConfig`, `RepositoryController.addOrUpdate/delete`.

**Context:** These POST/DELETE handlers return `"layout"` and stuff a `message`/`error` into the model. Convert to: do the work, set `message`/`error`, repopulate the model, and return the **content fragment** (`ViewResolver.view(template, true)` — these are always HTMX since the buttons are `hx-post`). Keep a non-HTMX fallback by reading the header.

- [ ] **Step 1: Edit `ApprovalController.approve`:**

```java
@PostMapping("/{id}/approve")
public String approve(Model model, @PathVariable Long id,
                      @RequestHeader(value = "HX-Request", required = false) String hx) {
    TrackedIssue issue = issueRepository.findById(id).orElseThrow();
    issue.setStatus(IssueStatus.COMPLETED);
    issueRepository.save(issue);
    eventService.log("APPROVAL_APPROVED",
            "Human approved PR for #" + issue.getIssueNumber(), issue.getRepo(), issue);
    populateModel(model, "Approved: " + issue.getRepo().fullName() + " #" + issue.getIssueNumber());
    return ViewResolver.view("approvals", hx != null);
}
```

- [ ] **Step 2: Apply the same shape** to `reject`, `SettingsController.pause/resume/quickSettings/saveConfig`, `RepositoryController.addOrUpdate/delete` — add the `hx` header param, keep the existing body, change the final `return "layout";` to `return ViewResolver.view("<template>", hx != null);`.

- [ ] **Step 3: Full test run**

Run: `./mvnw -o test`
Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit**

```bash
git add -A && git commit -m "Action endpoints return content fragment, not nested layout"
```

### Task 4: Honor filters in the issue table fragment

**Files:**
- Modify: `src/main/java/com/dbbaskette/issuebot/controller/IssueController.java:110-114` (`table`)
- Test: `src/test/java/com/dbbaskette/issuebot/controller/IssueControllerTest.java` (new)

**Context:** `table()` calls `findAll()`, ignoring active filters — SSE refresh and the Refresh button wipe the user's filter. Reuse the same filter logic as `list()` by extracting it.

- [ ] **Step 1: Write the failing test** (uses `@WebMvcTest` with mocked repos):

```java
package com.dbbaskette.issuebot.controller;

import com.dbbaskette.issuebot.config.IssueBotProperties;
import com.dbbaskette.issuebot.model.IssueStatus;
import com.dbbaskette.issuebot.repository.*;
import com.dbbaskette.issuebot.service.event.EventService;
import com.dbbaskette.issuebot.service.github.GitHubApiClient;
import com.dbbaskette.issuebot.service.polling.IssuePollingService;
import com.dbbaskette.issuebot.service.workflow.IssueWorkflowService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import java.util.List;
import static org.mockito.Mockito.*;

class IssueControllerTest {

    @Test
    void tableHonorsStatusFilter() {
        TrackedIssueRepository issues = mock(TrackedIssueRepository.class);
        WatchedRepoRepository repos = mock(WatchedRepoRepository.class);
        when(issues.findByStatus(IssueStatus.FAILED)).thenReturn(List.of());

        IssueController c = new IssueController(issues, repos,
                mock(IterationRepository.class), mock(EventRepository.class),
                mock(CostTrackingRepository.class), mock(IssuePollingService.class),
                mock(IssueWorkflowService.class), mock(EventService.class),
                mock(GitHubApiClient.class), mock(IssueBotProperties.class));

        org.springframework.ui.Model model = new org.springframework.ui.ExtendedModelMap();
        String view = c.table(model, "FAILED", null);

        verify(issues).findByStatus(IssueStatus.FAILED);
        verify(issues, never()).findAll();
        org.assertj.core.api.Assertions.assertThat(view).isEqualTo("issues :: table-rows");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -o test -Dtest=IssueControllerTest`
Expected: FAIL — `table` signature mismatch / `findAll` called.

- [ ] **Step 3: Refactor `IssueController`** — extract the filter logic from `list()` into a private method and call it from both:

```java
private List<TrackedIssue> filterIssues(String status, Long repoId) {
    if (status != null && !status.isBlank() && repoId != null) {
        try {
            IssueStatus s = IssueStatus.valueOf(status);
            return repoRepository.findById(repoId)
                    .map(r -> issueRepository.findByRepoAndStatus(r, s)).orElseGet(List::of);
        } catch (IllegalArgumentException e) { return List.of(); }
    } else if (status != null && !status.isBlank()) {
        try { return issueRepository.findByStatus(IssueStatus.valueOf(status)); }
        catch (IllegalArgumentException e) { return List.of(); }
    } else if (repoId != null) {
        return repoRepository.findById(repoId).map(issueRepository::findByRepo).orElseGet(List::of);
    }
    return issueRepository.findAll();
}
```

Replace the inline branching in `list()` with `List<TrackedIssue> issues = filterIssues(status, repoId);` and update `table`:

```java
@GetMapping("/table")
public String table(Model model,
                    @RequestParam(required = false) String status,
                    @RequestParam(required = false) Long repoId) {
    model.addAttribute("issues", filterIssues(status, repoId));
    return "issues :: table-rows";
}
```

- [ ] **Step 4: Run tests**

Run: `./mvnw -o test -Dtest=IssueControllerTest`
Expected: PASS.

- [ ] **Step 5: Frontend follow-up note** — in Task 12 the issues template must pass the active `status`/`repoId` to both the SSE `hx-get="/issues/table"` and the Refresh button (`hx-include` the filter form).

- [ ] **Step 6: Commit**

```bash
git add -A && git commit -m "Honor active filters in issue table fragment endpoint"
```

### Task 5: Stop leaking exception text on error page

**Files:**
- Modify: `src/main/java/com/dbbaskette/issuebot/controller/GlobalExceptionHandler.java`

- [ ] **Step 1: Read the handler** and locate where `errorMessage` is built from `e.getMessage()`.

- [ ] **Step 2: Change** so the model gets a generic message and the detail is logged:

```java
log.error("Unhandled exception serving {}", request.getRequestURI(), e);
model.addAttribute("errorMessage", "Something went wrong. Check the server logs for details.");
```

Keep any existing 404-specific branch (`NoSuchElementException`) returning a "Not found" message and a 404 status; the generic branch covers 500s.

- [ ] **Step 3: Compile + test**

Run: `./mvnw -o test`
Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit**

```bash
git add -A && git commit -m "Stop leaking raw exception text to error page"
```

### Task 6: Fix stream-json tool key inconsistency

**Files:**
- Modify: `src/main/java/com/dbbaskette/issuebot/service/claude/StreamJsonParser.java` and `service/workflow/IssueWorkflowService.java` (`streamClaudeLog`)
- Test: `src/test/java/com/dbbaskette/issuebot/service/claude/StreamJsonParserTest.java`

**Context:** `StreamJsonParser` reads `node.path("tool")` (fallback `name`); `streamClaudeLog` reads `node.path("tool_name")` (fallback `name`). The Claude CLI stream-json `tool_use` content block uses `"name"` for the tool name. Standardize on reading `"name"` first, then the legacy keys as fallback, in both places.

- [ ] **Step 1: Inspect the existing `StreamJsonParserTest` fixtures** to see the exact JSON shape used (`type":"assistant"` with `content[].type":"tool_use"`). Confirm the tool name field is `name`.

- [ ] **Step 2: Write a failing test** asserting tool name extraction from a `tool_use` block keyed by `name`:

```java
@Test
void extractsToolNameFromNameKey() {
    String line = "{\"type\":\"assistant\",\"message\":{\"content\":"
        + "[{\"type\":\"tool_use\",\"name\":\"Edit\",\"input\":{}}]}}";
    // assert via whatever StreamJsonParser exposes (e.g. parse(...).getToolCalls() contains "Edit")
}
```

(Match the assertion to the parser's actual API — read it first; if it accumulates tool names on the result, assert that list contains `"Edit"`.)

- [ ] **Step 3: Run — expect FAIL** if the parser currently only reads `tool`.

Run: `./mvnw -o test -Dtest=StreamJsonParserTest`

- [ ] **Step 4: Implement** a shared static helper on `StreamJsonParser`:

```java
/** Tool-use blocks use "name"; older/variant payloads used "tool"/"tool_name". */
static String toolName(com.fasterxml.jackson.databind.JsonNode block) {
    String n = block.path("name").asText("");
    if (n.isEmpty()) n = block.path("tool").asText("");
    if (n.isEmpty()) n = block.path("tool_name").asText("");
    return n;
}
```

Use `toolName(block)` in `StreamJsonParser` and in `IssueWorkflowService.streamClaudeLog` (replace `node.path("tool_name")`).

- [ ] **Step 5: Run tests**

Run: `./mvnw -o test -Dtest=StreamJsonParserTest`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add -A && git commit -m "Standardize stream-json tool-name extraction on the name key"
```

### Task 7: Gate the misleading first-iteration implementation comment

**Files:**
- Modify: `service/workflow/IssueWorkflowService.java` (`processIssue` loop around lines 178-179, 246-248; `postImplementationResponseToIssue` ~1000-1036)
- Test: `IssueWorkflowServiceTest`

**Context:** On iteration 1 with human `additionalInstructions`, `previousFeedback != null`, so the code posts "Addressed the review findings from iteration 0" — wrong source and wrong number. Only post this comment when the feedback originated from a review (iteration > 1), and word it to match the source.

- [ ] **Step 1: Read** the loop to identify the flag distinguishing review-originated feedback from human/CI feedback. Introduce a boolean `reviewFeedback` set true only on the review-failure path (~line 352) and false for human-instructions/CI paths.

- [ ] **Step 2: Write/extend a test** in `IssueWorkflowServiceTest` asserting that with `additionalInstructions` on a first iteration, `gitHubApi.addComment` is NOT called with the "Addressed the review findings" text. (Use the existing mock setup pattern in that test class.)

- [ ] **Step 3: Run — expect FAIL.**

- [ ] **Step 4: Implement** — guard the call: `if (reviewFeedback && iterationNum > 1) { postImplementationResponseToIssue(...); }` and change the wording to reference the prior iteration's review specifically.

- [ ] **Step 5: Run tests — expect PASS.** Then full suite `./mvnw -o test`.

- [ ] **Step 6: Commit**

```bash
git add -A && git commit -m "Only post implementation-response comment for review feedback"
```

### Task 8: Don't consume a review-iteration slot on invocation error

**Files:**
- Modify: `service/workflow/IssueWorkflowService.java` (`phaseIndependentReview` ~654-710)
- Test: `IssueWorkflowServiceTest`

**Context:** The method increments + persists `currentReviewIteration` before calling `reviewCode`; if `reviewCode` throws, the slot is consumed without a review. Move the increment to after a successful review.

- [ ] **Step 1: Write a test** that makes `codeReviewService.reviewCode(...)` throw and asserts `issue.getCurrentReviewIteration()` is unchanged (no save with an incremented value). Match the mock style in `IssueWorkflowServiceTest`.

- [ ] **Step 2: Run — expect FAIL.**

- [ ] **Step 3: Implement** — move the `setCurrentReviewIteration(... + 1)` + save to after `reviewCode` returns successfully (before persisting the result), so the catch path leaves it untouched.

- [ ] **Step 4: Run tests — expect PASS**, then `./mvnw -o test`.

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "Consume review-iteration slot only after a successful review"
```

---

## PHASE 1 — Design system + shell

### Task 9: CSS design system + theme tokens

**Files:**
- Rewrite: `src/main/resources/static/css/style.css`

**Context:** Single source of truth for the liquid-glass look in both themes. Define tokens, then component classes. The implementing agent writes the full sheet; the **token contract below is fixed** and everything else references it.

- [ ] **Step 1: Define the theme token contract** at the top of `style.css` (this exact contract — later tasks depend on these names):

```css
:root {
  --radius-md: 12px; --radius-lg: 18px; --radius-pill: 999px;
  --blur: 18px;
  --font-sans: 'Inter', system-ui, sans-serif;
  --font-mono: 'JetBrains Mono', ui-monospace, monospace;
  /* terminal is dark in BOTH themes */
  --term-bg: #0f1228; --term-fg: #cbd5e1; --term-accent: #5eead4;
}
:root[data-theme="light"] {
  --page-bg: #eef1f8;
  --aurora: radial-gradient(55% 45% at 12% 8%, rgba(139,123,255,.30), transparent 70%),
            radial-gradient(50% 45% at 92% 16%, rgba(56,189,248,.26), transparent 72%),
            radial-gradient(60% 55% at 75% 100%, rgba(244,114,182,.20), transparent 72%);
  --glass-bg: rgba(255,255,255,.60); --glass-border: rgba(255,255,255,.85);
  --glass-shadow: 0 8px 26px -14px rgba(80,70,160,.40);
  --text-primary: #1e1b35; --text-secondary: #4b5563; --text-tertiary: #94a3b8;
  --accent: #7c63ff; --accent-soft: rgba(124,99,255,.14); --accent-border: rgba(124,99,255,.40);
  --ok: #0f766e; --ok-soft: rgba(20,184,166,.14);
  --danger: #be123c; --danger-soft: rgba(244,63,94,.10);
  --info: #0284c7; --warn: #b45309;
}
:root[data-theme="dark"] {
  --page-bg: #0a0a14;
  --aurora: radial-gradient(60% 50% at 15% 10%, rgba(124,99,255,.40), transparent 70%),
            radial-gradient(55% 50% at 90% 20%, rgba(34,211,238,.28), transparent 70%),
            radial-gradient(70% 60% at 70% 100%, rgba(236,72,153,.22), transparent 70%);
  --glass-bg: rgba(255,255,255,.06); --glass-border: rgba(255,255,255,.12);
  --glass-shadow: 0 10px 30px -16px rgba(0,0,0,.6);
  --text-primary: #f8fafc; --text-secondary: #cbd5e1; --text-tertiary: #94a3b8;
  --accent: #a78bfa; --accent-soft: rgba(124,99,255,.16); --accent-border: rgba(124,99,255,.40);
  --ok: #5eead4; --ok-soft: rgba(45,212,191,.14);
  --danger: #fda4af; --danger-soft: rgba(244,63,94,.12);
  --info: #7dd3fc; --warn: #fbbf24;
}
body { background: var(--page-bg); color: var(--text-primary); font-family: var(--font-sans); }
body::before { content:''; position:fixed; inset:0; background: var(--aurora); z-index:-1; pointer-events:none; }
```

- [ ] **Step 2: Define component classes** referencing the tokens — `.glass-card`, `.glass-pill`, `.metric-tile` (+ `.is-link` hover lift, `--accent`/`--danger` modifier borders), `.phase-chip` (`.done/.active/.pending/.failed`), `.terminal-well` (uses `--term-*`), `.btn`/`.btn-primary`/`.btn-ghost`/`.btn-danger`, `.badge`+`.badge-{status}`, `.diff-line.added/.removed`, `.field-group`, `.toast`. Each uses `backdrop-filter: var(--blur)` + `-webkit-backdrop-filter`. Example:

```css
.glass-card { background: var(--glass-bg); border: 1px solid var(--glass-border);
  border-radius: var(--radius-lg); box-shadow: var(--glass-shadow);
  backdrop-filter: blur(var(--blur)); -webkit-backdrop-filter: blur(var(--blur)); padding: 16px; }
.metric-tile.is-link { cursor: pointer; transition: transform .12s ease, box-shadow .12s ease; }
.metric-tile.is-link:hover { transform: translateY(-2px); }
:focus-visible { outline: 2px solid var(--accent); outline-offset: 2px; }
@media (prefers-reduced-motion: reduce) { * { transition: none !important; animation: none !important; } }
```

- [ ] **Step 3: Port the sidebar/layout structural CSS** (`.sidebar`, `#content`, `.mobile-menu-btn`, `.sidebar-overlay`, responsive breakpoints) from the old sheet, restyled to glass. Keep the same class names the templates use so nothing breaks.

- [ ] **Step 4: Verify the app still loads** (`run` skill) — pages will look transitional until templates update, but nothing should 500.

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/static/css/style.css
git commit -m "Rewrite stylesheet as liquid-glass design system with dual themes"
```

### Task 10: Layout shell — theme toggle, a11y nav, toast, app.js

**Files:**
- Modify: `src/main/resources/templates/layout.html`
- Create: `src/main/resources/static/js/app.js`

- [ ] **Step 1: Add the no-flash theme bootstrap** as the FIRST element in `<head>` (before the stylesheet link):

```html
<script>
  (function(){var t=localStorage.getItem('theme')
    ||(matchMedia('(prefers-color-scheme: dark)').matches?'dark':'light');
    document.documentElement.setAttribute('data-theme',t);})();
</script>
```

- [ ] **Step 2: Add the theme toggle control** to the sidebar footer (or top of nav):

```html
<button id="theme-toggle" class="btn btn-ghost" aria-label="Toggle light/dark theme">
  <i class="ti ti-moon" aria-hidden="true"></i>
</button>
```

- [ ] **Step 3: Fix nav a11y** — add `aria-current` driven by `activePage` (e.g. `th:attr="aria-current=${activePage=='issues'}? 'page' : null"`) on each link, add `aria-expanded="false"` to the mobile menu button, and give the hamburger `aria-controls="sidebar"`. Add a visually-hidden skip link to `#content`.

- [ ] **Step 4: Add a toast region** inside `<main id="content">`-adjacent shell that renders flash/model messages:

```html
<div id="toast-region" aria-live="polite">
  <div th:if="${success}" class="toast toast-ok" th:text="${success}"></div>
  <div th:if="${message}" class="toast toast-ok" th:text="${message}"></div>
  <div th:if="${error}" class="toast toast-danger" th:text="${error}"></div>
</div>
```

(Place it so it also renders inside content fragments — add the same block to each content template's fragment top, or define a shared `th:fragment="toasts"` in layout and `th:replace` it. Use the shared-fragment approach.)

- [ ] **Step 5: Replace the inline-JS handlers** (mobile menu, active-state recompute) with `app.js`, loaded with `defer`. Implement in `app.js`: theme toggle (flip attribute + persist + swap icon), mobile menu toggle with `aria-expanded` sync, and auto-dismiss toasts after ~4s. Remove the old `htmx:pushedIntoHistory` active-state recompute (server `aria-current` replaces it).

- [ ] **Step 6: Manual check** — toggle theme persists across reloads and nav; no flash; hamburger works; keyboard focus visible.

- [ ] **Step 7: Commit**

```bash
git add -A && git commit -m "Glass layout shell: theme toggle, accessible nav, toast region"
```

---

## PHASE 2 — Per-page reskin + UX fixes

> Each page is an independent task. All reuse the design-system classes from Task 9. After each: `./mvnw -o test` green + visual check in both themes. Commit per page.

### Task 11: Dashboard — live, drill-through tiles, humanized feed

**Files:** Modify `templates/dashboard.html`; verify `DashboardController` supplies counts + recent events (it does).

- [ ] **Step 1:** Convert the 8 metric cards to `.metric-tile` glass tiles. Wrap the operational ones in links: Completed → `/issues?status=COMPLETED`, In Progress → `?status=IN_PROGRESS`, Failed → `?status=FAILED`, Blocked → `?status=BLOCKED`, Queued → `?status=QUEUED` — each `hx-get` into `#content` + `hx-push-url`. Give In Progress/Failed/Blocked accent/danger emphasis.
- [ ] **Step 2:** Make it live — add an SSE binding (reuse the existing SSE channel and `hx-ext="sse"`) or an `hx-get` poll `every 10s` on the metrics+feed container, targeting a new `dashboard :: live` fragment. Add the corresponding fragment + a `DashboardController` `/dashboard/live` (or reuse index with HX header) returning it.
- [ ] **Step 3:** Humanize the event feed — map `eventType` to a label + Tabler icon (small Thymeleaf `th:switch` or a helper), and link each event to its issue when `issue` is set.
- [ ] **Step 4:** Visual check both themes; `./mvnw -o test`.
- [ ] **Step 5:** Commit `"Liquid-glass dashboard with live drill-through tiles and event feed"`.

### Task 12: Issues queue — filter-honoring refresh, accessible rows

**Files:** Modify `templates/issues.html`.

- [ ] **Step 1:** Reskin table/filters to glass; status via `.badge-{status}`.
- [ ] **Step 2:** Pass filters through refresh — the SSE/`hx-get="/issues/table"` and Refresh button must `hx-include` the filter form (so `status`/`repoId` ride along, backed by Task 4).
- [ ] **Step 3:** Add `hx-indicator` spinner on filter change + refresh.
- [ ] **Step 4:** Make rows keyboard-accessible — wrap the row's primary nav in an `<a>` or add `tabindex="0" role="link"` + an Enter/Space handler in `app.js`. Keep the Start button's `stopPropagation`.
- [ ] **Step 5:** Visual check; `./mvnw -o test`. Commit.

### Task 13: Issue detail — diff coloring, terminal controls, pipeline, modal

**Files:** Modify `templates/issue-detail.html`; extend `app.js`.

- [ ] **Step 1:** Reskin header/metrics/blocked-by/iteration history to glass.
- [ ] **Step 2:** Diff coloring — in `app.js`, parse each diff block's lines and wrap `+`/`-` lines in `.diff-line.added/.removed`; apply on load and after HTMX swaps (`htmx:afterSwap`).
- [ ] **Step 3:** Terminal controls — wrap the live terminal in `.terminal-well` with a control bar: scroll-lock toggle (stop the force-scroll when user scrolls up; resume on "jump to bottom"), copy button, and a "N lines hidden" indicator when the 200-line cap trims. Add `aria-live="polite"` and harden the `window.__issueBotES` guard so it doesn't double-init on partial swaps.
- [ ] **Step 4:** Phase pipeline — render the 6 chips from a server-provided phase index; ensure COMPLETION shows `.done` when status is COMPLETED (today it never completes). If the index isn't already in the model, compute it in `populateDetailModel` and add it.
- [ ] **Step 5:** Retry modal — rebuild as accessible dialog in `app.js`/template: `role="dialog" aria-modal="true"`, focus trap, `Esc` to close, autofocus textarea, glass styling. Route "Mark Complete" through the same confirm dialog (replace native `confirm()`).
- [ ] **Step 6:** Visual check; `./mvnw -o test`. Commit.

### Task 14: Approvals — informed merge gate

**Files:** Modify `templates/approvals.html`; `ApprovalController.populateModel` to add PR URL + score.

- [ ] **Step 1:** Add to the model per approval: the PR URL (from issue/iteration) and the latest review score. Surface them on each glass card with a changed-file summary if available.
- [ ] **Step 2:** Add an Approve confirmation dialog (reuse the modal from Task 13); keep Reject's feedback textarea (autofocus on open).
- [ ] **Step 3:** Fix the self-assessment `<pre>` hardcoded light colors → `.terminal-well` / theme tokens.
- [ ] **Step 4:** Approved/rejected card animates out (respg reduced-motion). Visual check; `./mvnw -o test`. Commit.

### Task 15: Repositories — escaping fix, grouped form, GitHub links

**Files:** Modify `templates/repositories.html`; extend `app.js`.

- [ ] **Step 1:** Replace the `editRepo(...)` string-concat `onclick` with `data-*` attributes on the button + a single delegated handler in `app.js` that reads them and populates the form (fixes the injection/breakage on quotes).
- [ ] **Step 2:** Regroup the add/edit form into glass sections **Workflow / Safety / CI** (drop the inline `margin-top` checkbox hacks; use `.field-group`). On open: scroll into view + focus first field.
- [ ] **Step 3:** Link repo names to `https://github.com/{owner}/{name}`.
- [ ] **Step 4:** Collapse the 11-column table to cards on small screens; give abbreviated headers visible labels.
- [ ] **Step 5:** Visual check; `./mvnw -o test`. Commit.

### Task 16: Settings + Setup reskin

**Files:** Modify `templates/settings.html`, `templates/setup.html`.

- [ ] **Step 1:** Settings — glass cards; relabel "Save & Reload" to "Save (restart required)"; add a note that Quick Settings are in-memory until persisted; add a Discard confirmation. (Persisting Quick Settings to YAML is explicitly out of scope per spec.)
- [ ] **Step 2:** Setup — reskin the prereq checklist to glass; add a "Re-check" button (re-trigger the existing async check) and a copy-button on the example config. Keep the good skeleton-loading pattern.
- [ ] **Step 3:** Visual check; `./mvnw -o test`. Commit.

---

## PHASE 3 — Cross-cutting polish

### Task 17: Costs — Chart.js + date range + sorting

**Files:** Modify `templates/costs.html`; verify `CostController` data; extend `app.js`.

- [ ] **Step 1:** Add Chart.js via CDN in `costs.html` (`<script src="https://cdn.jsdelivr.net/npm/chart.js">`). Render a cost-over-time line and a per-repo bar from the model data (serialize the needed series as a JSON `<script type="application/json">` block the chart JS reads).
- [ ] **Step 2:** Add a date-range control (7d / 30d / all) — `hx-get` with a `range` param to a costs fragment, or client-side filter if all data is already present. If server-side, add the param to `CostController`.
- [ ] **Step 3:** Make both tables sortable (click header → sort, biggest cost default) via a small `app.js` sorter.
- [ ] **Step 4:** Visual check both themes; `./mvnw -o test`. Commit.

### Task 18: Global loading states + double-submit guard + final a11y pass

**Files:** `app.js`, templates with mutating buttons.

- [ ] **Step 1:** Add `hx-indicator` + `hx-disabled-elt="this"` (or JS disable-on-submit) to Start/Retry/Approve/Reject/Save buttons to prevent double-fire.
- [ ] **Step 2:** Audit `aria-live` on terminal + toast; confirm every status badge/dot pairs color with an icon or text; confirm focus-visible everywhere; confirm `prefers-reduced-motion` disables glow/animation.
- [ ] **Step 3:** Run the app, keyboard-only walkthrough of each page in both themes.
- [ ] **Step 4:** `./mvnw -o test` (full suite green). Commit `"Global loading states, double-submit guards, accessibility pass"`.

### Task 19: Final verification

- [ ] **Step 1:** `./mvnw -o test` — all 115+ tests green.
- [ ] **Step 2:** `run` skill — smoke every page in light AND dark, exercise Start/Retry/Approve/Reject/Save, confirm: toasts appear, no nested shell, live updates work, filters survive refresh, diffs are colored, terminal controls work, charts render, theme persists.
- [ ] **Step 3:** Update `README.md` UI/feature notes if the dashboard description changed (e.g. theme toggle, charts).
- [ ] **Step 4:** Final commit / ready for PR.

---

## Self-review notes
- **Spec coverage:** all 10 UX recs map to tasks (1: T3/T10 toasts+fragments; 2: T11 tiles; 3: T11 live; 4: T13 diff; 5: T15 escaping; 6: T4+T12 filters; 7: T13 terminal; 8: T14 approvals; 9: T10+T12 a11y nav/rows; 10: T15+T17 repos/costs). 3 bug fixes = T6/T7/T8. Error leak = T5. Theme toggle = T9/T10.
- **Type consistency:** `ViewResolver.view(String, boolean)` used identically in T1–T3; `filterIssues(String, Long)` defined and used in T4; `toolName(JsonNode)` defined and used in T6.
- **TDD applies** to backend tasks (T1, T4, T6, T7, T8). UI tasks use visual verification — explicitly noted, as Thymeleaf/CSS has no unit-test seam here.
- **Out of scope (flagged):** persisting Quick Settings to YAML (T16); removing `Iteration.selfAssessment` field.

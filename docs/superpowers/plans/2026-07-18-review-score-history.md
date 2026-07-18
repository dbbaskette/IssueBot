# Review Score History Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Show accurate, attractive implementation-review score history on the issue detail page and stop passing second reviews from appearing as conformance failures.

**Architecture:** Extract persisted review parsing into a reusable immutable view model, then build a pure score-history assembler that selects attempts and computes comparisons. The issue controller supplies the assembled history and an explicit guidance-eligibility flag to Thymeleaf; the template renders the approved trajectory design and never parses JSON or infers verdicts from counters.

**Tech Stack:** Java 21 records, Spring MVC, Thymeleaf, Jackson, AssertJ/JUnit 5, existing IssueBot CSS tokens; no new dependencies.

## Global Constraints

- `Iteration.reviewPassed` is authoritative for a persisted attempt verdict.
- Missing score dimensions are omitted from averages and are never treated as zero.
- Display rounding must not feed later score or delta calculations.
- The latest scored attempt compares with the immediately preceding scored attempt by default.
- Red is reserved for actual failed or negative states; verdict and direction cannot rely on color alone.
- The score card is expanded by default and must not horizontally scroll on a narrow mobile viewport.
- Raw review JSON remains in iteration history for diagnostics only.
- No client-side charting dependency may be added.

---

### Task 1: Shared structured review parser

**Files:**
- Create: `src/main/java/com/dbbaskette/issuebot/service/ui/ReviewScore.java`
- Create: `src/main/java/com/dbbaskette/issuebot/service/ui/ReviewScoreParser.java`
- Create: `src/test/java/com/dbbaskette/issuebot/service/ui/ReviewScoreParserTest.java`
- Modify: `src/main/java/com/dbbaskette/issuebot/service/ui/ApprovalCardAssembler.java:20-225`
- Modify: `src/test/java/com/dbbaskette/issuebot/controller/ApprovalControllerTest.java:260-345`

**Interfaces:**
- Produces: `ReviewScoreParser.parse(Iteration): ReviewScore`, where `ReviewScore` exposes `Boolean passed()`, nullable `Double overall()`, ordered `List<Dimension> dimensions()`, summary, findings, model, and criteria.
- Produces: compatibility accessors `specCompliance()`, `correctness()`, `codeQuality()`, `testCoverage()`, `architectureFit()`, `regressions()`, and `security()` for the approval and inbox templates.
- Consumes: persisted `Iteration.reviewPassed`, `reviewJson`, and `reviewModel`.

- [ ] **Step 1: Write parser tests for authoritative verdicts and present-only dimensions**

Create `ReviewScoreParserTest` with a failed-first/passed-second fixture and focused malformed/missing-dimension cases:

```java
class ReviewScoreParserTest {
    @Test
    void persistedVerdictWinsAndAverageOmitsMissingDimensions() {
        Iteration iteration = iteration(2, true, """
                {"passed":false,"summary":"Ready",
                 "specComplianceScore":0.96,"correctnessScore":0.94,
                 "criteria":[{"text":"Contract preserved","verdict":"met","note":""}]}
                """);

        ReviewScore score = ReviewScoreParser.parse(iteration);

        assertThat(score.passed()).isTrue();
        assertThat(score.overall()).isEqualTo(0.95);
        assertThat(score.dimensions()).extracting(ReviewScore.Dimension::key)
                .containsExactly("specCompliance", "correctness");
        assertThat(score.criteria()).hasSize(1);
    }

    @Test
    void malformedEvidenceKeepsPersistedVerdictWithoutInventingZeroScores() {
        ReviewScore score = ReviewScoreParser.parse(iteration(2, true, "not-json"));

        assertThat(score.passed()).isTrue();
        assertThat(score.overall()).isNull();
        assertThat(score.dimensions()).isEmpty();
    }

    @Test
    void evidenceWithoutPersistedVerdictIsNeutral() {
        ReviewScore score = ReviewScoreParser.parse(iteration(1, null, "not-json"));

        assertThat(score.passed()).isNull();
        assertThat(score.dimensions()).isEmpty();
    }
}
```

The test helper constructs `TrackedIssue`, `Iteration`, assigns `reviewPassed`, `reviewJson`, and `reviewModel`, and returns the iteration.

- [ ] **Step 2: Run the focused test to verify it fails**

Run: `./mvnw -q -Dtest=ReviewScoreParserTest test`

Expected: compilation fails because `ReviewScore` and `ReviewScoreParser` do not exist.

- [ ] **Step 3: Add the shared immutable score model**

Create `ReviewScore` with the exact public shape below:

```java
public record ReviewScore(
        Boolean passed,
        String summary,
        Double overall,
        List<Dimension> dimensions,
        int findingCount,
        String model,
        List<CodeReviewResult.CriterionVerdict> criteria
) {
    public record Dimension(String key, String label, double value) {}

    public double specCompliance() { return value("specCompliance"); }
    public double correctness() { return value("correctness"); }
    public double codeQuality() { return value("codeQuality"); }
    public double testCoverage() { return value("testCoverage"); }
    public double architectureFit() { return value("architectureFit"); }
    public double regressions() { return value("regressions"); }
    public double security() { return value("security"); }

    private double value(String key) {
        return dimensions.stream().filter(d -> d.key().equals(key))
                .mapToDouble(Dimension::value).findFirst().orElse(0.0);
    }
}
```

The compact constructor must defensively copy `dimensions` and `criteria` with `List.copyOf`.

- [ ] **Step 4: Implement present-only parsing**

Create `ReviewScoreParser` as a non-instantiable utility. Define the dimensions in this exact order:

```java
private static final List<DimensionDefinition> DIMENSIONS = List.of(
        new DimensionDefinition("specComplianceScore", "specCompliance", "Spec compliance"),
        new DimensionDefinition("correctnessScore", "correctness", "Correctness"),
        new DimensionDefinition("codeQualityScore", "codeQuality", "Code quality"),
        new DimensionDefinition("testCoverageScore", "testCoverage", "Test coverage"),
        new DimensionDefinition("architectureFitScore", "architectureFit", "Architecture fit"),
        new DimensionDefinition("regressionsScore", "regressions", "Regressions"),
        new DimensionDefinition("securityScore", "security", "Security")
);
```

`parse` must prefer `iteration.getReviewPassed()` over JSON's `passed`, add a dimension only when its JSON field is numeric, average only the added dimensions, parse criteria through `CriterionVerdict.lenient`, and return a verdict-only/neutral score after malformed JSON. Return `null` only when the iteration has neither a persisted verdict nor review JSON.

- [ ] **Step 5: Replace approval-card-local parsing**

Delete the nested `ApprovalCardAssembler.ReviewScore`, `MAPPER`, `parseReviewScore`, and `parseCriteria`. Import the shared `ReviewScore` and replace the call with:

```java
ReviewScore score = ReviewScoreParser.parse(it);
```

Update `ApprovalControllerTest` casts/imports from `ApprovalCardAssembler.ReviewScore` to `ReviewScore`; keep its existing criteria assertions unchanged. The existing approvals and inbox templates retain their current accessor names.

- [ ] **Step 6: Run focused parser and approval tests**

Run: `./mvnw -q -Dtest=ReviewScoreParserTest,ApprovalControllerTest,InboxControllerTest,InboxPageRenderTest test`

Expected: all selected tests pass; malformed JSON may emit one expected warning without failing the build.

- [ ] **Step 7: Commit the shared parser**

```bash
git add src/main/java/com/dbbaskette/issuebot/service/ui/ReviewScore.java \
        src/main/java/com/dbbaskette/issuebot/service/ui/ReviewScoreParser.java \
        src/main/java/com/dbbaskette/issuebot/service/ui/ApprovalCardAssembler.java \
        src/test/java/com/dbbaskette/issuebot/service/ui/ReviewScoreParserTest.java \
        src/test/java/com/dbbaskette/issuebot/controller/ApprovalControllerTest.java
git commit -m "refactor: share structured review score parsing"
```

---

### Task 2: Score-history comparison assembler

**Files:**
- Create: `src/main/java/com/dbbaskette/issuebot/service/ui/ReviewScoreHistoryAssembler.java`
- Create: `src/test/java/com/dbbaskette/issuebot/service/ui/ReviewScoreHistoryAssemblerTest.java`

**Interfaces:**
- Consumes: `ReviewScoreParser.parse(Iteration)` from Task 1 and ascending `List<Iteration>`.
- Produces: `ReviewScoreHistoryAssembler.assemble(List<Iteration>, Integer): History`.
- Produces records: `Attempt`, `DimensionDelta`, and `History`, including the selected attempt, latest attempt, previous scored attempt, newest-first attempts, deltas, overall delta, and criteria counts.

- [ ] **Step 1: Write comparison and selection tests**

Cover the approved review data and all delta modes:

```java
@Test
void latestScoredAttemptComparesWithPreviousScoredAttempt() {
    Iteration first = scored(1, false, 0.62, 0.45);
    Iteration unavailable = reviewUnavailable(2);
    Iteration latest = scored(3, true, 0.96, 0.94);

    History history = ReviewScoreHistoryAssembler.assemble(
            List.of(first, unavailable, latest), null);

    assertThat(history.selected().iterationNumber()).isEqualTo(3);
    assertThat(history.latest().score().passed()).isTrue();
    assertThat(history.previous().iterationNumber()).isEqualTo(1);
    assertThat(history.dimensions()).extracting(DimensionDelta::key, DimensionDelta::delta)
            .contains(tuple("specCompliance", 0.34), tuple("testCoverage", 0.49));
}

@Test
void requestedOlderAttemptUsesNearestEarlierScoredBaseline() {
    History history = ReviewScoreHistoryAssembler.assemble(
            List.of(scored(1, false, 0.60, 0.50),
                    scored(2, false, 0.75, 0.70),
                    scored(3, true, 0.95, 0.94)), 2);

    assertThat(history.selected().iterationNumber()).isEqualTo(2);
    assertThat(history.previous().iterationNumber()).isEqualTo(1);
    assertThat(history.attempts()).extracting(Attempt::iterationNumber)
            .containsExactly(3, 2, 1);
}
```

Also test first scored review (no prior/deltas), unchanged and negative deltas, a current-only dimension labeled `New`, current missing dimensions omitted, failed criteria sorted unmet-first, and passing criteria preserving persisted order.

- [ ] **Step 2: Run the focused test to verify it fails**

Run: `./mvnw -q -Dtest=ReviewScoreHistoryAssemblerTest test`

Expected: compilation fails because `ReviewScoreHistoryAssembler` does not exist.

- [ ] **Step 3: Implement immutable history records**

Use this public shape:

```java
public final class ReviewScoreHistoryAssembler {
    public record Attempt(int iterationNumber, Long iterationId, ReviewScore score) {
        public int overallPercent() { return percent(score.overall()); }
    }

    public record DimensionDelta(
            String key, String label, double current, Double previous, Double delta) {
        public int currentPercent() { return percent(current); }
        public Integer previousPercent() { return previous == null ? null : percent(previous); }
        public Integer deltaPoints() { return delta == null ? null : percent(delta); }
    }

    public record History(
            Attempt selected,
            Attempt latest,
            Attempt previous,
            List<Attempt> attempts,
            List<DimensionDelta> dimensions,
            Double overallDelta,
            List<CodeReviewResult.CriterionVerdict> criteria,
            long criteriaMet,
            int criteriaTotal) {
        public Integer overallDeltaPoints() {
            return overallDelta == null ? null : percent(overallDelta);
        }
    }

    private static int percent(double value) { return (int) Math.round(value * 100.0); }
}
```

Add `assemble(List<Iteration>, Integer)` with this algorithm:

```java
public static History assemble(List<Iteration> iterations, Integer requestedIteration) {
    List<Attempt> chronological = new ArrayList<>();
    for (Iteration iteration : iterations) {
        if (iteration.getReviewPassed() == null && iteration.getReviewJson() == null) continue;
        ReviewScore score = ReviewScoreParser.parse(iteration);
        if (score != null) {
            chronological.add(new Attempt(iteration.getIterationNum(), iteration.getId(), score));
        }
    }
    if (chronological.isEmpty()) return null;

    Attempt latest = chronological.getLast();
    Attempt selected = requestedIteration == null ? latest : chronological.stream()
            .filter(a -> a.iterationNumber() == requestedIteration)
            .findFirst().orElse(latest);

    Attempt previous = null;
    for (Attempt candidate : chronological) {
        if (candidate.iterationNumber() >= selected.iterationNumber()) break;
        if (candidate.score().overall() != null) previous = candidate;
    }

    Map<String, ReviewScore.Dimension> priorByKey = previous == null ? Map.of()
            : previous.score().dimensions().stream().collect(Collectors.toMap(
                    ReviewScore.Dimension::key, Function.identity()));
    List<DimensionDelta> deltas = selected.score().dimensions().stream().map(current -> {
        ReviewScore.Dimension prior = priorByKey.get(current.key());
        Double priorValue = prior == null ? null : prior.value();
        Double delta = priorValue == null ? null : current.value() - priorValue;
        return new DimensionDelta(current.key(), current.label(), current.value(), priorValue, delta);
    }).toList();

    Double overallDelta = selected.score().overall() == null || previous == null
            || previous.score().overall() == null ? null
            : selected.score().overall() - previous.score().overall();
    List<CodeReviewResult.CriterionVerdict> criteria = new ArrayList<>(selected.score().criteria());
    if (Boolean.FALSE.equals(selected.score().passed())) {
        criteria.sort(Comparator.comparingInt(c -> "unmet".equals(c.verdict()) ? 0 : 1));
    }
    long met = criteria.stream().filter(c -> "met".equals(c.verdict())).count();
    List<Attempt> newestFirst = new ArrayList<>(chronological);
    Collections.reverse(newestFirst);
    return new History(selected, latest, previous, List.copyOf(newestFirst), deltas,
            overallDelta, List.copyOf(criteria), met, criteria.size());
}
```

- [ ] **Step 4: Run the assembler test**

Run: `./mvnw -q -Dtest=ReviewScoreHistoryAssemblerTest test`

Expected: all score selection, delta, criteria-order, and missing-dimension tests pass.

- [ ] **Step 5: Commit the history assembler**

```bash
git add src/main/java/com/dbbaskette/issuebot/service/ui/ReviewScoreHistoryAssembler.java \
        src/test/java/com/dbbaskette/issuebot/service/ui/ReviewScoreHistoryAssemblerTest.java
git commit -m "feat: assemble review score comparisons"
```

---

### Task 3: Accurate issue-detail state and score-history model

**Files:**
- Modify: `src/main/java/com/dbbaskette/issuebot/controller/IssueController.java:208-226,971-1062`
- Modify: `src/test/java/com/dbbaskette/issuebot/controller/IssueControllerTest.java`
- Modify: `src/test/java/com/dbbaskette/issuebot/controller/IssueDetailPlanReviewRenderTest.java`

**Interfaces:**
- Consumes: `ReviewScoreHistoryAssembler.History` from Task 2.
- Produces model attributes: `reviewScoreHistory` and boolean `showPlanGuidance`.
- Accepts optional query parameter `reviewAttempt` as an integer iteration number.

- [ ] **Step 1: Add controller tests for history selection and passing-second-review gating**

Update direct `detail` calls to include the new fourth method argument before `hx`, then add:

```java
@Test
void passingSecondReviewBuildsHistoryWithoutGuidance() {
    Fixture f = approvedPlanFixture(IssueStatus.AWAITING_APPROVAL, 2);
    Iteration first = review(f.issue, 1, false, 0.62, 0.45);
    Iteration second = review(f.issue, 2, true, 0.96, 0.94);
    when(f.iterationRepository.findByIssueOrderByIterationNumAsc(f.issue))
            .thenReturn(List.of(first, second));

    Model model = new ExtendedModelMap();
    f.controller.detail(model, 1L, null, null, null);

    History history = (History) model.getAttribute("reviewScoreHistory");
    assertThat(history.selected().score().passed()).isTrue();
    assertThat(model.getAttribute("showPlanGuidance")).isEqualTo(false);
}

@Test
void requestedReviewAttemptSelectsOlderComparison() {
    Fixture f = approvedPlanFixture(IssueStatus.AWAITING_APPROVAL, 3);
    when(f.iterationRepository.findByIssueOrderByIterationNumAsc(f.issue)).thenReturn(List.of(
            review(f.issue, 1, false, 0.60, 0.50),
            review(f.issue, 2, false, 0.75, 0.70),
            review(f.issue, 3, true, 0.95, 0.94)));
    Model model = new ExtendedModelMap();

    f.controller.detail(model, 1L, null, "2", null);

    assertThat(((History) model.getAttribute("reviewScoreHistory"))
            .selected().iterationNumber()).isEqualTo(2);
}
```

Retain and strengthen the existing genuine-second-miss controller test to assert `showPlanGuidance == true` only for `FAILED` or `COOLDOWN` plus latest `reviewPassed == false`.

- [ ] **Step 2: Run the controller test to verify failure**

Run: `./mvnw -q -Dtest=IssueControllerTest test`

Expected: compilation/signature failure until the controller accepts `reviewAttempt`; the new model assertions fail until history is assembled.

- [ ] **Step 3: Populate explicit review history and guidance eligibility**

Change the endpoint signature to:

```java
public String detail(Model model, @PathVariable Long id,
                     @RequestParam(required = false) String planVersion,
                     @RequestParam(required = false) String reviewAttempt,
                     @RequestHeader(value = "HX-Request", required = false) String hx)
```

Parse `reviewAttempt` with the same invalid-input-to-null behavior used for `planVersion`. Pass it into `populateDetailModel`. After loading iterations:

```java
History reviewHistory = ReviewScoreHistoryAssembler.assemble(iterations, requestedReviewAttempt);
model.addAttribute("reviewScoreHistory", reviewHistory);
model.addAttribute("showPlanGuidance", shouldShowPlanGuidance(
        issue, currentPlanningVersion, selectedPlanIsHistorical, reviewHistory));
```

Have `populatePlanReviewModel` return a small private `PlanReviewSelection(current, selected, historical)` record so the guidance check uses the exact selected/current plan state without reading model internals.

Implement `shouldShowPlanGuidance` with all approved gates:

```java
return (issue.getStatus() == IssueStatus.FAILED || issue.getStatus() == IssueStatus.COOLDOWN)
        && issue.getPlanConformanceAttempt() == 2
        && selection.current() != null
        && selection.current().getState() == PlanningVersionState.APPROVED
        && !selection.historical()
        && history != null
        && Boolean.FALSE.equals(history.latest().score().passed());
```

Populate `planReviewAttempts` from the latest review-bearing attempts whenever history exists, not only when the counter equals two, because badges and diagnostics now reflect real attempts.

- [ ] **Step 4: Render attempt badges from persisted verdicts**

In `issue-detail.html`, change the guidance panel condition to `th:if="${showPlanGuidance}"`. Replace the hard-coded attempt badge with a three-state expression and accessible copy:

```html
<span class="status"
      th:classappend="${attempt.reviewPassed == null ? 'status-pending' : (attempt.reviewPassed ? 'status-completed' : 'status-failed')}"
      th:text="${attempt.reviewPassed == null ? 'Review unavailable' : (attempt.reviewPassed ? 'Conformed' : 'Did not conform')}">
    Review unavailable
</span>
```

Use `showPlanGuidance` in the header retry button and generic recovery-panel suppression conditions too, replacing their attempt-counter-only expressions.

- [ ] **Step 5: Add the reported regression render test**

Extend the render-test context with `reviewScoreHistory` and `showPlanGuidance`. Add a failed review 1 and passed review 2 fixture containing the real score pattern. Assert:

```java
assertThat(html).contains("Conforms to plan")
        .contains("PASSED")
        .contains("Spec compliance")
        .contains("+34")
        .doesNotContain("Needs guidance after review 2")
        .doesNotContain("Action required");
```

Update the existing second-miss render test to set `showPlanGuidance=true` and assert both attempt badges say `Did not conform`.

- [ ] **Step 6: Run state and render tests**

Run: `./mvnw -q -Dtest=IssueControllerTest,IssueDetailPlanReviewRenderTest test`

Expected: all tests pass, including the failed-first/passed-second regression.

- [ ] **Step 7: Commit accurate issue state**

```bash
git add src/main/java/com/dbbaskette/issuebot/controller/IssueController.java \
        src/main/resources/templates/issue-detail.html \
        src/test/java/com/dbbaskette/issuebot/controller/IssueControllerTest.java \
        src/test/java/com/dbbaskette/issuebot/controller/IssueDetailPlanReviewRenderTest.java
git commit -m "fix: derive conformance guidance from latest verdict"
```

---

### Task 4: Expanded score-trajectory card

**Files:**
- Modify: `src/main/resources/templates/issue-detail.html:100-200`
- Modify: `src/main/resources/static/css/style.css:1918-1940` and responsive sections
- Modify: `src/test/java/com/dbbaskette/issuebot/controller/IssueDetailPlanReviewRenderTest.java`

**Interfaces:**
- Consumes: `reviewScoreHistory` fields and percentage helpers from Tasks 2 and 3.
- Produces: server-rendered review-attempt selector URLs using `reviewAttempt`, trajectory rails, textual deltas, and expandable criteria.

- [ ] **Step 1: Add render assertions for the complete card**

Add tests for pass/fail/neutral verdict copy, first scored review, positive/negative/unchanged/new deltas, selector links, criteria counts, unmet-first criteria, and ARIA labels. The main comparison assertion must include:

```java
assertThat(html).contains("Implementation review")
        .contains("Conforms to plan")
        .contains("95%")
        .contains("22 points from review 1")
        .contains("Test coverage")
        .contains("94%")
        .contains("+49")
        .contains("4 of 4 met")
        .contains("aria-label=\"Test coverage: review 1 45 percent; review 2 94 percent; improved 49 points\"")
        .contains("reviewAttempt=1");
```

- [ ] **Step 2: Run the render test to verify the new assertions fail**

Run: `./mvnw -q -Dtest=IssueDetailPlanReviewRenderTest test`

Expected: the new score-card content and trajectory classes are absent.

- [ ] **Step 3: Render the expanded trajectory card**

Insert the card after failure/recovery notices and before the plan-review desk. Use semantic elements and these stable classes:

```html
<section th:if="${reviewScoreHistory != null}" th:with="history=${reviewScoreHistory}"
         class="panel mb-3 review-history-card" aria-labelledby="review-history-title">
  <div class="panel-header review-history-header">
    <div>
      <span class="eyebrow" th:text="${'Implementation review · Attempt ' + history.selected.iterationNumber}">Implementation review</span>
      <h3 id="review-history-title"
          th:text="${history.selected.score.passed == null ? 'Review unavailable' : (history.selected.score.passed ? 'Conforms to plan' : 'Did not conform')}">Review result</h3>
    </div>
    <span class="status" th:text="${history.selected.score.passed == null ? 'Unavailable' : (history.selected.score.passed ? 'Passed' : 'Changes requested')}">Result</span>
  </div>
  <div class="panel-body">
    <div class="review-score-overview" th:if="${history.selected.score.overall != null}">
      <strong th:text="${history.selected.overallPercent + '%'}">95%</strong>
      <span th:if="${history.previous == null}">First scored review</span>
      <span th:if="${history.previous != null and history.overallDeltaPoints != 0}"
            th:text="${#numbers.formatInteger(history.overallDeltaPoints, 1) + ' points from review ' + history.previous.iterationNumber}">22 points from review 1</span>
      <span th:if="${history.previous != null and history.overallDeltaPoints == 0}">No overall change</span>
    </div>
    <nav th:if="${history.attempts.size() > 1}" class="review-attempt-selector" aria-label="Review attempts">
      <a th:each="attempt : ${history.attempts}"
         th:href="@{'/issues/' + ${issue.id} + '?reviewAttempt=' + ${attempt.iterationNumber} + '#review-history'}"
         th:attr="aria-current=${attempt.iterationNumber == history.selected.iterationNumber ? 'true' : null}"
         th:text="${'Review ' + attempt.iterationNumber}">Review 2</a>
    </nav>
    <div class="review-dimension-grid">
      <div th:each="dimension : ${history.dimensions}" class="review-dimension">
        <div class="flex-between"><strong th:text="${dimension.label}">Test coverage</strong><span th:text="${dimension.currentPercent + '%'}">94%</span></div>
        <div class="review-score-rail" role="img" th:attr="aria-label=${dimension.label + ': current ' + dimension.currentPercent + ' percent'}">
          <span th:if="${dimension.previousPercent != null}" class="review-score-previous" th:style="${'width:' + dimension.previousPercent + '%'}"></span>
          <span class="review-score-current" th:style="${'width:' + dimension.currentPercent + '%'}"><i class="review-score-marker"></i></span>
        </div>
        <span class="review-score-delta" th:text="${dimension.deltaPoints == null ? 'New' : (dimension.deltaPoints == 0 ? 'No change' : (dimension.deltaPoints > 0 ? '+' : '') + dimension.deltaPoints)}">+49</span>
      </div>
    </div>
    <details th:if="${history.criteriaTotal > 0}" open>
      <summary class="review-criteria-summary" th:text="${history.criteriaMet + ' of ' + history.criteriaTotal + ' met'}">4 of 4 met</summary>
      <ul><li th:each="criterion : ${history.criteria}" th:text="${criterion.text}">Criterion</li></ul>
    </details>
  </div>
</section>
```

Complete the markup above so that it also:

- shows overall percentage only when non-null;
- says `First scored review`, `N points from review X`, or `No overall change`;
- renders newest-first attempt links while preserving `planVersion` when present;
- prints signed delta text and `New`/`No change` states;
- sets rail widths from `currentPercent` and `previousPercent` with bounded server-produced percentages;
- supplies the full textual `aria-label` for each trajectory;
- shows model metadata and finding count when available; and
- renders criteria inside an expanded `<details open>` element with unmet-first order supplied by the assembler.

- [ ] **Step 4: Add responsive trajectory styling**

Extend `style.css` using existing tokens. Define `.review-history-card`, `.review-score-overview`, `.review-attempt-selector`, `.review-dimension-grid`, `.review-dimension`, `.review-score-rail`, `.review-score-previous`, `.review-score-current`, `.review-score-marker`, `.review-score-delta`, and `.review-criteria-summary`.

Use `var(--info)` for the current rail, `var(--text-tertiary)` for the prior rail, `var(--ok)` for positive deltas, and `var(--danger)` for negative deltas. At widths below `700px`, force one dimension per row, make selector controls wrap, and prevent overflow with `min-width:0` and `overflow-wrap:anywhere`. Do not add animation.

- [ ] **Step 5: Run render regression tests**

Run: `./mvnw -q -Dtest=IssueDetailPlanReviewRenderTest test`

Expected: all selected tests pass; confirm the stylesheet is served in Task 5's live check.

- [ ] **Step 6: Commit the trajectory UI**

```bash
git add src/main/resources/templates/issue-detail.html \
        src/main/resources/static/css/style.css \
        src/test/java/com/dbbaskette/issuebot/controller/IssueDetailPlanReviewRenderTest.java
git commit -m "feat: show review score trajectory on issues"
```

---

### Task 5: Full verification and mobile visual check

**Files:**
- No planned source changes; any correction must be limited to the exact files listed in Tasks 1-4.

**Interfaces:**
- Consumes: the completed shared parser, history assembler, controller state, template, and CSS.
- Produces: verified issue-detail behavior at desktop and mobile widths with no regressions in approval/inbox rendering.

- [ ] **Step 1: Run the complete test suite**

Run: `./mvnw test`

Expected: `BUILD SUCCESS` with zero failures and zero errors.

- [ ] **Step 2: Build the runnable artifact**

Run: `./mvnw -q -DskipTests package`

Expected: exit code 0 and `target/issuebot-0.1.0-SNAPSHOT.jar` exists.

- [ ] **Step 3: Start the verified build using the existing local launch procedure**

Stop and replace only the existing IssueBot launchd job, preserving `.env` and the Codex CLI path:

```bash
launchctl remove com.dbbaskette.issuebot
launchctl submit -l com.dbbaskette.issuebot -- /bin/zsh -lc 'cd /Users/dbbaskette/Projects/IssueBot; set -a; source .env; set +a; export PATH=/Applications/ChatGPT.app/Contents/Resources:/Users/dbbaskette/.local/share/mise/installs/java/21.0.2/bin:/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin; exec /Users/dbbaskette/.local/share/mise/installs/java/21.0.2/bin/java -jar target/issuebot-0.1.0-SNAPSHOT.jar >/Users/dbbaskette/.issuebot/logs/issuebot-launchd.log 2>&1'
curl --fail --silent http://localhost:8090/actuator/health
```

Expected: `GET http://localhost:8090/actuator/health` returns `{"status":"UP"}`.

- [ ] **Step 4: Verify the reported issue in the browser**

Open the affected issue page and inspect at a desktop width and approximately 390px mobile width. Confirm:

- review 2 reads `Conforms to plan` and `Passed`;
- the red action-required guidance panel is absent;
- review 1 and review 2 rails and signed deltas match persisted values;
- criteria expand without horizontal scrolling;
- selecting an older attempt updates the URL and comparison;
- raw JSON remains available only in iteration history; and
- approvals/inbox still show the same latest review score and verdict.

- [ ] **Step 5: Inspect the final diff and repository state**

Run: `git diff --check && git status --short && git log --oneline -6`

Expected: no whitespace errors and a clean worktree unless the browser check found a correction that has not yet been committed.

- [ ] **Step 6: Commit any verification-only fixes**

If Step 4 required a correction, rerun the affected focused test and the full suite, then commit only that correction:

```bash
git add src/main/java/com/dbbaskette/issuebot/service/ui/ReviewScore.java \
        src/main/java/com/dbbaskette/issuebot/service/ui/ReviewScoreParser.java \
        src/main/java/com/dbbaskette/issuebot/service/ui/ReviewScoreHistoryAssembler.java \
        src/main/java/com/dbbaskette/issuebot/service/ui/ApprovalCardAssembler.java \
        src/main/java/com/dbbaskette/issuebot/controller/IssueController.java \
        src/main/resources/templates/issue-detail.html \
        src/main/resources/static/css/style.css \
        src/test/java/com/dbbaskette/issuebot/service/ui/ReviewScoreParserTest.java \
        src/test/java/com/dbbaskette/issuebot/service/ui/ReviewScoreHistoryAssemblerTest.java \
        src/test/java/com/dbbaskette/issuebot/controller/ApprovalControllerTest.java \
        src/test/java/com/dbbaskette/issuebot/controller/IssueControllerTest.java \
        src/test/java/com/dbbaskette/issuebot/controller/IssueDetailPlanReviewRenderTest.java
git commit -m "fix: polish review score history"
```

If no correction was needed, do not create an empty commit.

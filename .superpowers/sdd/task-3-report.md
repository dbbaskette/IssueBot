# Task 3 report: accurate issue-detail state and score-history model

## Files changed

- `src/main/java/com/dbbaskette/issuebot/controller/IssueController.java` — accepts and parses `reviewAttempt`, assembles review score history, exposes `reviewScoreHistory` and explicit `showPlanGuidance`, returns a typed plan selection, and loads the latest review-bearing attempts whenever history exists.
- `src/main/resources/templates/issue-detail.html` — uses `showPlanGuidance` for the guidance panel and both recovery suppression conditions, and renders persisted failed/passed/unavailable attempt badges.
- `src/test/java/com/dbbaskette/issuebot/controller/IssueControllerTest.java` — updates direct detail calls and covers passing review 2, historical review-attempt selection, status gating, and history-backed attempt loading independent of the counter.
- `src/test/java/com/dbbaskette/issuebot/controller/IssueDetailPlanReviewRenderTest.java` — covers the passing-second regression, normal recovery restoration, genuine second-miss guidance, and three-state persisted-verdict badges.

## RED evidence

1. `./mvnw -q -Dtest=IssueControllerTest test` exited 1 at test compilation after the tests were changed first. Every direct `detail(...)` call reported that the existing four-argument controller signature could not accept the new optional `reviewAttempt` argument.
2. The first full `./mvnw -q test` run exited 1 with 10 template errors in standalone issue-detail render fixtures. The errors consistently identified boolean coercion of an absent `showPlanGuidance` variable in the new retry/recovery condition. This exposed the need for null-safe `== true` / `!= true` expressions.

## GREEN evidence

1. `./mvnw -q -Dtest=IssueControllerTest,IssueDetailPlanReviewRenderTest test` exited 0 after the controller and template implementation.
2. `./mvnw -q -Dtest=IssueControllerTest,IssueDetailPlanReviewRenderTest,IssueDetailGoalCardRenderTest,IssueDetailLayoutRenderTest,IssueDetailTimelineRenderTest test` exited 0 after the null-safe template correction.
3. A fresh final `./mvnw -q test` exited 0 for the full Maven suite.
4. `git diff --check` exited 0.

The suite emits existing non-failing Mockito self-attachment, Flyway/H2 compatibility, and intentional failure-path logging warnings.

## Commit

`fix: derive conformance guidance from latest verdict` (this task commit)

## Self-review

- Guidance is true only for `FAILED`/`COOLDOWN`, conformance attempt 2, an approved current version, the current rather than historical plan selection, non-null history, and a persisted failed latest verdict.
- A passing or unavailable latest verdict cannot show the guidance panel or suppress normal recovery, even if the attempt counter remains 2.
- Requested review-attempt parsing shares plan-version's blank/malformed/overflow-to-null behavior; unknown scored attempts fall back to latest in the Task 2 assembler.
- The controller uses the returned `PlanReviewSelection` rather than reading model attributes back, and history assembly consumes the repository's ascending iteration list.
- Attempt diagnostics are populated from the latest two review-bearing rows whenever parsed history exists, independent of `planConformanceAttempt`.
- Badge text and class derive independently from each row's persisted `reviewPassed` value: `Did not conform`, `Conformed`, or `Review unavailable`.
- No Task 4 score-trajectory card markup, CSS, or score/delta render assertions were added.

## Concerns

- Existing standalone render contexts may omit the new model attribute, so template expressions intentionally treat missing `showPlanGuidance` as false for backward-compatible fragment testing.
- Independent review noted that an inconsistent `FAILED`/`COOLDOWN` issue with a stale attempt counter of 2 and a latest passing verdict now exposes normal recovery in the view, while the existing authoritative retry service still rejects any Plan First counter-2 retry. This task preserves that pre-existing guided-retry dispatch contract because the approved Task 3 boundary covers issue-detail model/render suppression; changing locked retry eligibility would require a separate workflow/dispatch design decision and tests.

## Review-fix follow-up

### Files changed

- `PlanRetryClassification.java` centralizes second-miss and guided-retry classification around the latest review-bearing row's persisted verdict. It uses the greatest persistence ID, so retry runs that reuse an iteration number are still ordered deterministically.
- `IssueController.java`, `IssueDispatchTransactionManager.java`, and the legacy `IssueDispatchService.java` now use that shared classification. The transactional path reads iterations after preserving the pause, issue, and repository locks.
- Controller, dispatch, persistence, polling, and integration fixtures now provide `IterationRepository` and cover explicit failed, later passed, JSON-only failed, guided retry, generic retry, and duplicate-iteration-number cases.
- `IssueDetailPlanReviewRenderTest.java` now pairs every badge label with its exact status class; `PlanRetryClassificationTest.java` directly covers persistence ordering independent of input order.

### RED evidence

1. `./mvnw -q -Dtest=IssueControllerTest,IssueDetailPlanReviewRenderTest,IssueDispatchTransactionManagerTest,IssueDispatchServiceTest test` first exited 1 at test compilation because the new legacy dispatch fixture required an iteration repository before its constructor existed.
2. The first full `./mvnw -q test` after the core implementation exited 1 because the persistence guided-retry fixture had no explicit persisted failed review, demonstrating that the new eligibility rule no longer accepted the counter alone.
3. Independent review found that reverse-scanning iteration-number order was ambiguous when retry runs reused an iteration number. The new `PlanRetryClassificationTest` then exited 1 because an older failed row could override a newer passing row when input tie order was reversed.

### GREEN evidence

1. `./mvnw -q -Dtest=IssueControllerTest,IssueDetailPlanReviewRenderTest,IssueDispatchTransactionManagerTest,IssueDispatchServiceTest,IssueDispatchServicePersistenceTest,IssuePollingServiceTest,IntegrationWorkflowTest test` exited 0 after aligning all controller and dispatch paths.
2. `./mvnw -q -Dtest=PlanRetryClassificationTest,IssueDispatchTransactionManagerTest test` exited 0 after selecting the latest review-bearing row by persistence ID and exercising duplicate iteration numbers.
3. A fresh final `./mvnw -q test` exited 0 for the full Maven suite.
4. `git diff --check` exited 0.

### Commit

`fix: align guided retry with persisted verdict` (review-fix commit)

### Self-review

- Only an explicit persisted `reviewPassed == false` classifies the second miss. A JSON fallback with a null persisted verdict is neutral, and a latest persisted pass allows generic retry.
- UI guidance, controller retry validation, the locked transactional dispatch path, and the legacy dispatch path all call the same classifier.
- The latest review-bearing row is selected by greatest persistence ID, not by iteration number, because guided/manual retry runs can reuse iteration numbers.
- Pause checking, issue locking, repository locking, repository serialization, and guided guidance/reset atomicity remain intact.
- Badge assertions verify the exact text/class pairs for failed, passed, and unavailable verdicts.

### Concerns

- The authoritative retry mismatch recorded in the original report is resolved. No remaining concerns were identified after the ordering review fix.

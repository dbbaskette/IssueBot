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

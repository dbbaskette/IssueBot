# Task 5 report — comparable review changes (#136)

## Result

Implemented exact review-change comparison without changing the existing review verdict policy.
New iterations persist immutable nullable workflow-run and approved-plan identity snapshots;
legacy rows remain unknown and cannot be compared against mutable current issue state. The review
parser now preserves criterion source IDs, full finding identity data, and absent-versus-empty
collection state. History selects the prior completed scored review only within the same persisted
issue/run/plan identity, names skipped attempts, and leaves unavailable data neutral.

The issue-detail review card now leads with a compact verdict/overall change sentence and uses
state-preserving native disclosure groups for dimension, criterion, and finding changes. All model
text is rendered through escaped Thymeleaf text bindings.

## Verification

- `./mvnw -q -Dtest=ReviewScoreParserTest,ReviewChangeAssemblerTest,ReviewScoreHistoryAssemblerTest,IterationReviewSnapshotPersistenceTest test`
  - Exit 0 after correcting one test assertion type; 34 tests passed, 0 failures/errors/skips.
- `./mvnw -q -Dtest=IssueDetailPlanReviewRenderTest,ReviewScoreResponsiveCssTest,ReviewScoreParserTest,ReviewChangeAssemblerTest,ReviewScoreHistoryAssemblerTest test`
  - Exit 0 after correcting the expected apostrophe HTML entity in the XSS assertion; 60 tests passed, 0 failures/errors/skips.
- `./mvnw -q -Dtest=ReviewScoreParserTest,ReviewChangeAssemblerTest,ReviewScoreHistoryAssemblerTest,IterationReviewSnapshotPersistenceTest,IterationManagerTest,IssueDetailPlanReviewRenderTest,ReviewScoreResponsiveCssTest test`
  - Final focused verification: exit 0; 90 tests passed, 0 failures/errors/skips.
- `git diff --check`
  - Exit 0; no whitespace errors.

## Files

- `.superpowers/sdd/2026-09-11-operator-flow/task-5-report.md`
- `src/main/java/com/dbbaskette/issuebot/model/Iteration.java`
- `src/main/java/com/dbbaskette/issuebot/service/ui/ReviewChangeAssembler.java`
- `src/main/java/com/dbbaskette/issuebot/service/ui/ReviewChanges.java`
- `src/main/java/com/dbbaskette/issuebot/service/ui/ReviewScore.java`
- `src/main/java/com/dbbaskette/issuebot/service/ui/ReviewScoreHistoryAssembler.java`
- `src/main/java/com/dbbaskette/issuebot/service/ui/ReviewScoreParser.java`
- `src/main/java/com/dbbaskette/issuebot/service/workflow/IssueWorkflowService.java`
- `src/main/resources/db/migration/V42__iteration_review_identity_snapshots.sql`
- `src/main/resources/static/css/style.css`
- `src/main/resources/templates/issue-detail.html`
- `src/test/java/com/dbbaskette/issuebot/controller/IssueDetailPlanReviewRenderTest.java`
- `src/test/java/com/dbbaskette/issuebot/controller/ReviewScoreResponsiveCssTest.java`
- `src/test/java/com/dbbaskette/issuebot/repository/IterationReviewSnapshotPersistenceTest.java`
- `src/test/java/com/dbbaskette/issuebot/service/ui/ReviewChangeAssemblerTest.java`
- `src/test/java/com/dbbaskette/issuebot/service/ui/ReviewScoreHistoryAssemblerTest.java`
- `src/test/java/com/dbbaskette/issuebot/service/ui/ReviewScoreParserTest.java`

## Self-review and concerns

- ReviewOutcome/PersistedReviewOutcome remains the only verdict authority; no second parser,
  classifier, fuzzy matcher, model call, or external integration was added.
- Finding identity is the normalized exact category/path/text tuple; line is deliberately excluded.
  Criterion identity prefers a preserved stable source ID and otherwise uses trim/collapsed-space/
  case-folded exact text. Missing identity parts and duplicates are explicitly not comparable.
- V42 intentionally does not backfill legacy rows. A non-null run plus null plan means a known
  no-plan run; null run means unknown legacy identity regardless of the current TrackedIssue.
- Task 7 must allocate V43 or later. The full combined Java/JavaScript suite and desktop/phone,
  light/dark browser evidence remain for the coordinated final verification task as directed.
- No version/changelog change was made because the approved design groups all tasks into one
  coordinated feature release.

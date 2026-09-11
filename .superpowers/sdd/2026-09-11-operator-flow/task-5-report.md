# Task 5 report — comparable review changes (#136)

## Result

Implemented exact review-change comparison without changing the existing review verdict policy.
New iterations persist immutable nullable workflow-run and approved-plan identity snapshots;
legacy rows remain unknown and cannot be compared against mutable current issue state. The review
parser now preserves criterion source IDs, full finding identity data, and absent-versus-empty
collection state. History selects the prior completed usable review only within the same persisted
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

## Review fix round 1 — exact reuse identity and scoreless structured evidence

### Result

- Added one shared `Iteration.matchesAttemptIdentity(...)` guard. The crash-rearm workflow,
  ordinary iteration claim, and plan-correction claim now reuse an incomplete row only when both
  immutable workflow-run and approved-plan snapshots match exactly. A known no-plan row matches
  only the same known run with no plan; a legacy row with an unknown run never matches.
- When the newest incomplete row has a different run or plan, the claim paths create a new row
  carrying the current immutable snapshots, including when retrying the current iteration number.
- Review history now selects and baselines authoritative PASSED/FAILED reviews with usable
  structured evidence even when all numeric scores are absent. Numeric deltas remain null.
  Authoritative verdicts with no score, dimension, criterion collection, or finding collection
  remain in history but do not replace the latest usable default selection or become baselines.

### Verification

- Red reproduction:
  `./mvnw -q -Dtest=ReviewScoreHistoryAssemblerTest,ReviewChangeAssemblerTest test`
  - Exit 1; 27 tests ran with 1 expected failure: the scoreless completed structured review was
    not selected (`expected: 20`, `actual: 10`).
- Implementation milestone:
  `./mvnw -q -Dtest=ReviewScoreHistoryAssemblerTest,ReviewChangeAssemblerTest,IterationManagerTest,IssueWorkflowServiceTest test`
  - Exit 0; all selected tests passed.
- Focused render and persistence milestone:
  `./mvnw -q -Dtest=ReviewScoreHistoryAssemblerTest,ReviewChangeAssemblerTest,IterationManagerTest,IssueWorkflowServiceTest,IterationReviewSnapshotPersistenceTest,IssueDetailPlanReviewRenderTest,ReviewScoreResponsiveCssTest test`
  - Exit 0; all selected tests passed.
- Workflow recovery milestone:
  `./mvnw -q -Dtest=CorrectionClaimTransactionTest,DecisionProducerIntegrationTest,IntegrationWorkflowTest,IterationRepositoryCurrentRowTest test`
  - Exit 0; all selected tests passed.
- Final focused verification:
  `./mvnw -q -Dtest=ReviewScoreParserTest,ReviewScoreHistoryAssemblerTest,ReviewChangeAssemblerTest,IterationManagerTest,IssueWorkflowServiceTest,CorrectionClaimTransactionTest,IterationReviewSnapshotPersistenceTest,IssueDetailPlanReviewRenderTest,ReviewScoreResponsiveCssTest,IntegrationWorkflowTest,DecisionProducerIntegrationTest,IterationRepositoryCurrentRowTest test`
  - Exit 0; 247 tests passed, 0 failures, 0 errors, 0 skipped.
- `git diff --check`
  - Exit 0; no whitespace errors.

### Fix-round files

- `.superpowers/sdd/2026-09-11-operator-flow/task-5-report.md`
- `src/main/java/com/dbbaskette/issuebot/model/Iteration.java`
- `src/main/java/com/dbbaskette/issuebot/service/ui/ReviewScoreHistoryAssembler.java`
- `src/main/java/com/dbbaskette/issuebot/service/workflow/IssueWorkflowService.java`
- `src/main/java/com/dbbaskette/issuebot/service/workflow/IterationManager.java`
- `src/test/java/com/dbbaskette/issuebot/controller/IssueDetailPlanReviewRenderTest.java`
- `src/test/java/com/dbbaskette/issuebot/repository/IterationReviewSnapshotPersistenceTest.java`
- `src/test/java/com/dbbaskette/issuebot/service/ui/ReviewChangeAssemblerTest.java`
- `src/test/java/com/dbbaskette/issuebot/service/ui/ReviewScoreHistoryAssemblerTest.java`
- `src/test/java/com/dbbaskette/issuebot/service/workflow/IssueWorkflowServiceTest.java`
- `src/test/java/com/dbbaskette/issuebot/service/workflow/IterationManagerTest.java`

### Fix-round self-review and concerns

- All three reuse decisions delegate to the same exact snapshot predicate; none infer identity from
  mutable issue values for legacy rows.
- The history eligibility change uses the existing persisted outcome and parser evidence flags; it
  introduces no second verdict policy and does not manufacture zero scores or resolved findings.
- Existing exact-identity reuse remains covered by the crash-rearm test and transactional retry
  integration test. New tests cover changed run, changed plan, legacy unknown versus known no-plan,
  scoreless complete collections, no-evidence classification, null deltas, and server rendering.
- No unresolved fix-round concern. The full combined suite and broad browser verification remain
  assigned to the coordinated final verification task.

## Review fix round 2 — post-implementation recovery identity

### Result

- The persisted current iteration used by LOCAL_CHECKS, CI, PR, review, or completion recovery is
  now filtered through the same exact immutable workflow-run and approved-plan snapshot predicate
  as every other reuse path.
- A mismatched run, mismatched plan, or legacy-unknown row clears checkpoint recovery and enters
  the ordinary next-iteration claim and implementation path. Its diff, verification state, CI
  result, and review evidence are never reused.
- A matching snapshot retains the existing recovery behavior and resumes after implementation.

### Verification

- First covering run:
  `./mvnw -q -Dtest=IssueWorkflowServiceTest,IntegrationWorkflowTest,CorrectionClaimTransactionTest,IterationReviewSnapshotPersistenceTest test`
  - Exit 1 at compilation because reassigned workflow locals could not be captured by the filter
    lambda. The filter now captures immutable scalar run and plan IDs.
- Same covering command after correction:
  `./mvnw -q -Dtest=IssueWorkflowServiceTest,IntegrationWorkflowTest,CorrectionClaimTransactionTest,IterationReviewSnapshotPersistenceTest test`
  - Exit 0; 132 tests passed, 0 failures, 0 errors, 0 skipped.
- `git diff --check`
  - Exit 0; no whitespace errors.

### Fix-round files

- `.superpowers/sdd/2026-09-11-operator-flow/task-5-report.md`
- `src/main/java/com/dbbaskette/issuebot/service/workflow/IssueWorkflowService.java`
- `src/test/java/com/dbbaskette/issuebot/service/workflow/IntegrationWorkflowTest.java`

### Fix-round self-review and concerns

- The regression executes the full workflow boundary for all three mismatch identities, asserts a
  fresh iteration is claimed and implemented, and asserts the stale row never reaches independent
  review. The existing matching recovery regression now makes its identity precondition explicit
  and still asserts implementation is not repeated.
- Resetting the durable phase to SETUP before the fresh claim prevents another restart from
  treating the rejected checkpoint as resumable. The existing recovery checkout is preserved for
  this invocation; no stale iteration evidence crosses into the new implementation.
- No score-history behavior was changed in this round. No unresolved scoped concern remains.

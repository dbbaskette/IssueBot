# Final Sequential Plan Reset Fixes

- Date: 2026-07-22
- Branch: `codex/sequential-plan-reset`
- Baseline: `f04096a`
- Implementation commit: `1be28e9`

## Outcome

All five final-review findings were addressed. The focused regression set and a fresh full Maven suite pass, the staged diff passed Git whitespace validation, and an independent review found no remaining actionable correctness issue.

## Finding Disposition

1. **Canonical repository-first mutation locking**
   - Audited every production caller of `TrackedIssueRepository.findByIdForPlanning` and `findByIdForDispatch`.
   - Mutation paths now resolve the repository ID with a non-locking scalar query, acquire `WatchedRepoRepository.findByIdForUpdate`, and only then execute the joined pessimistic issue query.
   - Updated Plan First generation/revision, workflow checkpoints, and approval decisions. The existing dispatch transaction manager already followed the required order.
   - Compatibility constructors remain source-compatible but fail closed before mutation when the repository dependency is absent.
   - Added deterministic H2 transaction races covering approval versus generation, revision, and implementation checkpoint.

2. **Atomic repository-first deletion**
   - Added `RepositoryDeletionTransactionManager` as the transactional deletion boundary.
   - Deletion locks the repository first, then issues, and removes dependent events, approval references, planning versions, costs, iterations, issues, and finally the repository with explicit flush boundaries.
   - Added deterministic H2 delete-versus-approval and delete-versus-ready-start races. Both converge without SQLSTATE `40001` and preserve a valid deterministic state.

3. **Inline Plan First errors**
   - Known ordering and protected-reservation failures now retain the exact message in `planError` and redirect to the deep `#plan-first` anchor.
   - The issue template renders the exact message inline at that section.
   - Stale-form handling and sanitization of unknown failures remain unchanged.

4. **Deterministic reservation ownership**
   - Dashboard and issue-detail owner maps now choose the lowest issue number, with ID as a stable tie-breaker, independently of input order.
   - Tests cover both input orders and assert the exact owner copy and link.

5. **Natural post-commit invalidation event**
   - Added a real Spring-proxied persistence test proving `PLAN_INVALIDATED` is stored exactly once and dispatched only after the approval transaction commits.

## TDD Evidence

- Canonical lock-order and fail-closed tests first failed 7/7, then passed 7/7 after implementation.
- Repository deletion-order test first failed 1/1, then passed with the completed deletion persistence class.
- Inline Plan First error tests first failed 3 of 5, then passed 5/5.
- Reservation-owner map tests first failed 2/2, then passed 2/2.
- Each new H2 concurrency regression and the proxied post-commit event regression passed independently before aggregation.

## Verification

- Focused aggregate:
  - Command: `./mvnw -Dtest=PlanFirstTransactionManagerTest,WorkflowCheckpointTransactionManagerTest,ApprovalDecisionServiceTest,ApprovalControllerTest,RepositoryDeletionPersistenceTest,IssueControllerTest,IssueDetailPlanReviewRenderTest,DashboardControlRoomAssemblerTest,PlanInvalidationEventPersistenceTest test`
  - Result: **246 tests, 0 failures, 0 errors, 0 skipped**.
- Fresh full suite after the final review edit:
  - Command: `./mvnw test`
  - Result: **1,194 tests, 0 failures, 0 errors, 0 skipped; BUILD SUCCESS**.
- `git diff --check`: passed.
- `git diff --cached --check`: passed before the implementation commit.

## Independent Review

The final independent review identified one P3 documentation concern: compatibility-constructor comments could imply fully functional mutation support. Both comments were clarified to state that repository-unaware lifecycle mutations deliberately fail closed. No actionable correctness finding remained after that change.

## Residual Concerns

- The concurrency regressions exercise H2's locking behavior; production-database lock semantics should continue to be covered by deployment-level integration testing.
- The bounded concurrency assertions use 5- and 10-second limits, which are intentionally generous but still sensitive to severely overloaded CI hosts.
- No push, deployment, or external-service mutation was performed.

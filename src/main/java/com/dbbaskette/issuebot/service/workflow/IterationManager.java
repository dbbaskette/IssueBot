package com.dbbaskette.issuebot.service.workflow;

import com.dbbaskette.issuebot.model.IssueStatus;
import com.dbbaskette.issuebot.model.FailureCategory;
import com.dbbaskette.issuebot.model.FailureRetryability;
import com.dbbaskette.issuebot.model.Iteration;
import com.dbbaskette.issuebot.model.TrackedIssue;
import com.dbbaskette.issuebot.model.WatchedRepo;
import com.dbbaskette.issuebot.repository.IterationRepository;
import com.dbbaskette.issuebot.repository.TrackedIssueRepository;
import com.dbbaskette.issuebot.repository.WatchedRepoRepository;
import com.dbbaskette.issuebot.service.harness.HarnessExecutionResult;
import com.dbbaskette.issuebot.service.event.EventService;
import com.dbbaskette.issuebot.service.github.GitHubApiClient;
import com.dbbaskette.issuebot.service.notification.NotificationService;
import com.dbbaskette.issuebot.service.review.CodeReviewResult;
import com.dbbaskette.issuebot.service.review.PersistedReviewOutcome;
import com.dbbaskette.issuebot.service.review.ReviewOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Manages iteration logic including:
 * - Max iterations guardrail
 * - Cooldown logic for failed issues
 * - Escalation when max iterations reached (needs-human label, comment, notification)
 * - Human rejection feedback handling
 */
@Component
public class IterationManager {
    @Autowired private com.dbbaskette.issuebot.service.history.DecisionProducer decisions;

    private static final Logger log = LoggerFactory.getLogger(IterationManager.class);
    private static final int DEFAULT_COOLDOWN_HOURS = 24;

    private final TrackedIssueRepository issueRepository;
    private final WatchedRepoRepository repoRepository;
    private final IterationRepository iterationRepository;
    private final GitHubApiClient gitHubApi;
    private final EventService eventService;
    private final NotificationService notificationService;
    private FailureDiagnosticService failureDiagnosticService;

    @Autowired(required = false)
    void setFailureDiagnosticService(FailureDiagnosticService failureDiagnosticService) {
        this.failureDiagnosticService = failureDiagnosticService;
    }

    public IterationManager(TrackedIssueRepository issueRepository,
                             WatchedRepoRepository repoRepository,
                             IterationRepository iterationRepository,
                             GitHubApiClient gitHubApi,
                             EventService eventService,
                             NotificationService notificationService) {
        this.issueRepository = issueRepository;
        this.repoRepository = repoRepository;
        this.iterationRepository = iterationRepository;
        this.gitHubApi = gitHubApi;
        this.eventService = eventService;
        this.notificationService = notificationService;
    }

    /**
     * Check if the issue can undergo another iteration.
     * Re-reads maxIterations from the database to pick up any settings changes
     * made while the workflow is running.
     */
    public boolean canIterate(TrackedIssue trackedIssue) {
        int maxIterations = getMaxIterationsFromDb(trackedIssue);
        int current = trackedIssue.getCurrentIteration();
        log.debug("canIterate check: currentIteration={}, maxIterations={}", current, maxIterations);
        return trackedIssue.isPlanCorrectionPending() || current < maxIterations;
    }

    /**
     * Atomically claims the single Plan First correction and creates its durable iteration row.
     * The returned row is the authoritative row for this run even when a guided retry previously
     * used the same iteration number.
     */
    @Transactional
    public Iteration claimPlanCorrectionIteration(TrackedIssue trackedIssue, int iterationNum) {
        repoRepository.findByIdForUpdate(trackedIssue.getRepo().getId()).orElseThrow();
        TrackedIssue claimTarget = issueRepository.findByIdForDispatch(trackedIssue.getId())
                .orElseThrow(() -> new IllegalStateException("Tracked issue no longer exists"));
        if (claimTarget.getWorkflowRun() != trackedIssue.getWorkflowRun() || claimTarget.getStatus() != IssueStatus.IN_PROGRESS)
            throw new IllegalStateException("Correction claim is stale");
        if (!claimTarget.isPlanCorrectionPending()) {
            throw new IllegalStateException("Plan correction is not pending");
        }
        if (iterationNum != claimTarget.getCurrentIteration() + 1) {
            throw new IllegalArgumentException("Correction iteration must advance by one");
        }

        Iteration authoritative = iterationRepository
                .findFirstByIssueIdAndIterationNumOrderByIdDesc(
                        claimTarget.getId(), iterationNum)
                .filter(candidate -> candidate.getCompletedAt() == null
                        && candidate.matchesAttemptIdentity(claimTarget.getWorkflowRun(),
                        approvedPlanId(claimTarget)))
                .orElse(null);

        claimTarget.setCurrentIteration(iterationNum);
        claimTarget.setCurrentPhase("IMPLEMENTATION");
        claimTarget.setPlanCorrectionPending(false);
        issueRepository.save(claimTarget);
        issueRepository.flush();

        if (authoritative != null) {
            recordRetry(claimTarget, authoritative);
            return authoritative;
        }
        Iteration iteration = new Iteration(claimTarget, iterationNum,
                claimTarget.getWorkflowRun(), approvedPlanId(claimTarget));
        iteration.setImplModel(claimTarget.getResolvedImplModel());
        iterationRepository.save(iteration);
        iterationRepository.flush();
        recordRetry(claimTarget, iteration);
        return iteration;
    }

    /** The ordinary iteration claim and retry choice commit together before execution. */
    @Transactional
    public Iteration claimImplementationIteration(TrackedIssue expected, int number) {
        repoRepository.findByIdForUpdate(expected.getRepo().getId()).orElseThrow();
        TrackedIssue issue = issueRepository.findByIdForDispatch(expected.getId()).orElseThrow();
        if (issue.getWorkflowRun() != expected.getWorkflowRun() || issue.getStatus() != IssueStatus.IN_PROGRESS)
            throw new IllegalStateException("Iteration claim is stale");
        var existing = iterationRepository.findFirstByIssueIdAndIterationNumOrderByIdDesc(issue.getId(), number)
                .filter(i -> i.getCompletedAt() == null
                        && i.getImplementationCompletedAt() == null
                        && i.matchesAttemptIdentity(issue.getWorkflowRun(), approvedPlanId(issue)))
                .orElse(null);
        if (number == issue.getCurrentIteration() && existing != null) return existing;
        if (number != issue.getCurrentIteration()
                && number != issue.getCurrentIteration() + 1) {
            throw new IllegalStateException("Iteration must be current or advance by one");
        }
        issue.setCurrentIteration(number);
        issue.setCurrentPhase("IMPLEMENTATION");
        issueRepository.saveAndFlush(issue);
        var iteration = existing == null
                ? new Iteration(issue, number, issue.getWorkflowRun(), approvedPlanId(issue))
                : existing;
        iteration.setImplModel(issue.getResolvedImplModel());
        iterationRepository.saveAndFlush(iteration);
        if (number > 1) recordRetry(issue, iteration);
        return iteration;
    }

    private Long approvedPlanId(TrackedIssue issue) {
        return issue.getApprovedPlanningVersion() == null
                ? null : issue.getApprovedPlanningVersion().getId();
    }

    private void recordRetry(TrackedIssue issue, Iteration iteration) {
        decisions.record(issue, com.dbbaskette.issuebot.service.history.DecisionProducer.run(issue)
                        + ":iteration:" + iteration.getId() + ":retry",
                com.dbbaskette.issuebot.service.history.DecisionDraft.Actor.AUTOMATION,
                com.dbbaskette.issuebot.service.history.DecisionDraft.Action.AUTO_RETRY,
                com.dbbaskette.issuebot.service.history.DecisionDraft.Outcome.ACCEPTED,
                com.dbbaskette.issuebot.service.history.DecisionDraft.Reason.POLICY_AUTOMATIC,
                issue.getApprovedPlanningVersion() == null ? null : issue.getApprovedPlanningVersion().getId(),
                iteration.getId(), null, null);
    }

    /**
     * Persist a completed review verdict and its plan-conformance transition atomically.
     * Keeping these writes in a separate Spring-managed component ensures the transaction
     * is applied when the workflow calls this method; a crash cannot leave a durable failed
     * verdict without the pending correction or second-miss state needed for recovery.
     */
    @Transactional
    public void persistCompletedReviewVerdict(TrackedIssue trackedIssue, Iteration iteration,
                                               CodeReviewResult reviewResult,
                                               ApprovedPlanContext approvedPlan) {
        ReviewOutcome outcome = reviewResult.outcome();
        iteration.setReviewPassed(outcome.persistedVerdict());
        iteration.setReviewJson(outcome == ReviewOutcome.OPERATIONAL_ERROR
                ? PersistedReviewOutcome.operationalErrorJson(reviewResult.summary())
                : reviewResult.rawJson());
        iteration.setReviewModel(reviewResult.modelUsed());
        iterationRepository.save(iteration);

        if (approvedPlan != null && !reviewResult.invocationFailed()) {
            int conformanceAttempt = trackedIssue.getPlanConformanceAttempt() + 1;
            trackedIssue.setPlanConformanceAttempt(conformanceAttempt);
            trackedIssue.setPlanCorrectionPending(!reviewResult.passed() && conformanceAttempt == 1);
            issueRepository.save(trackedIssue);
        }
    }

    /**
     * Read the current maxIterations value from the database, falling back
     * to the in-memory value if the repo is not found.
     */
    private int getMaxIterationsFromDb(TrackedIssue trackedIssue) {
        WatchedRepo repo = trackedIssue.getRepo();
        return repoRepository.findById(repo.getId())
                .map(WatchedRepo::getMaxIterations)
                .orElse(repo.getMaxIterations());
    }

    /**
     * Evaluate whether retrying an iteration is worthwhile based on the failure context.
     * Returns a reason string if retry should be skipped, or null if retry is OK.
     */
    public String shouldSkipRetry(TrackedIssue trackedIssue, HarnessExecutionResult implResult,
                                    String ciResult, String failureContext) {
        int currentIter = trackedIssue.getCurrentIteration();

        // Timed out — task is too large for a single session, retrying will burn the same tokens
        // Only skip after at least 2 attempts so the first timeout still allows one retry
        if (implResult != null && implResult.isTimedOut() && currentIter > 1) {
            return "Implementation timed out on iteration " + currentIter
                    + " — task is likely too large for automated resolution";
        }

        // Excessive token usage without success — task is at the boundary of what can be done
        // Opus at ~200K+ output tokens means it wrote extensively and still failed
        // Only skip after at least 2 attempts
        if (implResult != null && implResult.getOutputTokens() > 150_000 && currentIter > 1) {
            return "Implementation consumed " + implResult.getOutputTokens()
                    + " output tokens without success — task is too complex for retry";
        }

        // Implementation returned failure with no files changed — Claude couldn't make progress
        if (implResult != null && !implResult.isSuccess()
                && implResult.getFilesChanged().isEmpty()
                && currentIter > 1) {
            return "Implementation made no progress (0 files changed) across " + currentIter + " iterations";
        }

        // Check for repeated implementation failures: only skip if the previous context
        // was itself an implementation failure (not review feedback or human instructions)
        if (implResult != null && !implResult.isSuccess()
                && failureContext != null
                && isImplementationFailure(failureContext)
                && currentIter > 1) {
            return "Implementation failed again on iteration " + currentIter
                    + " with same error type — unlikely to succeed on retry";
        }

        return null; // OK to retry
    }

    private static boolean isImplementationFailure(String failureContext) {
        return failureContext.startsWith("Claude Code failed:")
                || failureContext.startsWith("Codex CLI failed:")
                || failureContext.startsWith("Agent failed:");
    }

    /**
     * Handle the case when retry is skipped because it's unlikely to succeed.
     */
    public void handleRetrySkipped(TrackedIssue trackedIssue, String reason) {
        String comment = "## IssueBot: Retry Skipped\n\n"
                + "IssueBot has decided not to retry this issue:\n\n"
                + "> " + reason + "\n\n"
                + "### Next Steps\n"
                + "- Consider breaking this issue into smaller, more focused tasks\n"
                + "- Review the branch `" + trackedIssue.getBranchName() + "` for partial progress\n"
                + "- Remove the `needs-human` label and add `agent-ready` to retry after simplifying\n\n"
                + "---\n*Generated by [IssueBot](https://github.com/dbbaskette/IssueBot)*";

        escalateFailure(trackedIssue,
                "Retry Skipped",
                reason,
                "RETRY_SKIPPED",
                "Skipping retry: " + reason,
                comment);
    }

    /**
     * Handle the case when the operator rejects a proposed decomposition from the
     * dashboard. Escalates to needs-human with wording that makes clear this was an
     * operator decision, not IssueBot declining to retry.
     */
    public void handleProposalRejected(TrackedIssue trackedIssue) {
        String comment = "## IssueBot: Split Proposal Rejected\n\n"
                + "The operator rejected IssueBot's proposed split of this issue.\n\n"
                + "### Next Steps\n"
                + "- Simplify or clarify the issue, then remove the `needs-human` label and add `agent-ready` to retry\n\n"
                + "---\n*Generated by [IssueBot](https://github.com/dbbaskette/IssueBot)*";

        escalateFailure(trackedIssue,
                "Split Proposal Rejected",
                "Operator rejected the proposed split — needs human attention",
                "DECOMPOSITION_REJECTED",
                "Operator rejected the proposed split — needs human attention",
                comment);
    }

    /**
     * Handle the case when the operator rejects a proposed implementation plan (#64)
     * for the second time. Mirrors {@link #handleProposalRejected} — escalates to
     * needs-human rather than regenerating a third plan.
     */
    public void handlePlanRejectedTwice(TrackedIssue trackedIssue) {
        String comment = "## IssueBot: Plan Rejected Twice\n\n"
                + "The operator rejected IssueBot's proposed implementation plan twice.\n\n"
                + "### Next Steps\n"
                + "- Simplify or clarify the issue, then remove the `needs-human` label and add `agent-ready` to retry\n\n"
                + "---\n*Generated by [IssueBot](https://github.com/dbbaskette/IssueBot)*";

        escalateFailure(trackedIssue,
                "Plan Rejected Twice",
                "Operator rejected the proposed plan twice — needs human attention",
                "PLAN_REJECTED_TWICE",
                "Operator rejected the proposed plan twice — needs human attention",
                comment);
    }

    /**
     * Handle the case when max iterations have been reached without success.
     */
    public void handleMaxIterationsReached(TrackedIssue trackedIssue) {
        int maxIterations = trackedIssue.getRepo().getMaxIterations();
        String comment = buildMaxIterationsComment(trackedIssue, maxIterations);

        escalateFailure(trackedIssue,
                "Max Iterations Reached",
                "Failed after " + maxIterations + " iterations, needs human attention",
                "MAX_ITERATIONS_REACHED",
                "Failed after " + maxIterations + " iterations, entering cooldown",
                comment);
    }

    /**
     * Check if the issue can undergo another review iteration.
     * Uses maxReviewIterations from WatchedRepo (default 2).
     * Re-reads from DB to pick up settings changes.
     */
    public boolean canReviewIterate(TrackedIssue trackedIssue) {
        WatchedRepo repo = trackedIssue.getRepo();
        int maxReviewIterations = repoRepository.findById(repo.getId())
                .map(WatchedRepo::getMaxReviewIterations)
                .orElse(repo.getMaxReviewIterations());
        return trackedIssue.getCurrentReviewIteration() < maxReviewIterations;
    }

    /**
     * Handle the case when max review iterations have been reached.
     */
    /**
     * @param blockerSummary a concise, human-readable summary of what the review objected to
     *                       (from {@code IssueWorkflowService.summarizeReviewBlockers}), appended
     *                       to the failure reason so "needs human" is actionable; may be blank.
     * @param richFindings   the full human-readable review findings for the GitHub escalation
     *                       comment (replaces a raw JSON dump); may be blank.
     * @param reviewInvocationFailed true when the review itself crashed (CLI/parse error) and never
     *                       judged the code, so the reason is framed as "could not run" not "not satisfied".
     */
    public void handleMaxReviewIterationsReached(TrackedIssue trackedIssue,
                                                  String blockerSummary, String richFindings,
                                                  boolean reviewInvocationFailed) {
        int maxReviewIterations = trackedIssue.getRepo().getMaxReviewIterations();
        String comment = buildMaxReviewIterationsComment(trackedIssue, maxReviewIterations,
                richFindings, reviewInvocationFailed);

        // Distinct framing: the review that CRASHED never judged the code, so "could not be
        // satisfied" (which implies the code fell short) would be misleading.
        StringBuilder detail = new StringBuilder(reviewInvocationFailed
                ? "The independent review could not run after " + maxReviewIterations
                        + " attempts (environment/CLI error, not necessarily a code problem), needs human attention."
                : "Independent review could not be satisfied after " + maxReviewIterations
                        + " iterations, needs human attention.");
        if (blockerSummary != null && !blockerSummary.isBlank()) {
            detail.append("\n").append(blockerSummary.strip());
        }

        escalateFailure(trackedIssue,
                reviewInvocationFailed ? "Review Could Not Run" : "Review Budget Exhausted",
                detail.toString(),
                reviewInvocationFailed ? "REVIEW_UNAVAILABLE" : "MAX_REVIEW_ITERATIONS_REACHED",
                reviewInvocationFailed
                        ? "Independent review was unavailable after " + maxReviewIterations
                                + " attempts; check the reviewer provider, CLI, authentication, "
                                + "and configuration before retrying."
                        : "Review failed after " + maxReviewIterations
                                + " iterations, entering cooldown",
                comment);
    }

    /** Escalate after the fixed two-verdict Plan First conformance cycle is exhausted. */
    public void handlePlanConformanceFailure(TrackedIssue trackedIssue, int approvedPlanVersion,
                                              String blockerSummary, String richFindings) {
        String detail = "Implementation did not conform to approved Plan v" + approvedPlanVersion
                + " after two review attempts, needs human guidance.";
        if (blockerSummary != null && !blockerSummary.isBlank()) {
            detail += "\n" + blockerSummary.strip();
        }

        String comment = buildPlanConformanceFailureComment(
                trackedIssue, approvedPlanVersion, richFindings);
        escalateFailure(trackedIssue,
                "Approved Plan Conformance Failed",
                detail,
                "MAX_REVIEW_ITERATIONS_REACHED",
                "Approved Plan v" + approvedPlanVersion
                        + " did not conform after two review attempts, entering cooldown",
                comment);
    }

    /**
     * Handle the case when an issue's effective cost budget (issue override, else
     * repo default) has been exceeded. Manual retry does not reset spend — raising
     * the budget in the Retry dialog (or on the repo) is the escape hatch.
     */
    public void handleBudgetExceeded(TrackedIssue trackedIssue, BigDecimal spent, BigDecimal budget) {
        String detail = "Budget exceeded: $" + spent.setScale(2, RoundingMode.HALF_UP)
                + " spent of $" + budget.setScale(2, RoundingMode.HALF_UP);
        String comment = buildBudgetExceededComment(spent, budget);

        escalateFailure(trackedIssue,
                "Budget Exceeded",
                detail,
                "BUDGET_EXCEEDED",
                detail,
                comment);
    }

    private String buildBudgetExceededComment(BigDecimal spent, BigDecimal budget) {
        StringBuilder sb = new StringBuilder();
        sb.append("## IssueBot: Budget Exceeded\n\n");
        sb.append("This issue has spent **$").append(spent.setScale(2, RoundingMode.HALF_UP))
                .append("** against a budget of **$").append(budget.setScale(2, RoundingMode.HALF_UP))
                .append("**.\n\n");
        sb.append("### Next Steps\n");
        sb.append("- Raise the budget in the Retry dialog for this issue, or increase the repo's Issue budget (USD) setting\n");
        sb.append("- Remove the `needs-human` label and add `agent-ready` (or use Retry) to continue — spend is cumulative and is not reset by a retry\n\n");
        sb.append("---\n*Generated by [IssueBot](https://github.com/dbbaskette/IssueBot)*");
        return sb.toString();
    }

    /**
     * Shared escalation logic: mark FAILED, label, comment, cooldown, notify, log.
     */
    // last_failure_reason is VARCHAR(2000). Bound EVERY escalation's detail defensively so a
    // verbose (model-controlled) reason can never overflow the column — an overflow throws on
    // save() and is only caught by the top-level handler, which would then skip the needs-human
    // label, escalation comment, and cooldown this method exists to guarantee.
    private static final int MAX_FAILURE_REASON_CHARS = 1900;

    private void escalateFailure(TrackedIssue trackedIssue, String notificationTitle,
                                   String notificationDetail, String eventType,
                                   String eventMessage, String issueComment) {
        WatchedRepo repo = trackedIssue.getRepo();
        int issueNumber = trackedIssue.getIssueNumber();

        log.warn("{} for {} #{}", notificationTitle, repo.fullName(), issueNumber);

        trackedIssue.setStatus(IssueStatus.FAILED);
        trackedIssue.setCurrentPhase(null);
        String summary = truncate(notificationDetail, MAX_FAILURE_REASON_CHARS);
        if (failureDiagnosticService != null) {
            FailureCategory category = failureCategory(eventType);
            failureDiagnosticService.record(trackedIssue, category, summary, null,
                    notificationDetail, suggestedAction(category),
                    category == FailureCategory.BUDGET
                            ? FailureRetryability.OPERATOR_ACTION_REQUIRED
                            : FailureRetryability.CONFIGURATION_CHANGE_RECOMMENDED);
        } else {
            trackedIssue.setLastFailureReason(summary);
            issueRepository.save(trackedIssue);
        }

        try {
            gitHubApi.addLabels(repo.getOwner(), repo.getName(), issueNumber,
                    List.of("needs-human"));
        } catch (Exception e) {
            log.warn("Failed to add needs-human label to {} #{}: {}",
                    repo.fullName(), issueNumber, e.getMessage());
        }

        try {
            gitHubApi.addComment(repo.getOwner(), repo.getName(), issueNumber, issueComment);
        } catch (Exception e) {
            log.warn("Failed to post escalation comment to {} #{}: {}",
                    repo.fullName(), issueNumber, e.getMessage());
        }

        enterCooldown(trackedIssue);

        notificationService.warn(notificationTitle,
                repo.fullName() + " #" + issueNumber + " — " + notificationDetail, trackedIssue);

        eventService.log(eventType, eventMessage, repo, trackedIssue);
    }

    private static FailureCategory failureCategory(String eventType) {
        if (eventType.contains("BUDGET")) return FailureCategory.BUDGET;
        if ("REVIEW_UNAVAILABLE".equals(eventType)) return FailureCategory.REVIEW_INFRASTRUCTURE;
        if (eventType.contains("REVIEW")) return FailureCategory.REVIEW;
        return FailureCategory.AGENT_EXIT;
    }

    private static String suggestedAction(FailureCategory category) {
        return switch (category) {
            case BUDGET -> "Raise the issue or repository budget before retrying.";
            case REVIEW -> "Review the blockers, add specific implementation guidance, then retry.";
            case REVIEW_INFRASTRUCTURE -> "Check the reviewer provider, CLI, authentication, and "
                    + "configuration before retrying the review.";
            default -> "Break the issue into smaller changes or add more specific guidance before retrying.";
        };
    }

    /**
     * Handle a human rejection in approval-gated mode.
     * Treat as a failed review and inject feedback.
     */
    public void handleHumanRejection(TrackedIssue trackedIssue, String feedback) {
        WatchedRepo repo = trackedIssue.getRepo();
        log.info("Human rejection for {} #{}: {}", repo.fullName(),
                trackedIssue.getIssueNumber(), feedback);

        eventService.log("HUMAN_REJECTION",
                "Human rejected with feedback: " + feedback, repo, trackedIssue);

        // Reset to IN_PROGRESS so the workflow can continue with feedback
        trackedIssue.setStatus(IssueStatus.IN_PROGRESS);
        issueRepository.save(trackedIssue);
    }

    /**
     * Put an issue into cooldown state.
     */
    public void enterCooldown(TrackedIssue trackedIssue) {
        trackedIssue.setStatus(IssueStatus.COOLDOWN);
        trackedIssue.setCooldownUntil(LocalDateTime.now().plusHours(DEFAULT_COOLDOWN_HOURS));
        issueRepository.save(trackedIssue);

        log.info("Issue {} #{} entering cooldown until {}",
                trackedIssue.getRepo().fullName(),
                trackedIssue.getIssueNumber(),
                trackedIssue.getCooldownUntil());
    }

    /**
     * Check if a cooldown has expired.
     */
    public boolean isCooldownExpired(TrackedIssue trackedIssue) {
        if (trackedIssue.getStatus() != IssueStatus.COOLDOWN) return true;
        LocalDateTime cooldownUntil = trackedIssue.getCooldownUntil();
        return cooldownUntil == null || LocalDateTime.now().isAfter(cooldownUntil);
    }

    private String buildMaxIterationsComment(TrackedIssue trackedIssue, int maxIterations) {
        StringBuilder sb = new StringBuilder();
        sb.append("## IssueBot: Max Iterations Reached\n\n");
        sb.append("IssueBot attempted to resolve this issue **").append(maxIterations)
                .append(" times** but was unable to produce a passing solution.\n\n");

        // Add last iteration details
        List<Iteration> iterations = iterationRepository
                .findByIssueOrderByIterationNumAsc(trackedIssue);
        if (!iterations.isEmpty()) {
            Iteration last = iterations.get(iterations.size() - 1);

            sb.append("### Last Attempt (Iteration ").append(last.getIterationNum()).append(")\n");
            if (last.getSelfAssessment() != null) {
                sb.append("**Self-Assessment:** ").append(
                        truncate(last.getSelfAssessment(), 500)).append("\n\n");
            }
            if (last.getCiResult() != null) {
                sb.append("**CI Result:** ").append(last.getCiResult()).append("\n\n");
            }
        }

        sb.append("### Next Steps\n");
        sb.append("- Review the branch `").append(trackedIssue.getBranchName()).append("` for partial progress\n");
        sb.append("- Remove the `needs-human` label and add `agent-ready` to retry after making changes\n");
        sb.append("- The issue will enter a 24-hour cooldown before auto-retry\n\n");
        sb.append("---\n*Generated by [IssueBot](https://github.com/dbbaskette/IssueBot)*");

        return sb.toString();
    }

    private String buildMaxReviewIterationsComment(TrackedIssue trackedIssue, int maxReviewIterations,
                                                    String richFindings, boolean reviewInvocationFailed) {
        StringBuilder sb = new StringBuilder();
        // Header must match the failure framing: a crashed review never judged the code.
        if (reviewInvocationFailed) {
            sb.append("## IssueBot: Review Could Not Run\n\n");
            sb.append("The independent review could not run after **").append(maxReviewIterations)
                    .append(" attempts** — an environment/CLI error, not necessarily a code problem.\n\n");
        } else {
            sb.append("## IssueBot: Review Budget Exhausted\n\n");
            sb.append("The independent code review could not be satisfied after **")
                    .append(maxReviewIterations).append(" review iterations**.\n\n");
        }

        List<Iteration> iterations = iterationRepository
                .findByIssueOrderByIterationNumAsc(trackedIssue);

        // Prefer the human-readable findings; fall back to the last iteration's raw JSON.
        if (richFindings != null && !richFindings.isBlank()) {
            // Bound the (model-controlled) findings for a readable comment.
            sb.append(reviewInvocationFailed ? "### Why the review couldn't run\n" : "### Why the review blocked\n")
                    .append(truncate(richFindings.strip(), 6000)).append("\n\n");
        } else if (!iterations.isEmpty() && iterations.get(iterations.size() - 1).getReviewJson() != null) {
            sb.append("### Last Review Findings\n");
            sb.append("```json\n").append(truncate(iterations.get(iterations.size() - 1).getReviewJson(), 1000))
                    .append("\n```\n\n");
        }
        if (!iterations.isEmpty() && iterations.get(iterations.size() - 1).getCiResult() != null) {
            sb.append("**CI Result:** ").append(iterations.get(iterations.size() - 1).getCiResult()).append("\n\n");
        }

        sb.append("### Next Steps\n");
        if (reviewInvocationFailed) {
            sb.append("- Check the reviewer provider, CLI, authentication, and configuration\n");
            sb.append("- Confirm the review command can run, then remove the `needs-human` label "
                    + "and add `agent-ready` to retry the review\n\n");
        } else {
            sb.append("- Review the draft PR for the branch `").append(trackedIssue.getBranchName()).append("`\n");
            sb.append("- Examine the review findings and either approve manually or provide guidance\n");
            sb.append("- Remove the `needs-human` label and add `agent-ready` to retry\n\n");
        }
        sb.append("---\n*Generated by [IssueBot](https://github.com/dbbaskette/IssueBot)*");

        return sb.toString();
    }

    private String buildPlanConformanceFailureComment(TrackedIssue trackedIssue,
                                                       int approvedPlanVersion,
                                                       String richFindings) {
        StringBuilder sb = new StringBuilder();
        sb.append("## IssueBot: Approved Plan Conformance Failed\n\n")
                .append("The implementation did not conform to **approved Plan v")
                .append(approvedPlanVersion)
                .append("** after **two review attempts**. No third corrective implementation was scheduled.\n\n");
        if (richFindings != null && !richFindings.isBlank()) {
            sb.append("### Final Blocking Feedback\n")
                    .append(truncate(richFindings.strip(), 4000)).append("\n\n");
        }

        List<Iteration> reviewIterations = iterationRepository
                .findByIssueOrderByIterationNumAsc(trackedIssue).stream()
                .filter(iteration -> iteration.getReviewJson() != null)
                .toList();
        int first = Math.max(0, reviewIterations.size() - 2);
        for (int i = first; i < reviewIterations.size(); i++) {
            Iteration iteration = reviewIterations.get(i);
            sb.append("### Review Attempt ").append(i - first + 1).append("\n")
                    .append("```json\n")
                    .append(truncate(iteration.getReviewJson(), 2500))
                    .append("\n```\n\n");
        }

        sb.append("### Next Steps\n")
                .append("- Review both conformance verdicts and provide targeted implementation guidance\n")
                .append("- Remove the `needs-human` label and add `agent-ready` when ready to retry\n\n")
                .append("---\n*Generated by [IssueBot](https://github.com/dbbaskette/IssueBot)*");
        return sb.toString();
    }

    private String truncate(String text, int maxLength) {
        if (text == null) return "";
        return text.length() <= maxLength ? text : text.substring(0, maxLength) + "...";
    }
}

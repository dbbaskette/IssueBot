package com.dbbaskette.issuebot.service.workflow;

import com.dbbaskette.issuebot.model.PlanningVersion;
import com.dbbaskette.issuebot.model.PlanningVersionState;
import com.dbbaskette.issuebot.model.TrackedIssue;
import com.dbbaskette.issuebot.model.WatchedRepo;
import com.dbbaskette.issuebot.repository.PlanningVersionRepository;
import com.dbbaskette.issuebot.repository.TrackedIssueRepository;
import com.dbbaskette.issuebot.service.claude.ClaudeCodeResult;
import com.dbbaskette.issuebot.service.claude.ClaudeCodeService;
import com.dbbaskette.issuebot.service.event.EventService;
import com.dbbaskette.issuebot.service.git.PlanningWorkspaceService;
import com.dbbaskette.issuebot.service.github.GitHubApiClient;
import com.dbbaskette.issuebot.service.notification.NotificationService;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Authoritative Plan First lifecycle: generate immutable structured versions, approve only the
 * latest pending version, and queue guided revisions without overwriting prior artifacts.
 */
@Service
public class PlanFirstService {

    private static final int MAX_REVISION_GUIDANCE_CHARS = 4000;

    static final String PLANNING_METHODOLOGY = """
            You are producing a DESIGN SPEC and IMPLEMENTATION PLAN before any code is written.
            Make no code changes and create no files. Inspect the issue and relevant code, state
            assumptions, compare credible approaches, choose the simplest sufficient design, and
            create small test-first implementation tasks with exact files and verification commands.

            Your final response must contain exactly these two top-level sections, in this order,
            with no text before, between, or after them except their content:
            # Design Spec
            # Implementation Plan
            """;

    private static final Logger log = LoggerFactory.getLogger(PlanFirstService.class);
    private static final int MAX_FAILURE_REASON_CHARS = 2_000;

    public enum PlanningOutcome { AWAITING_APPROVAL, FAILED, CANCELLED, STALE }

    /**
     * Temporary source-compatibility result for the controller until its version-bound endpoints
     * are migrated in the next implementation task. Revisions are no longer capped or escalated.
     */
    @Deprecated
    public enum RejectOutcome { REGENERATING, ESCALATED }

    private final ClaudeCodeService agent;
    private final GitHubApiClient gitHub;
    private final PlanFirstTransactionManager transactions;
    private final PlanArtifactParser parser;
    private final PlanningWorkspaceService planningWorkspaces;
    private final EventService events;
    private final NotificationService notifications;
    private final WorkflowCancellationService cancellations;

    @Autowired
    public PlanFirstService(ClaudeCodeService agent,
                            GitHubApiClient gitHub,
                            PlanFirstTransactionManager transactions,
                            PlanArtifactParser parser,
                            PlanningWorkspaceService planningWorkspaces,
                            EventService events,
                            NotificationService notifications,
                            WorkflowCancellationService cancellations) {
        this.agent = agent;
        this.gitHub = gitHub;
        this.transactions = transactions;
        this.parser = parser;
        this.planningWorkspaces = planningWorkspaces;
        this.events = events;
        this.notifications = notifications;
        this.cancellations = cancellations;
    }

    /** Convenience constructor for focused unit/integration fixtures; Spring uses the proxy constructor above. */
    PlanFirstService(ClaudeCodeService agent,
                     GitHubApiClient gitHub,
                     TrackedIssueRepository issues,
                     PlanningVersionRepository versions,
                     PlanArtifactParser parser,
                     PlanningWorkspaceService planningWorkspaces,
                     EventService events,
                     NotificationService notifications) {
        this(agent, gitHub, new PlanFirstTransactionManager(issues, versions), parser,
                planningWorkspaces, events, notifications, new WorkflowCancellationService());
    }

    /**
     * Generates and persists the next immutable Design Spec + Implementation Plan version.
     * Any invocation or validation failure puts the issue in a visible failed state; callers
     * must never continue into implementation after {@link PlanningOutcome#FAILED}.
     */
    public PlanningOutcome generateVersion(TrackedIssue trackedIssue, JsonNode issueDetails, Path repoPath) {
        Long issueId = trackedIssue.getId();
        PlanFirstTransactionManager.GenerationContext context;
        try {
            context = transactions.prepareGeneration(issueId);
        } catch (Exception e) {
            if (cancellations.isCancelled(issueId)) {
                return PlanningOutcome.CANCELLED;
            }
            return failPlanning(null, issueId, e);
        }
        if (cancellations.isCancelled(issueId)) {
            return PlanningOutcome.CANCELLED;
        }

        PlanFirstTransactionManager.GenerationCommit commit;
        try {
            String prompt = buildPlanningPrompt(issueDetails, context.feedback(), context.previous());

            ClaudeCodeResult result;
            try (PlanningWorkspaceService.PlanningWorkspace workspace = planningWorkspaces.open(repoPath)) {
                agent.pinProvider(context.provider());
                try {
                    try {
                        result = agent.executePlanning(
                                prompt, workspace.path(), context.model(), issueId, null);
                    } finally {
                        workspace.verifySourceUnchanged();
                    }
                } finally {
                    agent.clearPinnedProvider();
                }
            }
            if (cancellations.isCancelled(issueId)) {
                return PlanningOutcome.CANCELLED;
            }
            requireSuccessfulResult(result);

            PlanArtifactParser.PlanningArtifact artifact =
                    parser.parse(result.getFinalResultOrOutput());
            if (cancellations.isCancelled(issueId)) {
                return PlanningOutcome.CANCELLED;
            }
            commit = transactions.persistGeneratedVersion(
                    context, artifact.designSpec(), artifact.implementationPlan());
        } catch (PlanFirstTransactionManager.StalePlanningGenerationException e) {
            log.info("Discarding stale planning generation for issue {}: {}", issueId, e.getMessage());
            return PlanningOutcome.STALE;
        } catch (Exception e) {
            if (cancellations.isCancelled(issueId)) {
                return PlanningOutcome.CANCELLED;
            }
            return failPlanning(context, issueId, e);
        }

        // A pause can race with the final database commit. The durable checkpoint owner will
        // rearm it; do not emit external effects while cancellation is pending.
        if (cancellations.isCancelled(issueId)) {
            return PlanningOutcome.CANCELLED;
        }

        TrackedIssue issue = commit.issue();
        PlanningVersion version = commit.version();
        runAfterPersistence("publish planning proposal audit",
                () -> publishProposalAudit(issue, version));
        runAfterPersistence("record planning proposal event",
                () -> events.log(commit.revision() ? "PLAN_REVISION_GENERATED" : "PLAN_PROPOSED",
                        (commit.revision() ? "Generated plan revision version " : "Proposed planning version ")
                                + version.getVersionNumber() + " — awaiting approval",
                        issue.getRepo(), issue));
        runAfterPersistence("send planning proposal notification",
                () -> notifications.info(commit.revision() ? "Plan Revision Generated" : "Plan Proposed",
                        issue.getRepo().fullName() + " #" + issue.getIssueNumber()
                                + " — version " + version.getVersionNumber() + " awaits approval",
                        issue));
        return PlanningOutcome.AWAITING_APPROVAL;
    }

    /** Approves both artifacts in exactly the latest pending version. */
    public void approvePlan(Long issueId, Long expectedVersionId) {
        PlanFirstTransactionManager.LifecycleCommit commit =
                transactions.approvePlan(issueId, expectedVersionId);
        TrackedIssue issue = commit.issue();
        PlanningVersion current = commit.version();

        runAfterPersistence("publish planning approval audit",
                () -> publishApprovalAudit(issue, current));
        runAfterPersistence("record planning approval event",
                () -> events.log("PLAN_APPROVED",
                        "Approved planning version " + current.getVersionNumber()
                                + " — waiting for manual implementation start",
                        issue.getRepo(), issue));
        runAfterPersistence("send planning approval notification",
                () -> notifications.info("Plan Approved",
                        issue.getRepo().fullName() + " #" + issue.getIssueNumber()
                                + " — version " + current.getVersionNumber()
                                + " approved; waiting for you to start implementation",
                        issue));
    }

    /** Supersedes exactly the latest pending version and queues a guided regeneration. */
    public void requestRevision(Long issueId, Long expectedVersionId, String feedback) {
        String guidance = normalize(feedback);
        if (guidance == null) {
            throw new IllegalArgumentException("Revision guidance is required");
        }
        if (guidance.length() > MAX_REVISION_GUIDANCE_CHARS) {
            throw new IllegalArgumentException("Revision guidance must be 4,000 characters or fewer");
        }

        PlanFirstTransactionManager.LifecycleCommit commit =
                transactions.requestRevision(issueId, expectedVersionId, guidance);
        TrackedIssue issue = commit.issue();
        PlanningVersion current = commit.version();

        runAfterPersistence("publish planning revision audit",
                () -> publishRevisionAudit(issue, current, guidance));
        runAfterPersistence("record planning revision event",
                () -> events.log("PLAN_REVISION_REQUESTED",
                        "Revision requested for planning version " + current.getVersionNumber(),
                        issue.getRepo(), issue));
        runAfterPersistence("send planning revision notification",
                () -> notifications.info("Plan Revision Requested",
                        issue.getRepo().fullName() + " #" + issue.getIssueNumber()
                                + " — version " + current.getVersionNumber() + " will be regenerated",
                        issue));
    }

    /** Returns the complete immutable approved contract, never a pending or migrated legacy plan. */
    public Optional<ApprovedPlanContext> approvedContext(TrackedIssue issue) {
        if (issue == null) {
            return Optional.empty();
        }
        PlanningVersion approved = issue.getApprovedPlanningVersion();
        if (approved == null
                || approved.getState() != PlanningVersionState.APPROVED
                || approved.getId() == null
                || approved.getVersionNumber() < 1
                || normalize(approved.getDesignSpec()) == null
                || normalize(approved.getImplementationPlan()) == null) {
            return Optional.empty();
        }
        return Optional.of(new ApprovedPlanContext(approved.getId(), approved.getVersionNumber(),
                approved.getDesignSpec(), approved.getImplementationPlan()));
    }

    String buildPlanningPrompt(JsonNode issueDetails, String feedback,
                               PlanFirstTransactionManager.PreviousVersion previousVersion) {
        String title = issueDetails.path("title").asText();
        String body = issueDetails.path("body").asText("No description");
        StringBuilder prompt = new StringBuilder(PLANNING_METHODOLOGY)
                .append("\n\n## Issue\nTitle: ").append(title)
                .append("\nBody:\n").append(body).append('\n');
        if (feedback != null) {
            prompt.append("\n## Exact prior Design Spec — Version ")
                    .append(previousVersion.versionNumber()).append('\n')
                    .append(previousVersion.designSpec()).append('\n')
                    .append("\n## Exact prior Implementation Plan — Version ")
                    .append(previousVersion.versionNumber()).append('\n')
                    .append(previousVersion.implementationPlan()).append('\n');
            prompt.append("\n## Operator guidance for the next version\n")
                    .append(feedback).append('\n');
        }
        return prompt.toString();
    }

    private void requireSuccessfulResult(ClaudeCodeResult result) {
        if (result == null) {
            throw new IllegalStateException("Planner returned no result");
        }
        if (!result.isSuccess()) {
            String detail = normalize(result.getErrorMessage());
            if (detail == null) {
                detail = result.isTimedOut()
                        ? "Planner timed out"
                        : "Planner did not complete successfully";
            }
            throw new IllegalStateException(detail);
        }
    }

    private PlanningOutcome failPlanning(PlanFirstTransactionManager.GenerationContext context,
                                         Long issueId,
                                         Exception failure) {
        String detail = normalize(failure.getMessage());
        if (detail == null) {
            detail = failure.getClass().getSimpleName();
        }
        String reason = bound("Planning failed: " + detail, MAX_FAILURE_REASON_CHARS);
        PlanFirstTransactionManager.FailureCommit failureCommit = context == null
                ? transactions.failPlanningIfActive(issueId, reason)
                : transactions.failPlanning(context, reason);
        if (!failureCommit.committed()) {
            log.info("Discarding stale planning failure for issue {}: {}", issueId, detail);
            return PlanningOutcome.STALE;
        }
        TrackedIssue failed = failureCommit.issue();
        log.warn("Planning failed for {} #{}: {}",
                failed.getRepo().fullName(), failed.getIssueNumber(), detail);
        runAfterPersistence("record planning failure event",
                () -> events.log("PLAN_FAILED", reason, failed.getRepo(), failed));
        runAfterPersistence("send planning failure notification",
                () -> notifications.warn("Planning Failed",
                        failed.getRepo().fullName() + " #" + failed.getIssueNumber() + " — " + reason,
                        failed));
        return PlanningOutcome.FAILED;
    }

    private void publishProposalAudit(TrackedIssue issue, PlanningVersion version) {
        String body = "## IssueBot: Planning Version " + version.getVersionNumber() + "\n\n"
                + "# Design Spec\n\n" + version.getDesignSpec() + "\n\n"
                + "# Implementation Plan\n\n" + version.getImplementationPlan() + "\n\n"
                + "Approve or request a revision from the IssueBot dashboard.";
        publishGitHubAudit(issue, body);
    }

    private void publishApprovalAudit(TrackedIssue issue, PlanningVersion version) {
        publishGitHubAudit(issue,
                "Design Spec and Implementation Plan version " + version.getVersionNumber()
                        + " approved. Implementation is waiting for a manual start in IssueBot.");
    }

    private void publishRevisionAudit(TrackedIssue issue, PlanningVersion version, String feedback) {
        publishGitHubAudit(issue,
                "Revision requested for planning version " + version.getVersionNumber()
                        + ".\n\nGuidance: " + feedback);
    }

    private void publishGitHubAudit(TrackedIssue issue, String body) {
        WatchedRepo repo = issue.getRepo();
        try {
            gitHub.addComment(repo.getOwner(), repo.getName(), issue.getIssueNumber(), body);
        } catch (Exception e) {
            String detail = normalize(e.getMessage());
            if (detail == null) {
                detail = e.getClass().getSimpleName();
            }
            log.warn("Failed to publish Plan First audit comment to {} #{}: {}",
                    repo.fullName(), issue.getIssueNumber(), detail);
            String failureDetail = detail;
            runAfterPersistence("record GitHub audit failure event",
                    () -> events.log("PLAN_AUDIT_FAILED",
                            bound("GitHub audit comment failed: " + failureDetail,
                                    MAX_FAILURE_REASON_CHARS),
                            repo, issue));
        }
    }

    private void runAfterPersistence(String action, Runnable sideEffect) {
        try {
            sideEffect.run();
        } catch (Exception e) {
            String detail = normalize(e.getMessage());
            log.warn("Failed to {}: {}", action,
                    detail != null ? bound(detail, MAX_FAILURE_REASON_CHARS)
                            : e.getClass().getSimpleName());
        }
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static String bound(String value, int maxChars) {
        return value.length() <= maxChars ? value : value.substring(0, maxChars);
    }

    /**
     * Compatibility bridge for the current workflow caller. It always returns {@code true}
     * because both awaiting approval and planning failure are terminal for this processing run.
     */
    @Deprecated
    public boolean proposePlan(TrackedIssue trackedIssue, JsonNode issueDetails, Path repoPath) {
        generateVersion(trackedIssue, issueDetails, repoPath);
        return true;
    }

    /** Compatibility bridge until the controller submits an explicit version id. */
    @Deprecated
    public void approvePlan(TrackedIssue issue) {
        throw new IllegalStateException("Planning version id is required");
    }

    /** Compatibility bridge until the controller submits an explicit version id. */
    @Deprecated
    public RejectOutcome rejectPlan(TrackedIssue issue, String feedback) {
        throw new IllegalStateException("Planning version id is required");
    }
}

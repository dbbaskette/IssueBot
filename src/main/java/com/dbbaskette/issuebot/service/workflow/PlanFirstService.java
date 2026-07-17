package com.dbbaskette.issuebot.service.workflow;

import com.dbbaskette.issuebot.model.IssueStatus;
import com.dbbaskette.issuebot.model.PlanningVersion;
import com.dbbaskette.issuebot.model.PlanningVersionState;
import com.dbbaskette.issuebot.model.TrackedIssue;
import com.dbbaskette.issuebot.model.WatchedRepo;
import com.dbbaskette.issuebot.repository.PlanningVersionRepository;
import com.dbbaskette.issuebot.repository.TrackedIssueRepository;
import com.dbbaskette.issuebot.service.claude.ClaudeCodeResult;
import com.dbbaskette.issuebot.service.claude.ClaudeCodeService;
import com.dbbaskette.issuebot.service.event.EventService;
import com.dbbaskette.issuebot.service.github.GitHubApiClient;
import com.dbbaskette.issuebot.service.notification.NotificationService;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Optional;

/**
 * Authoritative Plan First lifecycle: generate immutable structured versions, approve only the
 * latest pending version, and queue guided revisions without overwriting prior artifacts.
 */
@Service
public class PlanFirstService {

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

    public enum PlanningOutcome { AWAITING_APPROVAL, FAILED }

    /**
     * Temporary source-compatibility result for the controller until its version-bound endpoints
     * are migrated in the next implementation task. Revisions are no longer capped or escalated.
     */
    @Deprecated
    public enum RejectOutcome { REGENERATING, ESCALATED }

    private final ClaudeCodeService agent;
    private final GitHubApiClient gitHub;
    private final TrackedIssueRepository issues;
    private final PlanningVersionRepository versions;
    private final PlanArtifactParser parser;
    private final EventService events;
    private final NotificationService notifications;

    public PlanFirstService(ClaudeCodeService agent,
                            GitHubApiClient gitHub,
                            TrackedIssueRepository issues,
                            PlanningVersionRepository versions,
                            PlanArtifactParser parser,
                            EventService events,
                            NotificationService notifications) {
        this.agent = agent;
        this.gitHub = gitHub;
        this.issues = issues;
        this.versions = versions;
        this.parser = parser;
        this.events = events;
        this.notifications = notifications;
    }

    /**
     * Generates and persists the next immutable Design Spec + Implementation Plan version.
     * Any invocation or validation failure puts the issue in a visible failed state; callers
     * must never continue into implementation after {@link PlanningOutcome#FAILED}.
     */
    public PlanningOutcome generateVersion(TrackedIssue trackedIssue, JsonNode issueDetails, Path repoPath) {
        PlanningVersion version;
        String feedback;
        try {
            String model = requireResolvedModel(trackedIssue);
            String provider = requireResolvedProvider(trackedIssue);
            feedback = normalize(trackedIssue.getPlanFeedback());
            String prompt = buildPlanningPrompt(issueDetails, feedback);

            ClaudeCodeResult result;
            agent.pinProvider(trackedIssue.getResolvedAgentProvider());
            try {
                result = agent.executePlanning(
                        prompt, repoPath, model, trackedIssue.getId(), null);
            } finally {
                agent.clearPinnedProvider();
            }
            requireSuccessfulResult(result);

            PlanArtifactParser.PlanningArtifact artifact =
                    parser.parse(result.getFinalResultOrOutput());
            int nextVersion = versions.findFirstByIssueIdOrderByVersionNumberDesc(trackedIssue.getId())
                    .map(previous -> previous.getVersionNumber() + 1)
                    .orElse(1);
            version = PlanningVersion.pending(trackedIssue, nextVersion,
                    artifact.designSpec(), artifact.implementationPlan(), provider, model, feedback);

            versions.save(version);
            trackedIssue.setPlanFeedback(null);
            trackedIssue.setStatus(IssueStatus.AWAITING_PLAN_APPROVAL);
            trackedIssue.setCurrentPhase(null);
            trackedIssue.setLastFailureReason(null);
            issues.save(trackedIssue);
        } catch (Exception e) {
            failPlanning(trackedIssue, e);
            return PlanningOutcome.FAILED;
        }

        boolean revision = feedback != null;
        runAfterPersistence("publish planning proposal audit",
                () -> publishProposalAudit(trackedIssue, version));
        runAfterPersistence("record planning proposal event",
                () -> events.log(revision ? "PLAN_REVISION_GENERATED" : "PLAN_PROPOSED",
                        (revision ? "Generated plan revision version " : "Proposed planning version ")
                                + version.getVersionNumber() + " — awaiting approval",
                        trackedIssue.getRepo(), trackedIssue));
        runAfterPersistence("send planning proposal notification",
                () -> notifications.info(revision ? "Plan Revision Generated" : "Plan Proposed",
                        trackedIssue.getRepo().fullName() + " #" + trackedIssue.getIssueNumber()
                                + " — version " + version.getVersionNumber() + " awaits approval",
                        trackedIssue));
        return PlanningOutcome.AWAITING_APPROVAL;
    }

    /** Approves both artifacts in exactly the latest pending version. */
    public synchronized void approvePlan(Long issueId, Long expectedVersionId) {
        TrackedIssue issue = requireIssue(issueId);
        PlanningVersion current = requireCurrentPending(issue);
        if (!Objects.equals(current.getId(), expectedVersionId)) {
            throw new IllegalStateException(
                    "Stale approval: current pending version is " + current.getVersionNumber());
        }

        current.approve(LocalDateTime.now());
        issue.setApprovedPlanningVersion(current);
        issue.setPlanConformanceAttempt(0);
        issue.setPlanCorrectionPending(false);
        issue.setStatus(IssueStatus.PENDING);
        versions.save(current);
        issues.save(issue);

        runAfterPersistence("publish planning approval audit",
                () -> publishApprovalAudit(issue, current));
        runAfterPersistence("record planning approval event",
                () -> events.log("PLAN_APPROVED",
                        "Approved planning version " + current.getVersionNumber()
                                + " — queued for implementation",
                        issue.getRepo(), issue));
        runAfterPersistence("send planning approval notification",
                () -> notifications.info("Plan Approved",
                        issue.getRepo().fullName() + " #" + issue.getIssueNumber()
                                + " — version " + current.getVersionNumber()
                                + " queued for implementation",
                        issue));
    }

    /** Supersedes exactly the latest pending version and queues a guided regeneration. */
    public synchronized void requestRevision(Long issueId, Long expectedVersionId, String feedback) {
        String guidance = normalize(feedback);
        if (guidance == null) {
            throw new IllegalArgumentException("Revision guidance is required");
        }

        TrackedIssue issue = requireIssue(issueId);
        PlanningVersion current = requireCurrentPending(issue);
        if (!Objects.equals(current.getId(), expectedVersionId)) {
            throw new IllegalStateException(
                    "Stale revision: current pending version is " + current.getVersionNumber());
        }

        current.supersede();
        versions.save(current);
        issue.setPlanFeedback(guidance);
        issue.setStatus(IssueStatus.PENDING);
        issues.save(issue);

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

    String buildPlanningPrompt(JsonNode issueDetails, String feedback) {
        String title = issueDetails.path("title").asText();
        String body = issueDetails.path("body").asText("No description");
        StringBuilder prompt = new StringBuilder(PLANNING_METHODOLOGY)
                .append("\n\n## Issue\nTitle: ").append(title)
                .append("\nBody:\n").append(body).append('\n');
        if (feedback != null) {
            prompt.append("\n## Operator guidance for the next version\n")
                    .append(feedback).append('\n');
        }
        return prompt.toString();
    }

    private String requireResolvedModel(TrackedIssue issue) {
        String model = normalize(issue.getResolvedImplModel());
        if (model == null) {
            throw new IllegalStateException("Resolved implementation model is required for planning");
        }
        return model;
    }

    private String requireResolvedProvider(TrackedIssue issue) {
        if (issue.getResolvedAgentProvider() == null) {
            throw new IllegalStateException("Resolved implementation provider is required for planning");
        }
        return issue.getResolvedAgentProvider().name();
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

    private TrackedIssue requireIssue(Long issueId) {
        return issues.findById(issueId)
                .orElseThrow(() -> new IllegalArgumentException("Issue not found: " + issueId));
    }

    private PlanningVersion requireCurrentPending(TrackedIssue issue) {
        if (issue.getStatus() != IssueStatus.AWAITING_PLAN_APPROVAL) {
            throw new IllegalStateException(
                    "Issue is not awaiting plan approval: " + issue.getStatus());
        }
        PlanningVersion current = versions.findFirstByIssueIdOrderByVersionNumberDesc(issue.getId())
                .orElseThrow(() -> new IllegalStateException("Issue has no planning version"));
        if (current.getState() != PlanningVersionState.PENDING) {
            throw new IllegalStateException("Issue has no current pending planning version");
        }
        return current;
    }

    private void failPlanning(TrackedIssue issue, Exception failure) {
        String detail = normalize(failure.getMessage());
        if (detail == null) {
            detail = failure.getClass().getSimpleName();
        }
        String reason = bound("Planning failed: " + detail, MAX_FAILURE_REASON_CHARS);
        log.warn("Planning failed for {} #{}: {}",
                issue.getRepo().fullName(), issue.getIssueNumber(), detail);
        issue.setStatus(IssueStatus.FAILED);
        issue.setCurrentPhase(null);
        issue.setLastFailureReason(reason);
        issues.save(issue);
        runAfterPersistence("record planning failure event",
                () -> events.log("PLAN_FAILED", reason, issue.getRepo(), issue));
        runAfterPersistence("send planning failure notification",
                () -> notifications.warn("Planning Failed",
                        issue.getRepo().fullName() + " #" + issue.getIssueNumber() + " — " + reason,
                        issue));
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
                "Planning version " + version.getVersionNumber()
                        + " approved — implementation will start shortly.");
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

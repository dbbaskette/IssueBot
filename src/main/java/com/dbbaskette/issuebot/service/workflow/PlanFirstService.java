package com.dbbaskette.issuebot.service.workflow;

import com.dbbaskette.issuebot.model.IssueStatus;
import com.dbbaskette.issuebot.model.TrackedIssue;
import com.dbbaskette.issuebot.model.WatchedRepo;
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

/**
 * Plan-first mode (#64): "pilot before scaling" — for a plan-first repo (or an issue
 * with the per-issue override set), run a cheap plan-only pass on the utility model
 * before spending expensive implementation-model tokens. The plan is posted as a
 * GitHub comment and stored on the tracked issue; the operator approves or rejects
 * it from the dashboard, mirroring the decomposition-proposal flow in
 * {@link IssueDecompositionService}.
 */
@Service
public class PlanFirstService {

    private static final Logger log = LoggerFactory.getLogger(PlanFirstService.class);
    private static final int MAX_PLAN_REJECTIONS = 2;
    private static final int MAX_PLAN_CHARS = 20_000;

    private final ClaudeCodeService claudeCode;
    private final GitHubApiClient gitHubApi;
    private final TrackedIssueRepository issueRepository;
    private final EventService eventService;
    private final NotificationService notificationService;
    private final IterationManager iterationManager;

    public PlanFirstService(ClaudeCodeService claudeCode,
                             GitHubApiClient gitHubApi,
                             TrackedIssueRepository issueRepository,
                             EventService eventService,
                             NotificationService notificationService,
                             IterationManager iterationManager) {
        this.claudeCode = claudeCode;
        this.gitHubApi = gitHubApi;
        this.issueRepository = issueRepository;
        this.eventService = eventService;
        this.notificationService = notificationService;
        this.iterationManager = iterationManager;
    }

    /**
     * Run the plan-only pass and, on success, park the issue at
     * {@link IssueStatus#AWAITING_PLAN_APPROVAL} awaiting operator approval.
     *
     * @return true if a plan was proposed (caller must return immediately without
     *         entering the implementation loop); false if the planner failed, in
     *         which case the issue is left untouched and the caller should proceed
     *         straight to implementation — a planner hiccup must never block the issue.
     */
    public boolean proposePlan(TrackedIssue trackedIssue, JsonNode issueDetails, Path repoPath) {
        WatchedRepo repo = trackedIssue.getRepo();
        int issueNumber = trackedIssue.getIssueNumber();

        String prompt = buildPlanPrompt(issueDetails, trackedIssue.getPlanRejections(),
                trackedIssue.getPlanFeedback());

        String plan;
        try {
            ClaudeCodeResult result = claudeCode.executeUtility(prompt, repoPath, null);
            if (result == null || result.getOutput() == null || result.getOutput().isBlank()) {
                log.warn("Plan proposal returned empty response for {} #{}, proceeding without a plan",
                        repo.fullName(), issueNumber);
                eventService.log("PLAN_FAILED",
                        "Planner returned no output — proceeding without a plan", repo, trackedIssue);
                return false;
            }
            plan = result.getOutput();
        } catch (Exception e) {
            log.warn("Plan proposal failed for {} #{}: {}", repo.fullName(), issueNumber, e.getMessage());
            eventService.log("PLAN_FAILED",
                    "Planner invocation failed: " + e.getMessage() + " — proceeding without a plan",
                    repo, trackedIssue);
            return false;
        }

        String truncatedPlan = truncate(plan, MAX_PLAN_CHARS);
        trackedIssue.setImplementationPlan(truncatedPlan);
        trackedIssue.setStatus(IssueStatus.AWAITING_PLAN_APPROVAL);
        trackedIssue.setCurrentPhase(null);
        issueRepository.save(trackedIssue);

        try {
            gitHubApi.addComment(repo.getOwner(), repo.getName(), issueNumber, buildPlanComment(truncatedPlan));
        } catch (Exception e) {
            log.warn("Failed to post plan comment to {} #{}: {}", repo.fullName(), issueNumber, e.getMessage());
        }

        eventService.log("PLAN_PROPOSED",
                "Proposed an implementation plan — awaiting approval", repo, trackedIssue);
        notificationService.info("Plan Proposed",
                repo.fullName() + " #" + issueNumber + " — approve or reject in the dashboard");
        return true;
    }

    /**
     * Approve a previously proposed plan: mark it approved and queue the issue for
     * implementation. The poller's {@code resumePendingIssues} picks it up on the
     * next cycle — no direct {@code processIssueAsync} call here, so the resume runs
     * off the poller's thread rather than the controller's.
     *
     * Synchronized (single-JVM app) and re-reads the issue from the database so a
     * second rapid submit hits the status guard instead of double-queuing.
     *
     * @throws IllegalStateException if the issue is not awaiting plan approval or has no plan
     */
    public synchronized void approvePlan(TrackedIssue trackedIssue) {
        TrackedIssue issue = issueRepository.findById(trackedIssue.getId()).orElse(trackedIssue);
        if (issue.getStatus() != IssueStatus.AWAITING_PLAN_APPROVAL || issue.getImplementationPlan() == null) {
            throw new IllegalStateException(
                    "Issue is not awaiting plan approval: " + issue.getStatus());
        }

        WatchedRepo repo = issue.getRepo();
        int issueNumber = issue.getIssueNumber();

        issue.setPlanApproved(true);
        issue.setStatus(IssueStatus.PENDING);
        issueRepository.save(issue);

        try {
            gitHubApi.addComment(repo.getOwner(), repo.getName(), issueNumber,
                    "Plan approved — implementation will start shortly.");
        } catch (Exception e) {
            log.warn("Failed to post plan-approval comment to {} #{}: {}",
                    repo.fullName(), issueNumber, e.getMessage());
        }

        eventService.log("PLAN_APPROVED",
                "Plan approved — queued, resumes on the next poll cycle (~60s)", repo, issue);
        notificationService.info("Plan Approved",
                repo.fullName() + " #" + issueNumber + " — queued for implementation");
    }

    /**
     * Reject a previously proposed plan with operator feedback. The first rejection
     * clears the plan and queues the issue so the next run regenerates a plan that
     * incorporates the feedback (the plan gate in {@code IssueWorkflowService} sees
     * {@code planApproved == false} and calls {@link #proposePlan} again). The second
     * rejection escalates to needs-human via {@link IterationManager#handlePlanRejectedTwice}
     * instead of regenerating a third time.
     *
     * Synchronized (single-JVM app) and re-reads the issue from the database so a
     * second rapid submit hits the status guard instead of double-escalating.
     *
     * @throws IllegalStateException if the issue is not awaiting plan approval or has no plan
     */
    public synchronized void rejectPlan(TrackedIssue trackedIssue, String feedback) {
        TrackedIssue issue = issueRepository.findById(trackedIssue.getId()).orElse(trackedIssue);
        if (issue.getStatus() != IssueStatus.AWAITING_PLAN_APPROVAL || issue.getImplementationPlan() == null) {
            throw new IllegalStateException(
                    "Issue is not awaiting plan approval: " + issue.getStatus());
        }

        int rejections = issue.getPlanRejections() + 1;
        issue.setPlanRejections(rejections);
        issue.setPlanFeedback(feedback);
        issue.setImplementationPlan(null);

        if (rejections >= MAX_PLAN_REJECTIONS) {
            issueRepository.save(issue);
            iterationManager.handlePlanRejectedTwice(issue);
            return;
        }

        issue.setStatus(IssueStatus.PENDING);
        issueRepository.save(issue);

        eventService.log("PLAN_REJECTED",
                "Plan rejected — regenerating with feedback, queued (~60s)", issue.getRepo(), issue);
        notificationService.info("Plan Rejected",
                issue.getRepo().fullName() + " #" + issue.getIssueNumber() + " — regenerating plan");
    }

    String buildPlanPrompt(JsonNode issueDetails, int planRejections, String planFeedback) {
        String title = issueDetails.path("title").asText();
        String body = issueDetails.path("body").asText("No description");

        StringBuilder sb = new StringBuilder();
        sb.append("You are planning, NOT implementing. Read the codebase as needed, then produce a ")
          .append("concise implementation plan: files to touch, approach, risks, test plan. ")
          .append("Make NO code changes. Respond with the plan as markdown.\n\n");
        sb.append("## Issue\n");
        sb.append("Title: ").append(title).append("\n");
        sb.append("Body:\n").append(body).append("\n\n");

        if (planRejections > 0 && planFeedback != null && !planFeedback.isBlank()) {
            sb.append("## Operator feedback on the previous plan\n").append(planFeedback).append("\n\n");
        }

        return sb.toString();
    }

    private String buildPlanComment(String plan) {
        return "## IssueBot: Proposed Implementation Plan\n\n" + plan
                + "\n\nApprove or reject from the IssueBot dashboard.\n\n"
                + "---\n*Generated by [IssueBot](https://github.com/dbbaskette/IssueBot)*";
    }

    private String truncate(String text, int maxLength) {
        if (text == null) return null;
        return text.length() <= maxLength ? text : text.substring(0, maxLength) + "\n... (truncated)";
    }
}

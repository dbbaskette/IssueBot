package com.dbbaskette.issuebot.service.workflow;

import com.dbbaskette.issuebot.model.IssueStatus;
import com.dbbaskette.issuebot.model.Iteration;
import com.dbbaskette.issuebot.model.TrackedIssue;
import com.dbbaskette.issuebot.model.WatchedRepo;
import com.dbbaskette.issuebot.repository.IterationRepository;
import com.dbbaskette.issuebot.repository.TrackedIssueRepository;
import com.dbbaskette.issuebot.service.event.EventService;
import com.dbbaskette.issuebot.service.github.GitHubApiClient;
import com.dbbaskette.issuebot.service.notification.NotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

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

    private static final Logger log = LoggerFactory.getLogger(IterationManager.class);
    private static final int DEFAULT_COOLDOWN_HOURS = 24;

    private final TrackedIssueRepository issueRepository;
    private final IterationRepository iterationRepository;
    private final GitHubApiClient gitHubApi;
    private final EventService eventService;
    private final NotificationService notificationService;

    public IterationManager(TrackedIssueRepository issueRepository,
                             IterationRepository iterationRepository,
                             GitHubApiClient gitHubApi,
                             EventService eventService,
                             NotificationService notificationService) {
        this.issueRepository = issueRepository;
        this.iterationRepository = iterationRepository;
        this.gitHubApi = gitHubApi;
        this.eventService = eventService;
        this.notificationService = notificationService;
    }

    /**
     * Check if the issue can undergo another iteration.
     */
    public boolean canIterate(TrackedIssue trackedIssue) {
        int maxIterations = trackedIssue.getRepo().getMaxIterations();
        return trackedIssue.getCurrentIteration() < maxIterations;
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
     */
    public boolean canReviewIterate(TrackedIssue trackedIssue) {
        int maxReviewIterations = trackedIssue.getRepo().getMaxReviewIterations();
        return trackedIssue.getCurrentReviewIteration() < maxReviewIterations;
    }

    /**
     * Handle the case when max review iterations have been reached.
     */
    public void handleMaxReviewIterationsReached(TrackedIssue trackedIssue) {
        int maxReviewIterations = trackedIssue.getRepo().getMaxReviewIterations();
        String comment = buildMaxReviewIterationsComment(trackedIssue, maxReviewIterations);

        escalateFailure(trackedIssue,
                "Review Budget Exhausted",
                "Independent review could not be satisfied after "
                        + maxReviewIterations + " iterations, needs human attention",
                "MAX_REVIEW_ITERATIONS_REACHED",
                "Review failed after " + maxReviewIterations + " iterations, entering cooldown",
                comment);
    }

    /**
     * Shared escalation logic: mark FAILED, label, comment, cooldown, notify, log.
     */
    private void escalateFailure(TrackedIssue trackedIssue, String notificationTitle,
                                   String notificationDetail, String eventType,
                                   String eventMessage, String issueComment) {
        WatchedRepo repo = trackedIssue.getRepo();
        int issueNumber = trackedIssue.getIssueNumber();

        log.warn("{} for {} #{}", notificationTitle, repo.fullName(), issueNumber);

        trackedIssue.setStatus(IssueStatus.FAILED);
        trackedIssue.setCurrentPhase(null);
        issueRepository.save(trackedIssue);

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
                repo.fullName() + " #" + issueNumber + " — " + notificationDetail);

        eventService.log(eventType, eventMessage, repo, trackedIssue);
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

    private String buildMaxReviewIterationsComment(TrackedIssue trackedIssue, int maxReviewIterations) {
        StringBuilder sb = new StringBuilder();
        sb.append("## IssueBot: Review Budget Exhausted\n\n");
        sb.append("The independent code review (Sonnet 4.6) could not be satisfied after **")
                .append(maxReviewIterations).append(" review iterations**.\n\n");

        // Add last review details
        List<Iteration> iterations = iterationRepository
                .findByIssueOrderByIterationNumAsc(trackedIssue);
        if (!iterations.isEmpty()) {
            Iteration last = iterations.get(iterations.size() - 1);
            if (last.getReviewJson() != null) {
                sb.append("### Last Review Findings\n");
                sb.append("```json\n").append(truncate(last.getReviewJson(), 1000)).append("\n```\n\n");
            }
            if (last.getCiResult() != null) {
                sb.append("**CI Result:** ").append(last.getCiResult()).append("\n\n");
            }
        }

        sb.append("### Next Steps\n");
        sb.append("- Review the draft PR for the branch `").append(trackedIssue.getBranchName()).append("`\n");
        sb.append("- Examine the review findings and either approve manually or provide guidance\n");
        sb.append("- Remove the `needs-human` label and add `agent-ready` to retry\n\n");
        sb.append("---\n*Generated by [IssueBot](https://github.com/dbbaskette/IssueBot)*");

        return sb.toString();
    }

    private String truncate(String text, int maxLength) {
        if (text == null) return "";
        return text.length() <= maxLength ? text : text.substring(0, maxLength) + "...";
    }
}

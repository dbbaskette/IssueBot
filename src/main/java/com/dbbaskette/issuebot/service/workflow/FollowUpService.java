package com.dbbaskette.issuebot.service.workflow;

import com.dbbaskette.issuebot.model.FollowUpMode;
import com.dbbaskette.issuebot.model.TrackedIssue;
import com.dbbaskette.issuebot.model.WatchedRepo;
import com.dbbaskette.issuebot.service.event.EventService;
import com.dbbaskette.issuebot.service.github.GitHubApiClient;
import com.dbbaskette.issuebot.service.review.CodeReviewResult;
import com.dbbaskette.issuebot.service.review.CodeReviewResult.ReviewFinding;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Routes non-blocking (medium/low severity) review findings to the appropriate
 * destination based on the repo's {@link FollowUpMode}:
 * <ul>
 *     <li>{@code OFF} — findings live only in the PR review comment, nothing further happens.</li>
 *     <li>{@code COMMENT_ONLY} — a single summary comment is posted on the original issue.</li>
 *     <li>{@code ROLLING_BACKLOG} — medium, low, and minor findings from passed and failed
 *         reviews are appended (deduplicated) to the repo's single rolling backlog issue.</li>
 *     <li>{@code PER_ISSUE} — legacy behavior: one new follow-up issue is created per
 *         completed issue.</li>
 * </ul>
 */
@Service
public class FollowUpService {

    private static final Logger log = LoggerFactory.getLogger(FollowUpService.class);
    static final String FOLLOW_UP_LABEL = "issuebot-followup";
    static final String FOLLOW_UP_TITLE_PREFIX = "Follow-Up:";
    private static final List<String> SELF_FEEDING_LABELS =
            List.of(FOLLOW_UP_LABEL, BacklogService.BACKLOG_LABEL);

    private final GitHubApiClient gitHubApi;
    private final BacklogService backlogService;
    private final EventService eventService;

    public FollowUpService(GitHubApiClient gitHubApi, BacklogService backlogService,
                            EventService eventService) {
        this.gitHubApi = gitHubApi;
        this.backlogService = backlogService;
        this.eventService = eventService;
    }

    /**
     * Route non-blocking review findings per the repo's configured {@link FollowUpMode}.
     */
    public void handleNonBlockingFindings(TrackedIssue trackedIssue, JsonNode issueDetails,
                                           CodeReviewResult review, int prNumber) {
        WatchedRepo repo = trackedIssue.getRepo();
        FollowUpMode mode = repo.getFollowUpMode();
        if (mode == FollowUpMode.OFF) {
            return;
        }

        if (isSelfFeeding(issueDetails)) {
            log.info("Skipping follow-up handling for {} #{} because it is already a "
                            + "follow-up/backlog issue",
                    repo.fullName(), trackedIssue.getIssueNumber());
            return;
        }

        if (review.invocationFailed() || (mode != FollowUpMode.ROLLING_BACKLOG && !review.passed())) return;
        List<ReviewFinding> nonBlocking = review.findings().stream()
                .filter(f -> "medium".equalsIgnoreCase(f.severity()) || "low".equalsIgnoreCase(f.severity())
                        || "minor".equalsIgnoreCase(f.severity()))
                .toList();

        switch (mode) {
            case ROLLING_BACKLOG -> {
                if (nonBlocking.isEmpty()) return;
                backlogService.addFindings(repo, nonBlocking, trackedIssue.getIssueNumber(), prNumber);
            }
            case COMMENT_ONLY -> {
                if (nonBlocking.isEmpty()) return;
                postSummaryComment(trackedIssue, nonBlocking, prNumber);
            }
            case PER_ISSUE -> {
                if (nonBlocking.isEmpty()) return;
                createLegacyFollowUpIssue(trackedIssue, issueDetails, nonBlocking, prNumber);
            }
            case OFF -> { }
        }
    }

    /**
     * True when the issue is itself a follow-up or backlog issue created by IssueBot —
     * such issues must not feed further findings back into themselves.
     */
    boolean isSelfFeeding(JsonNode issueDetails) {
        if (issueDetails == null || issueDetails.isMissingNode()) {
            return false;
        }

        String title = issueDetails.path("title").asText("");
        if (title.startsWith(FOLLOW_UP_TITLE_PREFIX)) {
            return true;
        }

        JsonNode labels = issueDetails.path("labels");
        if (labels.isArray()) {
            for (JsonNode label : labels) {
                String labelName = label.path("name").asText();
                for (String selfFeedingLabel : SELF_FEEDING_LABELS) {
                    if (selfFeedingLabel.equalsIgnoreCase(labelName)) {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    /**
     * Legacy behavior: create a new GitHub issue for non-blocking review findings.
     * Posts a comment on the original issue linking to the follow-up.
     */
    private void createLegacyFollowUpIssue(TrackedIssue trackedIssue, JsonNode issueDetails,
                                            List<ReviewFinding> nonBlocking, int prNumber) {
        WatchedRepo repo = trackedIssue.getRepo();
        int originalIssueNumber = trackedIssue.getIssueNumber();

        String title = FOLLOW_UP_TITLE_PREFIX + " Code Review Findings from #" + originalIssueNumber;

        StringBuilder body = new StringBuilder();
        body.append("The following non-blocking items were identified during the automated code review for #")
            .append(originalIssueNumber).append(" (PR #").append(prNumber)
            .append(") and should be addressed in a future iteration.\n\n");
        body.append("#### Findings\n\n");

        for (ReviewFinding f : nonBlocking) {
            body.append("**[").append(f.severity().toUpperCase()).append(" — ").append(f.category()).append("]");
            if (f.file() != null && !f.file().isBlank()) {
                body.append(" `").append(f.file());
                if (f.line() != null) body.append(":").append(f.line());
                body.append("`");
            }
            body.append("**\n");
            body.append(f.finding()).append("\n");
            if (f.suggestion() != null && !f.suggestion().isBlank()) {
                body.append("> **Suggestion:** ").append(f.suggestion()).append("\n");
            }
            body.append("\n");
        }

        body.append("---\n*Auto-created by [IssueBot](https://github.com/dbbaskette/IssueBot) from review of #")
            .append(originalIssueNumber).append("*");

        JsonNode newIssue = gitHubApi.createIssue(
                repo.getOwner(), repo.getName(), title, body.toString(),
                List.of(FOLLOW_UP_LABEL));

        int followUpNumber = newIssue.path("number").asInt();
        log.info("Created follow-up issue #{} for {} #{} with {} findings",
                followUpNumber, repo.fullName(), originalIssueNumber, nonBlocking.size());

        gitHubApi.addComment(repo.getOwner(), repo.getName(), originalIssueNumber,
                "Non-blocking review findings have been captured in follow-up issue #" + followUpNumber);

        eventService.log("FOLLOW_UP_ISSUE_CREATED",
                "Created follow-up issue #" + followUpNumber + " with " + nonBlocking.size() + " findings",
                repo, trackedIssue);
    }

    /**
     * Post a single summary comment on the original issue for {@code COMMENT_ONLY} mode.
     */
    private void postSummaryComment(TrackedIssue trackedIssue, List<ReviewFinding> nonBlocking, int prNumber) {
        WatchedRepo repo = trackedIssue.getRepo();
        int originalIssueNumber = trackedIssue.getIssueNumber();

        StringBuilder body = new StringBuilder();
        body.append("### Non-Blocking Review Findings\n\n");
        body.append("The following non-blocking items were identified during the automated code review (PR #")
            .append(prNumber).append(").\n\n");

        for (ReviewFinding f : nonBlocking) {
            body.append("**[").append(f.severity().toUpperCase()).append(" — ").append(f.category()).append("]");
            if (f.file() != null && !f.file().isBlank()) {
                body.append(" `").append(f.file());
                if (f.line() != null) body.append(":").append(f.line());
                body.append("`");
            }
            body.append("**\n");
            body.append(f.finding()).append("\n");
            if (f.suggestion() != null && !f.suggestion().isBlank()) {
                body.append("> **Suggestion:** ").append(f.suggestion()).append("\n");
            }
            body.append("\n");
        }

        body.append("---\n*These findings are informational and do not block completion — captured by ")
            .append("[IssueBot](https://github.com/dbbaskette/IssueBot)*");

        gitHubApi.addComment(repo.getOwner(), repo.getName(), originalIssueNumber, body.toString());
        log.info("Posted non-blocking findings summary comment to {} #{} with {} findings",
                repo.fullName(), originalIssueNumber, nonBlocking.size());
    }
}

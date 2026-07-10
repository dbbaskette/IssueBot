package com.dbbaskette.issuebot.service.workflow;

import com.dbbaskette.issuebot.model.FollowUpMode;
import com.dbbaskette.issuebot.model.TrackedIssue;
import com.dbbaskette.issuebot.model.WatchedRepo;
import com.dbbaskette.issuebot.service.event.EventService;
import com.dbbaskette.issuebot.service.github.GitHubApiClient;
import com.dbbaskette.issuebot.service.review.CodeReviewResult;
import com.dbbaskette.issuebot.service.review.CodeReviewResult.ReviewFinding;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FollowUpServiceTest {

    private GitHubApiClient gitHubApi;
    private BacklogService backlogService;
    private EventService eventService;
    private FollowUpService followUpService;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        gitHubApi = mock(GitHubApiClient.class);
        backlogService = mock(BacklogService.class);
        eventService = mock(EventService.class);
        followUpService = new FollowUpService(gitHubApi, backlogService, eventService);
        objectMapper = new ObjectMapper();
    }

    private WatchedRepo repoWithMode(FollowUpMode mode) {
        WatchedRepo repo = new WatchedRepo("owner", "repo");
        repo.setId(1L);
        repo.setFollowUpMode(mode);
        return repo;
    }

    private TrackedIssue trackedIssue(WatchedRepo repo) {
        TrackedIssue issue = new TrackedIssue(repo, 42, "Fix the bug");
        issue.setId(1L);
        return issue;
    }

    private ObjectNode issueDetails(String title, String... labels) {
        ObjectNode details = objectMapper.createObjectNode();
        details.put("title", title);
        ArrayNode labelArray = details.putArray("labels");
        for (String label : labels) {
            labelArray.addObject().put("name", label);
        }
        return details;
    }

    private CodeReviewResult reviewWithFindings(ReviewFinding... findings) {
        return new CodeReviewResult(
                true, "All good",
                0.9, 0.9, 0.9, 0.9, 0.9, 0.9, 1.0,
                List.of(findings),
                "advice",
                "{\"passed\":true}",
                1000, 500, "claude-sonnet-4-6", null);
    }

    // === Mode routing ===

    @Test
    void offModeCreatesNothing() {
        WatchedRepo repo = repoWithMode(FollowUpMode.OFF);
        TrackedIssue issue = trackedIssue(repo);
        ObjectNode details = issueDetails("Fix the bug");
        ReviewFinding medium = new ReviewFinding("medium", "code_quality",
                "src/Foo.java", 10, "Finding", "Suggestion");
        CodeReviewResult review = reviewWithFindings(medium);

        followUpService.handleNonBlockingFindings(issue, details, review, 99);

        verifyNoInteractions(gitHubApi);
        verifyNoInteractions(backlogService);
    }

    @Test
    void rollingBacklogSendsOnlyMediumFindings() {
        WatchedRepo repo = repoWithMode(FollowUpMode.ROLLING_BACKLOG);
        TrackedIssue issue = trackedIssue(repo);
        ObjectNode details = issueDetails("Fix the bug");
        ReviewFinding medium = new ReviewFinding("medium", "code_quality",
                "src/Foo.java", 10, "Medium finding", "Suggestion");
        ReviewFinding low = new ReviewFinding("low", "style",
                "src/Bar.java", 20, "Low finding", null);
        CodeReviewResult review = reviewWithFindings(medium, low);

        followUpService.handleNonBlockingFindings(issue, details, review, 99);

        @SuppressWarnings("unchecked")
        var captor = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(backlogService).addFindings(eq(repo), captor.capture(), eq(42), eq(99));
        assertThat(captor.getValue()).containsExactly(medium);
        verify(gitHubApi, never()).createIssue(any(), any(), any(), any(), any());
    }

    @Test
    void perIssueModeKeepsLegacyBehavior() {
        WatchedRepo repo = repoWithMode(FollowUpMode.PER_ISSUE);
        TrackedIssue issue = trackedIssue(repo);
        ObjectNode details = issueDetails("Fix the bug");
        ReviewFinding medium = new ReviewFinding("medium", "code_quality",
                "src/Foo.java", 10, "Medium finding", "Suggestion");
        CodeReviewResult review = reviewWithFindings(medium);

        ObjectNode created = objectMapper.createObjectNode();
        created.put("number", 555);
        when(gitHubApi.createIssue(eq("owner"), eq("repo"), anyString(), anyString(), anyList()))
                .thenReturn(created);

        followUpService.handleNonBlockingFindings(issue, details, review, 99);

        @SuppressWarnings("unchecked")
        var titleCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("unchecked")
        var labelsCaptor = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(gitHubApi).createIssue(eq("owner"), eq("repo"), titleCaptor.capture(),
                anyString(), labelsCaptor.capture());
        assertThat(titleCaptor.getValue()).startsWith("Follow-Up:");
        assertThat(labelsCaptor.getValue()).contains(FollowUpService.FOLLOW_UP_LABEL);

        // Linking comment posted on the original issue
        verify(gitHubApi).addComment(eq("owner"), eq("repo"), eq(42),
                contains("555"));
    }

    @Test
    void commentOnlyPostsSingleComment() {
        WatchedRepo repo = repoWithMode(FollowUpMode.COMMENT_ONLY);
        TrackedIssue issue = trackedIssue(repo);
        ObjectNode details = issueDetails("Fix the bug");
        ReviewFinding medium = new ReviewFinding("medium", "code_quality",
                "src/Foo.java", 10, "Medium finding", "Suggestion");
        ReviewFinding low = new ReviewFinding("low", "style",
                "src/Bar.java", 20, "Low finding", null);
        CodeReviewResult review = reviewWithFindings(medium, low);

        followUpService.handleNonBlockingFindings(issue, details, review, 99);

        verify(gitHubApi, times(1)).addComment(eq("owner"), eq("repo"), eq(42), anyString());
        verify(gitHubApi, never()).createIssue(any(), any(), any(), any(), any());
    }

    @Test
    void backlogAndFollowUpIssuesNeverFeedThemselves() {
        WatchedRepo repo = repoWithMode(FollowUpMode.ROLLING_BACKLOG);
        TrackedIssue issue = trackedIssue(repo);
        ObjectNode details = issueDetails("IssueBot Backlog", "issuebot-backlog");
        ReviewFinding medium = new ReviewFinding("medium", "code_quality",
                "src/Foo.java", 10, "Medium finding", "Suggestion");
        CodeReviewResult review = reviewWithFindings(medium);

        followUpService.handleNonBlockingFindings(issue, details, review, 99);

        verifyNoInteractions(gitHubApi);
        verifyNoInteractions(backlogService);
    }

    @Test
    void noMediumFindingsMeansNoBacklogCall() {
        WatchedRepo repo = repoWithMode(FollowUpMode.ROLLING_BACKLOG);
        TrackedIssue issue = trackedIssue(repo);
        ObjectNode details = issueDetails("Fix the bug");
        ReviewFinding low = new ReviewFinding("low", "style",
                "src/Bar.java", 20, "Low finding", null);
        CodeReviewResult review = reviewWithFindings(low);

        followUpService.handleNonBlockingFindings(issue, details, review, 99);

        verifyNoInteractions(backlogService);
    }

    // === isSelfFeeding (migrated from IssueWorkflowServiceTest.isFollowUpIssue_*) ===

    @Test
    void isSelfFeeding_trueWhenIssueHasFollowUpLabel() {
        ObjectNode issue = issueDetails("Tighten null handling", "bug", "issuebot-followup");
        assertThat(followUpService.isSelfFeeding(issue)).isTrue();
    }

    @Test
    void isSelfFeeding_trueWhenTitleHasFollowUpPrefix() {
        ObjectNode issue = issueDetails("Follow-Up: Code Review Findings from #42");
        assertThat(followUpService.isSelfFeeding(issue)).isTrue();
    }

    @Test
    void isSelfFeeding_trueWhenIssueHasBacklogLabel() {
        ObjectNode issue = issueDetails("IssueBot Backlog", "issuebot-backlog");
        assertThat(followUpService.isSelfFeeding(issue)).isTrue();
    }

    @Test
    void isSelfFeeding_falseForRegularIssue() {
        ObjectNode issue = issueDetails("Fix retry modal z-index", "bug");
        assertThat(followUpService.isSelfFeeding(issue)).isFalse();
    }

    @Test
    void isSelfFeeding_nullSafeForMissingNode() {
        assertThat(followUpService.isSelfFeeding(null)).isFalse();
        assertThat(followUpService.isSelfFeeding(com.fasterxml.jackson.databind.node.MissingNode.getInstance()))
                .isFalse();
    }
}

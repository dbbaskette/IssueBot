package com.dbbaskette.issuebot.service.workflow;

import com.dbbaskette.issuebot.model.TrackedIssue;
import com.dbbaskette.issuebot.model.WatchedRepo;
import com.dbbaskette.issuebot.service.github.GitHubApiClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ManagedMergeGuardTest {
    private static final String SHA = "a".repeat(40);
    private final GitHubApiClient github = mock(GitHubApiClient.class);
    private final ManagedMergeGuard guard = new ManagedMergeGuard(github);
    private final ObjectMapper json = new ObjectMapper();

    private TrackedIssue issue(String checks) throws Exception {
        WatchedRepo repo = new WatchedRepo();
        repo.setOwner("acme"); repo.setName("widgets"); repo.setCiEnabled(true);
        TrackedIssue issue = new TrackedIssue(); issue.setRepo(repo); issue.setPrNumber(55);
        when(github.getPullRequest("acme", "widgets", 55)).thenReturn(json.readTree("{\"head\":{\"sha\":\"" + SHA + "\"}}"));
        when(github.getCheckRuns("acme", "widgets", SHA)).thenReturn(json.readTree(checks));
        return issue;
    }

    @Test void matchingReviewedHeadAndSuccessfulChecksPermitConditionalMerge() throws Exception {
        TrackedIssue issue = issue("{\"total_count\":1,\"check_runs\":[{\"status\":\"completed\",\"conclusion\":\"success\"}]}");
        assertThat(guard.validateForMerge(issue, SHA)).isEqualTo(SHA);
    }

    @Test void changedHeadCannotReuseOldReview() throws Exception {
        TrackedIssue issue = issue("{\"check_runs\":[]}");
        when(github.getPullRequest("acme", "widgets", 55)).thenReturn(json.readTree("{\"head\":{\"sha\":\"" + "b".repeat(40) + "\"}}"));
        assertThatThrownBy(() -> guard.validateForMerge(issue, SHA)).hasMessageContaining("head changed");
        verify(github, never()).getCheckRuns(anyString(), anyString(), anyString());
    }

    @Test void closedPullRequestCannotBeMergedAfterReview() throws Exception {
        TrackedIssue issue = issue("{\"check_runs\":[]}");
        when(github.getPullRequest("acme", "widgets", 55)).thenReturn(json.readTree(
                "{\"state\":\"closed\",\"head\":{\"sha\":\"" + SHA + "\"}}"));
        assertThatThrownBy(() -> guard.validateForMerge(issue, SHA))
                .hasMessageContaining("pull request is closed");
        verify(github, never()).getCheckRuns(anyString(), anyString(), anyString());
    }

    @Test void pendingFailedAndIncompleteChecksFailClosed() throws Exception {
        for (String checks : new String[]{
                "{\"check_runs\":[{\"status\":\"in_progress\"}]}",
                "{\"check_runs\":[{\"status\":\"completed\",\"conclusion\":\"failure\"}]}",
                "{\"check_runs\":[{\"status\":\"completed\",\"conclusion\":null}]}",
                "{\"total_count\":2,\"check_runs\":[{\"status\":\"completed\",\"conclusion\":\"success\"}]}", "{}"}) {
            TrackedIssue issue = issue(checks);
            assertThatThrownBy(() -> guard.validateForMerge(issue, SHA)).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test void pendingCheckIsDistinguishableFromACompletedFailure() throws Exception {
        TrackedIssue issue = issue("{\"check_runs\":[{\"status\":\"in_progress\"}]}");
        assertThatThrownBy(() -> guard.validateForMerge(issue, SHA))
                .isInstanceOf(ManagedMergeGuard.PendingChecksException.class)
                .hasMessageContaining("still pending");
        when(github.getCheckRuns("acme", "widgets", SHA)).thenReturn(json.readTree(
                "{\"check_runs\":[{\"status\":\"completed\",\"conclusion\":\"failure\"}]}"));
        assertThatThrownBy(() -> guard.validateForMerge(issue, SHA))
                .isNotInstanceOf(ManagedMergeGuard.PendingChecksException.class)
                .hasMessageContaining("did not pass");
    }

    @Test void noChecksAllowedOnlyForCiOptionalRepository() throws Exception {
        TrackedIssue issue = issue("{\"check_runs\":[]}");
        assertThatThrownBy(() -> guard.validateForMerge(issue, SHA)).hasMessageContaining("not appeared");
        issue.getRepo().setCiEnabled(false);
        assertThat(guard.validateForMerge(issue, SHA)).isEqualTo(SHA);
    }

    @Test void missingReviewCommitFailsBeforeRemoteCalls() {
        assertThatThrownBy(() -> guard.validateForMerge(new TrackedIssue(), null)).hasMessageContaining("reviewed commit is missing");
        verifyNoInteractions(github);
    }
}

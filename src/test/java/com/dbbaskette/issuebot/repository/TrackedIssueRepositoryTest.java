package com.dbbaskette.issuebot.repository;

import com.dbbaskette.issuebot.model.IssueStatus;
import com.dbbaskette.issuebot.model.TrackedIssue;
import com.dbbaskette.issuebot.model.WatchedRepo;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@TestPropertySource(properties = "issuebot.github.token=test-token")
class TrackedIssueRepositoryTest {

    @Autowired
    private TrackedIssueRepository issues;

    @Autowired
    private WatchedRepoRepository repos;

    @Test
    void repositoryLockQueryReturnsIssuesByGithubNumber() {
        WatchedRepo repo = repos.save(new WatchedRepo("acme", "widgets"));
        issue(repo, 143, IssueStatus.QUEUED);
        issue(repo, 141, IssueStatus.QUEUED);
        issue(repo, 142, IssueStatus.QUEUED);

        assertThat(issues.findByRepoIdForUpdateOrderByIssueNumber(repo.getId()))
                .extracting(TrackedIssue::getIssueNumber)
                .containsExactly(141, 142, 143);
    }

    @Test
    void repositoryIdQueryReturnsOwnerForIssue() {
        WatchedRepo repo = repos.save(new WatchedRepo("acme", "widgets"));
        TrackedIssue issue = issue(repo, 141, IssueStatus.QUEUED);

        assertThat(issues.findRepoIdByIssueId(issue.getId())).contains(repo.getId());
    }

    @Test
    void repositoryStatusQueryReturnsMatchingIssuesByGithubNumber() {
        WatchedRepo repo = repos.save(new WatchedRepo("acme", "widgets"));
        issue(repo, 143, IssueStatus.QUEUED);
        issue(repo, 141, IssueStatus.READY_TO_START);
        issue(repo, 142, IssueStatus.QUEUED);
        issue(repo, 144, IssueStatus.COMPLETED);

        assertThat(issues.findByRepoAndStatusInOrderByIssueNumberAsc(
                repo, List.of(IssueStatus.QUEUED, IssueStatus.READY_TO_START)))
                .extracting(TrackedIssue::getIssueNumber)
                .containsExactly(141, 142, 143);
    }

    private TrackedIssue issue(WatchedRepo repo, int number, IssueStatus status) {
        TrackedIssue issue = new TrackedIssue(repo, number, "Issue " + number);
        issue.setStatus(status);
        return issues.save(issue);
    }
}

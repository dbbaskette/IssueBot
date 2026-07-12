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

/**
 * Real H2/Hibernate exercise of the Needs You inbox's (#91) newest-first finders and the
 * {@code countNeedsYou} default method — mirrors {@link TrackedIssueRepositorySearchTest}'s
 * @DataJpaTest convention.
 */
@DataJpaTest
@TestPropertySource(properties = {
        "issuebot.github.token=test-token"
})
class TrackedIssueRepositoryInboxQueriesTest {

    @Autowired
    private TrackedIssueRepository issueRepository;

    @Autowired
    private WatchedRepoRepository repoRepository;

    private WatchedRepo repo() {
        return repoRepository.save(new WatchedRepo("acme", "widgets"));
    }

    private TrackedIssue issue(WatchedRepo repo, int number, IssueStatus status) {
        TrackedIssue issue = new TrackedIssue(repo, number, "Issue " + number);
        issue.setStatus(status);
        return issueRepository.save(issue);
    }

    @Test
    void findByStatusOrderByIdDesc_returnsNewestFirst() {
        WatchedRepo r = repo();
        issue(r, 1, IssueStatus.AWAITING_APPROVAL);
        issue(r, 2, IssueStatus.AWAITING_APPROVAL);
        issue(r, 3, IssueStatus.AWAITING_APPROVAL);

        List<TrackedIssue> result = issueRepository.findByStatusOrderByIdDesc(IssueStatus.AWAITING_APPROVAL);

        assertThat(result).extracting(TrackedIssue::getIssueNumber).containsExactly(3, 2, 1);
    }

    @Test
    void findByStatusInOrderByIdDesc_combinesStatusesNewestFirst() {
        WatchedRepo r = repo();
        issue(r, 1, IssueStatus.FAILED);
        issue(r, 2, IssueStatus.COOLDOWN);
        issue(r, 3, IssueStatus.QUEUED); // excluded

        List<TrackedIssue> result = issueRepository.findByStatusInOrderByIdDesc(
                List.of(IssueStatus.FAILED, IssueStatus.COOLDOWN));

        assertThat(result).extracting(TrackedIssue::getIssueNumber).containsExactly(2, 1);
    }

    @Test
    void countNeedsYou_sumsAllFiveBlockingStatuses() {
        WatchedRepo r = repo();
        issue(r, 1, IssueStatus.AWAITING_APPROVAL);
        issue(r, 2, IssueStatus.AWAITING_PLAN_APPROVAL);
        issue(r, 3, IssueStatus.AWAITING_DECOMPOSITION);
        issue(r, 4, IssueStatus.FAILED);
        issue(r, 5, IssueStatus.COOLDOWN);
        issue(r, 6, IssueStatus.QUEUED); // does not count

        assertThat(issueRepository.countNeedsYou()).isEqualTo(5L);
    }

    @Test
    void countNeedsYou_zeroWhenNothingPending() {
        WatchedRepo r = repo();
        issue(r, 1, IssueStatus.QUEUED);
        issue(r, 2, IssueStatus.COMPLETED);

        assertThat(issueRepository.countNeedsYou()).isEqualTo(0L);
    }
}

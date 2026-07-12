package com.dbbaskette.issuebot.repository;

import com.dbbaskette.issuebot.model.IssueStatus;
import com.dbbaskette.issuebot.model.TrackedIssue;
import com.dbbaskette.issuebot.model.WatchedRepo;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real H2/Hibernate exercise of {@link TrackedIssueRepository#search} — the paged,
 * filterable query backing the issue-queue upgrade (#87). No repository-level test
 * convention existed before this (see IssueBotApplicationTests for the only other
 * DB-backed test, a bare context-load smoke test) so this establishes one: a
 * @DataJpaTest slice against the real Flyway-migrated schema, verifying the
 * CAST(issueNumber AS string) exact-match clause actually works under H2/Hibernate
 * rather than trusting it from JPQL syntax alone.
 */
@DataJpaTest
@TestPropertySource(properties = {
        "issuebot.github.token=test-token"
})
class TrackedIssueRepositorySearchTest {

    @Autowired
    private TrackedIssueRepository issueRepository;

    @Autowired
    private WatchedRepoRepository repoRepository;

    private WatchedRepo repo(String owner, String name) {
        return repoRepository.save(new WatchedRepo(owner, name));
    }

    private TrackedIssue issue(WatchedRepo repo, int number, String title, IssueStatus status) {
        TrackedIssue issue = new TrackedIssue(repo, number, title);
        issue.setStatus(status);
        return issueRepository.save(issue);
    }

    @Test
    void noFilters_returnsAllOrderedByIdDescending() {
        WatchedRepo r = repo("acme", "widgets");
        issue(r, 1, "First issue", IssueStatus.QUEUED);
        issue(r, 2, "Second issue", IssueStatus.QUEUED);
        issue(r, 3, "Third issue", IssueStatus.QUEUED);

        Page<TrackedIssue> page = issueRepository.search(null, null, null, PageRequest.of(0, 25));

        assertThat(page.getTotalElements()).isEqualTo(3);
        assertThat(page.getContent()).extracting(TrackedIssue::getIssueNumber)
                .containsExactly(3, 2, 1);
    }

    @Test
    void statusFilter_onlyMatchesThatStatus() {
        WatchedRepo r = repo("acme", "widgets");
        issue(r, 1, "Failed one", IssueStatus.FAILED);
        issue(r, 2, "Queued one", IssueStatus.QUEUED);

        Page<TrackedIssue> page = issueRepository.search(IssueStatus.FAILED, null, null, PageRequest.of(0, 25));

        assertThat(page.getContent()).extracting(TrackedIssue::getIssueNumber).containsExactly(1);
    }

    @Test
    void repoFilter_onlyMatchesThatRepo() {
        WatchedRepo r1 = repo("acme", "widgets");
        WatchedRepo r2 = repo("acme", "gadgets");
        issue(r1, 1, "Widget issue", IssueStatus.QUEUED);
        issue(r2, 2, "Gadget issue", IssueStatus.QUEUED);

        Page<TrackedIssue> page = issueRepository.search(null, r2.getId(), null, PageRequest.of(0, 25));

        assertThat(page.getContent()).extracting(TrackedIssue::getIssueNumber).containsExactly(2);
    }

    @Test
    void searchByTitleSubstring_isCaseInsensitive() {
        WatchedRepo r = repo("acme", "widgets");
        issue(r, 1, "Fix the Login Bug", IssueStatus.QUEUED);
        issue(r, 2, "Improve dashboard", IssueStatus.QUEUED);

        Page<TrackedIssue> page = issueRepository.search(null, null, "login", PageRequest.of(0, 25));

        assertThat(page.getContent()).extracting(TrackedIssue::getIssueNumber).containsExactly(1);
    }

    @Test
    void searchByIssueNumber_exactMatch() {
        WatchedRepo r = repo("acme", "widgets");
        issue(r, 42, "Some title without the number", IssueStatus.QUEUED);
        issue(r, 420, "Another title", IssueStatus.QUEUED);

        Page<TrackedIssue> page = issueRepository.search(null, null, "42", PageRequest.of(0, 25));

        // Exact match on 42 only — 420 does not equal "42" via the CAST comparison,
        // and neither title contains the literal substring "42".
        assertThat(page.getContent()).extracting(TrackedIssue::getIssueNumber).containsExactly(42);
    }

    @Test
    void searchCombinesIssueNumberAndTitleMatches_viaOr() {
        WatchedRepo r = repo("acme", "widgets");
        issue(r, 7, "Unrelated title", IssueStatus.QUEUED);          // matches by number
        issue(r, 99, "Contains 7 in the title", IssueStatus.QUEUED); // matches by title substring

        Page<TrackedIssue> page = issueRepository.search(null, null, "7", PageRequest.of(0, 25));

        assertThat(page.getContent()).extracting(TrackedIssue::getIssueNumber)
                .containsExactlyInAnyOrder(7, 99);
    }

    @Test
    void combinesStatusRepoAndSearch() {
        WatchedRepo r1 = repo("acme", "widgets");
        WatchedRepo r2 = repo("acme", "gadgets");
        issue(r1, 1, "Fix login", IssueStatus.FAILED);
        issue(r1, 2, "Fix login", IssueStatus.QUEUED); // wrong status
        issue(r2, 3, "Fix login", IssueStatus.FAILED); // wrong repo

        Page<TrackedIssue> page = issueRepository.search(IssueStatus.FAILED, r1.getId(), "login", PageRequest.of(0, 25));

        assertThat(page.getContent()).extracting(TrackedIssue::getIssueNumber).containsExactly(1);
    }

    @Test
    void paging_returnsCorrectPageAndMetadata() {
        WatchedRepo r = repo("acme", "widgets");
        for (int i = 1; i <= 60; i++) {
            issue(r, i, "Issue " + i, IssueStatus.QUEUED);
        }

        Page<TrackedIssue> firstPage = issueRepository.search(null, null, null, PageRequest.of(0, 25));
        Page<TrackedIssue> secondPage = issueRepository.search(null, null, null, PageRequest.of(1, 25));
        Page<TrackedIssue> thirdPage = issueRepository.search(null, null, null, PageRequest.of(2, 25));

        assertThat(firstPage.getTotalElements()).isEqualTo(60);
        assertThat(firstPage.getTotalPages()).isEqualTo(3);
        assertThat(firstPage.getContent()).hasSize(25);
        assertThat(firstPage.hasPrevious()).isFalse();
        assertThat(firstPage.hasNext()).isTrue();

        assertThat(secondPage.getContent()).hasSize(25);
        assertThat(secondPage.hasPrevious()).isTrue();
        assertThat(secondPage.hasNext()).isTrue();

        assertThat(thirdPage.getContent()).hasSize(10);
        assertThat(thirdPage.hasNext()).isFalse();

        // No overlap between pages (ordering is stable via ORDER BY id DESC).
        List<Long> allIds = new java.util.ArrayList<>();
        firstPage.getContent().forEach(i -> allIds.add(i.getId()));
        secondPage.getContent().forEach(i -> allIds.add(i.getId()));
        thirdPage.getContent().forEach(i -> allIds.add(i.getId()));
        assertThat(allIds).doesNotHaveDuplicates();
    }

    /**
     * SQL LIKE wildcards typed into the search box must be treated as literal characters
     * (#87 review): a raw "%" would match every title and "_" any single character. The
     * public {@link TrackedIssueRepository#search} escapes them before binding.
     */
    @Test
    void searchTreatsPercentAsLiteral_notWildcard() {
        WatchedRepo r = repo("acme", "widgets");
        issue(r, 1, "Improve 50% of the cache", IssueStatus.QUEUED);
        issue(r, 2, "Improve 50 things", IssueStatus.QUEUED); // would match "50%" if % were a wildcard

        Page<TrackedIssue> page = issueRepository.search(null, null, "50%", PageRequest.of(0, 25));

        assertThat(page.getContent()).extracting(TrackedIssue::getIssueNumber).containsExactly(1);
    }

    @Test
    void searchTreatsUnderscoreAsLiteral_notWildcard() {
        WatchedRepo r = repo("acme", "widgets");
        issue(r, 1, "Rename the 5_% variable", IssueStatus.QUEUED);
        issue(r, 2, "Rename 55 variables", IssueStatus.QUEUED); // raw "5_%" pattern would match "55 ..."

        Page<TrackedIssue> page = issueRepository.search(null, null, "5_%", PageRequest.of(0, 25));

        assertThat(page.getContent()).extracting(TrackedIssue::getIssueNumber).containsExactly(1);
    }

    @Test
    void searchTreatsBackslashAsLiteral() {
        WatchedRepo r = repo("acme", "widgets");
        issue(r, 1, "Fix the C:\\temp path handling", IssueStatus.QUEUED);
        issue(r, 2, "Fix the Ctemp path handling", IssueStatus.QUEUED);

        Page<TrackedIssue> page = issueRepository.search(null, null, "C:\\temp", PageRequest.of(0, 25));

        assertThat(page.getContent()).extracting(TrackedIssue::getIssueNumber).containsExactly(1);
    }

    @Test
    void blankSearchString_isTreatedAsNoFilter() {
        WatchedRepo r = repo("acme", "widgets");
        issue(r, 1, "Anything", IssueStatus.QUEUED);

        Page<TrackedIssue> page = issueRepository.search(null, null, null, PageRequest.of(0, 25));

        assertThat(page.getTotalElements()).isEqualTo(1);
    }
}

package com.dbbaskette.issuebot.repository;

import com.dbbaskette.issuebot.model.PlanningVersion;
import com.dbbaskette.issuebot.model.PlanningVersionState;
import com.dbbaskette.issuebot.model.TrackedIssue;
import com.dbbaskette.issuebot.model.WatchedRepo;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Real H2/Flyway persistence contract for immutable planning versions.
 */
@DataJpaTest
@TestPropertySource(properties = {
        "issuebot.github.token=test-token"
})
class PlanningVersionRepositoryTest {

    @Autowired
    private PlanningVersionRepository versions;

    @Autowired
    private TrackedIssueRepository issues;

    @Autowired
    private WatchedRepoRepository repos;

    private TrackedIssue issue() {
        WatchedRepo repo = repos.save(new WatchedRepo("acme", "widgets"));
        return issues.save(new TrackedIssue(repo, 42, "Version the plan"));
    }

    @Test
    void versionsAreReturnedNewestFirstAndVersionNumberIsUniquePerIssue() {
        TrackedIssue issue = issue();
        versions.save(PlanningVersion.pending(issue, 1, "spec one", "plan one", "CODEX", "gpt-5.6-sol", null));
        versions.save(PlanningVersion.pending(issue, 2, "spec two", "plan two", "CODEX", "gpt-5.6-sol", "add rollback"));

        assertThat(versions.findByIssueIdOrderByVersionNumberDesc(issue.getId()))
                .extracting(PlanningVersion::getVersionNumber)
                .containsExactly(2, 1);
        assertThatThrownBy(() -> versions.saveAndFlush(
                PlanningVersion.pending(issue, 2, "duplicate", "duplicate", "CODEX", "gpt-5.6-sol", null)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void scopedAndPendingLookupsReturnOnlyRequestedVersions() {
        TrackedIssue firstIssue = issue();
        TrackedIssue secondIssue = issues.save(new TrackedIssue(firstIssue.getRepo(), 43, "Another issue"));
        PlanningVersion newest = versions.save(PlanningVersion.pending(
                firstIssue, 2, "new spec", "new plan", "CODEX", "gpt-5.6-sol", null));
        PlanningVersion approved = versions.save(PlanningVersion.pending(
                firstIssue, 1, "old spec", "old plan", "CODEX", "gpt-5.6-sol", null));
        approved.approve(java.time.LocalDateTime.now());
        versions.save(approved);
        PlanningVersion otherPending = versions.save(PlanningVersion.pending(
                secondIssue, 1, "other spec", "other plan", "CODEX", "gpt-5.6-sol", null));

        assertThat(versions.findFirstByIssueIdOrderByVersionNumberDesc(firstIssue.getId()))
                .contains(newest);
        assertThat(versions.findByIssueIdAndVersionNumber(firstIssue.getId(), 1))
                .contains(approved);
        assertThat(versions.findByIssueIdInAndState(
                List.of(firstIssue.getId(), secondIssue.getId()), PlanningVersionState.PENDING))
                .containsExactlyInAnyOrder(newest, otherPending);
    }
}

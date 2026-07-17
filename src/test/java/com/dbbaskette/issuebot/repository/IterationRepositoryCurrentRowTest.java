package com.dbbaskette.issuebot.repository;

import com.dbbaskette.issuebot.model.Iteration;
import com.dbbaskette.issuebot.model.TrackedIssue;
import com.dbbaskette.issuebot.model.WatchedRepo;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@TestPropertySource(properties = "issuebot.github.token=test-token")
class IterationRepositoryCurrentRowTest {

    @Autowired private IterationRepository iterations;
    @Autowired private TrackedIssueRepository issues;
    @Autowired private WatchedRepoRepository repos;

    @Test
    void newestStableIdSelectsCurrentRowWhenGuidedRetryReusesIterationNumber() {
        WatchedRepo repo = repos.save(new WatchedRepo("acme", "widgets"));
        TrackedIssue issue = issues.save(new TrackedIssue(repo, 42, "Retry the plan"));
        Iteration priorRun = new Iteration(issue, 2);
        priorRun.setDiff("stale run diff");
        iterations.saveAndFlush(priorRun);
        Iteration currentRun = new Iteration(issue, 2);
        currentRun.setDiff("guided retry diff");
        iterations.saveAndFlush(currentRun);

        assertThat(iterations.findFirstByIssueIdAndIterationNumOrderByIdDesc(issue.getId(), 2))
                .contains(currentRun);
        assertThat(currentRun.getId()).isGreaterThan(priorRun.getId());
    }
}

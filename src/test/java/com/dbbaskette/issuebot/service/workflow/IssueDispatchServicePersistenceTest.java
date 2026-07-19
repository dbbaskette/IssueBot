package com.dbbaskette.issuebot.service.workflow;

import com.dbbaskette.issuebot.model.IssueStatus;
import com.dbbaskette.issuebot.model.Iteration;
import com.dbbaskette.issuebot.model.PlanningVersion;
import com.dbbaskette.issuebot.model.PlanningVersionState;
import com.dbbaskette.issuebot.model.ProcessingControl;
import com.dbbaskette.issuebot.model.ProcessingState;
import com.dbbaskette.issuebot.model.TrackedIssue;
import com.dbbaskette.issuebot.model.WatchedRepo;
import com.dbbaskette.issuebot.repository.PlanningVersionRepository;
import com.dbbaskette.issuebot.repository.IterationRepository;
import com.dbbaskette.issuebot.repository.ProcessingControlRepository;
import com.dbbaskette.issuebot.repository.TrackedIssueRepository;
import com.dbbaskette.issuebot.repository.WatchedRepoRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
/** Real Hibernate exercise of retry claiming with OSIV/test transaction disabled. */
@DataJpaTest
@Import(IssueDispatchTransactionManager.class)
@TestPropertySource(properties = {
        "issuebot.github.token=test-token",
        "spring.jpa.open-in-view=false"
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class IssueDispatchServicePersistenceTest {

    @Autowired
    private IssueDispatchTransactionManager dispatch;

    @Autowired
    private TrackedIssueRepository issues;

    @Autowired
    private PlanningVersionRepository versions;

    @Autowired
    private IterationRepository iterations;

    @Autowired
    private WatchedRepoRepository repos;

    @Autowired
    private ProcessingControlRepository controls;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void guidedRetryLoadsAndPreservesApprovedVersionOutsideRepositoryTransaction() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        Long[] ids = tx.execute(status -> {
            controls.save(new ProcessingControl(ProcessingState.RUNNING));
            WatchedRepo repo = repos.save(new WatchedRepo("acme", "widgets"));
            repo.setPlanFirst(true);
            TrackedIssue issue = new TrackedIssue(repo, 42, "Preserve the approved plan");
            issue.setStatus(IssueStatus.FAILED);
            issue.setPlanConformanceAttempt(2);
            issue = issues.save(issue);

            PlanningVersion approved = PlanningVersion.pending(issue, 3,
                    "approved spec", "approved plan", "CODEX", "gpt-5.6-sol", null);
            approved.approve(java.time.LocalDateTime.now());
            approved = versions.save(approved);
            issue.setApprovedPlanningVersion(approved);
            issues.saveAndFlush(issue);
            Iteration review = new Iteration(issue, 2);
            review.setReviewPassed(false);
            review.setReviewJson("{}");
            iterations.saveAndFlush(review);
            return new Long[]{issue.getId(), approved.getId()};
        });
        assertThat(ids).isNotNull();

        IssueDispatchService.ClaimResult result =
                dispatch.claimGuidedRetry(ids[0], "preserve the approved plan", 10);

        assertThat(result.claimed()).isTrue();
        assertThat(result.issue().getApprovedPlanningVersion().getId()).isEqualTo(ids[1]);
        assertThat(result.issue().getRepo().fullName()).isEqualTo("acme/widgets");
        Long preservedVersionId = tx.execute(status ->
                issues.findById(ids[0]).orElseThrow().getApprovedPlanningVersion().getId());
        assertThat(preservedVersionId).isEqualTo(ids[1]);
    }
}

package com.dbbaskette.issuebot.service.workflow;

import com.dbbaskette.issuebot.model.IssueStatus;
import com.dbbaskette.issuebot.model.Iteration;
import com.dbbaskette.issuebot.model.TrackedIssue;
import com.dbbaskette.issuebot.model.WatchedRepo;
import com.dbbaskette.issuebot.repository.CostTrackingRepository;
import com.dbbaskette.issuebot.repository.IterationRepository;
import com.dbbaskette.issuebot.repository.TrackedIssueRepository;
import com.dbbaskette.issuebot.repository.WatchedRepoRepository;
import com.dbbaskette.issuebot.service.harness.HarnessExecutionResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@Import({ImplementationTurnCheckpointService.class, ImplementationTurnCheckpointServiceTest.MapperConfig.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ImplementationTurnCheckpointServiceTest {
    @TestConfiguration
    static class MapperConfig {
        @Bean ObjectMapper objectMapper() { return new ObjectMapper(); }
    }

    @Autowired private ImplementationTurnCheckpointService checkpoints;
    @Autowired private WatchedRepoRepository repos;
    @Autowired private TrackedIssueRepository issues;
    @Autowired private IterationRepository iterations;
    @Autowired private CostTrackingRepository costs;
    @Autowired private ObjectMapper mapper;

    @Test
    void nativeTurnAndCostCommitTogetherAndLocalRepairKeepsTheSameIteration() {
        WatchedRepo repo = repos.saveAndFlush(new WatchedRepo("acme", "turn-checkpoint"));
        TrackedIssue issue = new TrackedIssue(repo, 1, "Foundation");
        issue.setStatus(IssueStatus.IN_PROGRESS);
        issue.setCurrentIteration(1);
        issue.setCurrentPhase("IMPLEMENTATION");
        issue = issues.saveAndFlush(issue);
        Iteration iteration = iterations.saveAndFlush(new Iteration(issue, 1));
        Long issueId = issue.getId();
        Long iterationId = iteration.getId();

        HarnessExecutionResult turn = new HarnessExecutionResult();
        turn.setSuccess(true);
        turn.setSessionId("native-session");
        turn.setFinalResult("done");
        turn.setInputTokens(100);
        turn.setOutputTokens(50);
        turn.setModel("gpt-6-astra");
        turn.setCostUsd(new BigDecimal("0.0123"));
        ImplementationOutcome outcome = new ImplementationOutcome(
                ImplementationOutcome.Status.COMPLETE, "Implemented", java.util.List.of(), "");

        checkpoints.record(issueId, iterationId, 1, outcome, turn);

        Iteration saved = iterations.findById(iteration.getId()).orElseThrow();
        assertThat(saved.getImplementationTurnCount()).isEqualTo(1);
        assertThat(saved.getImplementationOutcome()).isEqualTo("COMPLETE");
        assertThat(ImplementationTurnLedger.read(saved.getImplementationTurnsJson(), mapper))
                .hasSize(1);
        assertThat(issues.findById(issue.getId()).orElseThrow().getClaudeSessionId())
                .isEqualTo("native-session");
        assertThat(costs.totalCostForIssue(issue)).isEqualByComparingTo("0.0123");
        assertThatThrownBy(() -> checkpoints.record(issueId, iterationId, 1, outcome, turn))
                .hasMessageContaining("ordinal");

        checkpoints.reopenForLocalRepair(issue.getId(), iteration.getId(),
                "./mvnw verify failed: compilation error");
        Iteration repair = iterations.findById(iteration.getId()).orElseThrow();
        assertThat(repair.getImplementationTurnCount()).isEqualTo(1);
        assertThat(repair.getLocalCheckFailure()).contains("compilation error");
        assertThat(repair.getImplementationOutcome()).isEqualTo("CONTINUE");
        assertThat(issues.findById(issue.getId()).orElseThrow().getCurrentPhase())
                .isEqualTo("IMPLEMENTATION");
    }
}

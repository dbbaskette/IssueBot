package com.dbbaskette.issuebot.service.workflow;

import com.dbbaskette.issuebot.model.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ImplementationSummaryTest {
    private final TrackedIssue issue = new TrackedIssue(new WatchedRepo("owner", "repo"), 170, "Fixture");
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void blockedEvidenceIsNotNotStartedOrPassing() {
        issue.setStatus(IssueStatus.FAILED);
        var iteration = new Iteration(issue, 1);
        iteration.setImplementationOutcome("BLOCKED");
        iteration.setClaudeOutput("Implemented storage.\n" + ImplementationOutcome.MARKER
                + "{\"status\":\"BLOCKED\",\"summary\":\"PostgreSQL unavailable\","
                + "\"checks\":[{\"command\":\"./mvnw verify\",\"result\":\"2 database tests skipped\"}],"
                + "\"limitations\":\"Pinned dependency download failed\"}");
        var summary = ImplementationSummary.from(issue, iteration, mapper);
        assertThat(summary.label()).isEqualTo("Blocked");
        assertThat(summary.message()).contains("retained", "PostgreSQL unavailable");
        assertThat(summary.limitations()).contains("download failed");
        assertThat(summary.checks().getFirst().result()).contains("skipped");
    }

    @Test void malformedOutputStillUsesPersistedBlockedOutcome() {
        var iteration = new Iteration(issue, 1);
        iteration.setImplementationOutcome("BLOCKED");
        iteration.setClaudeOutput("Legacy output without a valid marker");
        assertThat(ImplementationSummary.from(issue, iteration, mapper).label()).isEqualTo("Blocked");
    }

    @Test void savedWorkIsIncompleteAndActiveResumeTakesPrecedence() {
        var iteration = new Iteration(issue, 1);
        iteration.setClaudeOutput("Work saved");
        assertThat(ImplementationSummary.from(issue, iteration, mapper).label()).isEqualTo("Incomplete");
        iteration.setImplementationOutcome("BLOCKED");
        issue.setStatus(IssueStatus.IN_PROGRESS);
        issue.setCurrentPhase("IMPLEMENTATION");
        assertThat(ImplementationSummary.from(issue, iteration, mapper).label()).isEqualTo("Working");
        iteration.setImplementationSucceeded(true);
        assertThat(ImplementationSummary.from(issue, iteration, mapper).label()).isEqualTo("Finished");
        issue.setCurrentPhase(null);
        assertThat(ImplementationSummary.from(issue, null, mapper).label()).isEqualTo("Not started");
    }
}

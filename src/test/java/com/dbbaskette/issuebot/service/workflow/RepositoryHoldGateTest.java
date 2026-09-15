package com.dbbaskette.issuebot.service.workflow;

import com.dbbaskette.issuebot.model.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class RepositoryHoldGateTest {
    @Test void heldReadyReservationDoesNotBlockButActiveWorkAlwaysDoes() {
        var repo = new WatchedRepo("owner", "repo");
        var candidate = new TrackedIssue(repo, 2, "Candidate");
        candidate.setId(2L);
        candidate.setStatus(IssueStatus.QUEUED);
        var held = new TrackedIssue(repo, 1, "Held approved plan");
        held.setId(1L);
        held.setStatus(IssueStatus.READY_TO_START);
        held.setOnHold(true);
        assertThat(RepositoryDispatchGate.blocker(candidate, List.of(held))).isNull();
        held.setOnHold(false);
        assertThat(RepositoryDispatchGate.blocker(candidate, List.of(held))).isSameAs(held);
        held.setOnHold(true);
        held.setStatus(IssueStatus.IN_PROGRESS);
        assertThat(RepositoryDispatchGate.blocker(candidate, List.of(held))).isSameAs(held);
    }
}

package com.dbbaskette.issuebot.service.harness;

import com.dbbaskette.issuebot.model.*;
import com.dbbaskette.issuebot.repository.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import static org.assertj.core.api.Assertions.*;

@DataJpaTest
@Import(HarnessInputService.class)
@TestPropertySource(properties="issuebot.github.token=test-token")
class HarnessInputServiceTest {
    @Autowired HarnessInputService service;
    @Autowired TrackedIssueRepository issues;
    @Autowired WatchedRepoRepository repos;
    @Autowired HarnessInputRequestRepository requests;
    private TrackedIssue issue(int number) {
        var repo=repos.save(new WatchedRepo("test","repo"+number));
        var issue=new TrackedIssue(repo,number,"Input test");issue.setStatus(IssueStatus.IN_PROGRESS);
        issue.setCurrentIteration(2);return issues.saveAndFlush(issue);
    }
    @Test void responseRetainsAttemptAndRejectsDuplicateAndCrossIssueReplies() {
        var issue=issue(1);var other=issue(2);service.connect("transport");
        var request=service.open(issue.getId(),"codex","transport","native","session","can_use_tool","{}");
        assertThat(issues.findById(issue.getId()).orElseThrow().isWaitingForInput()).isTrue();
        assertThatThrownBy(()->service.answer(other.getId(),request.getId(),"{}" )).isInstanceOf(IllegalStateException.class);
        service.answer(issue.getId(),request.getId(),"{\"behavior\":\"deny\"}");
        assertThatThrownBy(()->service.answer(issue.getId(),request.getId(),"{}" )).isInstanceOf(IllegalStateException.class);
        service.delivered(request.getId());
        assertThat(issue.getCurrentIteration()).isEqualTo(2);
        assertThat(issue.getStatus()).isEqualTo(IssueStatus.IN_PROGRESS);
        assertThat(issue.isWaitingForInput()).isFalse();
    }
    @Test void disconnectedRequestsAreNotReplayableAndRetainReservation() {
        var issue=issue(3);service.connect("transport");
        var request=service.open(issue.getId(),"claude","transport","native","session","can_use_tool","{}");
        service.disconnect("transport");
        assertThat(requests.findById(request.getId()).orElseThrow().getState()).isEqualTo(HarnessInputRequest.State.DISCONNECTED);
        assertThatThrownBy(()->service.answer(issue.getId(),request.getId(),"{}" )).isInstanceOf(IllegalStateException.class);
        assertThat(issue.isWaitingForInput()).isTrue();
        assertThat(issue.getStatus()).isEqualTo(IssueStatus.IN_PROGRESS);
    }
    @Test void policyIsPinnedUntilANewWorkflowRun() {
        var issue=issue(4);
        assertThat(service.policy(issue.getId())).isEqualTo(ExecutionPermissions.ASK);
        issue.getRepo().setExecutionPermissions(ExecutionPermissions.FULL_ACCESS);
        repos.saveAndFlush(issue.getRepo());
        assertThat(service.policy(issue.getId())).isEqualTo(ExecutionPermissions.ASK);
        issue.setWorkflowRun(issue.getWorkflowRun()+1);issues.saveAndFlush(issue);
        assertThat(service.policy(issue.getId())).isEqualTo(ExecutionPermissions.FULL_ACCESS);
    }

    @Test void withdrawnRequestsReleaseOnlyTheInputWaitNotTheIssue() throws Exception {
        var issue=issue(5);service.connect("transport");
        var request=service.open(issue.getId(),"codex","transport","native","session","item/tool/requestUserInput","{}");
        service.withdraw(request.getId());
        assertThat(service.await(request.getId(),()->true)).isNull();
        assertThat(issue.isWaitingForInput()).isFalse();
        assertThat(issue.getStatus()).isEqualTo(IssueStatus.IN_PROGRESS);
        assertThat(issue.getCurrentIteration()).isEqualTo(2);
        assertThatThrownBy(()->service.answer(issue.getId(),request.getId(),"{}" )).isInstanceOf(IllegalStateException.class);
    }

    @Test void oldRunResponseIsRejectedEvenIfNativeConnectionIsStillRegistered() {
        var issue=issue(6);service.connect("transport");
        var request=service.open(issue.getId(),"codex","transport","native","session","can_use_tool","{}");
        issue.setWorkflowRun(issue.getWorkflowRun()+1);issues.saveAndFlush(issue);
        assertThatThrownBy(()->service.answer(issue.getId(),request.getId(),"{}" )).isInstanceOf(IllegalStateException.class);
        assertThat(request.getState()).isEqualTo(HarnessInputRequest.State.WAITING);
    }
}

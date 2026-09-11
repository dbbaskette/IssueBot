package com.dbbaskette.issuebot.service.workflow;

import com.dbbaskette.issuebot.model.*;
import com.dbbaskette.issuebot.repository.*;
import com.dbbaskette.issuebot.service.github.GitHubApiClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@com.dbbaskette.issuebot.service.history.WithDecisionHistory
@DataJpaTest
@Import({DecompositionGroupTransactionManager.class, DecompositionGroupService.class,
        WorkflowCancellationService.class})
@TestPropertySource(properties = "issuebot.github.token=test-token")
class DecompositionGroupServicePersistenceTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired private WatchedRepoRepository repos;
    @Autowired private TrackedIssueRepository issues;
    @Autowired private DecompositionGroupRepository groups;
    @Autowired private DecompositionChildRepository children;
    @Autowired private DecompositionGroupTransactionManager transactions;
    @Autowired private DecompositionGroupService service;
    @Autowired private WorkflowCancellationService cancellations;
    @MockitoBean private GitHubApiClient github;

    @Test
    void creationIsDurableIdempotentAndCompletesOnlyAfterParentIsConfirmedClosed() throws Exception {
        WatchedRepo repo = repos.save(new WatchedRepo("acme", "widgets"));
        TrackedIssue parent = issues.save(new TrackedIssue(repo, 153, "Parent"));
        DecompositionGroup group = transactions.beginGroup(parent.getId(), List.of(
                new DecompositionGroupTransactionManager.ChildIntent(1, "1/2: First", "Body one"),
                new DecompositionGroupTransactionManager.ChildIntent(2, "2/2: Second", "Body two")));
        when(github.listIssues("acme", "widgets", "issuebot-decomposed", "all"))
                .thenReturn(List.of());
        when(github.createIssue(eq("acme"), eq("widgets"), anyString(), anyString(), anyList()))
                .thenReturn(node(155, "open", "1/2: First", ""))
                .thenReturn(node(156, "open", "2/2: Second", ""));
        when(github.listIssueComments("acme", "widgets", 153)).thenReturn(List.of());

        service.createOrResume(group.getId());
        service.createOrResume(group.getId());

        List<DecompositionChild> created =
                children.findByGroupOrderBySequencePositionAsc(groups.findById(group.getId()).orElseThrow());
        assertThat(created).hasSize(2).allSatisfy(child -> {
            assertThat(child.getCreationState()).isEqualTo(DecompositionChildState.CREATED);
            assertThat(child.getTrackedIssue()).isNotNull();
            assertThat(child.getTrackedIssue().getStatus()).isEqualTo(IssueStatus.QUEUED);
        });
        verify(github, times(2)).createIssue(
                eq("acme"), eq("widgets"), anyString(), contains("issuebot-decomposition:"), anyList());

        created.forEach(child -> {
            child.getTrackedIssue().setStatus(IssueStatus.COMPLETED);
            issues.save(child.getTrackedIssue());
        });
        when(github.getIssue("acme", "widgets", 153))
                .thenReturn(node(153, "closed", "Parent", ""));
        service.reconcileGroup(group.getId());

        assertThat(groups.findById(group.getId()).orElseThrow().getState())
                .isEqualTo(DecompositionGroupState.COMPLETED);
        assertThat(issues.findById(parent.getId()).orElseThrow().getStatus())
                .isEqualTo(IssueStatus.COMPLETED);
    }

    @Test
    void legacyRecoveryWaitsForUnrelatedWorkAndPreservesExistingChildState() throws Exception {
        WatchedRepo repo = repos.save(new WatchedRepo("acme", "widgets"));
        TrackedIssue parent = new TrackedIssue(repo, 153, "Parent");
        parent.setStatus(IssueStatus.DECOMPOSED);
        parent = issues.save(parent);
        TrackedIssue unrelated = new TrackedIssue(repo, 154, "Unrelated");
        unrelated.setStatus(IssueStatus.IN_PROGRESS);
        issues.save(unrelated);
        TrackedIssue first = new TrackedIssue(repo, 155, "1/2: First");
        first.setStatus(IssueStatus.READY_TO_START);
        issues.save(first);
        TrackedIssue second = new TrackedIssue(repo, 156, "2/2: Second");
        second.setStatus(IssueStatus.QUEUED);
        issues.save(second);

        when(github.listIssues("acme", "widgets", "issuebot-parent", "all"))
                .thenReturn(List.of(node(153, "open", "Parent", "")));
        when(github.listIssues("acme", "widgets", "issuebot-decomposed", "all"))
                .thenReturn(List.of(
                        node(156, "open", "2/2: Second", "decomposed from #153"),
                        node(155, "open", "1/2: First", "decomposed from #153")));

        List<DecompositionGroup> recovered = service.recoverLegacyGroups(repo);

        assertThat(recovered).singleElement()
                .extracting(DecompositionGroup::getState)
                .isEqualTo(DecompositionGroupState.WAITING);
        assertThat(issues.findById(first.getId()).orElseThrow().getStatus())
                .isEqualTo(IssueStatus.READY_TO_START);
        assertThat(children.findByGroupOrderBySequencePositionAsc(recovered.getFirst()))
                .extracting(DecompositionChild::getGithubIssueNumber)
                .containsExactly(155, 156);
    }

    @Test
    void partialLegacyRecoveryResumesByLegacyIdentityWithoutCreatingDuplicates() throws Exception {
        WatchedRepo repo = repos.save(new WatchedRepo("acme", "widgets"));
        TrackedIssue parent = new TrackedIssue(repo, 153, "Parent");
        parent.setStatus(IssueStatus.DECOMPOSED);
        parent = issues.save(parent);
        issues.save(new TrackedIssue(repo, 155, "1/2: First"));
        issues.save(new TrackedIssue(repo, 156, "2/2: Second"));
        DecompositionGroup group = transactions.beginGroup(parent.getId(), List.of(
                new DecompositionGroupTransactionManager.ChildIntent(1, "1/2: First", "decomposed from #153"),
                new DecompositionGroupTransactionManager.ChildIntent(2, "2/2: Second", "decomposed from #153")));
        DecompositionChild first = children.findByGroupOrderBySequencePositionAsc(group).getFirst();
        transactions.linkCreatedChild(group.getId(), first.getId(), 155);
        when(github.listIssues("acme", "widgets", "issuebot-decomposed", "all"))
                .thenReturn(List.of(
                        node(155, "open", "1/2: First", "decomposed from #153"),
                        node(156, "open", "2/2: Second", "decomposed from #153")));
        when(github.listIssueComments("acme", "widgets", 153)).thenReturn(List.of());

        service.createOrResume(group.getId());

        assertThat(children.findByGroupOrderBySequencePositionAsc(group))
                .extracting(DecompositionChild::getGithubIssueNumber)
                .containsExactly(155, 156);
        verify(github, never()).createIssue(anyString(), anyString(), anyString(), anyString(), anyList());
    }

    @Test
    void abandonmentRetainsOwnershipUntilRunningChildStopsThenCancelsTheGroup() throws Exception {
        WatchedRepo repo = repos.save(new WatchedRepo("acme", "widgets"));
        TrackedIssue parent = issues.save(new TrackedIssue(repo, 153, "Parent"));
        DecompositionGroup group = transactions.beginGroup(parent.getId(), List.of(
                new DecompositionGroupTransactionManager.ChildIntent(1, "First", "Body"),
                new DecompositionGroupTransactionManager.ChildIntent(2, "Second", "Body")));
        List<DecompositionChild> local = children.findByGroupOrderBySequencePositionAsc(group);
        transactions.linkCreatedChild(group.getId(), local.get(0).getId(), 155);
        transactions.linkCreatedChild(group.getId(), local.get(1).getId(), 156);
        group = transactions.activate(group.getId());
        TrackedIssue running = children.findByGroupOrderBySequencePositionAsc(group)
                .getFirst().getTrackedIssue();
        running.setStatus(IssueStatus.IN_PROGRESS);
        issues.save(running);

        DecompositionGroupService.AbandonResult requested =
                service.abandon(parent.getId(), "Requirements changed", "operator");

        assertThat(requested.completed()).isFalse();
        assertThat(groups.findById(group.getId()).orElseThrow().getState())
                .isEqualTo(DecompositionGroupState.ABANDONING);
        assertThat(cancellations.isCancelled(running.getId())).isTrue();

        running.setStatus(IssueStatus.PENDING);
        issues.save(running);
        when(github.getIssue("acme", "widgets", 153))
                .thenReturn(node(153, "open", "Parent", ""));
        when(github.listIssueComments("acme", "widgets", 153)).thenReturn(List.of());
        service.reconcileGroup(group.getId());

        assertThat(groups.findById(group.getId()).orElseThrow().getState())
                .isEqualTo(DecompositionGroupState.ABANDONED);
        assertThat(issues.findById(parent.getId()).orElseThrow().getStatus())
                .isEqualTo(IssueStatus.FAILED);
        assertThat(children.findByGroupOrderBySequencePositionAsc(group))
                .allSatisfy(child -> assertThat(child.getCreationState())
                        .isEqualTo(DecompositionChildState.CANCELLED));
        verify(github).addComment(eq("acme"), eq("widgets"), eq(153),
                contains("issuebot-decomposition-abandoned"));
    }

    @Test
    void onlyTheOldestUnfinishedGroupCanOwnARepository() {
        WatchedRepo repo = repos.save(new WatchedRepo("acme", "widgets"));
        TrackedIssue newerParent = issues.save(new TrackedIssue(repo, 160, "Newer parent"));
        DecompositionGroup newer = transactions.beginGroup(newerParent.getId(), List.of(
                new DecompositionGroupTransactionManager.ChildIntent(1, "Newer child", "Body")));
        TrackedIssue olderParent = issues.save(new TrackedIssue(repo, 153, "Older parent"));
        DecompositionGroup older = transactions.beginGroup(olderParent.getId(), List.of(
                new DecompositionGroupTransactionManager.ChildIntent(1, "Older child", "Body")));
        transactions.linkCreatedChild(newer.getId(),
                children.findByGroupOrderBySequencePositionAsc(newer).getFirst().getId(), 161);
        transactions.linkCreatedChild(older.getId(),
                children.findByGroupOrderBySequencePositionAsc(older).getFirst().getId(), 155);

        DecompositionGroup newerActivatedFirst = transactions.activate(newer.getId());
        DecompositionGroup olderActivatedSecond = transactions.activate(older.getId());

        assertThat(newerActivatedFirst.getState()).isEqualTo(DecompositionGroupState.WAITING);
        assertThat(olderActivatedSecond.getState()).isEqualTo(DecompositionGroupState.ACTIVE);
        assertThat(groups.findOwningByRepo(repo.getId()))
                .get().extracting(DecompositionGroup::getId).isEqualTo(older.getId());
    }

    @Test
    void abandonmentWinsWhenCompletionWasPreparedBeforeRelease() {
        WatchedRepo repo = repos.save(new WatchedRepo("acme", "widgets"));
        TrackedIssue parent = issues.save(new TrackedIssue(repo, 153, "Parent"));
        DecompositionGroup group = transactions.beginGroup(parent.getId(), List.of(
                new DecompositionGroupTransactionManager.ChildIntent(1, "Child", "Body")));
        transactions.linkCreatedChild(group.getId(),
                children.findByGroupOrderBySequencePositionAsc(group).getFirst().getId(), 155);
        transactions.activate(group.getId());
        transactions.reconcileState(group.getId(), DecompositionGroupState.COMPLETING, null);

        transactions.requestAbandon(group.getId(), "Requirements changed", "operator");
        transactions.complete(group.getId());

        assertThat(groups.findById(group.getId()).orElseThrow().getState())
                .isEqualTo(DecompositionGroupState.ABANDONING);
        assertThat(issues.findById(parent.getId()).orElseThrow().getStatus())
                .isNotEqualTo(IssueStatus.COMPLETED);
    }

    @Test
    void failedParentReopenRequiresAttentionBeforeMoreDispatch() throws Exception {
        WatchedRepo repo = repos.save(new WatchedRepo("acme", "widgets"));
        TrackedIssue parent = issues.save(new TrackedIssue(repo, 153, "Parent"));
        DecompositionGroup group = transactions.beginGroup(parent.getId(), List.of(
                new DecompositionGroupTransactionManager.ChildIntent(1, "Child", "Body")));
        transactions.linkCreatedChild(group.getId(),
                children.findByGroupOrderBySequencePositionAsc(group).getFirst().getId(), 155);
        transactions.activate(group.getId());
        when(github.getIssue("acme", "widgets", 155))
                .thenReturn(node(155, "open", "Child", ""));
        when(github.getIssue("acme", "widgets", 153))
                .thenReturn(node(153, "closed", "Parent", ""))
                .thenReturn(node(153, "closed", "Parent", ""));

        service.reconcileGroup(group.getId());

        DecompositionGroup reconciled = groups.findById(group.getId()).orElseThrow();
        assertThat(reconciled.getState()).isEqualTo(DecompositionGroupState.NEEDS_ATTENTION);
        assertThat(reconciled.getAttentionReason()).contains("could not be reopened");
        verify(github).reopenIssue("acme", "widgets", 153);
    }

    @Test
    void labelCleanupFailureRetainsOwnershipAndRetries() throws Exception {
        WatchedRepo repo = repos.save(new WatchedRepo("acme", "widgets"));
        TrackedIssue parent = issues.save(new TrackedIssue(repo, 153, "Parent"));
        DecompositionGroup group = transactions.beginGroup(parent.getId(), List.of(
                new DecompositionGroupTransactionManager.ChildIntent(1, "Child", "Body")));
        transactions.linkCreatedChild(group.getId(),
                children.findByGroupOrderBySequencePositionAsc(group).getFirst().getId(), 155);
        transactions.activate(group.getId());
        doThrow(new IllegalStateException("GitHub unavailable"))
                .doNothing()
                .when(github).removeLabel("acme", "widgets", 155, "agent-ready");
        when(github.getIssue("acme", "widgets", 153))
                .thenReturn(node(153, "open", "Parent", ""));
        when(github.listIssueComments("acme", "widgets", 153)).thenReturn(List.of());

        assertThatThrownBy(() -> service.abandon(
                parent.getId(), "Requirements changed", "operator"))
                .hasMessageContaining("GitHub unavailable");
        assertThat(groups.findById(group.getId()).orElseThrow().getState())
                .isEqualTo(DecompositionGroupState.ABANDONING);
        assertThat(groups.findOwningByRepo(repo.getId())).isPresent();

        service.reconcileGroup(group.getId());

        assertThat(groups.findById(group.getId()).orElseThrow().getState())
                .isEqualTo(DecompositionGroupState.ABANDONED);
        verify(github, times(2)).removeLabel("acme", "widgets", 155, "agent-ready");
    }

    @Test
    void completionRaceCompensatesByReopeningParentWhenAbandonmentWins() throws Exception {
        WatchedRepo repo = repos.save(new WatchedRepo("acme", "widgets"));
        TrackedIssue parent = issues.save(new TrackedIssue(repo, 153, "Parent"));
        DecompositionGroup group = transactions.beginGroup(parent.getId(), List.of(
                new DecompositionGroupTransactionManager.ChildIntent(1, "Child", "Body")));
        transactions.linkCreatedChild(group.getId(),
                children.findByGroupOrderBySequencePositionAsc(group).getFirst().getId(), 155);
        transactions.activate(group.getId());
        TrackedIssue child = children.findByGroupOrderBySequencePositionAsc(group)
                .getFirst().getTrackedIssue();
        child.setStatus(IssueStatus.COMPLETED);
        issues.save(child);
        when(github.getIssue("acme", "widgets", 153))
                .thenReturn(node(153, "open", "Parent", ""))
                .thenReturn(node(153, "closed", "Parent", ""))
                .thenReturn(node(153, "closed", "Parent", ""))
                .thenReturn(node(153, "open", "Parent", ""));
        doAnswer(ignored -> {
            transactions.requestAbandon(group.getId(), "Requirements changed", "operator");
            return null;
        }).when(github).closeIssue("acme", "widgets", 153);

        service.reconcileGroup(group.getId());

        assertThat(groups.findById(group.getId()).orElseThrow().getState())
                .isEqualTo(DecompositionGroupState.ABANDONING);
        assertThat(issues.findById(parent.getId()).orElseThrow().getStatus())
                .isNotEqualTo(IssueStatus.COMPLETED);
        verify(github).reopenIssue("acme", "widgets", 153);
        verify(github, never()).addComment(eq("acme"), eq("widgets"), eq(153),
                contains("All decomposition children completed"));
    }

    private static JsonNode node(int number, String state, String title, String body) throws Exception {
        return JSON.readTree("""
                {"number":%d,"state":"%s","title":"%s","body":"%s"}
                """.formatted(number, state, title, body));
    }
}

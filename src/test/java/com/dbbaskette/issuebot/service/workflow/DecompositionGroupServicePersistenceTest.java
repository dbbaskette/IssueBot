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
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

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

    private static JsonNode node(int number, String state, String title, String body) throws Exception {
        return JSON.readTree("""
                {"number":%d,"state":"%s","title":"%s","body":"%s"}
                """.formatted(number, state, title, body));
    }
}

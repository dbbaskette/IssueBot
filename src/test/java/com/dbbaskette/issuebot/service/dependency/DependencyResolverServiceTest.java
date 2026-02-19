package com.dbbaskette.issuebot.service.dependency;

import com.dbbaskette.issuebot.model.IssueStatus;
import com.dbbaskette.issuebot.model.TrackedIssue;
import com.dbbaskette.issuebot.model.WatchedRepo;
import com.dbbaskette.issuebot.repository.TrackedIssueRepository;
import com.dbbaskette.issuebot.service.dependency.DependencyResolverService.DependencyResult;
import com.dbbaskette.issuebot.service.github.GitHubApiClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DependencyResolverServiceTest {

    private DependencyResolverService resolver;
    private GitHubApiClient gitHubApiClient;
    private TrackedIssueRepository issueRepository;
    private WatchedRepo testRepo;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        gitHubApiClient = mock(GitHubApiClient.class);
        issueRepository = mock(TrackedIssueRepository.class);
        resolver = new DependencyResolverService(gitHubApiClient, issueRepository);
        testRepo = new WatchedRepo("owner", "repo");
    }

    // --- parseBlockedBy tests ---

    @Test
    void parseBlockedBy_noLine() {
        assertEquals(List.of(), resolver.parseBlockedBy("Just a regular issue body.\nNo blockers here."));
    }

    @Test
    void parseBlockedBy_nullBody() {
        assertEquals(List.of(), resolver.parseBlockedBy(null));
    }

    @Test
    void parseBlockedBy_emptyBody() {
        assertEquals(List.of(), resolver.parseBlockedBy(""));
    }

    @Test
    void parseBlockedBy_singleRef() {
        assertEquals(List.of(5), resolver.parseBlockedBy("**Blocked by:** #5"));
    }

    @Test
    void parseBlockedBy_multipleRefs() {
        assertEquals(List.of(5, 6, 15),
                resolver.parseBlockedBy("**Blocked by:** #5, #6, #15"));
    }

    @Test
    void parseBlockedBy_strikethroughRemoval() {
        assertEquals(List.of(6),
                resolver.parseBlockedBy("**Blocked by:** ~~#5~~, #6"));
    }

    @Test
    void parseBlockedBy_allStrikethrough() {
        assertEquals(List.of(),
                resolver.parseBlockedBy("**Blocked by:** ~~#5~~, ~~#6~~"));
    }

    @Test
    void parseBlockedBy_caseInsensitive() {
        assertEquals(List.of(10),
                resolver.parseBlockedBy("**blocked by:** #10"));
    }

    @Test
    void parseBlockedBy_embeddedInBody() {
        String body = "## Description\nSome work to do.\n\n**Blocked by:** #3, #7\n\n## Tasks\n- stuff";
        assertEquals(List.of(3, 7), resolver.parseBlockedBy(body));
    }

    // --- resolve tests ---

    @Test
    void resolve_noBlockers() {
        ObjectNode issueNode = mapper.createObjectNode();
        issueNode.put("body", "Just a regular issue");
        issueNode.put("state", "open");
        when(gitHubApiClient.getIssue("owner", "repo", 10)).thenReturn(issueNode);

        DependencyResult result = resolver.resolve(testRepo, 10);
        assertTrue(result.allBlockers().isEmpty());
        assertTrue(result.unresolvedBlockers().isEmpty());
        assertFalse(result.hasCycle());
    }

    @Test
    void resolve_allClosed() {
        // Issue 10 depends on #5
        ObjectNode issue10 = mapper.createObjectNode();
        issue10.put("body", "**Blocked by:** #5");
        issue10.put("state", "open");
        when(gitHubApiClient.getIssue("owner", "repo", 10)).thenReturn(issue10);

        // Issue 5 is closed
        ObjectNode issue5 = mapper.createObjectNode();
        issue5.put("body", "");
        issue5.put("state", "closed");
        when(gitHubApiClient.getIssue("owner", "repo", 5)).thenReturn(issue5);

        when(issueRepository.findByRepoAndIssueNumber(testRepo, 5)).thenReturn(Optional.empty());

        DependencyResult result = resolver.resolve(testRepo, 10);
        assertEquals(List.of(5), result.allBlockers());
        assertTrue(result.unresolvedBlockers().isEmpty());
        assertFalse(result.hasCycle());
    }

    @Test
    void resolve_someOpen() {
        ObjectNode issue20 = mapper.createObjectNode();
        issue20.put("body", "**Blocked by:** #5, #15");
        issue20.put("state", "open");
        when(gitHubApiClient.getIssue("owner", "repo", 20)).thenReturn(issue20);

        // #5 is closed
        ObjectNode issue5 = mapper.createObjectNode();
        issue5.put("body", "");
        issue5.put("state", "closed");
        when(gitHubApiClient.getIssue("owner", "repo", 5)).thenReturn(issue5);
        when(issueRepository.findByRepoAndIssueNumber(testRepo, 5)).thenReturn(Optional.empty());

        // #15 is open
        ObjectNode issue15 = mapper.createObjectNode();
        issue15.put("body", "");
        issue15.put("state", "open");
        when(gitHubApiClient.getIssue("owner", "repo", 15)).thenReturn(issue15);
        when(issueRepository.findByRepoAndIssueNumber(testRepo, 15)).thenReturn(Optional.empty());

        DependencyResult result = resolver.resolve(testRepo, 20);
        assertEquals(List.of(5, 15), result.allBlockers());
        assertEquals(List.of(15), result.unresolvedBlockers());
        assertFalse(result.hasCycle());
    }

    @Test
    void resolve_cycleDetection() {
        // #10 blocked by #5, #5 blocked by #10
        ObjectNode issue10 = mapper.createObjectNode();
        issue10.put("body", "**Blocked by:** #5");
        issue10.put("state", "open");
        when(gitHubApiClient.getIssue("owner", "repo", 10)).thenReturn(issue10);

        ObjectNode issue5 = mapper.createObjectNode();
        issue5.put("body", "**Blocked by:** #10");
        issue5.put("state", "open");
        when(gitHubApiClient.getIssue("owner", "repo", 5)).thenReturn(issue5);
        when(issueRepository.findByRepoAndIssueNumber(testRepo, 5)).thenReturn(Optional.empty());

        DependencyResult result = resolver.resolve(testRepo, 10);
        assertTrue(result.hasCycle());
        assertEquals(List.of(5), result.allBlockers());
    }

    @Test
    void resolve_deepChain() {
        // #20 -> #15 -> #5
        ObjectNode issue20 = mapper.createObjectNode();
        issue20.put("body", "**Blocked by:** #15");
        issue20.put("state", "open");
        when(gitHubApiClient.getIssue("owner", "repo", 20)).thenReturn(issue20);

        ObjectNode issue15 = mapper.createObjectNode();
        issue15.put("body", "**Blocked by:** #5");
        issue15.put("state", "open");
        when(gitHubApiClient.getIssue("owner", "repo", 15)).thenReturn(issue15);
        when(issueRepository.findByRepoAndIssueNumber(testRepo, 15)).thenReturn(Optional.empty());

        ObjectNode issue5 = mapper.createObjectNode();
        issue5.put("body", "");
        issue5.put("state", "open");
        when(gitHubApiClient.getIssue("owner", "repo", 5)).thenReturn(issue5);
        when(issueRepository.findByRepoAndIssueNumber(testRepo, 5)).thenReturn(Optional.empty());

        DependencyResult result = resolver.resolve(testRepo, 20);
        assertEquals(List.of(5, 15), result.allBlockers());
        assertEquals(List.of(5, 15), result.unresolvedBlockers());
        assertFalse(result.hasCycle());
    }

    @Test
    void resolve_branchingDag() {
        // #20 -> #10, #15; both #10 and #15 -> #5
        ObjectNode issue20 = mapper.createObjectNode();
        issue20.put("body", "**Blocked by:** #10, #15");
        issue20.put("state", "open");
        when(gitHubApiClient.getIssue("owner", "repo", 20)).thenReturn(issue20);

        ObjectNode issue10 = mapper.createObjectNode();
        issue10.put("body", "**Blocked by:** #5");
        issue10.put("state", "open");
        when(gitHubApiClient.getIssue("owner", "repo", 10)).thenReturn(issue10);
        when(issueRepository.findByRepoAndIssueNumber(testRepo, 10)).thenReturn(Optional.empty());

        ObjectNode issue15 = mapper.createObjectNode();
        issue15.put("body", "**Blocked by:** #5");
        issue15.put("state", "open");
        when(gitHubApiClient.getIssue("owner", "repo", 15)).thenReturn(issue15);
        when(issueRepository.findByRepoAndIssueNumber(testRepo, 15)).thenReturn(Optional.empty());

        ObjectNode issue5 = mapper.createObjectNode();
        issue5.put("body", "");
        issue5.put("state", "open");
        when(gitHubApiClient.getIssue("owner", "repo", 5)).thenReturn(issue5);
        when(issueRepository.findByRepoAndIssueNumber(testRepo, 5)).thenReturn(Optional.empty());

        DependencyResult result = resolver.resolve(testRepo, 20);
        assertTrue(result.allBlockers().containsAll(List.of(5, 10, 15)));
        assertEquals(3, result.allBlockers().size());
        assertFalse(result.hasCycle());
    }

    // --- allBlockersResolved tests ---

    @Test
    void allBlockersResolved_allClosed() {
        ObjectNode issue5 = mapper.createObjectNode();
        issue5.put("state", "closed");
        when(gitHubApiClient.getIssue("owner", "repo", 5)).thenReturn(issue5);
        when(issueRepository.findByRepoAndIssueNumber(testRepo, 5)).thenReturn(Optional.empty());

        ObjectNode issue6 = mapper.createObjectNode();
        issue6.put("state", "closed");
        when(gitHubApiClient.getIssue("owner", "repo", 6)).thenReturn(issue6);
        when(issueRepository.findByRepoAndIssueNumber(testRepo, 6)).thenReturn(Optional.empty());

        assertTrue(resolver.allBlockersResolved(testRepo, "5,6"));
    }

    @Test
    void allBlockersResolved_someOpen() {
        ObjectNode issue5 = mapper.createObjectNode();
        issue5.put("state", "closed");
        when(gitHubApiClient.getIssue("owner", "repo", 5)).thenReturn(issue5);
        when(issueRepository.findByRepoAndIssueNumber(testRepo, 5)).thenReturn(Optional.empty());

        ObjectNode issue6 = mapper.createObjectNode();
        issue6.put("state", "open");
        when(gitHubApiClient.getIssue("owner", "repo", 6)).thenReturn(issue6);
        when(issueRepository.findByRepoAndIssueNumber(testRepo, 6)).thenReturn(Optional.empty());

        assertFalse(resolver.allBlockersResolved(testRepo, "5,6"));
    }

    @Test
    void allBlockersResolved_emptyString() {
        assertTrue(resolver.allBlockersResolved(testRepo, ""));
    }

    @Test
    void allBlockersResolved_nullString() {
        assertTrue(resolver.allBlockersResolved(testRepo, null));
    }

    @Test
    void allBlockersResolved_completedInIssueBot() {
        // Issue is still open on GitHub but COMPLETED in IssueBot
        ObjectNode issue5 = mapper.createObjectNode();
        issue5.put("state", "open");
        when(gitHubApiClient.getIssue("owner", "repo", 5)).thenReturn(issue5);

        TrackedIssue completed = new TrackedIssue(testRepo, 5, "Done");
        completed.setStatus(IssueStatus.COMPLETED);
        when(issueRepository.findByRepoAndIssueNumber(testRepo, 5)).thenReturn(Optional.of(completed));

        assertTrue(resolver.allBlockersResolved(testRepo, "5"));
    }

    // --- topologicalSort tests ---

    @Test
    void topologicalSort_noDeps() {
        TrackedIssue a = createIssue(3, null);
        TrackedIssue b = createIssue(1, null);
        TrackedIssue c = createIssue(2, null);

        List<TrackedIssue> sorted = resolver.topologicalSort(List.of(a, b, c));
        // Should be sorted by ascending issue number when no deps
        assertEquals(1, sorted.get(0).getIssueNumber());
        assertEquals(2, sorted.get(1).getIssueNumber());
        assertEquals(3, sorted.get(2).getIssueNumber());
    }

    @Test
    void topologicalSort_linearChain() {
        // #3 depends on #2, #2 depends on #1
        TrackedIssue i1 = createIssue(1, null);
        TrackedIssue i2 = createIssue(2, "1");
        TrackedIssue i3 = createIssue(3, "2");

        List<TrackedIssue> sorted = resolver.topologicalSort(List.of(i3, i1, i2));
        assertEquals(1, sorted.get(0).getIssueNumber());
        assertEquals(2, sorted.get(1).getIssueNumber());
        assertEquals(3, sorted.get(2).getIssueNumber());
    }

    @Test
    void topologicalSort_diamond() {
        // #4 depends on #2 and #3; #2 and #3 depend on #1
        TrackedIssue i1 = createIssue(1, null);
        TrackedIssue i2 = createIssue(2, "1");
        TrackedIssue i3 = createIssue(3, "1");
        TrackedIssue i4 = createIssue(4, "2,3");

        List<TrackedIssue> sorted = resolver.topologicalSort(List.of(i4, i3, i2, i1));
        assertEquals(1, sorted.get(0).getIssueNumber());
        // #2 and #3 could be in either order but both before #4
        assertTrue(sorted.indexOf(i2) < sorted.indexOf(i4));
        assertTrue(sorted.indexOf(i3) < sorted.indexOf(i4));
        assertEquals(4, sorted.get(3).getIssueNumber());
    }

    @Test
    void topologicalSort_externalBlockers() {
        // #10 depends on #5 (not in the queued set) — should still appear
        TrackedIssue i10 = createIssue(10, "5");
        TrackedIssue i7 = createIssue(7, null);

        List<TrackedIssue> sorted = resolver.topologicalSort(List.of(i10, i7));
        // #7 has no deps, #10's blocker (#5) is external, so both have in-degree 0
        // Tie-break by issue number: 7 first
        assertEquals(7, sorted.get(0).getIssueNumber());
        assertEquals(10, sorted.get(1).getIssueNumber());
    }

    @Test
    void topologicalSort_empty() {
        assertTrue(resolver.topologicalSort(List.of()).isEmpty());
    }

    private TrackedIssue createIssue(int number, String blockedBy) {
        TrackedIssue issue = new TrackedIssue(testRepo, number, "Issue #" + number);
        issue.setBlockedByIssues(blockedBy);
        return issue;
    }
}

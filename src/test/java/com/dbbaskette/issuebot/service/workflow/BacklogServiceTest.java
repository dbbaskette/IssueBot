package com.dbbaskette.issuebot.service.workflow;

import com.dbbaskette.issuebot.model.WatchedRepo;
import com.dbbaskette.issuebot.service.event.EventService;
import com.dbbaskette.issuebot.service.github.GitHubApiClient;
import com.dbbaskette.issuebot.service.review.CodeReviewResult.ReviewFinding;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BacklogServiceTest {

    private GitHubApiClient gitHubApi;
    private EventService eventService;
    private BacklogService service;
    private WatchedRepo repo;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        gitHubApi = mock(GitHubApiClient.class);
        eventService = mock(EventService.class);
        service = new BacklogService(gitHubApi, eventService);
        repo = new WatchedRepo("owner", "repo");
        repo.setId(1L);
    }

    @Test
    void dedupKeyIsStableAndIgnoresLineNumbers() {
        ReviewFinding a = new ReviewFinding("medium", "code_quality", "src/Foo.java", 42, "Magic number 7", "extract constant");
        ReviewFinding b = new ReviewFinding("medium", "code_quality", "src/Foo.java", 99, "Magic number 7", "different suggestion");
        assertThat(BacklogService.dedupKey(a)).isEqualTo(BacklogService.dedupKey(b));
    }

    @Test
    void differentFindingsGetDifferentKeys() {
        ReviewFinding a = new ReviewFinding("medium", "code_quality", "src/Foo.java", 42, "Magic number 7", null);
        ReviewFinding b = new ReviewFinding("medium", "security", "src/Foo.java", 42, "Magic number 7", null);
        assertThat(BacklogService.dedupKey(a)).isNotEqualTo(BacklogService.dedupKey(b));
    }

    @Test
    void mergeAppendsOnlyNewFindingsAndUpdatesKeyStore() {
        String existingBody = """
                Findings from automated reviews.

                - [ ] **[MEDIUM — code_quality]** `src/Foo.java:42` — Magic number 7 (from #10 / PR #11)

                <!-- issuebot-keys: %s -->
                """.formatted(BacklogService.dedupKey(
                        new ReviewFinding("medium", "code_quality", "src/Foo.java", 42, "Magic number 7", null)));

        List<ReviewFinding> incoming = List.of(
                new ReviewFinding("medium", "code_quality", "src/Foo.java", 42, "Magic number 7", null), // dup
                new ReviewFinding("medium", "test_coverage", "src/Bar.java", 5, "No test for null path", null));

        BacklogService.MergeResult result = BacklogService.merge(existingBody, incoming, 12, 13);
        assertThat(result.added()).isEqualTo(1);
        assertThat(result.body()).contains("No test for null path");
        assertThat(result.body()).containsOnlyOnce("Magic number 7");
        assertThat(result.body()).contains("issuebot-keys:");
    }

    @Test
    void mergeIsIdempotentOnHeaderAcrossRepeatedCalls() {
        String initialBody = """
                Findings from automated reviews.

                <!-- issuebot-keys: -->
                """;
        ReviewFinding finding = new ReviewFinding("medium", "code_quality", "src/Foo.java", 42, "Magic number 7", null);

        String first = BacklogService.merge(initialBody, List.of(finding), 10, 11).body();

        BacklogService.MergeResult second = BacklogService.merge(first, List.of(finding), 12, 13);
        assertThat(second.added()).isZero();

        BacklogService.MergeResult third = BacklogService.merge(second.body(), List.of(finding), 14, 15);
        assertThat(third.added()).isZero();

        assertThat(third.body()).isEqualTo(second.body());
        assertThat(third.body()).doesNotContain("\n\n\n");
    }

    @Test
    void mergeSanitizesLineBreaksToPreventItemAndKeyStoreInjection() {
        ReviewFinding prior = new ReviewFinding("medium", "code_quality", "src/Foo.java", 42, "Magic number 7", null);
        String priorKey = BacklogService.dedupKey(prior);
        String existingBody = """
                Findings from automated reviews.

                - [ ] **[MEDIUM — code_quality]** `src/Foo.java:42` — Magic number 7 (from #10 / PR #11)

                <!-- issuebot-keys: %s -->
                """.formatted(priorKey);

        ReviewFinding evil = new ReviewFinding("high", "security", "src/Evil.java", 7,
                "evil\n- [ ] phantom\n<!-- issuebot-keys: dead -->", null);

        BacklogService.MergeResult first = BacklogService.merge(existingBody, List.of(evil), 20, 21);
        assertThat(first.added()).isEqualTo(1);

        // exactly one new checklist line (2 total), and no phantom item at line start
        assertThat(first.body().lines().filter(l -> l.startsWith("- [")).count()).isEqualTo(2);
        assertThat(first.body().lines().filter(l -> l.startsWith("- [ ] phantom")).count()).isZero();

        // key store intact: single trailing store line, prior key preserved, new key added, no hijacked key
        List<String> keyLines = first.body().lines().filter(l -> l.startsWith("<!-- issuebot-keys:")).toList();
        assertThat(keyLines).hasSize(1);
        assertThat(keyLines.get(0)).contains(priorKey);
        assertThat(keyLines.get(0)).contains(BacklogService.dedupKey(evil));
        assertThat(keyLines.get(0)).doesNotContain("dead");

        // second merge of the same finding is deduped
        BacklogService.MergeResult second = BacklogService.merge(first.body(), List.of(evil), 22, 23);
        assertThat(second.added()).isZero();
    }

    @Test
    void mergePrunesOldestCheckedItemsBeyondCap() {
        StringBuilder body = new StringBuilder("Findings.\n\n");
        for (int i = 0; i < 55; i++) {
            body.append("- [x] **[MEDIUM — code_quality]** `f").append(i).append(".java:1` — done item ").append(i)
                .append(" (from #1 / PR #2)\n");
        }
        body.append("\n<!-- issuebot-keys: -->\n");
        BacklogService.MergeResult result = BacklogService.merge(body.toString(),
                List.of(new ReviewFinding("medium", "code_quality", "new.java", 1, "fresh", null)), 3, 4);
        long items = result.body().lines().filter(l -> l.startsWith("- [")).count();
        assertThat(items).isLessThanOrEqualTo(50);
        assertThat(result.body()).contains("fresh");
    }

    @Test
    void mergeDropsKeysOfPrunedItemsFromKeyStore() {
        StringBuilder body = new StringBuilder("Findings.\n\n");
        List<String> keys = new ArrayList<>();
        for (int i = 0; i < 55; i++) {
            String key = String.format("%016x", (long) i);
            keys.add(key);
            body.append("- [x] done item ").append(i).append(" (from #1 / PR #2) <!-- k:").append(key).append(" -->\n");
        }
        body.append("\n<!-- issuebot-keys: ").append(String.join(",", keys)).append(" -->\n");

        BacklogService.MergeResult result = BacklogService.merge(body.toString(),
                List.of(new ReviewFinding("medium", "code_quality", "new.java", 1, "fresh", null)), 3, 4);

        // 55 existing + 1 new = 56 → 6 oldest checked items pruned; their keys must leave the store
        String keyLine = result.body().lines()
                .filter(l -> l.startsWith("<!-- issuebot-keys:")).findFirst().orElseThrow();
        for (int i = 0; i < 6; i++) {
            assertThat(keyLine).doesNotContain(keys.get(i));
        }
        assertThat(keyLine).contains(keys.get(6));
        assertThat(result.body().lines().filter(l -> l.startsWith("- [")).count()).isEqualTo(50);
    }

    // --- addFindings orchestration (mocked GitHub API + events) ---

    private static final ReviewFinding FINDING =
            new ReviewFinding("medium", "code_quality", "src/Foo.java", 42, "Magic number 7", null);

    private ObjectNode backlogIssue(int number, String state, String body) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("number", number).put("state", state).put("body", body);
        return node;
    }

    @Test
    void addFindingsCreatesBacklogIssueWhenNoneExists() {
        when(gitHubApi.listIssues("owner", "repo", BacklogService.BACKLOG_LABEL, "all"))
                .thenReturn(List.of());
        when(gitHubApi.createIssue(eq("owner"), eq("repo"), eq(BacklogService.BACKLOG_TITLE),
                anyString(), eq(List.of(BacklogService.BACKLOG_LABEL))))
                .thenReturn(objectMapper.createObjectNode().put("number", 7));

        service.addFindings(repo, List.of(FINDING), 1, 2);

        verify(gitHubApi).createIssue(eq("owner"), eq("repo"), eq(BacklogService.BACKLOG_TITLE),
                contains("Magic number 7"), eq(List.of(BacklogService.BACKLOG_LABEL)));
        verify(gitHubApi, never()).updateIssueBody(anyString(), anyString(), anyInt(), anyString());
        verify(eventService).log(eq("BACKLOG_UPDATED"), anyString(), eq(repo));
    }

    @Test
    void addFindingsReopensClosedBacklogBeforeUpdating() {
        when(gitHubApi.listIssues("owner", "repo", BacklogService.BACKLOG_LABEL, "all"))
                .thenReturn(List.of(backlogIssue(7, "closed", "Header.\n\n<!-- issuebot-keys: -->\n")));

        service.addFindings(repo, List.of(FINDING), 1, 2);

        verify(gitHubApi).reopenIssue("owner", "repo", 7);
        verify(gitHubApi).updateIssueBody(eq("owner"), eq("repo"), eq(7), contains("Magic number 7"));
    }

    @Test
    void addFindingsSkipsBodyUpdateWhenAllFindingsAreDuplicates() {
        String key = BacklogService.dedupKey(FINDING);
        String body = "Header.\n\n- [ ] existing <!-- k:" + key + " -->\n\n<!-- issuebot-keys: " + key + " -->\n";
        when(gitHubApi.listIssues("owner", "repo", BacklogService.BACKLOG_LABEL, "all"))
                .thenReturn(List.of(backlogIssue(7, "open", body)));

        service.addFindings(repo, List.of(FINDING), 1, 2);

        verify(gitHubApi, never()).updateIssueBody(anyString(), anyString(), anyInt(), anyString());
        verify(gitHubApi, never()).reopenIssue(anyString(), anyString(), anyInt());
    }

    @Test
    void addFindingsSwallowsGitHubErrorsAndLogsFailureEvent() {
        when(gitHubApi.listIssues(anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new RuntimeException("boom"));

        assertThatCode(() -> service.addFindings(repo, List.of(FINDING), 1, 2))
                .doesNotThrowAnyException();

        verify(eventService).log(eq("BACKLOG_UPDATE_FAILED"), contains("boom"), eq(repo));
        verify(eventService, never()).log(eq("BACKLOG_UPDATED"), anyString(), any(WatchedRepo.class));
    }
}

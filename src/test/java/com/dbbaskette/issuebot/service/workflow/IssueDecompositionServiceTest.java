package com.dbbaskette.issuebot.service.workflow;

import com.dbbaskette.issuebot.model.IssueStatus;
import com.dbbaskette.issuebot.model.TrackedIssue;
import com.dbbaskette.issuebot.model.WatchedRepo;
import com.dbbaskette.issuebot.repository.TrackedIssueRepository;
import com.dbbaskette.issuebot.service.claude.ClaudeCodeResult;
import com.dbbaskette.issuebot.service.claude.ClaudeCodeService;
import com.dbbaskette.issuebot.service.event.EventService;
import com.dbbaskette.issuebot.service.github.GitHubApiClient;
import com.dbbaskette.issuebot.service.notification.NotificationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class IssueDecompositionServiceTest {

    private IssueDecompositionService decompositionService;
    private ClaudeCodeService claudeCode;
    private GitHubApiClient gitHubApi;
    private TrackedIssueRepository issueRepository;
    private EventService eventService;
    private NotificationService notificationService;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        claudeCode = mock(ClaudeCodeService.class);
        gitHubApi = mock(GitHubApiClient.class);
        issueRepository = mock(TrackedIssueRepository.class);
        eventService = mock(EventService.class);
        notificationService = mock(NotificationService.class);
        objectMapper = new ObjectMapper();

        decompositionService = new IssueDecompositionService(
                claudeCode, gitHubApi, issueRepository, eventService,
                notificationService, objectMapper);
    }

    @Test
    void isDecomposable_timeout() {
        assertTrue(decompositionService.isDecomposable(
                "Implementation timed out on iteration 2 — task is likely too large for automated resolution"));
    }

    @Test
    void isDecomposable_tooComplex() {
        assertTrue(decompositionService.isDecomposable(
                "Implementation consumed 200000 output tokens without success — task is too complex for retry"));
    }

    @Test
    void isDecomposable_tooLarge() {
        assertTrue(decompositionService.isDecomposable(
                "Failed after 5 iterations — task is likely too large for automated resolution"));
    }

    @Test
    void isDecomposable_normalFailure() {
        assertFalse(decompositionService.isDecomposable(
                "Implementation failed again on iteration 2 with same error type"));
    }

    @Test
    void isDecomposable_null() {
        assertFalse(decompositionService.isDecomposable(null));
    }

    @Test
    void parseSubIssues_validJson() {
        String json = """
                [
                  {
                    "title": "1/3: Add data model",
                    "description": "Create JPA entity and repository",
                    "acceptance_criteria": "- Entity created\\n- Tests pass"
                  },
                  {
                    "title": "2/3: Implement service layer",
                    "description": "Create service with business logic",
                    "acceptance_criteria": "- Service created\\n- Tests pass"
                  },
                  {
                    "title": "3/3: Add REST endpoint",
                    "description": "Create controller with endpoints",
                    "acceptance_criteria": "- Endpoint works\\n- Tests pass"
                  }
                ]
                """;

        List<IssueDecompositionService.SubIssue> result = decompositionService.parseSubIssues(json);
        assertEquals(3, result.size());
        assertEquals("1/3: Add data model", result.get(0).title());
        assertEquals("Create JPA entity and repository", result.get(0).description());
    }

    @Test
    void parseSubIssues_jsonInMarkdownCodeBlock() {
        String output = """
                Here's the decomposition:
                ```json
                [
                  {"title": "1/2: First task", "description": "Do first thing", "acceptance_criteria": "Done"},
                  {"title": "2/2: Second task", "description": "Do second thing", "acceptance_criteria": "Done"}
                ]
                ```
                """;

        List<IssueDecompositionService.SubIssue> result = decompositionService.parseSubIssues(output);
        assertEquals(2, result.size());
    }

    @Test
    void parseSubIssues_noJsonArray_throws() {
        assertThrows(RuntimeException.class, () ->
                decompositionService.parseSubIssues("No JSON here, just text."));
    }

    @Test
    void parseSubIssues_capsAtMaxSubIssues() {
        StringBuilder json = new StringBuilder("[");
        for (int i = 1; i <= 8; i++) {
            if (i > 1) json.append(",");
            json.append("{\"title\":\"").append(i).append("/8: Task ").append(i)
                .append("\",\"description\":\"Do thing ").append(i)
                .append("\",\"acceptance_criteria\":\"Done\"}");
        }
        json.append("]");

        List<IssueDecompositionService.SubIssue> result = decompositionService.parseSubIssues(json.toString());
        assertEquals(5, result.size()); // capped at MAX_SUB_ISSUES
    }

    @Test
    void decompose_success() {
        TrackedIssue issue = createIssue();
        ObjectNode issueDetails = createIssueDetails();

        String claudeOutput = """
                [
                  {"title": "1/2: First task", "description": "Do first thing", "acceptance_criteria": "Done"},
                  {"title": "2/2: Second task", "description": "Do second thing", "acceptance_criteria": "Done"}
                ]
                """;
        ClaudeCodeResult claudeResult = new ClaudeCodeResult();
        claudeResult.setSuccess(true);
        claudeResult.setOutput(claudeOutput);
        when(claudeCode.executeReview(anyString(), any(Path.class), any())).thenReturn(claudeResult);

        ObjectNode sub1 = objectMapper.createObjectNode();
        sub1.put("number", 100);
        ObjectNode sub2 = objectMapper.createObjectNode();
        sub2.put("number", 101);
        when(gitHubApi.createIssue(eq("owner"), eq("repo"), anyString(), anyString(), anyList()))
                .thenReturn(sub1, sub2);

        boolean result = decompositionService.decompose(issue, issueDetails,
                Path.of("/tmp/repo"), "timed out");

        assertTrue(result);
        assertEquals(IssueStatus.DECOMPOSED, issue.getStatus());
        assertNull(issue.getCurrentPhase());
        verify(issueRepository).save(issue);
        verify(gitHubApi, times(2)).createIssue(eq("owner"), eq("repo"), anyString(), anyString(), anyList());
        verify(gitHubApi).addComment(eq("owner"), eq("repo"), eq(42), contains("sub-issues"));
        verify(gitHubApi).closeIssue("owner", "repo", 42);
        verify(notificationService).info(eq("Issue Decomposed"), anyString());
    }

    @Test
    void decompose_analysisReturnsOneSubIssue_returnsFalse() {
        TrackedIssue issue = createIssue();
        ObjectNode issueDetails = createIssueDetails();

        String claudeOutput = """
                [{"title": "Only task", "description": "Single task", "acceptance_criteria": "Done"}]
                """;
        ClaudeCodeResult claudeResult = new ClaudeCodeResult();
        claudeResult.setSuccess(true);
        claudeResult.setOutput(claudeOutput);
        when(claudeCode.executeReview(anyString(), any(Path.class), any())).thenReturn(claudeResult);

        boolean result = decompositionService.decompose(issue, issueDetails,
                Path.of("/tmp/repo"), "timed out");

        assertFalse(result);
        assertNotEquals(IssueStatus.DECOMPOSED, issue.getStatus());
        verify(gitHubApi, never()).createIssue(anyString(), anyString(), anyString(), anyString(), anyList());
    }

    @Test
    void decompose_claudeReturnsNull_returnsFalse() {
        TrackedIssue issue = createIssue();
        ObjectNode issueDetails = createIssueDetails();

        ClaudeCodeResult claudeResult = new ClaudeCodeResult();
        claudeResult.setSuccess(false);
        claudeResult.setOutput(null);
        when(claudeCode.executeReview(anyString(), any(Path.class), any())).thenReturn(claudeResult);

        boolean result = decompositionService.decompose(issue, issueDetails,
                Path.of("/tmp/repo"), "timed out");

        assertFalse(result);
    }

    @Test
    void decompose_allGitHubCreationsFail_returnsFalse() {
        TrackedIssue issue = createIssue();
        ObjectNode issueDetails = createIssueDetails();

        String claudeOutput = """
                [
                  {"title": "1/2: First task", "description": "Do first thing", "acceptance_criteria": "Done"},
                  {"title": "2/2: Second task", "description": "Do second thing", "acceptance_criteria": "Done"}
                ]
                """;
        ClaudeCodeResult claudeResult = new ClaudeCodeResult();
        claudeResult.setSuccess(true);
        claudeResult.setOutput(claudeOutput);
        when(claudeCode.executeReview(anyString(), any(Path.class), any())).thenReturn(claudeResult);

        when(gitHubApi.createIssue(anyString(), anyString(), anyString(), anyString(), anyList()))
                .thenThrow(new RuntimeException("API error"));

        boolean result = decompositionService.decompose(issue, issueDetails,
                Path.of("/tmp/repo"), "timed out");

        assertFalse(result);
        assertNotEquals(IssueStatus.DECOMPOSED, issue.getStatus());
    }

    @Test
    void buildDecompositionPrompt_includesTitleAndBody() {
        ObjectNode details = createIssueDetails();
        String prompt = decompositionService.buildDecompositionPrompt(details);

        assertTrue(prompt.contains("Fix the login bug"));
        assertTrue(prompt.contains("special characters"));
        assertTrue(prompt.contains("sub-tasks"));
    }

    private TrackedIssue createIssue() {
        WatchedRepo repo = new WatchedRepo("owner", "repo");
        repo.setId(1L);
        TrackedIssue issue = new TrackedIssue(repo, 42, "Fix the login bug");
        issue.setId(1L);
        issue.setStatus(IssueStatus.IN_PROGRESS);
        issue.setBranchName("issuebot/issue-42-fix-login-bug");
        issue.setCurrentIteration(2);
        return issue;
    }

    private ObjectNode createIssueDetails() {
        ObjectNode details = objectMapper.createObjectNode();
        details.put("title", "Fix the login bug");
        details.put("body", "Users can't log in when password contains special characters");
        details.putArray("labels");
        return details;
    }
}

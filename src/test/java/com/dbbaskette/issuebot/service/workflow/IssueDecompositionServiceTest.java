package com.dbbaskette.issuebot.service.workflow;

import com.dbbaskette.issuebot.model.DecompositionMode;
import com.dbbaskette.issuebot.model.IssueStatus;
import com.dbbaskette.issuebot.model.TrackedIssue;
import com.dbbaskette.issuebot.model.WatchedRepo;
import com.dbbaskette.issuebot.repository.TrackedIssueRepository;
import com.dbbaskette.issuebot.service.claude.ClaudeCodeResult;
import com.dbbaskette.issuebot.service.claude.ClaudeCodeService;
import com.dbbaskette.issuebot.service.event.EventService;
import com.dbbaskette.issuebot.service.github.GitHubApiClient;
import com.dbbaskette.issuebot.service.notification.NotificationService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

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
    private IterationManager iterationManager;

    @BeforeEach
    void setUp() {
        claudeCode = mock(ClaudeCodeService.class);
        gitHubApi = mock(GitHubApiClient.class);
        issueRepository = mock(TrackedIssueRepository.class);
        eventService = mock(EventService.class);
        notificationService = mock(NotificationService.class);
        objectMapper = new ObjectMapper();
        iterationManager = mock(IterationManager.class);

        decompositionService = new IssueDecompositionService(
                claudeCode, gitHubApi, issueRepository, eventService,
                notificationService, objectMapper, iterationManager);
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
                    "acceptance_criteria": "- Entity created\\n- Tests pass",
                    "hints": "- Follow TrackedIssue.java pattern\\n- Add V8 migration"
                  },
                  {
                    "title": "2/3: Implement service layer",
                    "description": "Create service with business logic",
                    "acceptance_criteria": "- Service created\\n- Tests pass",
                    "hints": "- See IssueWorkflowService for patterns"
                  },
                  {
                    "title": "3/3: Add REST endpoint",
                    "description": "Create controller with endpoints",
                    "acceptance_criteria": "- Endpoint works\\n- Tests pass",
                    "hints": "- Follow IssueController pattern"
                  }
                ]
                """;

        List<IssueDecompositionService.SubIssue> result = decompositionService.parseSubIssues(json);
        assertEquals(3, result.size());
        assertEquals("1/3: Add data model", result.get(0).title());
        assertEquals("Create JPA entity and repository", result.get(0).description());
        assertTrue(result.get(0).hints().contains("TrackedIssue.java"));
    }

    @Test
    void parseSubIssues_jsonInMarkdownCodeBlock() {
        String output = """
                Here's the decomposition:
                ```json
                [
                  {"title": "1/2: First task", "description": "Do first thing", "acceptance_criteria": "Done", "hints": "Look at Foo.java"},
                  {"title": "2/2: Second task", "description": "Do second thing", "acceptance_criteria": "Done", "hints": ""}
                ]
                ```
                """;

        List<IssueDecompositionService.SubIssue> result = decompositionService.parseSubIssues(output);
        assertEquals(2, result.size());
    }

    @Test
    void parseSubIssues_missingHintsField_defaultsToEmpty() {
        String json = """
                [
                  {"title": "1/2: Task", "description": "Do thing", "acceptance_criteria": "Done"},
                  {"title": "2/2: Task2", "description": "Do other", "acceptance_criteria": "Done"}
                ]
                """;
        List<IssueDecompositionService.SubIssue> result = decompositionService.parseSubIssues(json);
        assertEquals(2, result.size());
        assertEquals("", result.get(0).hints());
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
                .append("\",\"acceptance_criteria\":\"Done\"")
                .append(",\"hints\":\"Hint ").append(i).append("\"}");
        }
        json.append("]");

        List<IssueDecompositionService.SubIssue> result = decompositionService.parseSubIssues(json.toString());
        assertEquals(5, result.size()); // capped at MAX_SUB_ISSUES
    }

    @Test
    void decompose_success() {
        TrackedIssue issue = createIssue();
        issue.getRepo().setDecompositionMode(DecompositionMode.AUTO);
        ObjectNode issueDetails = createIssueDetails();

        String claudeOutput = """
                [
                  {"title": "1/2: First task", "description": "Do first thing", "acceptance_criteria": "Done", "hints": "Look at Foo.java"},
                  {"title": "2/2: Second task", "description": "Do second thing", "acceptance_criteria": "Done", "hints": "See Bar.java"}
                ]
                """;
        ClaudeCodeResult claudeResult = new ClaudeCodeResult();
        claudeResult.setSuccess(true);
        claudeResult.setOutput(claudeOutput);
        when(claudeCode.executeUtility(anyString(), any(Path.class), any())).thenReturn(claudeResult);

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
        verify(gitHubApi, never()).closeIssue(anyString(), anyString(), anyInt());
        verify(gitHubApi).addLabels(eq("owner"), eq("repo"), eq(42), eq(List.of("issuebot-parent")));
        verify(gitHubApi).removeLabel("owner", "repo", 42, "agent-ready");
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
        when(claudeCode.executeUtility(anyString(), any(Path.class), any())).thenReturn(claudeResult);

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
        when(claudeCode.executeUtility(anyString(), any(Path.class), any())).thenReturn(claudeResult);

        boolean result = decompositionService.decompose(issue, issueDetails,
                Path.of("/tmp/repo"), "timed out");

        assertFalse(result);
    }

    @Test
    void decompose_allGitHubCreationsFail_returnsFalse() {
        TrackedIssue issue = createIssue();
        issue.getRepo().setDecompositionMode(DecompositionMode.AUTO);
        ObjectNode issueDetails = createIssueDetails();

        String claudeOutput = """
                [
                  {"title": "1/2: First task", "description": "Do first thing", "acceptance_criteria": "Done", "hints": ""},
                  {"title": "2/2: Second task", "description": "Do second thing", "acceptance_criteria": "Done", "hints": ""}
                ]
                """;
        ClaudeCodeResult claudeResult = new ClaudeCodeResult();
        claudeResult.setSuccess(true);
        claudeResult.setOutput(claudeOutput);
        when(claudeCode.executeUtility(anyString(), any(Path.class), any())).thenReturn(claudeResult);

        when(gitHubApi.createIssue(anyString(), anyString(), anyString(), anyString(), anyList()))
                .thenThrow(new RuntimeException("API error"));

        boolean result = decompositionService.decompose(issue, issueDetails,
                Path.of("/tmp/repo"), "timed out");

        assertFalse(result);
        assertNotEquals(IssueStatus.DECOMPOSED, issue.getStatus());
    }

    // === PROPOSE mode / parent-as-tracker tests ===

    @Test
    void proposeStoresProposalAndDoesNotCreateIssues() {
        TrackedIssue issue = createIssue();
        issue.getRepo().setDecompositionMode(DecompositionMode.PROPOSE);
        ObjectNode issueDetails = createIssueDetails();

        String claudeOutput = """
                [
                  {"title": "1/2: First task", "description": "Do first thing", "acceptance_criteria": "Done", "hints": "Look at Foo.java"},
                  {"title": "2/2: Second task", "description": "Do second thing", "acceptance_criteria": "Done", "hints": "See Bar.java"}
                ]
                """;
        ClaudeCodeResult claudeResult = new ClaudeCodeResult();
        claudeResult.setSuccess(true);
        claudeResult.setOutput(claudeOutput);
        when(claudeCode.executeUtility(anyString(), any(Path.class), any())).thenReturn(claudeResult);

        boolean result = decompositionService.decompose(issue, issueDetails,
                Path.of("/tmp/repo"), "timed out");

        assertTrue(result);
        assertEquals(IssueStatus.AWAITING_DECOMPOSITION, issue.getStatus());
        assertNotNull(issue.getDecompositionProposal());
        assertTrue(issue.getDecompositionProposal().contains("title"));
        verify(gitHubApi, never()).createIssue(anyString(), anyString(), anyString(), anyString(), anyList());
        verify(gitHubApi, never()).closeIssue(anyString(), anyString(), anyInt());
        verify(gitHubApi).addComment(eq("owner"), eq("repo"), eq(42), contains("Proposed Split"));
    }

    @Test
    void approveCreatesSubIssuesAndConvertsParentToTracker() throws Exception {
        TrackedIssue issue = createIssue();
        issue.setStatus(IssueStatus.AWAITING_DECOMPOSITION);
        String proposalJson = """
                [
                  {"title": "1/3: First task", "description": "Do first thing", "acceptance_criteria": "Done", "hints": ""},
                  {"title": "2/3: Second task", "description": "Do second thing", "acceptance_criteria": "Done", "hints": ""},
                  {"title": "3/3: Third task", "description": "Do third thing", "acceptance_criteria": "Done", "hints": ""}
                ]
                """;
        issue.setDecompositionProposal(proposalJson);
        when(issueRepository.findById(1L)).thenReturn(Optional.of(issue));

        ObjectNode sub1 = objectMapper.createObjectNode();
        sub1.put("number", 101);
        ObjectNode sub2 = objectMapper.createObjectNode();
        sub2.put("number", 102);
        ObjectNode sub3 = objectMapper.createObjectNode();
        sub3.put("number", 103);
        when(gitHubApi.createIssue(eq("owner"), eq("repo"), anyString(), anyString(), anyList()))
                .thenReturn(sub1, sub2, sub3);

        decompositionService.approveProposal(issue);

        verify(gitHubApi, times(3)).createIssue(eq("owner"), eq("repo"), anyString(), anyString(),
                eq(List.of("agent-ready", "issuebot-decomposed")));
        verify(gitHubApi).addLabels(eq("owner"), eq("repo"), eq(42), eq(List.of("issuebot-parent")));
        verify(gitHubApi).removeLabel("owner", "repo", 42, "agent-ready");
        verify(gitHubApi, never()).closeIssue(anyString(), anyString(), anyInt());
        assertEquals(IssueStatus.DECOMPOSED, issue.getStatus());
        assertNull(issue.getDecompositionProposal());
    }

    @Test
    void approveWithAllCreationsFailingKeepsProposalAndStatus() {
        TrackedIssue issue = createIssue();
        issue.setStatus(IssueStatus.AWAITING_DECOMPOSITION);
        String proposalJson = """
                [
                  {"title": "1/2: First task", "description": "Do first thing", "acceptance_criteria": "Done", "hints": ""},
                  {"title": "2/2: Second task", "description": "Do second thing", "acceptance_criteria": "Done", "hints": ""}
                ]
                """;
        issue.setDecompositionProposal(proposalJson);
        when(issueRepository.findById(1L)).thenReturn(Optional.of(issue));
        when(gitHubApi.createIssue(anyString(), anyString(), anyString(), anyString(), anyList()))
                .thenThrow(new RuntimeException("API error"));

        assertThrows(IllegalStateException.class, () -> decompositionService.approveProposal(issue));

        assertEquals(IssueStatus.AWAITING_DECOMPOSITION, issue.getStatus());
        assertNotNull(issue.getDecompositionProposal());
        verify(gitHubApi, never()).addLabels(anyString(), anyString(), anyInt(), anyList());
        verify(gitHubApi, never()).removeLabel(anyString(), anyString(), anyInt(), anyString());
        verify(gitHubApi, never()).closeIssue(anyString(), anyString(), anyInt());
    }

    @Test
    void secondApproveIsRejectedByGuard() throws Exception {
        TrackedIssue issue = createIssue();
        issue.setStatus(IssueStatus.AWAITING_DECOMPOSITION);
        String proposalJson = """
                [
                  {"title": "1/2: First task", "description": "Do first thing", "acceptance_criteria": "Done", "hints": ""},
                  {"title": "2/2: Second task", "description": "Do second thing", "acceptance_criteria": "Done", "hints": ""}
                ]
                """;
        issue.setDecompositionProposal(proposalJson);
        when(issueRepository.findById(1L)).thenReturn(Optional.of(issue));

        ObjectNode sub1 = objectMapper.createObjectNode();
        sub1.put("number", 101);
        ObjectNode sub2 = objectMapper.createObjectNode();
        sub2.put("number", 102);
        when(gitHubApi.createIssue(eq("owner"), eq("repo"), anyString(), anyString(), anyList()))
                .thenReturn(sub1, sub2);

        decompositionService.approveProposal(issue);

        // Second submit re-reads the (now DECOMPOSED, proposal-cleared) issue and must hit the guard
        assertThrows(IllegalStateException.class, () -> decompositionService.approveProposal(issue));

        verify(gitHubApi, times(2)).createIssue(anyString(), anyString(), anyString(), anyString(), anyList());
    }

    @Test
    void rejectDelegatesToEscalation() {
        TrackedIssue issue = createIssue();
        issue.setStatus(IssueStatus.AWAITING_DECOMPOSITION);
        issue.setDecompositionProposal("[{\"title\":\"x\",\"description\":\"y\",\"acceptance_criteria\":\"z\",\"hints\":\"\"}]");
        when(issueRepository.findById(1L)).thenReturn(Optional.of(issue));

        decompositionService.rejectProposal(issue);

        assertNull(issue.getDecompositionProposal());
        verify(iterationManager).handleProposalRejected(issue);
    }

    @Test
    void approveGuardsWrongStatus() {
        TrackedIssue issue = createIssue();
        issue.setStatus(IssueStatus.IN_PROGRESS);
        when(issueRepository.findById(1L)).thenReturn(Optional.of(issue));

        assertThrows(IllegalStateException.class, () -> decompositionService.approveProposal(issue));

        verifyNoInteractions(gitHubApi);
    }

    @Test
    void buildDecompositionPrompt_includesTitleAndBody() {
        ObjectNode details = createIssueDetails();
        String prompt = decompositionService.buildDecompositionPrompt(details);

        assertTrue(prompt.contains("Fix the login bug"));
        assertTrue(prompt.contains("special characters"));
        assertTrue(prompt.contains("sub-tasks"));
        assertTrue(prompt.contains("hints"));
    }

    // === Pre-screen tests ===

    @Test
    void preScreen_tooLarge_returnsTrue() {
        String claudeOutput = """
                {"too_large": true, "reason": "Spans 4 layers with 12+ files", "estimated_files": 12, "estimated_complexity": "high"}
                """;
        ClaudeCodeResult claudeResult = new ClaudeCodeResult();
        claudeResult.setSuccess(true);
        claudeResult.setOutput(claudeOutput);
        when(claudeCode.executeUtility(anyString(), any(Path.class), any())).thenReturn(claudeResult);

        IssueDecompositionService.PreScreenResult result =
                decompositionService.preScreen(createIssueDetails(), Path.of("/tmp/repo"));

        assertTrue(result.tooLarge());
        assertTrue(result.reason().contains("12+ files"));
    }

    @Test
    void preScreen_notTooLarge_returnsFalse() {
        String claudeOutput = """
                {"too_large": false, "reason": "Simple bug fix in 2 files", "estimated_files": 2, "estimated_complexity": "low"}
                """;
        ClaudeCodeResult claudeResult = new ClaudeCodeResult();
        claudeResult.setSuccess(true);
        claudeResult.setOutput(claudeOutput);
        when(claudeCode.executeUtility(anyString(), any(Path.class), any())).thenReturn(claudeResult);

        IssueDecompositionService.PreScreenResult result =
                decompositionService.preScreen(createIssueDetails(), Path.of("/tmp/repo"));

        assertFalse(result.tooLarge());
    }

    @Test
    void preScreen_claudeFailure_defaultsToFalse() {
        when(claudeCode.executeUtility(anyString(), any(Path.class), any()))
                .thenThrow(new RuntimeException("API error"));

        IssueDecompositionService.PreScreenResult result =
                decompositionService.preScreen(createIssueDetails(), Path.of("/tmp/repo"));

        assertFalse(result.tooLarge());
    }

    @Test
    void preScreen_emptyResponse_defaultsToFalse() {
        ClaudeCodeResult claudeResult = new ClaudeCodeResult();
        claudeResult.setOutput("");
        when(claudeCode.executeUtility(anyString(), any(Path.class), any())).thenReturn(claudeResult);

        IssueDecompositionService.PreScreenResult result =
                decompositionService.preScreen(createIssueDetails(), Path.of("/tmp/repo"));

        assertFalse(result.tooLarge());
    }

    @Test
    void preScreen_invalidJson_defaultsToFalse() {
        ClaudeCodeResult claudeResult = new ClaudeCodeResult();
        claudeResult.setOutput("Not JSON at all");
        when(claudeCode.executeUtility(anyString(), any(Path.class), any())).thenReturn(claudeResult);

        IssueDecompositionService.PreScreenResult result =
                decompositionService.preScreen(createIssueDetails(), Path.of("/tmp/repo"));

        assertFalse(result.tooLarge());
    }

    @Test
    void parsePreScreenResult_validJson() {
        String output = """
                ```json
                {"too_large": true, "reason": "Multiple distinct features requested", "estimated_files": 8, "estimated_complexity": "high"}
                ```
                """;
        IssueDecompositionService.PreScreenResult result = decompositionService.parsePreScreenResult(output);
        assertTrue(result.tooLarge());
        assertEquals("Multiple distinct features requested", result.reason());
    }

    @Test
    void parsePreScreenResult_noJson_defaultsFalse() {
        IssueDecompositionService.PreScreenResult result =
                decompositionService.parsePreScreenResult("Just some text, no JSON.");
        assertFalse(result.tooLarge());
    }

    @Test
    void buildPreScreenPrompt_includesTitleAndBody() {
        String prompt = decompositionService.buildPreScreenPrompt(createIssueDetails());
        assertTrue(prompt.contains("Fix the login bug"));
        assertTrue(prompt.contains("special characters"));
        assertTrue(prompt.contains("too_large"));
    }

    // === Decomposition guard tests ===

    @Test
    void neverDecomposesAnAlreadyDecomposedIssue() {
        TrackedIssue issue = createIssue();
        ObjectNode issueDetails = createIssueDetailsWithLabel("issuebot-decomposed");

        boolean result = decompositionService.decompose(issue, issueDetails,
                Path.of("/tmp/repo"), "timed out");

        assertFalse(result);
        verify(gitHubApi, never()).createIssue(anyString(), anyString(), anyString(), anyString(), anyList());
        verify(claudeCode, never()).executeUtility(anyString(), any(Path.class), any());
    }

    @Test
    void preScreenSkipsDecomposedIssues() {
        ObjectNode issueDetails = createIssueDetailsWithLabel("issuebot-decomposed");

        IssueDecompositionService.PreScreenResult result =
                decompositionService.preScreen(issueDetails, Path.of("/tmp/repo"));

        assertFalse(result.tooLarge());
        verify(claudeCode, never()).executeUtility(anyString(), any(Path.class), any());
    }

    @Test
    void refusesDecompositionBeyondOpenSubIssueCap() {
        TrackedIssue issue = createIssue();
        ObjectNode issueDetails = createIssueDetails();

        // Stub Claude + GitHub so decomposition WOULD succeed if the cap didn't stop it first —
        // this ensures the cap guard itself is what causes the false return, not an unrelated failure.
        String claudeOutput = """
                [
                  {"title": "1/2: First task", "description": "Do first thing", "acceptance_criteria": "Done", "hints": ""},
                  {"title": "2/2: Second task", "description": "Do second thing", "acceptance_criteria": "Done", "hints": ""}
                ]
                """;
        ClaudeCodeResult claudeResult = new ClaudeCodeResult();
        claudeResult.setSuccess(true);
        claudeResult.setOutput(claudeOutput);
        when(claudeCode.executeUtility(anyString(), any(Path.class), any())).thenReturn(claudeResult);

        when(gitHubApi.listIssues("owner", "repo", "issuebot-decomposed", "open"))
                .thenReturn(createOpenSubIssueNodes(10));

        boolean result = decompositionService.decompose(issue, issueDetails,
                Path.of("/tmp/repo"), "timed out");

        assertFalse(result);
        verify(gitHubApi, never()).createIssue(anyString(), anyString(), anyString(), anyString(), anyList());
        verify(claudeCode, never()).executeUtility(anyString(), any(Path.class), any());
    }

    private List<JsonNode> createOpenSubIssueNodes(int count) {
        List<JsonNode> nodes = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("number", 200 + i);
            nodes.add(node);
        }
        return nodes;
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

    private ObjectNode createIssueDetailsWithLabel(String labelName) {
        ObjectNode details = objectMapper.createObjectNode();
        details.put("title", "Fix the login bug");
        details.put("body", "Users can't log in when password contains special characters");
        ObjectNode label = objectMapper.createObjectNode();
        label.put("name", labelName);
        details.putArray("labels").add(label);
        return details;
    }
}

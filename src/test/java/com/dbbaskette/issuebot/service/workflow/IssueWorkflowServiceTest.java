package com.dbbaskette.issuebot.service.workflow;

import com.dbbaskette.issuebot.repository.CostTrackingRepository;
import com.dbbaskette.issuebot.repository.IterationRepository;
import com.dbbaskette.issuebot.repository.TrackedIssueRepository;
import com.dbbaskette.issuebot.service.claude.ClaudeCodeService;
import com.dbbaskette.issuebot.service.event.EventService;
import com.dbbaskette.issuebot.service.git.GitOperationsService;
import com.dbbaskette.issuebot.service.github.GitHubApiClient;
import com.dbbaskette.issuebot.service.notification.NotificationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class IssueWorkflowServiceTest {

    private IssueWorkflowService workflowService;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        workflowService = new IssueWorkflowService(
                mock(GitOperationsService.class),
                mock(GitHubApiClient.class),
                mock(ClaudeCodeService.class),
                mock(TrackedIssueRepository.class),
                mock(IterationRepository.class),
                mock(CostTrackingRepository.class),
                mock(EventService.class),
                mock(NotificationService.class),
                mock(IterationManager.class),
                objectMapper
        );
    }

    @Test
    void parseSelfAssessment_validJson() {
        String json = """
                {"passed": true, "summary": "All good", "issues": [], "completenessScore": 0.9, "correctnessScore": 0.95, "testCoverageScore": 0.8, "codeStyleScore": 0.9}
                """;
        SelfAssessmentResult result = workflowService.parseSelfAssessment(json);
        assertTrue(result.isPassed());
        assertEquals("All good", result.getSummary());
        assertTrue(result.getIssues().isEmpty());
    }

    @Test
    void parseSelfAssessment_failedAssessment() {
        String json = """
                {"passed": false, "summary": "Tests missing", "issues": ["No unit tests", "Missing edge case"], "completenessScore": 0.5}
                """;
        SelfAssessmentResult result = workflowService.parseSelfAssessment(json);
        assertFalse(result.isPassed());
        assertEquals(2, result.getIssues().size());
    }

    @Test
    void parseSelfAssessment_jsonWithSurroundingText() {
        String output = "Here is my assessment:\n{\"passed\": true, \"summary\": \"Looks good\", \"issues\": []}\nEnd.";
        SelfAssessmentResult result = workflowService.parseSelfAssessment(output);
        assertTrue(result.isPassed());
    }

    @Test
    void parseSelfAssessment_invalidJson() {
        String output = "This is not JSON at all";
        SelfAssessmentResult result = workflowService.parseSelfAssessment(output);
        assertFalse(result.isPassed());
        assertNotNull(result.getSummary());
    }

    @Test
    void parseSelfAssessment_emptyOutput() {
        SelfAssessmentResult result = workflowService.parseSelfAssessment("");
        assertFalse(result.isPassed());
    }

    @Test
    void parseSelfAssessment_nullOutput() {
        SelfAssessmentResult result = workflowService.parseSelfAssessment(null);
        assertFalse(result.isPassed());
    }

    @Test
    void buildImplementationPrompt_firstIteration() {
        ObjectNode issue = objectMapper.createObjectNode();
        issue.put("title", "Add pagination");
        issue.put("body", "Add pagination to the /users endpoint");
        issue.putArray("labels");

        String prompt = workflowService.buildImplementationPrompt(issue, null, null, null);
        assertTrue(prompt.contains("Add pagination"));
        assertTrue(prompt.contains("/users endpoint"));
        assertFalse(prompt.contains("Previous Iteration"));
    }

    @Test
    void buildImplementationPrompt_retryWithContext() {
        ObjectNode issue = objectMapper.createObjectNode();
        issue.put("title", "Add pagination");
        issue.put("body", "Description");
        issue.putArray("labels");

        String prompt = workflowService.buildImplementationPrompt(issue,
                "diff content", "Tests failed", "Build error on line 42");
        assertTrue(prompt.contains("Previous Iteration"));
        assertTrue(prompt.contains("Tests failed"));
        assertTrue(prompt.contains("Build error on line 42"));
        assertTrue(prompt.contains("diff content"));
    }
}

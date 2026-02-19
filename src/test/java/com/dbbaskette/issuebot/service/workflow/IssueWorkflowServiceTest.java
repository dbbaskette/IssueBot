package com.dbbaskette.issuebot.service.workflow;

import com.dbbaskette.issuebot.repository.CostTrackingRepository;
import com.dbbaskette.issuebot.repository.IterationRepository;
import com.dbbaskette.issuebot.repository.TrackedIssueRepository;
import com.dbbaskette.issuebot.service.ci.CiTemplateService;
import com.dbbaskette.issuebot.service.claude.ClaudeCodeService;
import com.dbbaskette.issuebot.service.event.EventService;
import com.dbbaskette.issuebot.service.event.SseService;
import com.dbbaskette.issuebot.service.git.GitOperationsService;
import com.dbbaskette.issuebot.service.github.GitHubApiClient;
import com.dbbaskette.issuebot.service.notification.NotificationService;
import com.dbbaskette.issuebot.service.review.CodeReviewResult;
import com.dbbaskette.issuebot.service.review.CodeReviewService;
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
                mock(CodeReviewService.class),
                mock(CiTemplateService.class),
                mock(TrackedIssueRepository.class),
                mock(IterationRepository.class),
                mock(CostTrackingRepository.class),
                mock(EventService.class),
                mock(SseService.class),
                mock(NotificationService.class),
                mock(IterationManager.class),
                objectMapper
        );
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

    @Test
    void buildReviewFeedback_includesAllFindings() {
        CodeReviewResult review = new CodeReviewResult(
                false, "Missing test coverage",
                0.9, 0.8, 0.85, 0.4, 0.9, 0.9, 1.0,
                List.of(
                        new CodeReviewResult.ReviewFinding("high", "test_coverage",
                                "src/main/Service.java", 42,
                                "No tests for edge case", "Add test for null input"),
                        new CodeReviewResult.ReviewFinding("medium", "code_quality",
                                "src/main/Controller.java", null,
                                "Method too long", "Extract helper method")
                ),
                "Focus on test coverage",
                null, 1000, 500, "claude-sonnet-4-6"
        );

        String feedback = workflowService.buildReviewFeedback(review);
        assertTrue(feedback.contains("Missing test coverage"));
        assertTrue(feedback.contains("[HIGH]"));
        assertTrue(feedback.contains("Service.java:42"));
        assertTrue(feedback.contains("No tests for edge case"));
        assertTrue(feedback.contains("Focus on test coverage"));
        assertTrue(feedback.contains("tests=40%"));
    }
}

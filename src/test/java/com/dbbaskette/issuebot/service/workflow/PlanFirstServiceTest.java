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
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PlanFirstServiceTest {

    private PlanFirstService planFirstService;
    private ClaudeCodeService claudeCode;
    private GitHubApiClient gitHubApi;
    private TrackedIssueRepository issueRepository;
    private EventService eventService;
    private NotificationService notificationService;
    private IterationManager iterationManager;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        claudeCode = mock(ClaudeCodeService.class);
        gitHubApi = mock(GitHubApiClient.class);
        issueRepository = mock(TrackedIssueRepository.class);
        eventService = mock(EventService.class);
        notificationService = mock(NotificationService.class);
        iterationManager = mock(IterationManager.class);
        objectMapper = new ObjectMapper();

        planFirstService = new PlanFirstService(
                claudeCode, gitHubApi, issueRepository, eventService, notificationService, iterationManager);
    }

    private TrackedIssue createIssue() {
        WatchedRepo repo = new WatchedRepo("owner", "repo");
        repo.setId(1L);
        TrackedIssue issue = new TrackedIssue(repo, 42, "Add pagination");
        issue.setId(1L);
        issue.setStatus(IssueStatus.IN_PROGRESS);
        return issue;
    }

    private ObjectNode createIssueDetails() {
        ObjectNode details = objectMapper.createObjectNode();
        details.put("title", "Add pagination");
        details.put("body", "Add pagination to the /users endpoint");
        details.putArray("labels");
        return details;
    }

    // === proposePlan ===

    @Test
    void proposePlan_success_storesPlanPostsCommentAndSetsStatus() {
        TrackedIssue issue = createIssue();
        ObjectNode issueDetails = createIssueDetails();

        ClaudeCodeResult result = new ClaudeCodeResult();
        result.setSuccess(true);
        result.setOutput("1. Add a Pageable param\n2. Update the repository query");
        when(claudeCode.executeUtility(anyString(), any(Path.class), any())).thenReturn(result);

        boolean proposed = planFirstService.proposePlan(issue, issueDetails, Path.of("/tmp/repo"));

        assertTrue(proposed);
        assertEquals(IssueStatus.AWAITING_PLAN_APPROVAL, issue.getStatus());
        assertNull(issue.getCurrentPhase());
        assertNotNull(issue.getImplementationPlan());
        assertTrue(issue.getImplementationPlan().contains("Pageable"));
        verify(issueRepository).save(issue);
        verify(gitHubApi).addComment(eq("owner"), eq("repo"), eq(42),
                contains("Proposed Implementation Plan"));
        verify(eventService).log(eq("PLAN_PROPOSED"), anyString(), eq(issue.getRepo()), eq(issue));
        verify(notificationService).info(eq("Plan Proposed"), anyString(), eq(issue));
    }

    @Test
    void proposePlan_plannerFailure_fallsThroughWithoutBlockingIssue() {
        TrackedIssue issue = createIssue();
        ObjectNode issueDetails = createIssueDetails();

        ClaudeCodeResult result = new ClaudeCodeResult();
        result.setSuccess(false);
        result.setOutput(null);
        when(claudeCode.executeUtility(anyString(), any(Path.class), any())).thenReturn(result);

        boolean proposed = planFirstService.proposePlan(issue, issueDetails, Path.of("/tmp/repo"));

        assertFalse(proposed);
        assertEquals(IssueStatus.IN_PROGRESS, issue.getStatus()); // untouched
        assertNull(issue.getImplementationPlan());
        verify(issueRepository, never()).save(any());
        verify(gitHubApi, never()).addComment(anyString(), anyString(), anyInt(), anyString());
        verify(eventService).log(eq("PLAN_FAILED"), anyString(), eq(issue.getRepo()), eq(issue));
    }

    @Test
    void proposePlan_plannerThrows_fallsThroughWithoutBlockingIssue() {
        TrackedIssue issue = createIssue();
        ObjectNode issueDetails = createIssueDetails();

        when(claudeCode.executeUtility(anyString(), any(Path.class), any()))
                .thenThrow(new RuntimeException("CLI crashed"));

        boolean proposed = planFirstService.proposePlan(issue, issueDetails, Path.of("/tmp/repo"));

        assertFalse(proposed);
        assertEquals(IssueStatus.IN_PROGRESS, issue.getStatus());
        verify(issueRepository, never()).save(any());
        verify(eventService).log(eq("PLAN_FAILED"), contains("CLI crashed"), eq(issue.getRepo()), eq(issue));
    }

    @Test
    void buildPlanPrompt_firstAttempt_noFeedbackSection() {
        String prompt = planFirstService.buildPlanPrompt(createIssueDetails(), 0, null);

        assertTrue(prompt.contains("Add pagination"));
        assertTrue(prompt.contains("NOT implementing"));
        assertTrue(prompt.contains("NO code changes"));
        assertFalse(prompt.contains("Operator feedback"));
    }

    @Test
    void buildPlanPrompt_afterRejection_includesOperatorFeedback() {
        String prompt = planFirstService.buildPlanPrompt(createIssueDetails(), 1,
                "Please also consider the caching layer");

        assertTrue(prompt.contains("Operator feedback on the previous plan"));
        assertTrue(prompt.contains("caching layer"));
    }

    // === approvePlan ===

    @Test
    void approvePlan_setsApprovedAndQueuesForPoller() {
        TrackedIssue issue = createIssue();
        issue.setStatus(IssueStatus.AWAITING_PLAN_APPROVAL);
        issue.setImplementationPlan("Do the thing");
        when(issueRepository.findById(1L)).thenReturn(Optional.of(issue));

        planFirstService.approvePlan(issue);

        assertTrue(issue.isPlanApproved());
        assertEquals(IssueStatus.PENDING, issue.getStatus());
        verify(issueRepository).save(issue);
        verify(gitHubApi).addComment(eq("owner"), eq("repo"), eq(42), contains("approved"));
        verify(eventService).log(eq("PLAN_APPROVED"), anyString(), eq(issue.getRepo()), eq(issue));
        verify(notificationService).info(eq("Plan Approved"), anyString(), eq(issue));
    }

    @Test
    void approvePlan_guardsWrongStatus() {
        TrackedIssue issue = createIssue();
        issue.setStatus(IssueStatus.IN_PROGRESS);
        when(issueRepository.findById(1L)).thenReturn(Optional.of(issue));

        assertThrows(IllegalStateException.class, () -> planFirstService.approvePlan(issue));
        verifyNoInteractions(gitHubApi);
    }

    @Test
    void approvePlan_guardsMissingPlan() {
        TrackedIssue issue = createIssue();
        issue.setStatus(IssueStatus.AWAITING_PLAN_APPROVAL);
        issue.setImplementationPlan(null);
        when(issueRepository.findById(1L)).thenReturn(Optional.of(issue));

        assertThrows(IllegalStateException.class, () -> planFirstService.approvePlan(issue));
    }

    // === rejectPlan ===

    @Test
    void rejectPlan_firstRejection_storesFeedbackAndClearsPlan() {
        TrackedIssue issue = createIssue();
        issue.setStatus(IssueStatus.AWAITING_PLAN_APPROVAL);
        issue.setImplementationPlan("Do the thing");
        when(issueRepository.findById(1L)).thenReturn(Optional.of(issue));

        PlanFirstService.RejectOutcome outcome =
                planFirstService.rejectPlan(issue, "Too vague — be specific about test coverage");

        assertEquals(PlanFirstService.RejectOutcome.REGENERATING, outcome);
        assertEquals(1, issue.getPlanRejections());
        assertEquals("Too vague — be specific about test coverage", issue.getPlanFeedback());
        assertNull(issue.getImplementationPlan());
        assertEquals(IssueStatus.PENDING, issue.getStatus());
        verify(issueRepository).save(issue);
        verifyNoInteractions(iterationManager);
        verify(eventService).log(eq("PLAN_REJECTED"), anyString(), eq(issue.getRepo()), eq(issue));
    }

    @Test
    void rejectPlan_secondRejection_escalatesViaIterationManager() {
        TrackedIssue issue = createIssue();
        issue.setStatus(IssueStatus.AWAITING_PLAN_APPROVAL);
        issue.setImplementationPlan("Do the thing");
        issue.setPlanRejections(1);
        when(issueRepository.findById(1L)).thenReturn(Optional.of(issue));

        PlanFirstService.RejectOutcome outcome =
                planFirstService.rejectPlan(issue, "Still not good enough");

        assertEquals(PlanFirstService.RejectOutcome.ESCALATED, outcome);
        assertEquals(2, issue.getPlanRejections());
        assertEquals("Still not good enough", issue.getPlanFeedback());
        assertNull(issue.getImplementationPlan());
        verify(issueRepository).save(issue);
        verify(iterationManager).handlePlanRejectedTwice(issue);
        // Escalation itself sets terminal status — PlanFirstService must not also set PENDING
        verify(eventService, never()).log(eq("PLAN_REJECTED"), anyString(), any(), any());
    }

    @Test
    void rejectPlan_guardsWrongStatus() {
        TrackedIssue issue = createIssue();
        issue.setStatus(IssueStatus.IN_PROGRESS);
        when(issueRepository.findById(1L)).thenReturn(Optional.of(issue));

        assertThrows(IllegalStateException.class, () -> planFirstService.rejectPlan(issue, "feedback"));
        verifyNoInteractions(iterationManager);
    }

    @Test
    void rejectPlan_guardsMissingPlan() {
        TrackedIssue issue = createIssue();
        issue.setStatus(IssueStatus.AWAITING_PLAN_APPROVAL);
        issue.setImplementationPlan(null);
        when(issueRepository.findById(1L)).thenReturn(Optional.of(issue));

        assertThrows(IllegalStateException.class, () -> planFirstService.rejectPlan(issue, "feedback"));
    }
}

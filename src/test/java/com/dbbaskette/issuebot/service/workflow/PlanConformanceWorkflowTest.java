package com.dbbaskette.issuebot.service.workflow;

import com.dbbaskette.issuebot.config.IssueBotProperties;
import com.dbbaskette.issuebot.model.IssueStatus;
import com.dbbaskette.issuebot.model.Iteration;
import com.dbbaskette.issuebot.model.RepoMode;
import com.dbbaskette.issuebot.model.TrackedIssue;
import com.dbbaskette.issuebot.model.WatchedRepo;
import com.dbbaskette.issuebot.repository.CostTrackingRepository;
import com.dbbaskette.issuebot.repository.IssueGuidanceRepository;
import com.dbbaskette.issuebot.repository.IterationRepository;
import com.dbbaskette.issuebot.repository.RepoLessonRepository;
import com.dbbaskette.issuebot.repository.TrackedIssueRepository;
import com.dbbaskette.issuebot.repository.WatchedRepoRepository;
import com.dbbaskette.issuebot.service.ci.CiTemplateService;
import com.dbbaskette.issuebot.service.claude.ClaudeCodeResult;
import com.dbbaskette.issuebot.service.claude.ClaudeCodeService;
import com.dbbaskette.issuebot.service.claude.ModelResolver;
import com.dbbaskette.issuebot.service.event.EventService;
import com.dbbaskette.issuebot.service.event.SseService;
import com.dbbaskette.issuebot.service.git.GitOperationsService;
import com.dbbaskette.issuebot.service.github.GitHubApiClient;
import com.dbbaskette.issuebot.service.notification.NotificationService;
import com.dbbaskette.issuebot.service.review.CodeReviewResult;
import com.dbbaskette.issuebot.service.review.CodeReviewService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PlanConformanceWorkflowTest {

    private GitOperationsService gitOps;
    private GitHubApiClient gitHubApi;
    private ClaudeCodeService agent;
    private CodeReviewService reviewer;
    private TrackedIssueRepository issueRepository;
    private IterationRepository iterationRepository;
    private CostTrackingRepository costRepository;
    private PlanFirstService planFirstService;
    private IssueWorkflowService workflow;
    private ObjectMapper objectMapper;
    private ApprovedPlanContext approvedPlan;

    @BeforeEach
    void setUp() {
        gitOps = mock(GitOperationsService.class);
        gitHubApi = mock(GitHubApiClient.class);
        agent = mock(ClaudeCodeService.class);
        reviewer = mock(CodeReviewService.class);
        issueRepository = mock(TrackedIssueRepository.class);
        iterationRepository = mock(IterationRepository.class);
        costRepository = mock(CostTrackingRepository.class);
        planFirstService = mock(PlanFirstService.class);
        objectMapper = new ObjectMapper();
        approvedPlan = new ApprovedPlanContext(4L, 2, "spec contract", "plan contract");

        WatchedRepoRepository repoRepository = mock(WatchedRepoRepository.class);
        when(repoRepository.findById(any())).thenReturn(Optional.empty());
        IterationManager iterationManager = new IterationManager(
                issueRepository, repoRepository, iterationRepository, gitHubApi,
                mock(EventService.class), mock(NotificationService.class));

        workflow = new IssueWorkflowService(
                gitOps, gitHubApi, agent, reviewer, mock(CiTemplateService.class),
                mock(LocalVerificationService.class), issueRepository, iterationRepository,
                costRepository, mock(EventService.class), mock(SseService.class),
                mock(NotificationService.class), iterationManager,
                mock(IssueDecompositionService.class), planFirstService, mock(FollowUpService.class),
                new ModelResolver(new IssueBotProperties()), new WorkflowCancellationService(),
                mock(IssueGuidanceRepository.class), mock(RepoLessonRepository.class),
                mock(LessonsService.class), objectMapper);
        workflow.reviewRetryBackoffBaseMs = 0;
    }

    @Test
    void firstPlanConformanceMissGetsExactlyOneCorrectionBeyondNormalMax() throws Exception {
        TrackedIssue issue = planFirstIssue();
        arrangeWorkflow(issue);
        when(reviewer.reviewCode(any(), anyString(), anyString(), anyString(), anyString(), anyLong(),
                anyList(), anyBoolean(), anyDouble(), any(), eq(approvedPlan), any()))
                .thenReturn(failedConformance("first miss"), passedConformance());

        workflow.processIssue(issue);

        assertThat(issue.getPlanConformanceAttempt()).isEqualTo(2);
        assertThat(issue.isPlanCorrectionPending()).isFalse();
        assertThat(issue.getStatus()).isEqualTo(IssueStatus.COMPLETED);
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(agent, times(2)).executeImplementation(
                promptCaptor.capture(), any(), anyString(), any(), anyLong(), any());
        assertThat(promptCaptor.getAllValues().get(1))
                .contains("Assessment Feedback")
                .contains("first miss");
        verify(reviewer, times(2)).reviewCode(any(), anyString(), anyString(), anyString(), anyString(), anyLong(),
                anyList(), anyBoolean(), anyDouble(), any(), eq(approvedPlan), any());
    }

    @Test
    void secondPlanConformanceMissStopsAfterTwoRealVerdicts() throws Exception {
        TrackedIssue issue = planFirstIssue();
        arrangeWorkflow(issue);
        when(reviewer.reviewCode(any(), anyString(), anyString(), anyString(), anyString(), anyLong(),
                anyList(), anyBoolean(), anyDouble(), any(), eq(approvedPlan), any()))
                .thenReturn(failedConformance("first miss"), failedConformance("second miss"));

        workflow.processIssue(issue);

        assertThat(issue.getPlanConformanceAttempt()).isEqualTo(2);
        assertThat(issue.isPlanCorrectionPending()).isFalse();
        assertThat(issue.getStatus()).isIn(IssueStatus.FAILED, IssueStatus.COOLDOWN);
        assertThat(issue.getLastFailureReason())
                .contains("approved Plan v2")
                .contains("two review attempts");
        verify(agent, times(2)).executeImplementation(
                anyString(), any(), anyString(), any(), anyLong(), any());
        verify(reviewer, times(2)).reviewCode(any(), anyString(), anyString(), anyString(), anyString(), anyLong(),
                anyList(), anyBoolean(), anyDouble(), any(), eq(approvedPlan), any());
        verify(iterationRepository, atLeastOnce()).save(argThat(iteration ->
                iteration.getIterationNum() == 1
                        && iteration.getReviewJson() != null
                        && iteration.getReviewJson().contains("first miss")));
        verify(iterationRepository, atLeastOnce()).save(argThat(iteration ->
                iteration.getIterationNum() == 2
                        && iteration.getReviewJson() != null
                        && iteration.getReviewJson().contains("second miss")));
    }

    @Test
    void reviewInvocationFailuresConsumeNoPlanConformanceAttempt() throws Exception {
        TrackedIssue issue = planFirstIssue();
        arrangeWorkflow(issue);
        when(reviewer.reviewCode(any(), anyString(), anyString(), anyString(), anyString(), anyLong(),
                anyList(), anyBoolean(), anyDouble(), any(), eq(approvedPlan), any()))
                .thenReturn(CodeReviewResult.failed("review unavailable", 0, 0, "review-model"));

        workflow.processIssue(issue);

        assertThat(issue.getPlanConformanceAttempt()).isZero();
        assertThat(issue.isPlanCorrectionPending()).isFalse();
        verify(reviewer, times(5)).reviewCode(any(), anyString(), anyString(), anyString(), anyString(), anyLong(),
                anyList(), anyBoolean(), anyDouble(), any(), eq(approvedPlan), any());
        verify(agent).executeImplementation(anyString(), any(), anyString(), any(), anyLong(), any());
    }

    private TrackedIssue planFirstIssue() {
        WatchedRepo repo = new WatchedRepo("owner", "repo");
        repo.setId(1L);
        repo.setBranch("main");
        repo.setMode(RepoMode.AUTONOMOUS);
        repo.setPlanFirst(true);
        repo.setPreScreenEnabled(false);
        repo.setCiEnabled(false);
        repo.setMaxIterations(1);
        repo.setMaxReviewIterations(5);
        TrackedIssue issue = new TrackedIssue(repo, 42, "Implement approved plan");
        issue.setId(1L);
        return issue;
    }

    private void arrangeWorkflow(TrackedIssue issue) throws Exception {
        ObjectNode details = objectMapper.createObjectNode();
        details.put("title", issue.getIssueTitle());
        details.put("body", "- [ ] required behavior");
        details.putArray("labels");

        Git git = mock(Git.class);
        when(planFirstService.approvedContext(issue)).thenReturn(Optional.of(approvedPlan));
        when(gitOps.cloneOrPull("owner", "repo", "main")).thenReturn(git);
        when(gitOps.createBranch(eq(git), eq(42), anyString())).thenReturn("issuebot/issue-42-approved-plan");
        when(gitOps.repoLocalPath("owner", "repo")).thenReturn(Path.of("/tmp/repo"));
        when(gitOps.openRepo("owner", "repo")).thenReturn(git);
        when(gitOps.diff(any(), eq("main"))).thenReturn("+ implementation");
        when(gitHubApi.getIssue("owner", "repo", 42)).thenReturn(details);
        when(gitHubApi.listOpenPullRequests(anyString(), anyString(), anyString())).thenReturn(List.of());
        ObjectNode pr = objectMapper.createObjectNode();
        pr.put("number", 99);
        when(gitHubApi.createPullRequest(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyBoolean())).thenReturn(pr);
        when(issueRepository.findById(1L)).thenReturn(Optional.of(issue));
        when(iterationRepository.findByIssueOrderByIterationNumAsc(issue)).thenReturn(List.of(
                new Iteration(issue, 1), new Iteration(issue, 2)));
        when(costRepository.totalCostForIssue(issue)).thenReturn(BigDecimal.ZERO);
        when(costRepository.totalCostForIssueByPhase(eq(issue), anyString())).thenReturn(BigDecimal.ZERO);
        when(agent.executeImplementation(anyString(), any(), anyString(), any(), anyLong(), any()))
                .thenReturn(successImplementation());
    }

    private ClaudeCodeResult successImplementation() {
        ClaudeCodeResult result = new ClaudeCodeResult();
        result.setSuccess(true);
        result.setOutput("implemented");
        result.setModel("implementation-model");
        return result;
    }

    private CodeReviewResult failedConformance(String summary) {
        return new CodeReviewResult(false, summary,
                0.5, 0.9, 0.9, 0.9, 0.9, 0.9, 1.0,
                List.of(new CodeReviewResult.ReviewFinding("high", "spec_compliance", "src/Main.java", 1,
                        "Approved deliverable missing", "Implement it")),
                "Conform to the approved plan", "{\"passed\":false,\"summary\":\"" + summary + "\"}",
                10, 10, "review-model", null,
                List.of(new CodeReviewResult.CriterionVerdict("required behavior", "unmet", "missing")));
    }

    private CodeReviewResult passedConformance() {
        return new CodeReviewResult(true, "conforms",
                0.95, 0.95, 0.95, 0.95, 0.95, 0.95, 1.0,
                List.of(), "", "{\"passed\":true}", 10, 10, "review-model", null,
                List.of(new CodeReviewResult.CriterionVerdict("required behavior", "met", "done")));
    }
}

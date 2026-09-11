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
import com.dbbaskette.issuebot.service.harness.HarnessExecutionResult;
import com.dbbaskette.issuebot.service.harness.CodingHarnessService;
import com.dbbaskette.issuebot.service.claude.ModelResolver;
import com.dbbaskette.issuebot.service.event.EventService;
import com.dbbaskette.issuebot.service.event.SseService;
import com.dbbaskette.issuebot.service.git.GitOperationsService;
import com.dbbaskette.issuebot.service.github.GitHubApiClient;
import com.dbbaskette.issuebot.service.notification.NotificationService;
import com.dbbaskette.issuebot.service.review.CodeReviewResult;
import com.dbbaskette.issuebot.service.review.CodeReviewService;
import com.dbbaskette.issuebot.service.review.ReviewTestEvidence;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PlanConformanceWorkflowTest {

    private GitOperationsService gitOps;
    private GitHubApiClient gitHubApi;
    private CodingHarnessService agent;
    private CodeReviewService reviewer;
    private TrackedIssueRepository issueRepository;
    private IterationRepository iterationRepository;
    private CostTrackingRepository costRepository;
    private PlanFirstService planFirstService;
    private LocalVerificationService localVerificationService;
    private IterationManager iterationManager;
    private WorkflowCancellationService cancellationService;
    private IssueWorkflowService workflow;
    private ObjectMapper objectMapper;
    private ApprovedPlanContext approvedPlan;

    @BeforeEach
    void setUp() {
        gitOps = mock(GitOperationsService.class);
        gitHubApi = mock(GitHubApiClient.class);
        agent = mock(CodingHarnessService.class);
        when(agent.harnessId()).thenReturn("claude");
        reviewer = mock(CodeReviewService.class);
        issueRepository = mock(TrackedIssueRepository.class);
        iterationRepository = mock(IterationRepository.class);
        costRepository = mock(CostTrackingRepository.class);
        planFirstService = mock(PlanFirstService.class);
        localVerificationService = mock(LocalVerificationService.class);
        objectMapper = new ObjectMapper();
        approvedPlan = new ApprovedPlanContext(4L, 2, "spec contract", "plan contract");

        WatchedRepoRepository repoRepository = mock(WatchedRepoRepository.class);
        when(repoRepository.findById(any())).thenReturn(Optional.empty());
        iterationManager = new IterationManager(
                issueRepository, repoRepository, iterationRepository, gitHubApi,
                mock(EventService.class), mock(NotificationService.class));
        com.dbbaskette.issuebot.service.history.HistoryTestFixtures.iterationManager(iterationManager);

        cancellationService = new WorkflowCancellationService();
        workflow = new IssueWorkflowService(
                gitOps, gitHubApi, agent, reviewer, mock(CiTemplateService.class),
                localVerificationService, issueRepository, iterationRepository,
                costRepository, mock(EventService.class), mock(SseService.class),
                mock(NotificationService.class), iterationManager,
                mock(IssueDecompositionService.class), planFirstService, mock(FollowUpService.class),
                new ModelResolver(new IssueBotProperties(), new com.dbbaskette.issuebot.service.harness.HarnessSelectionFixture().selections), cancellationService,
                mock(IssueGuidanceRepository.class), mock(RepoLessonRepository.class),
                mock(LessonsService.class), objectMapper);
        workflow.reviewRetryBackoffBaseMs = 0;
    }

    @Test
    void firstPlanConformanceMissGetsExactlyOneCorrectionBeyondNormalMax() throws Exception {
        TrackedIssue issue = planFirstIssue();
        arrangeWorkflow(issue);
        List<String> savedClaims = new ArrayList<>();
        doAnswer(invocation -> {
            TrackedIssue saved = invocation.getArgument(0);
            savedClaims.add(saved.getCurrentIteration() + ":" + saved.isPlanCorrectionPending());
            return saved;
        }).when(issueRepository).save(any(TrackedIssue.class));
        when(reviewer.reviewCode(any(), anyString(), anyString(), anyString(), anyString(), anyLong(),
                anyList(), anyBoolean(), anyDouble(), any(), eq(approvedPlan), any(), any()))
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
        assertThat(savedClaims).contains("2:false").doesNotContain("2:true");
        assertThat(iterationManager.canIterate(issue)).isFalse();
        ArgumentCaptor<ReviewTestEvidence> evidence = ArgumentCaptor.forClass(ReviewTestEvidence.class);
        verify(reviewer, times(2)).reviewCode(any(), anyString(), anyString(), anyString(), anyString(), anyLong(),
                anyList(), anyBoolean(), anyDouble(), any(), eq(approvedPlan), evidence.capture(), any());
        assertThat(evidence.getAllValues().get(0).priorReviewContext()).isNull();
        assertThat(evidence.getAllValues().get(1).priorReviewContext())
                .contains("first miss")
                .contains("Approved deliverable missing");
    }

    @Test
    void secondPlanConformanceMissStopsAfterTwoRealVerdicts() throws Exception {
        TrackedIssue issue = planFirstIssue();
        arrangeWorkflow(issue);
        when(reviewer.reviewCode(any(), anyString(), anyString(), anyString(), anyString(), anyLong(),
                anyList(), anyBoolean(), anyDouble(), any(), eq(approvedPlan), any(), any()))
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
                anyList(), anyBoolean(), anyDouble(), any(), eq(approvedPlan), any(), any());
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
                anyList(), anyBoolean(), anyDouble(), any(), eq(approvedPlan), any(), any()))
                .thenReturn(CodeReviewResult.failed("review unavailable", 0, 0, "review-model"));

        workflow.processIssue(issue);

        assertThat(issue.getPlanConformanceAttempt()).isZero();
        assertThat(issue.isPlanCorrectionPending()).isFalse();
        assertThat(issue.getStatus()).isIn(IssueStatus.FAILED, IssueStatus.COOLDOWN);
        verify(reviewer, times(5)).reviewCode(any(), anyString(), anyString(), anyString(), anyString(), anyLong(),
                anyList(), anyBoolean(), anyDouble(), any(), eq(approvedPlan), any(), any());
        verify(agent).executeImplementation(anyString(), any(), anyString(), any(), anyLong(), any());
    }

    @Test
    void thrownReviewInvocationsRetryThenEscalateWithoutConformanceVerdict() throws Exception {
        TrackedIssue issue = planFirstIssue();
        arrangeWorkflow(issue);
        when(reviewer.reviewCode(any(), anyString(), anyString(), anyString(), anyString(), anyLong(),
                anyList(), anyBoolean(), anyDouble(), any(), eq(approvedPlan), any(), any()))
                .thenThrow(new IllegalStateException("review process crashed"));

        workflow.processIssue(issue);

        assertThat(issue.getPlanConformanceAttempt()).isZero();
        assertThat(issue.getStatus()).isIn(IssueStatus.FAILED, IssueStatus.COOLDOWN);
        assertThat(issue.getStatus()).isNotEqualTo(IssueStatus.COMPLETED);
        verify(gitHubApi).addLabels("owner", "repo", 42, List.of("needs-human"));
        verify(reviewer, times(5)).reviewCode(any(), anyString(), anyString(), anyString(), anyString(), anyLong(),
                anyList(), anyBoolean(), anyDouble(), any(), eq(approvedPlan), any(), any());
        verify(agent).executeImplementation(anyString(), any(), anyString(), any(), anyLong(), any());
    }

    @Test
    void operatorStopDuringFailedReviewStopsBeforeRetryBackoffCostOrVerdict() throws Exception {
        TrackedIssue issue = planFirstIssue();
        arrangeWorkflow(issue);
        when(reviewer.reviewCode(any(), anyString(), anyString(), anyString(), anyString(), anyLong(),
                anyList(), anyBoolean(), anyDouble(), any(), eq(approvedPlan), any(), any()))
                .thenAnswer(invocation -> {
                    cancellationService.requestCancel(issue.getId(), CancellationReason.OPERATOR_STOP);
                    return CodeReviewResult.failed(
                            "review process stopped for pause", 13, 5, "review-model");
                });

        workflow.processIssue(issue);

        assertThat(issue.getStatus()).isEqualTo(IssueStatus.FAILED);
        assertThat(issue.getSuspensionReason()).isNull();
        assertThat(issue.getLastFailureReason()).isEqualTo("Cancelled by operator");
        assertThat(issue.getPlanConformanceAttempt()).isZero();
        verify(reviewer).reviewCode(any(), anyString(), anyString(), anyString(), anyString(), anyLong(),
                anyList(), anyBoolean(), anyDouble(), any(), eq(approvedPlan), any(), any());
        verify(costRepository, never()).save(argThat(cost -> "REVIEW".equals(cost.getPhase())));
        verify(iterationRepository, never()).save(argThat(iteration -> iteration.getReviewPassed() != null));
    }

    @Test
    void reviewReceivesCurrentLocalAndCiEvidence() throws Exception {
        TrackedIssue issue = planFirstIssue();
        issue.getRepo().setVerificationCommands("verify");
        arrangeWorkflow(issue);
        when(localVerificationService.run(any(), anyList(), anyInt(), any()))
                .thenReturn(new LocalVerificationService.Result(true, null, "passed"));
        when(reviewer.reviewCode(any(), anyString(), anyString(), anyString(), anyString(), anyLong(),
                anyList(), anyBoolean(), anyDouble(), any(), eq(approvedPlan), any(ReviewTestEvidence.class), any()))
                .thenReturn(passedConformance());

        workflow.processIssue(issue, "Keep the public API stable");

        verify(reviewer).reviewCode(any(), anyString(), anyString(), anyString(), anyString(), anyLong(),
                anyList(), anyBoolean(), anyDouble(), any(), eq(approvedPlan),
                argThat(evidence -> "PASSED".equals(evidence.localVerificationResult())
                        && "SKIPPED".equals(evidence.ciResult())
                        && evidence.priorReviewContext().contains("Keep the public API stable")), any());
    }

    @Test
    void restartedCorrectionRehydratesPersistedReviewAndAugmentsWithHumanInstructions() throws Exception {
        TrackedIssue issue = planFirstIssue();
        issue.setCurrentIteration(1);
        issue.setPlanConformanceAttempt(1);
        issue.setPlanCorrectionPending(true);
        arrangeWorkflow(issue);
        Iteration prior = new Iteration(issue, 1);
        String persistedVerdict = "{\"passed\":false,\"summary\":\"persisted conformance miss\","
                + "\"findings\":[{\"finding\":\"stored missing deliverable\"}]}";
        prior.setReviewJson(persistedVerdict);
        prior.setReviewPassed(false);
        when(iterationRepository.findFirstByIssueIdAndIterationNumOrderByIdDesc(issue.getId(), 1))
                .thenReturn(java.util.Optional.of(prior));
        when(reviewer.reviewCode(any(), anyString(), anyString(), anyString(), anyString(), anyLong(),
                anyList(), anyBoolean(), anyDouble(), any(), eq(approvedPlan), any(), any()))
                .thenReturn(passedConformance());

        workflow.processIssue(issue, "Keep the public API stable");

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(agent).executeImplementation(
                promptCaptor.capture(), any(), anyString(), any(), anyLong(), any());
        assertThat(promptCaptor.getValue())
                .contains(persistedVerdict)
                .contains("Keep the public API stable");
        assertThat(issue.isPlanCorrectionPending()).isFalse();
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

    private HarnessExecutionResult successImplementation() {
        HarnessExecutionResult result = new HarnessExecutionResult();
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

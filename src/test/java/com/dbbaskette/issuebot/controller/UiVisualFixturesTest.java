package com.dbbaskette.issuebot.controller;

import com.dbbaskette.issuebot.config.IssueBotProperties;
import com.dbbaskette.issuebot.config.ConfigInitializer;
import com.dbbaskette.issuebot.model.*;
import com.dbbaskette.issuebot.repository.*;
import com.dbbaskette.issuebot.service.claude.ClaudeCodeService;
import com.dbbaskette.issuebot.service.codex.CodexModelCatalog;
import com.dbbaskette.issuebot.service.github.GitHubApiClient;
import com.dbbaskette.issuebot.service.harness.CodingHarnessService;
import com.dbbaskette.issuebot.service.polling.IssuePollingService;
import com.dbbaskette.issuebot.service.workflow.ProcessingControlService;
import com.dbbaskette.issuebot.validation.StartupValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/** Synthetic, read-only real-MVC fixtures for browser review without starting workers. */
@SpringBootTest(properties = {
        "spring.config.import=", "issuebot.github.token=test-token", "issuebot.auth.username=", "issuebot.auth.password=",
        "spring.datasource.url=jdbc:h2:mem:ui-visual-fixtures;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
})
@AutoConfigureMockMvc
@Transactional
class UiVisualFixturesTest {
    private static final String REVIEW_JSON = """
            {"passed":true,"summary":"Recovery remains atomic and restart-safe.",
             "specComplianceScore":0.94,"correctnessScore":0.92,"codeQualityScore":0.88,
             "testCoverageScore":0.91,"architectureScore":0.90,"regressionRiskScore":0.86,
             "securityScore":0.95,"findings":[],
             "criteria":[
               {"text":"Only one worker acquires a checkpoint","verdict":"met","note":"Atomic claim verified."},
               {"text":"Recovery survives restart","verdict":"met","note":"Restart test passes."}
             ]}
            """;
    private static final String DIFF = """
            diff --git a/src/main/java/CheckpointStore.java b/src/main/java/CheckpointStore.java
            index 7cab123..9afe456 100644
            --- a/src/main/java/CheckpointStore.java
            +++ b/src/main/java/CheckpointStore.java
            @@ -41,2 +41,3 @@
            -  return repository.find(id);
            +  return repository.claimAtomically(id);
            +  // the database constraint selects one worker
            """;

    @Autowired MockMvc mvc;
    @Autowired WatchedRepoRepository repos;
    @Autowired TrackedIssueRepository issues;
    @Autowired PlanningVersionRepository versions;
    @Autowired StageApprovalRepository approvals;
    @Autowired IterationRepository iterations;
    @Autowired CostTrackingRepository costs;
    @Autowired EventRepository events;
    @Autowired RepoLessonRepository lessons;
    @Autowired NotificationRepository notifications;
    @Autowired IssueBotProperties properties;
    @Autowired SettingsController settingsController;
    @Autowired ObjectMapper objectMapper;

    @MockitoBean IssuePollingService polling;
    @MockitoBean StartupValidator startupValidator;
    @MockitoBean ClaudeCodeService claudeCodeService;
    @MockitoBean CodingHarnessService harnessService;
    @MockitoBean GitHubApiClient gitHubApiClient;
    @MockitoBean CodexModelCatalog codexModelCatalog;
    @MockitoBean ProcessingControlService processingControl;
    @MockitoBean ConfigInitializer configInitializer;

    @TempDir Path tempDir;

    @Test
    void rendersAndExportsEveryScreenStateWithoutLiveProbesOrMutations() throws Exception {
        when(polling.isEnabled()).thenReturn(false);
        when(processingControl.mode()).thenReturn(ProcessingState.STOPPED);
        when(processingControl.isRunning()).thenReturn(false);
        when(codexModelCatalog.models()).thenReturn(CodexModelCatalog.fallbackModels());
        when(codexModelCatalog.contains(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(invocation -> CodexModelCatalog.fallbackModels().stream()
                        .anyMatch(model -> model.id().equals(invocation.getArgument(0))));
        when(harnessService.checkCliAvailable()).thenReturn(true);
        when(harnessService.checkSubscriptionAuthentication("codex")).thenReturn(false);
        when(harnessService.displayName()).thenReturn("Codex CLI");
        when(gitHubApiClient.validateToken()).thenReturn(new GitHubApiClient.TokenStatus(
                GitHubApiClient.TokenState.INVALID, "Synthetic fixture: token rejected."));
        when(gitHubApiClient.getCheckRuns(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(objectMapper.readTree("{\"check_runs\":[{\"conclusion\":\"success\"}]}"));

        properties.setAgentProvider("codex");
        properties.setWorkDirectory(tempDir.toString());
        properties.getCodexCli().setImplementationModel("gpt-5.6-sol");
        properties.getCodexCli().setImplementationReasoningEffort("high");
        properties.getCodexCli().setReviewModel("gpt-5.6-terra");
        properties.getCodexCli().setReviewReasoningEffort("medium");
        properties.getCodexCli().setUtilityModel("gpt-5.6-luna");
        properties.getCodexCli().setUtilityReasoningEffort("low");
        Path syntheticConfig = tempDir.resolve("config.yml");
        Files.writeString(syntheticConfig, "issuebot:\n  agent-provider: codex\n  max-concurrent-issues: 3\n");
        settingsController.setConfigPathForTests(syntheticConfig);

        Map<String, ExportedRoute> routes = new LinkedHashMap<>();
        String emptyInbox = render(get("/inbox").param("fixture", "empty-paused"), 200);
        String emptyInboxFragment = render(hx(get("/inbox").param("fixture", "empty-paused")), 200);
        assertThat(emptyInbox).contains("Needs You", "No actions need your attention", "Work stopped");
        put(routes, "/inbox?fixture=empty-paused", "inbox-empty-paused", emptyInbox, emptyInboxFragment);
        routes.put("/fixtures/inbox-empty-paused", routes.get("/inbox?fixture=empty-paused"));

        String emptyCosts = render(get("/costs").param("fixture", "no-data"), 200);
        String emptyCostsFragment = render(hx(get("/costs").param("fixture", "no-data")), 200);
        assertThat(emptyCosts).contains("Costs", "No cost records yet");
        put(routes, "/costs?fixture=no-data", "costs-no-data", emptyCosts, emptyCostsFragment);
        routes.put("/fixtures/costs-no-data", routes.get("/costs?fixture=no-data"));

        WatchedRepo repo = new WatchedRepo("northstar", "workflow-engine");
        repo.setWorkflowPolicy(WorkflowPolicy.STAGED);
        repo.setApprovalStages("IMPLEMENTATION,REVIEW,MERGE");
        repo.setImplementationModel("gpt-5.6-sol");
        repo.setImplementationReasoningEffort("high");
        repo.setReviewModel("gpt-5.6-terra");
        repo.setReviewReasoningEffort("medium");
        repo.setVerificationCommands("./mvnw -q test\nnode --test src/test/js/*.cjs");
        repo.setAllowedPaths("[\"src/main\",\"src/test\"]");
        repo.setLessonsEnabled(true);
        repo.setIssueBudgetUsd(new BigDecimal("15.00"));
        repo = repos.saveAndFlush(repo);
        lessons.saveAndFlush(new RepoLesson(repo.getId(),
                "Claim durable checkpoints before dispatching a worker.", 138));

        TrackedIssue stageIssue = issue(repo, 142,
                "Make checkpoint recovery reliable across concurrent workers",
                IssueStatus.AWAITING_APPROVAL, "STAGE_APPROVAL_IMPLEMENTATION");
        stageIssue.setCurrentIteration(2);
        stageIssue.setBranchName("issuebot/142-checkpoint-recovery");
        stageIssue.setResolvedHarnessId("codex");
        stageIssue.setResolvedImplModel("gpt-5.6-sol");
        stageIssue.setResolvedReviewModel("gpt-5.6-terra");
        stageIssue = issues.saveAndFlush(stageIssue);

        PlanningVersion oldPlan = PlanningVersion.pending(stageIssue, 2,
                "## Earlier design\n\nUse a process-local lock.",
                "## Earlier plan\n\n1. Add an in-memory guard.", "CODEX", "gpt-5.6-sol", null);
        oldPlan.supersede();
        versions.saveAndFlush(oldPlan);
        PlanningVersion approvedPlan = PlanningVersion.pending(stageIssue, 3,
                "## Reliable checkpoint recovery\n\nPrevent duplicate work when workers resume the same checkpoint.",
                "## Implementation plan\n\n1. Add an atomic checkpoint claim.\n2. Test concurrent recovery.\n3. Verify restart behavior.",
                "CODEX", "gpt-5.6-sol", "Include restart verification.");
        approvedPlan.approve(LocalDateTime.now().minusMinutes(20));
        approvedPlan = versions.saveAndFlush(approvedPlan);
        stageIssue.setApprovedPlanningVersion(approvedPlan);
        stageIssue.setImplementationPlan(approvedPlan.getImplementationPlan());
        stageIssue.setPlanApproved(true);
        stageIssue = issues.saveAndFlush(stageIssue);

        Iteration firstIteration = reviewIteration(stageIssue, 1, false,
                REVIEW_JSON.replace("0.94", "0.72"), DIFF.replace("claimAtomically", "findForUpdate"));
        firstIteration.setSelfAssessment("The first attempt exposed a restart race.");
        iterations.saveAndFlush(firstIteration);
        Iteration secondIteration = reviewIteration(stageIssue, 2, true, REVIEW_JSON, DIFF);
        secondIteration.setSelfAssessment("Atomic claim and restart coverage are complete.");
        secondIteration.setClaudeOutput("Implemented durable claim and added concurrent recovery coverage.");
        iterations.saveAndFlush(secondIteration);

        StageApproval stageDecision = new StageApproval();
        stageDecision.setIssue(stageIssue);
        stageDecision.setStage(WorkflowStage.IMPLEMENTATION);
        stageDecision.setAttempt(1);
        stageDecision.setArtifactVersionId(approvedPlan.getId());
        stageDecision.setHarnessId("codex");
        stageDecision.setModel("gpt-5.6-sol");
        approvals.saveAndFlush(stageDecision);

        TrackedIssue prIssue = issue(repo, 143, "Polish the operator approval flow",
                IssueStatus.AWAITING_APPROVAL, "COMPLETION");
        prIssue.setBranchName("issuebot/143-approval-flow");
        prIssue.setPrNumber(87);
        prIssue.setCurrentIteration(1);
        prIssue = issues.saveAndFlush(prIssue);
        iterations.saveAndFlush(reviewIteration(prIssue, 1, true, REVIEW_JSON, DIFF));

        TrackedIssue queuedIssue = issues.saveAndFlush(issue(repo, 144,
                "Add cache-safe fixture navigation", IssueStatus.QUEUED, null));
        TrackedIssue blockedIssue = issue(repo, 145,
                "Publish the fixture verification notes", IssueStatus.BLOCKED, null);
        blockedIssue.setBlockedByIssues(String.valueOf(queuedIssue.getIssueNumber()));
        issues.saveAndFlush(blockedIssue);

        TrackedIssue runningIssue = issue(repo, 146,
                "Exercise the live dashboard fragment", IssueStatus.IN_PROGRESS, "INDEPENDENT_REVIEW");
        runningIssue.setStartedAt(LocalDateTime.now().minusMinutes(8));
        runningIssue.setCurrentIteration(1);
        runningIssue = issues.saveAndFlush(runningIssue);
        costs.saveAndFlush(new CostTracking(runningIssue, 1, 4200, 900,
                new BigDecimal("0.0000"), "gpt-5.6-sol"));

        events.saveAndFlush(new Event("PHASE_INDEPENDENT_REVIEW",
                "Independent review is checking restart safety.", repo, runningIssue));
        events.saveAndFlush(new Event("STAGE_APPROVAL_REQUIRED",
                "Implementation is ready for operator approval.", repo, stageIssue));

        Notification info = notifications.saveAndFlush(new Notification(Notification.Severity.INFO,
                "Fixture export ready", "All real-MVC routes rendered from synthetic data.", null));
        Notification warn = notifications.saveAndFlush(new Notification(Notification.Severity.WARN,
                "Stage decision waiting", "Review implementation evidence for #142.", stageIssue.getId()));
        Notification error = notifications.saveAndFlush(new Notification(Notification.Severity.ERROR,
                "Authentication check failed", "Synthetic setup fixture shows a failed prerequisite.", null));
        error.setReadAt(LocalDateTime.now().minusMinutes(2));
        notifications.saveAndFlush(error);

        String dashboard = render(get("/"), 200);
        String dashboardFragment = render(hx(get("/")), 200);
        assertThat(dashboard).contains("Dashboard", "Needs your decision", "Currently processing",
                "More workflow counts", stageIssue.getIssueTitle(), runningIssue.getIssueTitle());
        put(routes, "/", "dashboard", dashboard, dashboardFragment);

        String dashboardLive = render(get("/dashboard/live"), 200);
        assertThat(dashboardLive).contains("dashboard-live", "Independent review is checking restart safety");
        putFragment(routes, "/dashboard/live", "dashboard-live", dashboardLive);

        String repositories = render(get("/repositories"), 200);
        String repositoriesFragment = render(hx(get("/repositories")), 200);
        assertThat(repositories).contains("Repositories", repo.fullName(), "Approval checkpoints",
                "Claim durable checkpoints before dispatching a worker", "gpt-5.6-sol");
        put(routes, "/repositories", "repositories", repositories, repositoriesFragment);

        String queue = render(get("/issues"), 200);
        String queueFragment = render(hx(get("/issues")), 200);
        assertThat(queue).contains("Issues", stageIssue.getIssueTitle(), queuedIssue.getIssueTitle(),
                blockedIssue.getIssueTitle(), "queue:dependencies");
        put(routes, "/issues", "issues", queue, queueFragment);

        String detailRoute = "/issues/" + stageIssue.getId();
        String detail = render(get(detailRoute), 200);
        String detailFragment = render(hx(get(detailRoute)), 200);
        assertThat(detail).contains(stageIssue.getIssueTitle(), "Implementation approval",
                "Version 3", "Version 2", "data-diff-viewer", "Restart test passes",
                "iteration-history");
        put(routes, detailRoute, "issue-detail", detail, detailFragment);

        String liveStatusRoute = detailRoute + "/live-status";
        String liveStatus = render(get(liveStatusRoute), 200);
        assertThat(liveStatus).contains("live-status", "stage-approval");
        putFragment(routes, liveStatusRoute, "issue-live-status", liveStatus);

        String inbox = render(get("/inbox"), 200);
        String inboxFragment = render(hx(get("/inbox")), 200);
        assertThat(inbox).contains("Needs You", "Implementation approval", "Review stage", "View PR #87");
        put(routes, "/inbox", "inbox", inbox, inboxFragment);

        String inboxLive = render(get("/inbox/live").param("includeInbox", "true"), 200);
        assertThat(inboxLive).contains("needs-you-badge", "needs-you-content", "Implementation approval");
        putFragment(routes, "/inbox/live?includeInbox=true", "inbox-live", inboxLive);

        String approvalPage = render(get("/approvals"), 200);
        String approvalFragment = render(hx(get("/approvals")), 200);
        assertThat(approvalPage).contains("Approvals", "Implementation approval", "Awaiting approval",
                "View PR #87", "data-diff-viewer");
        put(routes, "/approvals", "approvals", approvalPage, approvalFragment);

        String settings = render(get("/settings"), 200);
        String settingsFragment = render(hx(get("/settings")), 200);
        assertThat(settings).contains("Settings", "gpt-5.6-sol", "gpt-5.6-terra", "high", "medium");
        put(routes, "/settings", "settings", settings, settingsFragment);

        String setup = render(get("/setup"), 200);
        String setupFragment = render(hx(get("/setup")), 200);
        assertThat(setup).contains("Setup", "Prerequisites", "Checking", "Optional: webhooks");
        put(routes, "/setup", "setup", setup, setupFragment);

        String prereqs = render(get("/setup/prereqs"), 200);
        assertThat(prereqs).contains("OK", "FAILED", "INVALID",
                "Synthetic fixture: token rejected.", tempDir.toString());
        putFragment(routes, "/setup/prereqs", "setup-prereqs", prereqs);

        String costPage = render(get("/costs"), 200);
        String costFragment = render(hx(get("/costs")), 200);
        assertThat(costPage).contains("Costs", "$0.0000", runningIssue.getIssueTitle())
                .doesNotContain("No cost records yet");
        put(routes, "/costs", "costs-recorded-zero", costPage, costFragment);

        long unreadBefore = notifications.countByReadAtIsNull();
        String panel = render(get("/notifications/panel"), 200);
        assertThat(panel).contains(info.getTitle(), warn.getTitle(), error.getTitle(),
                "sev-info", "sev-warn", "sev-error", "Mark all read");
        assertThat(notifications.countByReadAtIsNull()).isEqualTo(unreadBefore);
        assertThat(notifications.findById(info.getId()).orElseThrow().getReadAt()).isNull();
        putFragment(routes, "/notifications/panel", "notifications-panel", panel);

        String missingRoute = "/issues/999999";
        String errorPage = render(get(missingRoute), 404);
        String errorFragment = render(hx(get(missingRoute)), 404);
        assertThat(errorPage).contains("Not Found", "Issue not found", "Back to the queue");
        put(routes, missingRoute, "error-missing-issue", errorPage, errorFragment);

        verify(harnessService).checkCliAvailable();
        verify(harnessService).checkSubscriptionAuthentication("codex");
        verify(claudeCodeService, never()).checkCliAvailable();
        verify(claudeCodeService, never()).checkAuthentication();
        verify(gitHubApiClient).validateToken();
        verify(gitHubApiClient, never()).addComment(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyString());

        export(routes);
    }

    private TrackedIssue issue(WatchedRepo repo, int number, String title, IssueStatus status, String phase) {
        TrackedIssue issue = new TrackedIssue(repo, number, title);
        issue.setWorkflowPolicy(repo.getWorkflowPolicy());
        issue.setApprovalStages(repo.getApprovalStages());
        issue.setStatus(status);
        issue.setCurrentPhase(phase);
        return issue;
    }

    private Iteration reviewIteration(TrackedIssue issue, int number, boolean passed,
                                      String reviewJson, String diff) {
        Iteration iteration = new Iteration(issue, number);
        iteration.setReviewPassed(passed);
        iteration.setReviewJson(reviewJson);
        iteration.setReviewModel("gpt-5.6-terra");
        iteration.setImplModel("gpt-5.6-sol");
        iteration.setCiResult("PASSED");
        iteration.setLocalCheckResult("PASSED");
        iteration.setDiff(diff);
        iteration.setCompletedAt(LocalDateTime.now().minusMinutes(4 - number));
        return iteration;
    }

    private String render(MockHttpServletRequestBuilder request, int expectedStatus) throws Exception {
        var result = mvc.perform(request).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(expectedStatus);
        return result.getResponse().getContentAsString();
    }

    private MockHttpServletRequestBuilder hx(MockHttpServletRequestBuilder request) {
        return request.header("HX-Request", "true");
    }

    private void put(Map<String, ExportedRoute> routes, String route, String basename,
                     String full, String fragment) {
        routes.put(route, new ExportedRoute("pages/" + basename + ".html",
                "fragments/" + basename + ".html", full, fragment));
    }

    private void putFragment(Map<String, ExportedRoute> routes, String route,
                             String basename, String fragment) {
        routes.put(route, new ExportedRoute(null, "fragments/" + basename + ".html", null, fragment));
    }

    private void export(Map<String, ExportedRoute> routes) throws Exception {
        String output = System.getProperty("issuebot.visualOutput");
        if (output == null || output.isBlank()) return;

        Path root = Path.of(output);
        Files.createDirectories(root.resolve("pages"));
        Files.createDirectories(root.resolve("fragments"));
        Map<String, Map<String, String>> manifestRoutes = new LinkedHashMap<>();
        for (Map.Entry<String, ExportedRoute> entry : routes.entrySet()) {
            ExportedRoute route = entry.getValue();
            Map<String, String> variants = new LinkedHashMap<>();
            if (route.fullFile() != null) {
                Files.writeString(root.resolve(route.fullFile()), route.fullHtml());
                variants.put("full", route.fullFile());
            }
            if (route.fragmentFile() != null) {
                Files.writeString(root.resolve(route.fragmentFile()), route.fragmentHtml());
                variants.put("fragment", route.fragmentFile());
            }
            manifestRoutes.put(entry.getKey(), variants);
        }

        Files.writeString(root.resolve("fixture-index.html"), fixtureIndex(manifestRoutes));
        manifestRoutes.put("/fixtures", Map.of("full", "fixture-index.html"));
        Files.writeString(root.resolve("fixture-manifest.json"), objectMapper.writerWithDefaultPrettyPrinter()
                .writeValueAsString(Map.of("routes", manifestRoutes)));

        Path source = Path.of("src/main/resources/static");
        try (var paths = Files.walk(source)) {
            for (Path file : paths.filter(Files::isRegularFile).toList()) {
                Path target = root.resolve(source.relativize(file));
                Files.createDirectories(target.getParent());
                Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private String fixtureIndex(Map<String, Map<String, String>> routes) {
        StringBuilder links = new StringBuilder();
        routes.keySet().forEach(route -> links.append("<li><a href=\"")
                .append(org.springframework.web.util.HtmlUtils.htmlEscape(route))
                .append("\"><code>")
                .append(org.springframework.web.util.HtmlUtils.htmlEscape(route))
                .append("</code></a></li>"));
        return """
                <!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width">
                <title>IssueBot visual fixtures</title><link rel="stylesheet" href="/css/style.css"></head>
                <body><main id="content" style="padding:2rem;max-width:70rem;margin:auto">
                <h1>IssueBot visual fixtures</h1><p>All responses are synthetic, read-only real-MVC exports.</p>
                <ul>%s</ul></main></body></html>
                """.formatted(links);
    }

    private record ExportedRoute(String fullFile, String fragmentFile,
                                 String fullHtml, String fragmentHtml) {}
}

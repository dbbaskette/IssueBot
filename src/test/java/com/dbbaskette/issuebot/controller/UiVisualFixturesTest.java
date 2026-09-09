package com.dbbaskette.issuebot.controller;

import com.dbbaskette.issuebot.config.IssueBotProperties.AgentProvider;
import com.dbbaskette.issuebot.model.*;
import com.dbbaskette.issuebot.repository.*;
import com.dbbaskette.issuebot.service.polling.IssuePollingService;
import com.dbbaskette.issuebot.validation.StartupValidator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import java.nio.file.*;
import java.time.LocalDateTime;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Synthetic, read-only render fixtures. Optional export supports manual visual review without starting a worker. */
@SpringBootTest(properties = {
        "spring.config.import=", "issuebot.github.token=test-token", "issuebot.auth.username=", "issuebot.auth.password=",
        "spring.datasource.url=jdbc:h2:mem:ui-visual-fixtures;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
})
@AutoConfigureMockMvc
@Transactional
class UiVisualFixturesTest {
    @Autowired MockMvc mvc;
    @Autowired WatchedRepoRepository repos;
    @Autowired TrackedIssueRepository issues;
    @Autowired PlanningVersionRepository versions;
    @Autowired StageApprovalRepository approvals;
    @MockitoBean IssuePollingService polling;
    @MockitoBean StartupValidator startupValidator;

    @Test void rendersRepresentativeIssueAndRepositoryPages() throws Exception {
        WatchedRepo repo = new WatchedRepo("northstar", "workflow-engine");
        repo.setWorkflowPolicy(WorkflowPolicy.STAGED);
        repo.setApprovalStages("IMPLEMENTATION,REVIEW,MERGE");
        repo.setImplementationModel("gpt-5.5");
        repo.setReviewModel("gpt-5.5");
        repo = repos.saveAndFlush(repo);
        TrackedIssue issue = new TrackedIssue(repo, 142, "Make checkpoint recovery reliable across concurrent workers");
        issue.setWorkflowPolicy(WorkflowPolicy.STAGED);
        issue.setApprovalStages(repo.getApprovalStages());
        issue.setStatus(IssueStatus.AWAITING_APPROVAL);
        issue.setCurrentPhase("STAGE_APPROVAL_IMPLEMENTATION");
        issue = issues.saveAndFlush(issue);
        PlanningVersion plan = PlanningVersion.pending(issue, 3,
                "## Reliable checkpoint recovery\n\nPrevent duplicate work when multiple workers resume the same checkpoint.\n\n### Acceptance criteria\n- Only one worker acquires the checkpoint.\n- Recovery survives a process restart.",
                "## Implementation plan\n\n1. Add an atomic checkpoint claim.\n2. Test concurrent recovery.\n3. Verify restart behavior and document the guarantee.", "CODEX", "gpt-5.5", "Include restart verification.");
        plan.approve(LocalDateTime.now());
        plan = versions.saveAndFlush(plan);
        issue.setApprovedPlanningVersion(plan);
        issue.setPlanApproved(true);
        issues.saveAndFlush(issue);
        StageApproval decision = new StageApproval();
        decision.setIssue(issue);
        decision.setStage(WorkflowStage.IMPLEMENTATION);
        decision.setAttempt(1);
        decision.setArtifactVersionId(plan.getId());
        decision.setProvider(AgentProvider.CODEX);
        decision.setModel("gpt-5.5");
        approvals.saveAndFlush(decision);

        String issueHtml = mvc.perform(get("/issues/" + issue.getId())).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String repoHtml = mvc.perform(get("/repositories")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(issueHtml).contains(issue.getIssueTitle(), "stage-approval");
        assertThat(repoHtml).contains("workflow-engine");
        String dashboardHtml = mvc.perform(get("/")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String inboxHtml = mvc.perform(get("/inbox")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String queueHtml = mvc.perform(get("/issues")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(dashboardHtml).contains("workflow-engine");
        assertThat(inboxHtml).contains("northstar/workflow-engine #142", "Review stage");
        assertThat(queueHtml).contains(issue.getIssueTitle());
        String output = System.getProperty("issuebot.visualOutput");
        if (output != null) {
            Path root = Path.of(output);
            Files.createDirectories(root);
            Files.writeString(root.resolve("issue.html"), issueHtml);
            Files.writeString(root.resolve("repositories.html"), repoHtml);
            Files.writeString(root.resolve("dashboard.html"), dashboardHtml);
            Files.writeString(root.resolve("inbox.html"), inboxHtml);
            Files.writeString(root.resolve("queue.html"), queueHtml);
            Path source = Path.of("src/main/resources/static");
            try (var paths = Files.walk(source)) {
                for (Path file : paths.filter(Files::isRegularFile).toList()) {
                    Path target = root.resolve(source.relativize(file));
                    Files.createDirectories(target.getParent());
                    Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }
}

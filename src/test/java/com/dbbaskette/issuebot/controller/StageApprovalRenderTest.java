package com.dbbaskette.issuebot.controller;

import com.dbbaskette.issuebot.model.*;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import org.thymeleaf.context.WebContext;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import org.thymeleaf.web.servlet.JakartaServletWebApplication;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class StageApprovalRenderTest {
    @Test
    void modelDrivenStageRendersSingleApprovalActionAndSelectedModel() {
        String html = render(WorkflowStage.IMPLEMENTATION);
        assertThat(html).contains("name=\"selection\"", "CODEX:gpt-5.5", "selected=\"selected\"", "Approve implementation", "#plan-first");
        assertThat(html.split("Approve implementation", -1)).hasSize(2);
        assertThat(html).contains("Starts implementation.", "approval is required for verification.",
                "action=\"/issues/1/stages/2/approve\"", "id=\"stage-approval-form-2\" hx-preserve=\"true\"");
    }

    @Test
    void deterministicStageDoesNotRenderModelSelector() {
        assertThat(render(WorkflowStage.MERGE)).contains("Approve merge", "#iteration-history",
                        "No further approval checkpoints; continues automatically to completion.")
                .doesNotContain("name=\"selection\"");
    }

    @Test
    void everyStageHasOnePreciselyNamedSubmitAction() {
        for (WorkflowStage stage : WorkflowStage.values()) {
            String html = render(stage, true);
            assertThat(html.split("type=\"submit\"", -1)).hasSize(2);
            assertThat(html).contains("Approve " + stage.name().toLowerCase(java.util.Locale.ROOT))
                    .doesNotContain("Approve and run", "Stage decision history");
        }
    }

    @Test
    void stageWaitUsesDedicatedCardAndSuppressesLegacyApprovalInPollRegion() {
        assertThat(render(WorkflowStage.REVIEW, true)).contains("id=\"stage-approval\"", "Approve review")
                .doesNotContain("id=\"approval-decision\"", "data-modal-open=\"approve-modal\"");
    }

    private String render(WorkflowStage stage) {
        return render(stage, false);
    }

    private String render(WorkflowStage stage, boolean decisionRegion) {
        var resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        var engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);
        var servlet = new MockServletContext();
        var context = new WebContext(JakartaServletWebApplication.buildApplication(servlet)
                .buildExchange(new MockHttpServletRequest(servlet), new MockHttpServletResponse()));
        var approval = mock(StageApproval.class);
        when(approval.getId()).thenReturn(2L);
        when(approval.getStage()).thenReturn(stage);
        when(approval.getAttempt()).thenReturn(1);
        when(approval.getState()).thenReturn(StageApproval.State.WAITING);
        when(approval.getProvider()).thenReturn(com.dbbaskette.issuebot.config.IssueBotProperties.AgentProvider.CODEX);
        when(approval.getModel()).thenReturn("gpt-5.5");
        when(approval.getArtifactVersionId()).thenReturn(3L);
        var issue = new TrackedIssue();
        issue.setId(1L);
        issue.setStatus(IssueStatus.AWAITING_APPROVAL);
        issue.setCurrentPhase("STAGE_APPROVAL_" + stage.name());
        context.setVariable("issue", issue);
        context.setVariable("pendingStageApproval", approval);
        context.setVariable("stageApprovalHistory", List.of(approval));
        context.setVariable("stageModels", Map.of("CODEX", List.of("gpt-5.5")));
        context.setVariable("processingMode", ProcessingState.RUNNING);
        if (decisionRegion) return engine.process(new org.thymeleaf.TemplateSpec("issue-detail",
                java.util.Set.of("approval-decision-region"), org.thymeleaf.templatemode.TemplateMode.HTML, null), context);
        return engine.process("fragments/stage-approval", context);
    }
}

package com.dbbaskette.issuebot.controller;

import com.dbbaskette.issuebot.model.*;
import com.dbbaskette.issuebot.service.ui.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import org.thymeleaf.TemplateSpec;
import org.thymeleaf.context.WebContext;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import org.thymeleaf.web.servlet.JakartaServletWebApplication;
import java.util.*;
import static com.dbbaskette.issuebot.service.ui.RecoveryGuidance.PrerequisiteState.*;
import static org.assertj.core.api.Assertions.assertThat;

class RecoveryGuidanceRenderTest {
    @Test void knownUnmetDisablesBothFormsWithRefreshableButtonsOutsidePreservedDrafts() {
        for (boolean guided : List.of(false, true)) {
            String html = render(KNOWN_UNMET, ProcessingState.RUNNING, guided);
            assertThat(html).contains("Retry is blocked", "href=\"/setup\"", "disabled=\"disabled\"", "hx-preserve=\"true\"");
            String formId = guided ? "plan-guidance-form-7-0" : "recovery-form-7-0";
            assertThat(html).contains("form=\"" + formId + "\"");
            int form = html.indexOf("id=\"" + formId + "\"");
            assertThat(html.indexOf("</form>", form)).isLessThan(html.indexOf("form=\"" + formId + "\"", form));
        }
    }

    @Test void unknownDoesNotDisableButGlobalStopStillDoesAndEvidenceIsEscaped() {
        String unknown = render(NOT_VERIFIED, ProcessingState.RUNNING, false);
        assertThat(unknown).contains("Not verified", "&lt;script&gt;", "id=\"recovery-evidence\"")
                .doesNotContain("<script>", "untrusted action", "disabled=\"disabled\"");
        assertThat(render(VERIFIED_READY, ProcessingState.STOPPED, false)).contains("disabled=\"disabled\"");
    }

    private String render(RecoveryGuidance.PrerequisiteState state, ProcessingState processing, boolean guided) {
        var resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/"); resolver.setSuffix(".html"); resolver.setTemplateMode(TemplateMode.HTML);
        var engine = new SpringTemplateEngine(); engine.setTemplateResolver(resolver);
        var servlet = new MockServletContext();
        var exchange = JakartaServletWebApplication.buildApplication(servlet).buildExchange(
                new MockHttpServletRequest(servlet), new MockHttpServletResponse());
        var context = new WebContext(exchange, Locale.US);
        var issue = new TrackedIssue(new WatchedRepo("acme", "repo"), 7, "Issue");
        issue.setId(7L); issue.setStatus(IssueStatus.FAILED);
        var diagnostic = new FailureDiagnostic(issue, FailureCategory.REVIEW_INFRASTRUCTURE,
                "untrusted summary", "REVIEW", "<script>evidence</script>", "untrusted action", FailureRetryability.RETRYABLE);
        context.setVariable("issue", issue);
        context.setVariable("showPlanGuidance", guided);
        context.setVariable("processingMode", processing);
        context.setVariable("planReviewAttempts", List.of());
        context.setVariable("latestFailureDiagnostic", diagnostic);
        context.setVariable("recoveryGuidance", new RecoveryGuidanceAssembler().assemble(diagnostic, state));
        return engine.process(new TemplateSpec("issue-detail", Set.of("recovery-region"), (TemplateMode) null, null), context);
    }
}

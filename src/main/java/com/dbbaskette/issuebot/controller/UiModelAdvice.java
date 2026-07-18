package com.dbbaskette.issuebot.controller;

import com.dbbaskette.issuebot.config.IssueBotProperties;
import com.dbbaskette.issuebot.util.HumanizeHelper;
import com.dbbaskette.issuebot.service.workflow.ProcessingControlService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * Publishes shared, read-only helpers to every controller's model so Thymeleaf templates can
 * call them directly (e.g. {@code ${humanize.phase(issue.currentPhase)}}). Standard Thymeleaf
 * usage permits invoking methods on model attributes — this isn't a SpEL {@code T(...)} type
 * expression (which newer Thymeleaf restricts), just an ordinary property/method access on a
 * bean already in the model, so no expression-restriction workaround is needed.
 *
 * <p>Only ZERO-COST attributes belong here: {@code @ModelAttribute} methods on a
 * {@code @ControllerAdvice} run for EVERY handler invocation app-wide — including SSE stream
 * subscriptions, webhook posts, and the 30s fragment polls — not just page renders. The bell's
 * unread count (a database COUNT) started life here and was moved into the layout-rendering
 * page controllers, alongside their {@code pendingApprovals} count, for exactly that reason
 * (PR #102 review).
 */
@ControllerAdvice
public class UiModelAdvice {

    private final IssueBotProperties properties;
    private final ProcessingControlService processingControl;

    public UiModelAdvice(IssueBotProperties properties, ProcessingControlService processingControl) {
        this.properties = properties;
        this.processingControl = processingControl;
    }

    @ModelAttribute("humanize")
    public HumanizeHelper humanize() {
        return new HumanizeHelper();
    }

    /**
     * Gates the notification bell's visibility in layout.html (#89) — mirrors the same
     * "Dashboard Notifications" toggle {@code NotificationService#sendDashboardEvent} already
     * checks for the toast/event stream. Persistence itself is unconditional (see
     * {@code NotificationService} javadoc); only the bell's visibility is gated here.
     * A plain in-memory property read — no query — so it is safe in this advice.
     */
    @ModelAttribute("dashboardNotificationsEnabled")
    public boolean dashboardNotificationsEnabled() {
        return properties.getNotifications().isDashboard();
    }

    @ModelAttribute("processingPaused")
    public boolean processingPaused() { return processingControl.isPaused(); }

    @ModelAttribute("agentProviderName")
    public String agentProviderName() { return properties.getAgentProvider().getDisplayName(); }

    @ModelAttribute("codexProvider")
    public boolean codexProvider() {
        return properties.getAgentProvider() == IssueBotProperties.AgentProvider.CODEX;
    }

    /** Current local URL used to return operators to the same view after pause/resume. */
    @ModelAttribute("currentPath")
    public String currentPath(HttpServletRequest request) {
        String query = request.getQueryString();
        return request.getRequestURI() + (query == null || query.isBlank() ? "" : "?" + query);
    }
}

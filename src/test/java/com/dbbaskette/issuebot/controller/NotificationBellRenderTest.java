package com.dbbaskette.issuebot.controller;

import com.dbbaskette.issuebot.model.Notification;
import com.dbbaskette.issuebot.util.HumanizeHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;
import org.thymeleaf.TemplateSpec;
import org.thymeleaf.context.WebContext;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import org.thymeleaf.web.servlet.IServletWebExchange;
import org.thymeleaf.web.servlet.JakartaServletWebApplication;

import java.io.StringWriter;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static com.dbbaskette.issuebot.controller.DashboardRenderFixtures.emptyControlRoom;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Renders the real layout.html + notifications.html through Thymeleaf (no Spring context, no
 * database) to verify the notification bell (#89) — mirrors the approach in
 * {@link LayoutSseAndAgentStatusRenderTest}: no Spring context needed, just the real templates
 * plus a hand-built {@link WebContext}.
 */
class NotificationBellRenderTest {

    private SpringTemplateEngine templateEngine;
    private IServletWebExchange webExchange;

    @BeforeEach
    void setUp() {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode(TemplateMode.HTML);
        resolver.setCharacterEncoding("UTF-8");

        templateEngine = new SpringTemplateEngine();
        templateEngine.setTemplateResolver(resolver);

        MockServletContext servletContext = new MockServletContext();
        JakartaServletWebApplication webApplication = JakartaServletWebApplication.buildApplication(servletContext);
        MockHttpServletRequest request = new MockHttpServletRequest(servletContext);
        MockHttpServletResponse response = new MockHttpServletResponse();
        webExchange = webApplication.buildExchange(request, response);
    }

    /**
     * Renders the whole non-HTMX "layout" view (contentTemplate="dashboard"), the way a real
     * full-page GET / would — mirrors {@code LayoutSseAndAgentStatusRenderTest#renderFullDashboardPage}.
     */
    private String renderFullDashboardPage(Boolean dashboardNotificationsEnabled, Long unreadCount) {
        WebContext context = new WebContext(webExchange, Locale.US);
        context.setVariable("contentTemplate", "dashboard");
        context.setVariable("activePage", "dashboard");
        context.setVariable("pageTitle", "Dashboard");
        context.setVariable("agentRunning", true);
        context.setVariable("pendingApprovals", 0L);
        context.setVariable("dashboardNotificationsEnabled", dashboardNotificationsEnabled);
        context.setVariable("unreadNotificationCount", unreadCount);
        // dashboard.html's "content" fragment nests the "live" fragment inline.
        context.setVariable("completed", 1L);
        context.setVariable("inProgress", 2L);
        context.setVariable("pending", 3L);
        context.setVariable("queued", 4L);
        context.setVariable("blocked", 5L);
        context.setVariable("failed", 6L);
        context.setVariable("decomposed", 7L);
        context.setVariable("awaitingDecomposition", 8L);
        context.setVariable("awaitingPlanApproval", 9L);
        context.setVariable("repoCount", 10L);
        context.setVariable("totalCost", new BigDecimal("12.34"));
        context.setVariable("events", List.of());
        context.setVariable("controlRoom", emptyControlRoom());
        context.setVariable("humanize", new HumanizeHelper());
        context.setVariable("processingMode", com.dbbaskette.issuebot.model.ProcessingState.RUNNING);

        TemplateSpec spec = new TemplateSpec("layout", null,
                (org.thymeleaf.templatemode.TemplateMode) null, null);
        StringWriter writer = new StringWriter();
        templateEngine.process(spec, context, writer);
        return writer.toString();
    }

    @Test
    void bell_visible_whenDashboardNotificationsEnabled() {
        String html = renderFullDashboardPage(true, 3L);

        assertThat(html).contains("id=\"notif-bell-btn\"");
        assertThat(html).contains("hx-get=\"/notifications/panel\"");
        assertThat(html).contains("aria-controls=\"notif-panel\"");
        assertThat(html).contains("id=\"notif-panel\"");
    }

    @Test
    void bell_hidden_whenDashboardNotificationsDisabled() {
        String html = renderFullDashboardPage(false, 3L);

        assertThat(html).doesNotContain("id=\"notif-bell-btn\"");
    }

    /**
     * The bell stays inside the sidebar, but #notif-panel MUST render outside it (at
     * body level). Inside .sidebar the sidebar's backdrop-filter becomes the containing
     * block for the panel's position:fixed, and its overflow-x:hidden then clips the
     * panel at the sidebar's right edge — the reported "notifications are hidden" bug,
     * which an earlier absolute→fixed swap did not actually fix. Regression guard.
     */
    @Test
    void notifPanel_rendersOutsideTheSidebar_notClippedByItsBackdropFilterAndOverflow() {
        String html = renderFullDashboardPage(true, 3L);

        int sidebarStart = html.indexOf("class=\"sidebar\"");
        int sidebarEnd = html.indexOf("</nav>", sidebarStart);
        assertThat(sidebarStart).isGreaterThan(0);
        assertThat(sidebarEnd).isGreaterThan(sidebarStart);

        String sidebarMarkup = html.substring(sidebarStart, sidebarEnd);
        assertThat(sidebarMarkup).contains("id=\"notif-bell-btn\"");     // bell is in the sidebar
        assertThat(sidebarMarkup).doesNotContain("id=\"notif-panel\"");  // panel is NOT
        // ...but the panel still exists, rendered after the sidebar closes.
        assertThat(html.indexOf("id=\"notif-panel\"")).isGreaterThan(sidebarEnd);
    }

    @Test
    void bell_showsUnreadBadge_whenCountPositive() {
        String html = renderFullDashboardPage(true, 5L);

        assertThat(html).contains("notif-badge");
        assertThat(html).containsPattern("notif-badge[^>]*>\\s*5\\s*<");
    }

    @Test
    void bell_hidesBadge_whenCountZero() {
        String html = renderFullDashboardPage(true, 0L);

        assertThat(html).doesNotContain("notif-badge");
    }

    // === notifications.html panel fragment ===

    private String renderPanel(List<Notification> notifications, long unreadCount) {
        WebContext context = new WebContext(webExchange, Locale.US);
        context.setVariable("notifications", notifications);
        context.setVariable("unreadCount", unreadCount);

        TemplateSpec spec = new TemplateSpec("notifications", Set.of("panel"),
                (org.thymeleaf.templatemode.TemplateMode) null, null);
        StringWriter writer = new StringWriter();
        templateEngine.process(spec, context, writer);
        return writer.toString();
    }

    private Notification notification(Notification.Severity severity, String title, String detail, Long issueId) {
        Notification n = new Notification(severity, title, detail, issueId);
        n.setId(1L);
        n.setCreatedAt(LocalDateTime.of(2026, 7, 11, 14, 30));
        return n;
    }

    @Test
    void panel_showsEmptyState_whenNoNotifications() {
        String html = renderPanel(List.of(), 0);

        assertThat(html).contains("No notifications yet");
    }

    @Test
    void panel_rendersItems_titleDetailAndRelativeTime() {
        Notification n = notification(Notification.Severity.INFO, "Issue Completed",
                "acme/widgets #42 — PR #7 created & merged", null);

        String html = renderPanel(List.of(n), 0);

        assertThat(html).contains("Issue Completed");
        // th:text HTML-escapes the detail — "&" becomes "&amp;" — which is correct/expected.
        assertThat(html).contains("acme/widgets #42 — PR #7 created &amp; merged");
        assertThat(html).contains("Jul 11, 14:30");
    }

    @Test
    void severityAndReadStateRemainIndependentOfPresentation() {
        Notification unread = notification(Notification.Severity.WARN, "Waiting", "Review this stage", 42L);
        Notification read = notification(Notification.Severity.ERROR, "Failed", "Inspect the error", null);
        LocalDateTime readAt = LocalDateTime.of(2026, 7, 11, 15, 0);
        read.setReadAt(readAt);

        String html = renderPanel(List.of(unread, read), 1);

        assertThat(html).contains("notif-item is-unread", "sev-warn", "ti-alert-triangle",
                "sev-error", "ti-alert-circle", "Jul 11, 14:30", "href=\"/issues/42\"",
                "hx-post=\"/notifications/read\"", "hx-target=\"#notif-panel\"");
        assertThat(html.split("is-unread", -1)).hasSize(2);
        assertThat(unread.getReadAt()).isNull();
        assertThat(read.getReadAt()).isEqualTo(readAt);
    }

    @Test
    void panel_issueLinkedNotification_wrapsInIssueLink() {
        Notification n = notification(Notification.Severity.WARN, "Auto-Merge Failed",
                "acme/widgets #42 — needs manual merge", 42L);

        String html = renderPanel(List.of(n), 0);

        assertThat(html).contains("href=\"/issues/42\"");
    }

    @Test
    void panel_systemNotification_hasNoIssueLink() {
        Notification n = notification(Notification.Severity.INFO, "System", "no issue here", null);

        String html = renderPanel(List.of(n), 0);

        assertThat(html).doesNotContain("href=\"/issues/");
    }

    @Test
    void panel_showsMarkAllReadButton_whenUnreadCountPositive() {
        String html = renderPanel(List.of(), 4);

        assertThat(html).contains("hx-post=\"/notifications/read\"");
        assertThat(html).contains("Mark all read");
    }

    @Test
    void panel_hidesMarkAllReadButton_whenNoUnread() {
        String html = renderPanel(List.of(), 0);

        assertThat(html).doesNotContain("hx-post=\"/notifications/read\"");
    }

    @Test
    void panel_carriesUnreadCountDataAttribute_forJsBadgeSync() {
        String html = renderPanel(List.of(), 7);

        assertThat(html).contains("data-unread-count=\"7\"");
    }
}

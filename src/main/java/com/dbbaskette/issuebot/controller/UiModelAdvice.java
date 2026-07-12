package com.dbbaskette.issuebot.controller;

import com.dbbaskette.issuebot.config.IssueBotProperties;
import com.dbbaskette.issuebot.repository.NotificationRepository;
import com.dbbaskette.issuebot.util.HumanizeHelper;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * Publishes shared, read-only helpers to every controller's model so Thymeleaf templates can
 * call them directly (e.g. {@code ${humanize.phase(issue.currentPhase)}}). Standard Thymeleaf
 * usage permits invoking methods on model attributes — this isn't a SpEL {@code T(...)} type
 * expression (which newer Thymeleaf restricts), just an ordinary property/method access on a
 * bean already in the model, so no expression-restriction workaround is needed.
 */
@ControllerAdvice
public class UiModelAdvice {

    private final IssueBotProperties properties;
    private final NotificationRepository notificationRepository;

    public UiModelAdvice(IssueBotProperties properties, NotificationRepository notificationRepository) {
        this.properties = properties;
        this.notificationRepository = notificationRepository;
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
     */
    @ModelAttribute("dashboardNotificationsEnabled")
    public boolean dashboardNotificationsEnabled() {
        return properties.getNotifications().isDashboard();
    }

    /**
     * Unread-count badge on the bell (#89) — a cheap {@code COUNT(*)} query on every page
     * render. Acceptable: the notifications table is small (top-20 panel, no unbounded growth
     * concern for this query) and this mirrors the existing per-render {@code pendingApprovals}
     * count each controller already computes.
     */
    @ModelAttribute("unreadNotificationCount")
    public long unreadNotificationCount() {
        return notificationRepository.countByReadAtIsNull();
    }
}

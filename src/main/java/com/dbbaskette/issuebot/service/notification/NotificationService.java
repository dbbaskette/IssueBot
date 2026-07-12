package com.dbbaskette.issuebot.service.notification;

import com.dbbaskette.issuebot.config.IssueBotProperties;
import com.dbbaskette.issuebot.model.Notification;
import com.dbbaskette.issuebot.model.TrackedIssue;
import com.dbbaskette.issuebot.repository.NotificationRepository;
import com.dbbaskette.issuebot.service.event.EventService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.awt.*;

/**
 * The single funnel for user-facing notifications: desktop tray, dashboard toast/event, and
 * (#89) a persistent {@link Notification} row backing the notification bell's history panel.
 *
 * <p>Persistence is best-effort and unconditional — it happens on every call regardless of the
 * "Dashboard Notifications" toggle ({@link IssueBotProperties.NotificationConfig#isDashboard()}),
 * which only gates the toast/event stream and (in the UI) the bell's visibility. A failed insert
 * is logged and swallowed so a database hiccup never prevents a notification from reaching the
 * operator through the channels that still work.
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final IssueBotProperties properties;
    private final EventService eventService;
    private final NotificationRepository notificationRepository;
    private final boolean systemTraySupported;

    public NotificationService(IssueBotProperties properties, EventService eventService,
                                NotificationRepository notificationRepository) {
        this.properties = properties;
        this.eventService = eventService;
        this.notificationRepository = notificationRepository;
        this.systemTraySupported = checkSystemTraySupport();
    }

    public void sendDesktopNotification(String title, String message, TrayIcon.MessageType type) {
        if (!properties.getNotifications().isDesktop()) {
            log.debug("Desktop notifications disabled, skipping: {}", title);
            return;
        }

        if (!systemTraySupported) {
            log.debug("SystemTray not supported, logging notification: {} - {}", title, message);
            return;
        }

        try {
            SystemTray tray = SystemTray.getSystemTray();
            Image image = Toolkit.getDefaultToolkit().createImage(new byte[0]);
            TrayIcon trayIcon = new TrayIcon(image, "IssueBot");
            trayIcon.setImageAutoSize(true);
            tray.add(trayIcon);
            trayIcon.displayMessage(title, message, type);
            // Remove after a delay to avoid cluttering the tray
            Thread.ofVirtual().start(() -> {
                try {
                    Thread.sleep(10_000);
                    tray.remove(trayIcon);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        } catch (Exception e) {
            log.warn("Failed to send desktop notification: {}", e.getMessage());
        }
    }

    public void sendDashboardEvent(String eventType, String message) {
        if (!properties.getNotifications().isDashboard()) {
            return;
        }
        eventService.log(eventType, message);
    }

    public void info(String title, String message) {
        info(title, message, null);
    }

    /**
     * Issue-aware overload (#89) — stamps {@code issue_id} on the persisted row so the
     * notification-bell panel can deep link to {@code /issues/{id}}. Only wired up at call sites
     * where a {@link TrackedIssue} is already in scope; the 2-arg overload above still covers
     * system-level notifications with no associated issue.
     */
    public void info(String title, String message, TrackedIssue issue) {
        sendDesktopNotification(title, message, TrayIcon.MessageType.INFO);
        sendDashboardEvent("NOTIFICATION_INFO", title + ": " + message);
        persist(Notification.Severity.INFO, title, message, issue);
    }

    public void warn(String title, String message) {
        warn(title, message, null);
    }

    public void warn(String title, String message, TrackedIssue issue) {
        sendDesktopNotification(title, message, TrayIcon.MessageType.WARNING);
        sendDashboardEvent("NOTIFICATION_WARN", title + ": " + message);
        persist(Notification.Severity.WARN, title, message, issue);
    }

    public void error(String title, String message) {
        sendDesktopNotification(title, message, TrayIcon.MessageType.ERROR);
        sendDashboardEvent("NOTIFICATION_ERROR", title + ": " + message);
        persist(Notification.Severity.ERROR, title, message, null);
    }

    /** Column limits from the V22 migration — over-long inputs are clamped, not dropped. */
    static final int MAX_TITLE_LENGTH = 200;
    static final int MAX_DETAIL_LENGTH = 1000;

    /**
     * Best-effort persistence for the notification-bell history (#89) — unconditional (not
     * gated by the dashboard toggle, see class javadoc) and never allowed to propagate: a
     * database failure here must not take down desktop/toast delivery, which already happened
     * by the time this runs. Title/detail are clamped to their column widths up front (PR #102
     * review) so an over-long message — e.g. a long issue title concatenated into the detail —
     * persists truncated instead of tripping the failure path and vanishing from the history.
     */
    private void persist(Notification.Severity severity, String title, String message, TrackedIssue issue) {
        try {
            Long issueId = issue != null ? issue.getId() : null;
            notificationRepository.save(new Notification(severity,
                    clamp(title, MAX_TITLE_LENGTH), clamp(message, MAX_DETAIL_LENGTH), issueId));
        } catch (Exception e) {
            log.warn("Failed to persist notification '{}': {}", title, e.getMessage());
        }
    }

    private static String clamp(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private boolean checkSystemTraySupport() {
        try {
            boolean supported = SystemTray.isSupported();
            if (!supported) {
                log.info("SystemTray not supported on this platform, desktop notifications will be logged only");
            }
            return supported;
        } catch (Exception e) {
            log.debug("SystemTray check failed: {}", e.getMessage());
            return false;
        }
    }
}

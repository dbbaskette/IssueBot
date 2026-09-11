package com.dbbaskette.issuebot.service.notification;

import com.dbbaskette.issuebot.config.IssueBotProperties;
import com.dbbaskette.issuebot.model.Notification;
import com.dbbaskette.issuebot.model.TrackedIssue;
import com.dbbaskette.issuebot.repository.NotificationRepository;
import com.dbbaskette.issuebot.repository.NotificationPreferenceRepository;
import com.dbbaskette.issuebot.service.ui.IssueNextActionResolver;
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
    private final NotificationPreferenceRepository preferences;
    private final IssueNextActionResolver nextAction;
    private final NotificationTriageService triage;

    public NotificationService(IssueBotProperties properties, EventService eventService,
                                NotificationRepository notificationRepository) {
        this(properties, eventService, notificationRepository, null, new IssueNextActionResolver(), null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public NotificationService(IssueBotProperties properties, EventService eventService,
            NotificationRepository notificationRepository, NotificationPreferenceRepository preferences,
            IssueNextActionResolver nextAction, NotificationTriageService triage) {
        this.properties = properties;
        this.eventService = eventService;
        this.notificationRepository = notificationRepository;
        this.preferences = preferences;
        this.nextAction = nextAction;
        this.triage = triage;
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
        emit(null, Notification.Severity.INFO, title, message, issue);
    }

    public void warn(String title, String message) {
        warn(title, message, null);
    }

    public void warn(String title, String message, TrackedIssue issue) {
        emit(null, Notification.Severity.WARN, title, message, issue);
    }

    public void error(String title, String message) {
        emit(null, Notification.Severity.ERROR, title, message, null);
    }

    public void approval(String title, String message, TrackedIssue issue) {
        emit(Notification.Category.APPROVAL, Notification.Severity.INFO, title, message, issue);
    }
    public void recovery(String title, String message, TrackedIssue issue) {
        emit(Notification.Category.RECOVERY, Notification.Severity.WARN, title, message, issue);
    }
    public void progress(String title, String message, TrackedIssue issue) {
        emit(Notification.Category.PROGRESS, Notification.Severity.INFO, title, message, issue);
    }
    public void completion(String title, String message, TrackedIssue issue) {
        emit(Notification.Category.COMPLETION, Notification.Severity.INFO, title, message, issue);
    }
    public void systemError(String title, String message) {
        emit(Notification.Category.SYSTEM, Notification.Severity.ERROR, title, message, null);
    }

    private void emit(Notification.Category category, Notification.Severity severity, String title,
                      String message, TrackedIssue issue) {
        // Store before delivery selection. A mute is never a retention or search filter.
        persist(category, severity, title, message, issue);
        boolean muted = false;
        if (preferences != null && (category == Notification.Category.PROGRESS || category == Notification.Category.COMPLETION)
                && !nextAction.resolve(issue).actionRequired()) {
            try {
                muted = preferences.findById(category).map(com.dbbaskette.issuebot.model.NotificationPreference::isMuted).orElse(false)
                        && (issue == null || triage == null || !triage.isActionRequired(issue.getId()));
            }
            catch (Exception unavailable) { log.warn("Notification preferences unavailable; delivering notification"); }
        }
        if (muted) return;
        TrayIcon.MessageType type = switch (severity) {
            case INFO -> TrayIcon.MessageType.INFO;
            case WARN -> TrayIcon.MessageType.WARNING;
            case ERROR -> TrayIcon.MessageType.ERROR;
        };
        sendDesktopNotification(title, message, type);
        sendDashboardEvent("NOTIFICATION_" + severity.name(), title + ": " + message);
    }

    /** Column limits from the V22 migration — over-long inputs are clamped, not dropped. */
    static final int MAX_TITLE_LENGTH = 200;
    static final int MAX_DETAIL_LENGTH = 1000;

    /**
     * Best-effort persistence for the notification-bell history (#89) — unconditional (not
     * gated by the dashboard toggle, see class javadoc) and never allowed to propagate: a
     * database failure here must not prevent the subsequent desktop/toast delivery. Title/detail
     * are clamped to their column widths up front (PR #102
     * review) so an over-long message — e.g. a long issue title concatenated into the detail —
     * persists truncated instead of tripping the failure path and vanishing from the history.
     */
    private void persist(Notification.Category category, Notification.Severity severity, String title, String message, TrackedIssue issue) {
        try {
            Long issueId = issue != null ? issue.getId() : null;
            Notification notification = new Notification(severity,
                    clamp(title, MAX_TITLE_LENGTH), clamp(message, MAX_DETAIL_LENGTH), issueId);
            Long repoId = issue != null && issue.getRepo() != null ? issue.getRepo().getId() : null;
            notification.setRepoId(repoId);
            notification.setCategory(category);
            if (issueId != null) notification.setGroupKey("issue:" + (repoId == null ? 0 : repoId) + ":" + issueId);
            else if (category != null) notification.setGroupKey("system:" + category.name());
            notificationRepository.save(notification);
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

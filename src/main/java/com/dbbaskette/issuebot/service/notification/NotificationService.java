package com.dbbaskette.issuebot.service.notification;

import com.dbbaskette.issuebot.config.IssueBotProperties;
import com.dbbaskette.issuebot.service.event.EventService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.awt.*;

@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final IssueBotProperties properties;
    private final EventService eventService;
    private final boolean systemTraySupported;

    public NotificationService(IssueBotProperties properties, EventService eventService) {
        this.properties = properties;
        this.eventService = eventService;
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
        sendDesktopNotification(title, message, TrayIcon.MessageType.INFO);
        sendDashboardEvent("NOTIFICATION_INFO", title + ": " + message);
    }

    public void warn(String title, String message) {
        sendDesktopNotification(title, message, TrayIcon.MessageType.WARNING);
        sendDashboardEvent("NOTIFICATION_WARN", title + ": " + message);
    }

    public void error(String title, String message) {
        sendDesktopNotification(title, message, TrayIcon.MessageType.ERROR);
        sendDashboardEvent("NOTIFICATION_ERROR", title + ": " + message);
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

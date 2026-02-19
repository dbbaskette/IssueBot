package com.dbbaskette.issuebot.service.tool;

import com.dbbaskette.issuebot.service.notification.NotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class NotificationTool {

    private static final Logger log = LoggerFactory.getLogger(NotificationTool.class);

    private final NotificationService notificationService;

    public NotificationTool(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    public String sendDesktopNotification(
            String title,
            String message,
            String type) {
        try {
            switch (type.toUpperCase()) {
                case "WARNING" -> notificationService.warn(title, message);
                case "ERROR" -> notificationService.error(title, message);
                default -> notificationService.info(title, message);
            }
            return "{\"success\": true}";
        } catch (Exception e) {
            log.error("Failed to send notification", e);
            return "{\"error\": \"" + e.getMessage().replace("\"", "'") + "\"}";
        }
    }

    public String sendDashboardEvent(
            String eventType,
            String message) {
        try {
            notificationService.sendDashboardEvent(eventType, message);
            return "{\"success\": true}";
        } catch (Exception e) {
            log.error("Failed to send dashboard event", e);
            return "{\"error\": \"" + e.getMessage().replace("\"", "'") + "\"}";
        }
    }
}

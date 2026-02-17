package com.dbbaskette.issuebot.service.tool;

import com.dbbaskette.issuebot.service.notification.NotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Component
public class NotificationTool {

    private static final Logger log = LoggerFactory.getLogger(NotificationTool.class);

    private final NotificationService notificationService;

    public NotificationTool(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @Tool(description = "Send a desktop notification to the user via the OS notification system")
    public String sendDesktopNotification(
            @ToolParam(description = "Notification title") String title,
            @ToolParam(description = "Notification message") String message,
            @ToolParam(description = "Notification type: INFO, WARNING, or ERROR") String type) {
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

    @Tool(description = "Send an event to the IssueBot dashboard for real-time display")
    public String sendDashboardEvent(
            @ToolParam(description = "Event type (e.g. 'ISSUE_DETECTED', 'BUILD_FAILED')") String eventType,
            @ToolParam(description = "Event message") String message) {
        try {
            notificationService.sendDashboardEvent(eventType, message);
            return "{\"success\": true}";
        } catch (Exception e) {
            log.error("Failed to send dashboard event", e);
            return "{\"error\": \"" + e.getMessage().replace("\"", "'") + "\"}";
        }
    }
}

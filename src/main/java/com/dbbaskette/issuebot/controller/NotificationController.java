package com.dbbaskette.issuebot.controller;

import com.dbbaskette.issuebot.repository.NotificationRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.time.LocalDateTime;

/**
 * Backs the notification bell (#89): the dropdown panel is always loaded/refreshed via HTMX
 * (there is no full-page route for it — the panel only ever exists inside layout.html's header),
 * so both endpoints unconditionally return the "notifications :: panel" fragment, the same way
 * {@code SettingsController#agentStatusFragment} always returns its own fragment name.
 */
@Controller
@RequestMapping("/notifications")
public class NotificationController {

    private final NotificationRepository notificationRepository;

    public NotificationController(NotificationRepository notificationRepository) {
        this.notificationRepository = notificationRepository;
    }

    @GetMapping("/panel")
    public String panel(Model model) {
        populatePanel(model);
        return "notifications :: panel";
    }

    /**
     * Marks every unread notification read and returns the refreshed panel — the client swaps
     * this back in so the badge/list update immediately without a second round trip.
     */
    @PostMapping("/read")
    public String markRead(Model model) {
        notificationRepository.markAllRead(LocalDateTime.now());
        populatePanel(model);
        return "notifications :: panel";
    }

    private void populatePanel(Model model) {
        model.addAttribute("notifications", notificationRepository.findTop20ByOrderByCreatedAtDesc());
        model.addAttribute("unreadCount", notificationRepository.countByReadAtIsNull());
    }
}

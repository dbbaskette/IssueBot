package com.dbbaskette.issuebot.controller;

import com.dbbaskette.issuebot.config.IssueBotProperties;
import com.dbbaskette.issuebot.model.IssueStatus;
import com.dbbaskette.issuebot.repository.TrackedIssueRepository;
import com.dbbaskette.issuebot.service.polling.IssuePollingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Controller
@RequestMapping("/settings")
public class SettingsController {

    private static final Logger log = LoggerFactory.getLogger(SettingsController.class);

    private final IssueBotProperties properties;
    private final IssuePollingService pollingService;
    private final TrackedIssueRepository issueRepository;

    public SettingsController(IssueBotProperties properties,
                               IssuePollingService pollingService,
                               TrackedIssueRepository issueRepository) {
        this.properties = properties;
        this.pollingService = pollingService;
        this.issueRepository = issueRepository;
    }

    @GetMapping
    public String settings(Model model) {
        populateModel(model, null, null);
        return "layout";
    }

    @PostMapping("/pause")
    public String pause(Model model) {
        pollingService.setEnabled(false);
        populateModel(model, "Agent paused.", null);
        return "layout";
    }

    @PostMapping("/resume")
    public String resume(Model model) {
        pollingService.setEnabled(true);
        populateModel(model, "Agent resumed.", null);
        return "layout";
    }

    @PostMapping("/quick")
    public String quickSettings(Model model,
                                 @RequestParam int pollIntervalSeconds,
                                 @RequestParam int maxConcurrentIssues,
                                 @RequestParam boolean desktopNotifications,
                                 @RequestParam boolean dashboardNotifications) {
        properties.setPollIntervalSeconds(pollIntervalSeconds);
        properties.setMaxConcurrentIssues(maxConcurrentIssues);
        properties.getNotifications().setDesktop(desktopNotifications);
        properties.getNotifications().setDashboard(dashboardNotifications);

        populateModel(model, "Settings updated.", null);
        return "layout";
    }

    @PostMapping("/config")
    public String saveConfig(Model model, @RequestParam String configContent) {
        Path configPath = getConfigPath();
        try {
            // Basic YAML validation: check it's not empty and has some structure
            if (configContent == null || configContent.isBlank()) {
                populateModel(model, null, "Configuration cannot be empty.");
                return "layout";
            }

            if (!configContent.contains("issuebot:")) {
                populateModel(model, null, "Invalid configuration: missing 'issuebot:' root key.");
                return "layout";
            }

            Files.writeString(configPath, configContent);
            log.info("Configuration saved to {}", configPath);
            populateModel(model, "Configuration saved. Restart to apply all changes.", null);
        } catch (IOException e) {
            log.error("Failed to save configuration", e);
            populateModel(model, null, "Failed to save: " + e.getMessage());
        }
        return "layout";
    }

    private void populateModel(Model model, String message, String error) {
        model.addAttribute("activePage", "settings");
        model.addAttribute("contentTemplate", "settings");
        model.addAttribute("config", properties);
        model.addAttribute("agentRunning", pollingService.isEnabled());
        model.addAttribute("pendingApprovals", issueRepository.countByStatus(IssueStatus.AWAITING_APPROVAL));

        Path configPath = getConfigPath();
        model.addAttribute("configPath", configPath.toString());
        try {
            model.addAttribute("configContent",
                    Files.exists(configPath) ? Files.readString(configPath) : "# No config file found");
        } catch (IOException e) {
            model.addAttribute("configContent", "# Error reading config: " + e.getMessage());
        }

        if (message != null) model.addAttribute("message", message);
        if (error != null) model.addAttribute("error", error);
    }

    private Path getConfigPath() {
        return Path.of(System.getProperty("user.home"), ".issuebot", "config.yml");
    }
}

package com.dbbaskette.issuebot.controller;

import com.dbbaskette.issuebot.config.IssueBotProperties;
import com.dbbaskette.issuebot.model.IssueStatus;
import com.dbbaskette.issuebot.repository.TrackedIssueRepository;
import com.dbbaskette.issuebot.service.claude.ModelCatalog;
import com.dbbaskette.issuebot.service.polling.IssuePollingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

@Controller
@RequestMapping("/settings")
public class SettingsController {

    private static final Logger log = LoggerFactory.getLogger(SettingsController.class);

    private final IssueBotProperties properties;
    private final IssuePollingService pollingService;
    private final TrackedIssueRepository issueRepository;
    private Path configPath = Path.of(System.getProperty("user.home"), ".issuebot", "config.yml");

    public SettingsController(IssueBotProperties properties,
                               IssuePollingService pollingService,
                               TrackedIssueRepository issueRepository) {
        this.properties = properties;
        this.pollingService = pollingService;
        this.issueRepository = issueRepository;
    }

    @GetMapping
    public String settings(Model model,
                           @RequestHeader(value = "HX-Request", required = false) String hx) {
        populateModel(model, null, null);
        return ViewResolver.view("settings", hx != null);
    }

    @PostMapping("/pause")
    public String pause(Model model,
                        @RequestHeader(value = "HX-Request", required = false) String hx) {
        pollingService.setEnabled(false);
        populateModel(model, "Agent paused.", null);
        return ViewResolver.view("settings", hx != null);
    }

    @PostMapping("/resume")
    public String resume(Model model,
                         @RequestHeader(value = "HX-Request", required = false) String hx) {
        pollingService.setEnabled(true);
        populateModel(model, "Agent resumed.", null);
        return ViewResolver.view("settings", hx != null);
    }

    @PostMapping("/quick")
    public String quickSettings(Model model,
                                 @RequestParam int pollIntervalSeconds,
                                 @RequestParam int maxConcurrentIssues,
                                 @RequestParam boolean desktopNotifications,
                                 @RequestParam boolean dashboardNotifications,
                                 @RequestHeader(value = "HX-Request", required = false) String hx) {
        properties.setPollIntervalSeconds(pollIntervalSeconds);
        properties.setMaxConcurrentIssues(maxConcurrentIssues);
        properties.getNotifications().setDesktop(desktopNotifications);
        properties.getNotifications().setDashboard(dashboardNotifications);

        populateModel(model, "Settings updated.", null);
        return ViewResolver.view("settings", hx != null);
    }

    @PostMapping("/models")
    public String saveModels(@RequestParam String implementationModel,
                              @RequestParam String reviewModel,
                              @RequestParam String utilityModel,
                              RedirectAttributes redirectAttributes) {
        if (implementationModel == null || implementationModel.isBlank()
                || reviewModel == null || reviewModel.isBlank()
                || utilityModel == null || utilityModel.isBlank()) {
            redirectAttributes.addFlashAttribute("error", "Model selections cannot be blank.");
            return "redirect:/settings";
        }

        if (!writeModelsToConfig(implementationModel, reviewModel, utilityModel)) {
            redirectAttributes.addFlashAttribute("error",
                    "Could not parse " + configPath + " — fix the YAML in the editor below, then try again.");
            return "redirect:/settings";
        }

        properties.getClaudeCode().setImplementationModel(implementationModel);
        properties.getClaudeCode().setReviewModel(reviewModel);
        properties.getClaudeCode().setUtilityModel(utilityModel);

        redirectAttributes.addFlashAttribute("success",
                "Models updated — applies to the next issue picked up (no restart needed)");
        return "redirect:/settings";
    }

    /**
     * Loads config.yml into a Map, navigates/creates the issuebot.claude-code
     * maps, sets the three model keys, and dumps the whole structure back in
     * block flow style. Returns false (without writing) if the existing file
     * fails to parse as YAML.
     */
    @SuppressWarnings("unchecked")
    private boolean writeModelsToConfig(String implementationModel, String reviewModel, String utilityModel) {
        Map<String, Object> root;
        try {
            if (Files.exists(configPath)) {
                Object loaded = new Yaml().load(Files.readString(configPath));
                if (loaded == null) {
                    root = new LinkedHashMap<>();
                } else if (loaded instanceof Map) {
                    root = (Map<String, Object>) loaded;
                } else {
                    // Parses, but not a mapping at the root — can't navigate it safely.
                    log.error("Config file {} did not parse to a YAML mapping", configPath);
                    return false;
                }
            } else {
                root = new LinkedHashMap<>();
            }
        } catch (Exception e) {
            log.error("Failed to parse {}: {}", configPath, e.getMessage());
            return false;
        }

        Object issuebotObj = root.get("issuebot");
        Map<String, Object> issuebot;
        if (issuebotObj instanceof Map) {
            issuebot = (Map<String, Object>) issuebotObj;
        } else {
            issuebot = new LinkedHashMap<>();
            root.put("issuebot", issuebot);
        }

        Object claudeCodeObj = issuebot.get("claude-code");
        Map<String, Object> claudeCode;
        if (claudeCodeObj instanceof Map) {
            claudeCode = (Map<String, Object>) claudeCodeObj;
        } else {
            claudeCode = new LinkedHashMap<>();
            issuebot.put("claude-code", claudeCode);
        }

        claudeCode.put("implementation-model", implementationModel);
        claudeCode.put("review-model", reviewModel);
        claudeCode.put("utility-model", utilityModel);

        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        try {
            if (configPath.getParent() != null) {
                Files.createDirectories(configPath.getParent());
            }
            Files.writeString(configPath, new Yaml(options).dump(root));
        } catch (IOException e) {
            log.error("Failed to write {}: {}", configPath, e.getMessage());
            return false;
        }
        return true;
    }

    @PostMapping("/config")
    public String saveConfig(Model model, @RequestParam String configContent,
                             @RequestHeader(value = "HX-Request", required = false) String hx) {
        Path configPath = getConfigPath();
        try {
            // Basic YAML validation: check it's not empty and has some structure
            if (configContent == null || configContent.isBlank()) {
                populateModel(model, null, "Configuration cannot be empty.");
                return ViewResolver.view("settings", hx != null);
            }

            if (!configContent.contains("issuebot:")) {
                populateModel(model, null, "Invalid configuration: missing 'issuebot:' root key.");
                return ViewResolver.view("settings", hx != null);
            }

            Files.writeString(configPath, configContent);
            log.info("Configuration saved to {}", configPath);
            populateModel(model, "Configuration saved. Restart to apply all changes.", null);
        } catch (IOException e) {
            log.error("Failed to save configuration", e);
            populateModel(model, null, "Failed to save: " + e.getMessage());
        }
        return ViewResolver.view("settings", hx != null);
    }

    private void populateModel(Model model, String message, String error) {
        model.addAttribute("activePage", "settings");
        model.addAttribute("contentTemplate", "settings");
        model.addAttribute("config", properties);
        model.addAttribute("agentRunning", pollingService.isEnabled());
        model.addAttribute("pendingApprovals", issueRepository.countByStatus(IssueStatus.AWAITING_APPROVAL));

        String implementationModel = properties.getClaudeCode().getImplementationModel();
        String reviewModel = properties.getClaudeCode().getReviewModel();
        model.addAttribute("modelCatalog", ModelCatalog.MODELS);
        model.addAttribute("implementationModel", implementationModel);
        model.addAttribute("reviewModel", reviewModel);
        model.addAttribute("utilityModel", properties.getClaudeCode().getUtilityModel());
        // Whether the current value isn't in the catalog — drives the "Custom…" option/input in the template.
        model.addAttribute("implementationModelCustom", ModelCatalog.find(implementationModel).isEmpty());
        model.addAttribute("reviewModelCustom", ModelCatalog.find(reviewModel).isEmpty());

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
        return configPath;
    }

    /** Test-only hook: point the controller at a temp config file instead of ~/.issuebot/config.yml. */
    void setConfigPathForTests(Path configPath) {
        this.configPath = configPath;
    }
}

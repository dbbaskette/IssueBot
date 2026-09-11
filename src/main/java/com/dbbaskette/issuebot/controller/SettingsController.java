package com.dbbaskette.issuebot.controller;

import com.dbbaskette.issuebot.config.IssueBotProperties;
import com.dbbaskette.issuebot.model.IssueStatus;
import com.dbbaskette.issuebot.repository.NotificationRepository;
import com.dbbaskette.issuebot.repository.TrackedIssueRepository;
import com.dbbaskette.issuebot.service.harness.CodingHarnessRegistry;
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
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;

@Controller
@RequestMapping("/settings")
public class SettingsController {

    private static final Logger log = LoggerFactory.getLogger(SettingsController.class);

    private final IssueBotProperties properties;
    private final IssuePollingService pollingService;
    private final TrackedIssueRepository issueRepository;
    private final NotificationRepository notificationRepository;
    private final CodingHarnessRegistry registry;
    private Path configPath = Path.of(System.getProperty("user.home"), ".issuebot", "config.yml");

    public SettingsController(IssueBotProperties properties,
                               IssuePollingService pollingService,
                               TrackedIssueRepository issueRepository,
                               NotificationRepository notificationRepository,
                               CodingHarnessRegistry registry) {
        this.properties = properties;
        this.pollingService = pollingService;
        this.issueRepository = issueRepository;
        this.notificationRepository = notificationRepository;
        this.registry = registry;
    }

    @GetMapping
    public String settings(Model model,
                           @RequestHeader(value = "HX-Request", required = false) String hx) {
        populateModel(model, null, null);
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

        boolean written = writeConfigValues(Map.of(
                "poll-interval-seconds", pollIntervalSeconds,
                "max-concurrent-issues", maxConcurrentIssues,
                "notifications", Map.of("desktop", desktopNotifications, "dashboard", dashboardNotifications)));

        if (written) {
            populateModel(model, "Saved — applies immediately and persists across restarts.", null);
        } else {
            populateModel(model, null,
                    "Settings applied to the running agent, but could not be saved to " + configPath
                            + " — fix the YAML in the editor below, then try again.");
        }
        return ViewResolver.view("settings", hx != null);
    }

    /** Sentinel posted by the "Custom…" option when the custom text input never got a value (e.g. JS off). */
    static final String CUSTOM_SENTINEL = "__custom__";

    @PostMapping("/models")
    public String saveModels(@RequestParam String harnessId,
                              @RequestParam String implementationModel,
                              @RequestParam String reviewModel,
                              @RequestParam String utilityModel,
                              @RequestParam(required = false) String implementationReasoningEffort,
                              @RequestParam(required = false) String reviewReasoningEffort,
                              @RequestParam(required = false) String utilityReasoningEffort,
                              RedirectAttributes redirectAttributes) {
        // Flash exact input before validation, including values preceding the failing role.
        redirectAttributes.addFlashAttribute("harnessId", harnessId);
        redirectAttributes.addFlashAttribute("implementationModel", implementationModel);
        redirectAttributes.addFlashAttribute("reviewModel", reviewModel);
        redirectAttributes.addFlashAttribute("utilityModel", utilityModel);
        redirectAttributes.addFlashAttribute("implementationReasoningEffort", implementationReasoningEffort);
        redirectAttributes.addFlashAttribute("reviewReasoningEffort", reviewReasoningEffort);
        redirectAttributes.addFlashAttribute("utilityReasoningEffort", utilityReasoningEffort);
        implementationModel = implementationModel == null ? null : implementationModel.trim();
        reviewModel = reviewModel == null ? null : reviewModel.trim();
        utilityModel = utilityModel == null ? null : utilityModel.trim();

        if (isInvalidModelId(implementationModel) || isInvalidModelId(reviewModel)
                || isInvalidModelId(utilityModel)) {
            redirectAttributes.addFlashAttribute("error", "Choose a listed model for each role.");
            return "redirect:/settings";
        }

        try {
            harnessId = registry.require(harnessId).id();
            if (!java.util.Set.of("claude", "codex").contains(harnessId)) {
                throw new IllegalArgumentException("No settings section is configured for this coding harness.");
            }
            implementationReasoningEffort = reasoning.resolve(harnessId, implementationModel, implementationReasoningEffort).reasoningLevel();
            reviewReasoningEffort = reasoning.resolve(harnessId, reviewModel, reviewReasoningEffort).reasoningLevel();
            utilityReasoningEffort = reasoning.resolve(harnessId, utilityModel, utilityReasoningEffort).reasoningLevel();
        } catch (IllegalArgumentException ex) {
            redirectAttributes.addFlashAttribute("error", ex.getMessage());
            return "redirect:/settings";
        }
        if (!writeModelsToConfig(harnessId, implementationModel, reviewModel, utilityModel,
                implementationReasoningEffort, reviewReasoningEffort, utilityReasoningEffort)) {
            redirectAttributes.addFlashAttribute("error",
                    "Could not parse " + configPath + " — fix the YAML in the editor below, then try again.");
            return "redirect:/settings";
        }

        properties.setAgentProvider(harnessId);
        if ("codex".equals(harnessId)) {
            properties.getCodexCli().setImplementationModel(implementationModel);
            properties.getCodexCli().setReviewModel(reviewModel);
            properties.getCodexCli().setUtilityModel(utilityModel);
            properties.getCodexCli().setImplementationReasoningEffort(implementationReasoningEffort);
            properties.getCodexCli().setReviewReasoningEffort(reviewReasoningEffort);
            properties.getCodexCli().setUtilityReasoningEffort(utilityReasoningEffort);
        } else {
            properties.getClaudeCode().setImplementationModel(implementationModel);
            properties.getClaudeCode().setReviewModel(reviewModel);
            properties.getClaudeCode().setUtilityModel(utilityModel);
            properties.getClaudeCode().setImplementationReasoningEffort(implementationReasoningEffort);
            properties.getClaudeCode().setReviewReasoningEffort(reviewReasoningEffort);
            properties.getClaudeCode().setUtilityReasoningEffort(utilityReasoningEffort);
        }

        for (String key : java.util.List.of("harnessId", "implementationModel", "reviewModel", "utilityModel",
                "implementationReasoningEffort", "reviewReasoningEffort", "utilityReasoningEffort")) {
            redirectAttributes.getFlashAttributes().remove(key);
        }
        redirectAttributes.addFlashAttribute("success",
                registry.require(harnessId).displayName() + " selected — applies to the next issue picked up (no restart needed)");
        return "redirect:/settings";
    }

    /** Backward-compatible direct-call overload retained for controller unit tests and callers. */
    String saveModels(String harnessId,
                      String implementationModel, String reviewModel, String utilityModel,
                      RedirectAttributes redirectAttributes) {
        return saveModels(harnessId, implementationModel, reviewModel, utilityModel,
                null, null, null, redirectAttributes);
    }

    /** Backward-compatible direct-call overload retained for controller unit tests and callers. */
    String saveModels(String implementationModel, String reviewModel, String utilityModel,
                      RedirectAttributes redirectAttributes) {
        return saveModels(properties.getAgentProvider(), implementationModel, reviewModel,
                utilityModel, null, null, null, redirectAttributes);
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.dbbaskette.issuebot.service.harness.HarnessSelectionService reasoning;

    private static boolean isInvalidModelId(String modelId) {
        return modelId == null || modelId.isBlank() || CUSTOM_SENTINEL.equals(modelId);
    }

    /**
     * Loads config.yml into a Map, navigates/creates the issuebot.claude-code
     * maps, sets the three model keys, and delegates to
     * {@link #writeConfigValues(Map)} to merge and persist them.
     */
    private boolean writeModelsToConfig(String harnessId,
                                        String implementationModel, String reviewModel,
                                        String utilityModel, String implementationReasoningEffort,
                                        String reviewReasoningEffort, String utilityReasoningEffort) {
        String section = "codex".equals(harnessId) ? "codex-cli" : "claude-code";
        Map<String, Object> providerSettings = new LinkedHashMap<>();
        providerSettings.put("implementation-model", implementationModel);
        providerSettings.put("review-model", reviewModel);
        providerSettings.put("utility-model", utilityModel);
        providerSettings.put("implementation-reasoning-effort", implementationReasoningEffort);
        providerSettings.put("review-reasoning-effort", reviewReasoningEffort);
        providerSettings.put("utility-reasoning-effort", utilityReasoningEffort);
        return writeConfigValues(Map.of(
                "agent-provider", harnessId,
                section, providerSettings));
    }

    /**
     * Loads config.yml into a Map, merges the given key/value pairs into the
     * {@code issuebot} map (one level deep — if both the existing and new
     * value for a key are Maps, the new entries are merged into the existing
     * map rather than replacing it wholesale; this is what lets
     * {@code claude-code} and {@code notifications} sub-keys be updated
     * independently of their siblings), and dumps the whole structure back in
     * block flow style. The dump is written to a sibling temp file and moved
     * into place (atomically where the filesystem supports it) so a mid-write
     * failure can never truncate the user's config. Returns false (without
     * writing) if the existing file fails to parse as YAML.
     */
    @SuppressWarnings("unchecked")
    private boolean writeConfigValues(Map<String, Object> issuebotLevelUpdates) {
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

        for (Map.Entry<String, Object> entry : issuebotLevelUpdates.entrySet()) {
            Object existing = issuebot.get(entry.getKey());
            if (existing instanceof Map && entry.getValue() instanceof Map) {
                ((Map<String, Object>) existing).putAll((Map<String, Object>) entry.getValue());
            } else {
                issuebot.put(entry.getKey(), entry.getValue());
            }
        }

        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        String yamlOut = new Yaml(options).dump(root);
        return writeStringAtomic(yamlOut);
    }

    /**
     * Writes the given content to a sibling temp file and moves it into place
     * (atomically where the filesystem supports it) so a mid-write failure can
     * never truncate the user's config.
     */
    private boolean writeStringAtomic(String content) {
        Path tmp = configPath.resolveSibling(configPath.getFileName() + ".tmp");
        try {
            if (configPath.getParent() != null) {
                Files.createDirectories(configPath.getParent());
            }
            Files.writeString(tmp, content);
            try {
                Files.move(tmp, configPath,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, configPath, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            log.error("Failed to write {}: {}", configPath, e.getMessage());
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException cleanup) {
                log.warn("Could not remove temp file {}: {}", tmp, cleanup.getMessage());
            }
            return false;
        }
        return true;
    }

    @PostMapping("/config")
    public String saveConfig(Model model, @RequestParam String configContent,
                             @RequestHeader(value = "HX-Request", required = false) String hx) {
        if (configContent == null || configContent.isBlank()) {
            populateModel(model, null, "Configuration cannot be empty.");
            return ViewResolver.view("settings", hx != null);
        }

        Object parsed;
        try {
            parsed = new Yaml().load(configContent);
        } catch (Exception e) {
            populateModel(model, null, "config.yml not saved — YAML parse error: " + e.getMessage());
            return ViewResolver.view("settings", hx != null);
        }

        if (!(parsed instanceof Map) || !(((Map<?, ?>) parsed).get("issuebot") instanceof Map)) {
            populateModel(model, null,
                    "config.yml not saved — configuration must contain an issuebot: section.");
            return ViewResolver.view("settings", hx != null);
        }

        if (!writeStringAtomic(configContent)) {
            populateModel(model, null, "Failed to save configuration to " + configPath);
            return ViewResolver.view("settings", hx != null);
        }

        log.info("Configuration saved to {}", configPath);
        populateModel(model, "Configuration saved. Restart to apply all changes.", null);
        return ViewResolver.view("settings", hx != null);
    }

    private void populateModel(Model model, String message, String error) {
        model.addAttribute("activePage", "settings");
        model.addAttribute("contentTemplate", "settings");
        model.addAttribute("config", properties);
        model.addAttribute("agentRunning", pollingService.isEnabled());
        model.addAttribute("pendingApprovals", issueRepository.countByStatus(IssueStatus.AWAITING_APPROVAL));
        model.addAttribute("unreadNotificationCount", notificationRepository.countByReadAtIsNull());

        boolean codex = "codex".equals(properties.getAgentProvider());
        putDefault(model, "harnessId", properties.getAgentProvider());
        putDefault(model, "implementationModel", codex ? properties.getCodexCli().getImplementationModel()
                : properties.getClaudeCode().getImplementationModel());
        putDefault(model, "reviewModel", codex ? properties.getCodexCli().getReviewModel()
                : properties.getClaudeCode().getReviewModel());
        putDefault(model, "utilityModel", codex ? properties.getCodexCli().getUtilityModel()
                : properties.getClaudeCode().getUtilityModel());
        putReasoningDefault(model, "implementationReasoningEffort", "implementationModel", codex ? properties.getCodexCli().getImplementationReasoningEffort()
                : properties.getClaudeCode().getImplementationReasoningEffort());
        putReasoningDefault(model, "reviewReasoningEffort", "reviewModel", codex ? properties.getCodexCli().getReviewReasoningEffort()
                : properties.getClaudeCode().getReviewReasoningEffort());
        putReasoningDefault(model, "utilityReasoningEffort", "utilityModel", codex ? properties.getCodexCli().getUtilityReasoningEffort()
                : properties.getClaudeCode().getUtilityReasoningEffort());

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

    private void putReasoningDefault(Model model, String key, String modelKey, String value) {
        if (model.containsAttribute(key)) return; // Flash values must remain exact, even when blank.
        if (value == null || value.isBlank()) {
            value = registry.adapters().stream().filter(h -> h.id().equals(properties.getAgentProvider()))
                    .flatMap(h -> h.models().stream())
                    .filter(m -> m.id().equals(model.getAttribute(modelKey)))
                    .map(com.dbbaskette.issuebot.service.harness.HarnessModel::defaultReasoningLevel)
                    .findFirst().orElse(value);
        }
        model.addAttribute(key, value);
    }

    private static void putDefault(Model model, String key, Object value) {
        if (!model.containsAttribute(key)) model.addAttribute(key, value);
    }

    private Path getConfigPath() {
        return configPath;
    }

    /** Test-only hook: point the controller at a temp config file instead of ~/.issuebot/config.yml. */
    void setConfigPathForTests(Path configPath) {
        this.configPath = configPath;
    }
}

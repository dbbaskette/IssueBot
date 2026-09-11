package com.dbbaskette.issuebot.controller;

import com.dbbaskette.issuebot.config.IssueBotProperties;
import com.dbbaskette.issuebot.model.IssueStatus;
import com.dbbaskette.issuebot.repository.NotificationRepository;
import com.dbbaskette.issuebot.repository.TrackedIssueRepository;
import com.dbbaskette.issuebot.repository.WatchedRepoRepository;
import com.dbbaskette.issuebot.service.harness.CodingHarnessService;
import com.dbbaskette.issuebot.service.github.GitHubApiClient;
import com.dbbaskette.issuebot.service.polling.IssuePollingService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;

import java.io.File;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Controller
public class SetupController {

    private static final DateTimeFormatter LAST_EVENT_FORMAT =
            DateTimeFormatter.ofPattern("MMM d, HH:mm:ss").withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter DELIVERY_TIME_FORMAT =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    private final CodingHarnessService harnessService;
    private final IssueBotProperties properties;
    private final IssuePollingService pollingService;
    private final TrackedIssueRepository issueRepository;
    private final GitHubApiClient gitHubApiClient;
    private final WatchedRepoRepository repoRepository;
    private final WebhookController webhookController;
    private final WebhookDeliveryLog webhookDeliveryLog;
    private final NotificationRepository notificationRepository;

    public SetupController(CodingHarnessService harnessService,
                            IssueBotProperties properties,
                            IssuePollingService pollingService,
                            TrackedIssueRepository issueRepository,
                            GitHubApiClient gitHubApiClient,
                            WatchedRepoRepository repoRepository,
                            WebhookController webhookController,
                            WebhookDeliveryLog webhookDeliveryLog,
                            NotificationRepository notificationRepository) {
        this.harnessService = harnessService;
        this.properties = properties;
        this.pollingService = pollingService;
        this.issueRepository = issueRepository;
        this.gitHubApiClient = gitHubApiClient;
        this.repoRepository = repoRepository;
        this.webhookController = webhookController;
        this.webhookDeliveryLog = webhookDeliveryLog;
        this.notificationRepository = notificationRepository;
    }

    /** Row of the Webhooks table on the setup page: a watched repo and when it last sent a webhook event. */
    public record WebhookRepoStatus(String fullName, String lastEventDisplay) {}

    /** Row of the recent-deliveries table on the setup page's Webhooks card. */
    public record WebhookDeliveryRow(String time, String eventAction, String repo,
                                      String outcome, String badgeClass, String detail) {}

    /**
     * Main setup page — loads instantly with "Checking..." placeholders.
     * Actual prereq checks are loaded async via /setup/prereqs.
     */
    @GetMapping("/setup")
    public String setup(Model model,
                        @RequestHeader(value = "HX-Request", required = false) String hx) {
        model.addAttribute("activePage", "setup");
        model.addAttribute("contentTemplate", "setup");
        model.addAttribute("agentRunning", pollingService.isEnabled());
        model.addAttribute("pendingApprovals", issueRepository.countByStatus(IssueStatus.AWAITING_APPROVAL));
        model.addAttribute("unreadNotificationCount", notificationRepository.countByReadAtIsNull());
        addProviderAttributes(model);

        model.addAttribute("webhookPath", "/webhooks/github");
        model.addAttribute("webhookSecretConfigured", webhookController.isSecretConfigured());
        model.addAttribute("webhookRepoStatuses", webhookRepoStatuses());

        model.addAttribute("webhookTotalReceived", webhookDeliveryLog.totalReceived());
        model.addAttribute("webhookSignatureFailures", webhookDeliveryLog.signatureFailures());
        model.addAttribute("webhookActionsTaken", webhookDeliveryLog.actionsTaken());
        model.addAttribute("webhookDeliveries", webhookDeliveryRows());

        return ViewResolver.view("setup", hx != null);
    }

    private List<WebhookRepoStatus> webhookRepoStatuses() {
        Map<String, Instant> lastEvents = webhookController.getLastEventTimestamps();
        return repoRepository.findAll().stream()
                .map(repo -> new WebhookRepoStatus(
                        repo.fullName(),
                        Optional.ofNullable(lastEvents.get(repo.fullName()))
                                .map(LAST_EVENT_FORMAT::format)
                                .orElse("never")))
                .toList();
    }

    private List<WebhookDeliveryRow> webhookDeliveryRows() {
        return webhookDeliveryLog.recentDeliveries().stream()
                .map(d -> new WebhookDeliveryRow(
                        DELIVERY_TIME_FORMAT.format(d.at()),
                        eventActionLabel(d.event(), d.action()),
                        d.repo() == null ? "—" : d.repo(),
                        d.outcome(),
                        outcomeBadgeClass(d.outcome()),
                        d.detail() == null ? "—" : d.detail()))
                .toList();
    }

    private static String eventActionLabel(String event, String action) {
        if (event == null || event.isBlank()) return "—";
        if (action == null || action.isBlank()) return event;
        return event + "." + action;
    }

    /** Maps a delivery outcome to an existing status-badge CSS class (ok/warn/danger/neutral). */
    private static String outcomeBadgeClass(String outcome) {
        return switch (outcome) {
            case "started", "recheck" -> "status-completed";
            case "queued" -> "status-queued";
            case "blocked" -> "status-blocked";
            case "bad-signature", "oversized", "error" -> "status-failed";
            default -> "status-pending"; // already_tracked, ignored
        };
    }

    /**
     * HTMX fragment endpoint — runs the actual prerequisite checks.
     * Called async after the setup page renders.
     */
    @GetMapping("/setup/prereqs")
    public String prereqs(Model model) {
        addProviderAttributes(model);
        // Fresh CLI check (don't rely on stale cache)
        boolean cliAvailable = harnessService.checkCliAvailable();
        model.addAttribute("cliAvailable", cliAvailable);

        // Fresh auth check (clear cache so we re-verify)
        boolean cliAuthenticated = false;
        if (cliAvailable) {
            harnessService.clearAuthCache();
            cliAuthenticated = harnessService.checkAuthentication();
        }
        model.addAttribute("cliAuthenticated", cliAuthenticated);

        String token = properties.getGithub().getToken();
        boolean githubTokenSet = token != null && !token.isBlank() && !"not-set".equals(token);
        model.addAttribute("githubTokenSet", githubTokenSet);

        // Presence isn't enough — verify the token actually authenticates with GitHub.
        boolean githubTokenValid = false;
        String githubTokenMessage = "Set the GITHUB_TOKEN environment variable with 'repo' scope.";
        if (githubTokenSet) {
            GitHubApiClient.TokenStatus status = gitHubApiClient.validateToken();
            githubTokenValid = status.valid();
            githubTokenMessage = status.message();
        }
        model.addAttribute("githubTokenValid", githubTokenValid);
        model.addAttribute("githubTokenMessage", githubTokenMessage);

        // Work directory check
        File workDir = new File(properties.getWorkDirectory());
        boolean workDirOk;
        String workDirMessage;
        if (workDir.exists()) {
            long freeSpaceMb = workDir.getFreeSpace() / (1024 * 1024);
            workDirOk = freeSpaceMb > 500;
            workDirMessage = workDir.getAbsolutePath() + " (" + freeSpaceMb + " MB free)";
        } else {
            boolean created = workDir.mkdirs();
            workDirOk = created;
            workDirMessage = created
                    ? workDir.getAbsolutePath() + " (created)"
                    : "Could not create " + workDir.getAbsolutePath();
        }
        model.addAttribute("workDirOk", workDirOk);
        model.addAttribute("workDirMessage", workDirMessage);

        model.addAttribute("allPassed", cliAvailable && cliAuthenticated && githubTokenValid && workDirOk);

        return "setup :: prereqs";
    }

    private void addProviderAttributes(Model model) {
        model.addAttribute("agentProvider", IssueBotProperties.AgentProvider.fromConfig(properties.getAgentProvider()));
        model.addAttribute("agentProviderName", IssueBotProperties.AgentProvider.fromConfig(properties.getAgentProvider()).getDisplayName());
        model.addAttribute("codexProvider",
                "codex".equals(properties.getAgentProvider()));
    }
}

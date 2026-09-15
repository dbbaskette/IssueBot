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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import com.dbbaskette.issuebot.service.workflow.PrerequisiteStatusService;
import static com.dbbaskette.issuebot.service.workflow.PrerequisiteStatusService.Component.*;
import static com.dbbaskette.issuebot.service.workflow.PrerequisiteStatusService.Result.*;

import java.io.File;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Controller
public class SetupController {
    @org.springframework.web.bind.annotation.ModelAttribute("managedHarnessProfiles")
    public java.util.List<com.dbbaskette.issuebot.service.harness.ManagedHarnessProfile> managedHarnessProfiles() {
        return com.dbbaskette.issuebot.service.harness.ManagedHarnessProfile.profiles();
    }
    @org.springframework.web.bind.annotation.ModelAttribute("managedSkillBundle")
    public com.dbbaskette.issuebot.service.harness.ManagedSkillBundle.Identity managedSkillBundle() {
        return com.dbbaskette.issuebot.service.harness.ManagedSkillBundle.bundled().identity();
    }

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
    private final PrerequisiteStatusService prerequisites;

    public SetupController(CodingHarnessService harnessService,
                            IssueBotProperties properties,
                            IssuePollingService pollingService,
                            TrackedIssueRepository issueRepository,
                            GitHubApiClient gitHubApiClient,
                            WatchedRepoRepository repoRepository,
                            WebhookController webhookController,
                            WebhookDeliveryLog webhookDeliveryLog,
                            NotificationRepository notificationRepository) {
        this(harnessService, properties, pollingService, issueRepository, gitHubApiClient, repoRepository,
                webhookController, webhookDeliveryLog, notificationRepository, new PrerequisiteStatusService(properties));
    }

    @org.springframework.beans.factory.annotation.Autowired
    public SetupController(CodingHarnessService harnessService, IssueBotProperties properties,
            IssuePollingService pollingService, TrackedIssueRepository issueRepository,
            GitHubApiClient gitHubApiClient, WatchedRepoRepository repoRepository,
            WebhookController webhookController, WebhookDeliveryLog webhookDeliveryLog,
            NotificationRepository notificationRepository, PrerequisiteStatusService prerequisites) {
        this.harnessService = harnessService;
        this.properties = properties;
        this.pollingService = pollingService;
        this.issueRepository = issueRepository;
        this.gitHubApiClient = gitHubApiClient;
        this.repoRepository = repoRepository;
        this.webhookController = webhookController;
        this.webhookDeliveryLog = webhookDeliveryLog;
        this.notificationRepository = notificationRepository;
        this.prerequisites = prerequisites;
    }

    /** Row of the Webhooks table on the setup page: a watched repo and when it last sent a webhook event. */
    public record WebhookRepoStatus(String fullName, String lastEventDisplay) {}

    /** Row of the recent-deliveries table on the setup page's Webhooks card. */
    public record WebhookDeliveryRow(String time, String eventAction, String repo,
                                      String outcome, String badgeClass, String detail) {}

    /**
     * Main setup page — cached or unknown status only. Checks require an explicit POST.
     */
    @GetMapping("/setup")
    public String setup(Model model,
                        @RequestHeader(value = "HX-Request", required = false) String hx) {
        model.addAttribute("activePage", "setup");
        model.addAttribute("contentTemplate", "setup");
        model.addAttribute("agentRunning", pollingService.isEnabled());
        model.addAttribute("pendingApprovals", issueRepository.countByStatus(IssueStatus.AWAITING_APPROVAL));
        addHarnessAttributes(model);
        addPrerequisiteAttributes(model);

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
     * Read-only fragment endpoint. Never invokes CLI, network, or filesystem checks.
     */
    @GetMapping("/setup/prereqs")
    public String prereqs(Model model) {
        addHarnessAttributes(model);
        addPrerequisiteAttributes(model);
        return "setup :: prereqs";
    }

    /** Explicit CSRF-protected action; no surrounding database transaction. */
    @PostMapping("/setup/prereqs")
    public String recheck(Model model) {
        String harness = properties.getAgentProvider();
        var context = prerequisites.context(harness);
        boolean cli = observeHarness(context, CLI, () -> harnessService.probeCliAvailability(harness));
        if (cli) observeHarness(context, SUBSCRIPTION, () -> harnessService.probeSubscriptionAuthentication(harness));
        else prerequisites.record(context, SUBSCRIPTION, UNKNOWN);
        String token = properties.getGithub().getToken();
        boolean githubTokenSet = token != null && !token.isBlank() && !"not-set".equals(token);
        var github = githubTokenSet ? UNKNOWN : UNMET;
        if (githubTokenSet) {
            try {
                GitHubApiClient.TokenStatus status = gitHubApiClient.validateToken();
                if (status != null) github = switch (status.state()) {
                    case VALID -> READY;
                    case INVALID -> UNMET;
                    case UNKNOWN -> UNKNOWN;
                };
            } catch (RuntimeException unavailable) { github = UNKNOWN; }
        }
        prerequisites.record(context, GITHUB, github);
        prerequisites.record(context, WORK_DIRECTORY, check(() -> {
            File directory = new File(properties.getWorkDirectory());
            if (!directory.exists() && !directory.mkdirs()) return false;
            return directory.isDirectory() && directory.canWrite() && directory.getFreeSpace() / (1024 * 1024) > 500;
        }));
        return prereqs(model);
    }

    private PrerequisiteStatusService.Result check(java.util.function.BooleanSupplier probe) {
        try { return probe.getAsBoolean() ? READY : UNMET; }
        catch (RuntimeException unavailable) { return UNKNOWN; }
    }

    private boolean observeHarness(PrerequisiteStatusService.Context context, PrerequisiteStatusService.Component component,
            java.util.function.Supplier<com.dbbaskette.issuebot.service.harness.HarnessReadiness> probe) {
        try { return prerequisites.observe(context, component, probe); }
        catch (RuntimeException unavailable) { return false; }
    }

    private void addPrerequisiteAttributes(Model model) {
        var context = prerequisites.context(properties.getAgentProvider());
        model.addAttribute("prerequisiteRows", List.of(
                new PrerequisiteRow(harnessService.displayName(), prerequisites.result(context, CLI)),
                new PrerequisiteRow(harnessService.displayName() + " Auth", prerequisites.result(context, SUBSCRIPTION)),
                new PrerequisiteRow("GitHub Token", prerequisites.result(context, GITHUB)),
                new PrerequisiteRow("Work Directory", prerequisites.result(context, WORK_DIRECTORY))));
        model.addAttribute("prerequisiteState", prerequisites.retryState());
    }

    public record PrerequisiteRow(String label, PrerequisiteStatusService.Result result) {
        public String status() { return switch (result) { case READY -> "Verified"; case UNMET -> "Needs attention"; case UNKNOWN -> "Not verified"; }; }
        public String tone() { return switch (result) { case READY -> "status-completed"; case UNMET -> "status-failed"; case UNKNOWN -> "status-pending"; }; }
        public String detail() {
            if (result == UNKNOWN) return "No current result. Select Re-check to verify.";
            if (result == READY) return "Verified within the last five minutes.";
            if (label.equals("GitHub Token")) return "Set a valid GitHub token with repository access, then re-check.";
            if (label.equals("Work Directory")) return "Ensure the work directory is writable with at least 500 MB free, then re-check.";
            return label.endsWith(" Auth") ? "Complete subscription login for the selected harness, then re-check."
                    : "Install the selected harness and make it available on PATH, then re-check.";
        }
    }

    private void addHarnessAttributes(Model model) {
        model.addAttribute("effectiveHarnessId", properties.getAgentProvider());
        model.addAttribute("effectiveHarnessName", harnessService.displayName());
    }
}

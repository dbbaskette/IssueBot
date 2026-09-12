package com.dbbaskette.issuebot.controller;

import com.dbbaskette.issuebot.config.IssueBotProperties;
import com.dbbaskette.issuebot.service.workflow.PrerequisiteStatusService;
import com.dbbaskette.issuebot.model.WatchedRepo;
import com.dbbaskette.issuebot.repository.NotificationRepository;
import com.dbbaskette.issuebot.repository.TrackedIssueRepository;
import com.dbbaskette.issuebot.repository.WatchedRepoRepository;
import com.dbbaskette.issuebot.security.WebhookSignatureVerifier;
import com.dbbaskette.issuebot.service.harness.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.dbbaskette.issuebot.service.github.GitHubApiClient;
import com.dbbaskette.issuebot.service.github.GitHubApiClient.TokenState;
import com.dbbaskette.issuebot.service.github.GitHubApiClient.TokenStatus;
import com.dbbaskette.issuebot.service.polling.IssuePollingService;
import org.junit.jupiter.api.Test;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.ui.Model;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SetupControllerTest {
    @Test void recheckRequiresCsrfAndGetNeverCreatesWorkDirectory(@org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
        var props = new IssueBotProperties();
        var work = directory.resolve("work");
        props.setWorkDirectory(work.toString());
        var harness = mock(CodingHarnessService.class);
        when(harness.displayName()).thenReturn("Selected harness");
        var github = mock(GitHubApiClient.class);
        var controller = new SetupController(harness, props, mock(IssuePollingService.class),
                mock(TrackedIssueRepository.class), github, repoRepository,
                webhookController, webhookDeliveryLog, mock(NotificationRepository.class));
        var tokens = new org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository();
        var csrfFilter = new org.springframework.security.web.csrf.CsrfFilter(tokens);
        csrfFilter.setRequestHandler(new org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler());
        var mvc = MockMvcBuilders.standaloneSetup(controller).addFilters(csrfFilter).build();
        mvc.perform(get("/setup")).andExpect(status().isOk());
        mvc.perform(get("/setup/prereqs")).andExpect(status().isOk());
        assertThat(java.nio.file.Files.exists(work)).isFalse();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/setup/prereqs"))
                .andExpect(status().isForbidden());
        verify(harness, never()).probeCliAvailability(anyString());
        verifyNoInteractions(github);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/setup/prereqs")
                .with(request -> {
                    var token = tokens.generateToken(request);
                    tokens.saveToken(token, request, new org.springframework.mock.web.MockHttpServletResponse());
                    request.addParameter(token.getParameterName(), token.getToken());
                    return request;
                }))
                .andExpect(status().isOk());
        verify(harness).probeCliAvailability("claude");
        assertThat(java.nio.file.Files.isDirectory(work)).isTrue();
    }

    @Test void githubUnavailableIsUnknownNotInvalid() {
        var github = mock(GitHubApiClient.class);
        when(github.validateToken()).thenReturn(new TokenStatus(TokenState.UNKNOWN, "private network detail"));
        var model = new ExtendedModelMap();
        controller(github, "test-token").recheck(model);
        assertThat(result(model, "GitHub Token")).isEqualTo(PrerequisiteStatusService.Result.UNKNOWN);
        assertThat(model.toString()).doesNotContain("private network detail", "test-token");
    }

    @Test void unknownHarnessOutcomeIsNotReportedAsAnUnmetPrerequisite() {
        var github = mock(GitHubApiClient.class);
        when(github.validateToken()).thenReturn(new TokenStatus(TokenState.VALID, "Verified"));
        var controller = controller(github, "test-token");
        var harness = (CodingHarnessService) org.springframework.test.util.ReflectionTestUtils.getField(controller, "harnessService");
        when(harness.displayName()).thenReturn("Selected harness");
        when(harness.probeSubscriptionAuthentication("claude")).thenReturn(HarnessReadiness.UNKNOWN);
        var model = new ExtendedModelMap();
        controller.recheck(model);
        assertThat(result(model, "Selected harness Auth")).isEqualTo(PrerequisiteStatusService.Result.UNKNOWN);
        // Other components may independently be unmet; this auth observation must remain unknown.
    }
    private static PrerequisiteStatusService.Result result(Model model, String label) {
        @SuppressWarnings("unchecked")
        var rows = (List<SetupController.PrerequisiteRow>) model.getAttribute("prerequisiteRows");
        return rows.stream().filter(row -> label.equals(row.label())).findFirst().orElseThrow().result();
    }

    @Test void setupRequestSkipsAllCatalogDiscoveryAndReadinessChecksRemainFresh() throws Exception {
        var selected = new CountingAdapter("claude", "Selected Harness");
        var other = new CountingAdapter("codex", "Other Harness");
        var registry = new CodingHarnessRegistry(List.of(selected, other));
        var props = new IssueBotProperties();
        var issues = mock(TrackedIssueRepository.class);
        var selections = new HarnessSelectionService(registry, props, issues,
                mock(com.dbbaskette.issuebot.repository.StageApprovalRepository.class));
        var harness = new CodingHarnessService(registry, props, selections);
        var controller = new SetupController(harness, props, mock(IssuePollingService.class),
                issues, mock(GitHubApiClient.class), repoRepository,
                webhookController, webhookDeliveryLog, mock(NotificationRepository.class));
        var mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new HarnessCatalogAdvice(registry, new ObjectMapper(), props)).build();

        mvc.perform(get("/setup")).andExpect(status().isOk())
                .andExpect(model().attribute("effectiveHarnessName", "Selected Harness"));
        assertThat(selected.catalogCalls).as("selected catalog discovery on Setup GET").isZero();
        assertThat(other.catalogCalls).as("unselected catalog discovery on Setup GET").isZero();
        assertThat(selected.availabilityCalls).isZero();
        assertThat(selected.subscriptionCalls).isZero();

        mvc.perform(get("/setup/prereqs")).andExpect(status().isOk())
                .andExpect(model().attribute("prerequisiteState", com.dbbaskette.issuebot.service.ui.RecoveryGuidance.PrerequisiteState.NOT_VERIFIED));
        assertThat(selected.availabilityCalls).isZero();
        assertThat(selected.subscriptionCalls).isZero();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/setup/prereqs"))
                .andExpect(status().isOk());
        selected.subscriptionReady = true;
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/setup/prereqs"))
                .andExpect(status().isOk());
        mvc.perform(get("/setup/prereqs")).andExpect(status().isOk());
        assertThat(selected.availabilityCalls).isEqualTo(2);
        assertThat(selected.subscriptionCalls).isEqualTo(2);
        assertThat(other.availabilityCalls).isZero();
        assertThat(other.subscriptionCalls).isZero();
        assertThat(selected.catalogCalls).isZero();
        assertThat(other.catalogCalls).isZero();
    }

    private static final class CountingAdapter implements CodingHarnessAdapter {
        private final String id;
        private final String displayName;
        int catalogCalls;
        int availabilityCalls;
        int subscriptionCalls;
        boolean subscriptionReady;

        CountingAdapter(String id, String displayName) { this.id = id; this.displayName = displayName; }
        public String id() { return id; }
        public String displayName() { return displayName; }
        public List<HarnessModel> models() { catalogCalls++; return List.of(); }
        public HarnessCapabilities capabilities() { return HarnessCapabilities.NONE; }
        public boolean checkCliAvailable() { availabilityCalls++; return true; }
        public boolean checkSubscriptionAuthentication() { subscriptionCalls++; return subscriptionReady; }
        public HarnessReadiness probeSubscriptionAuthentication() { return checkSubscriptionAuthentication() ? HarnessReadiness.READY : HarnessReadiness.UNMET; }
        public HarnessExecutionResult execute(HarnessExecutionRequest request,
                java.util.function.Consumer<String> lines) { throw new AssertionError("Setup cannot execute work"); }
    }

    @Test void readinessCannotReportSubscriptionSuccessFromAGenericLogin() {
        var harness = mock(CodingHarnessService.class);
        when(harness.displayName()).thenReturn("Example Harness");
        when(harness.probeCliAvailability(anyString())).thenReturn(HarnessReadiness.READY);
        when(harness.checkAuthentication()).thenReturn(true);
        when(harness.probeSubscriptionAuthentication("example")).thenReturn(HarnessReadiness.UNMET);
        var props = new IssueBotProperties();
        props.setAgentProvider("example");
        var controller = new SetupController(harness, props, mock(IssuePollingService.class),
                mock(TrackedIssueRepository.class), mock(GitHubApiClient.class), repoRepository,
                webhookController, webhookDeliveryLog, mock(NotificationRepository.class));
        var model = new ExtendedModelMap();
        controller.recheck(model);
        assertThat(result(model, "Example Harness")).isEqualTo(PrerequisiteStatusService.Result.READY);
        assertThat(result(model, "Example Harness Auth")).isEqualTo(PrerequisiteStatusService.Result.UNMET);
        verify(harness).probeSubscriptionAuthentication("example");
    }

    @Test void setupUsesSelectedHarnessMetadataWithoutRunningReadinessChecks() {
        var harness = mock(CodingHarnessService.class);
        when(harness.displayName()).thenReturn("Example Harness");
        var props = new IssueBotProperties();
        props.setAgentProvider("example");
        var controller = new SetupController(harness, props, mock(IssuePollingService.class),
                mock(TrackedIssueRepository.class), mock(GitHubApiClient.class), repoRepository,
                webhookController, webhookDeliveryLog, mock(NotificationRepository.class));
        var model = new ExtendedModelMap();
        controller.setup(model, null);
        assertThat(model.get("effectiveHarnessId")).isEqualTo("example");
        assertThat(model.get("effectiveHarnessName")).isEqualTo("Example Harness");
        verify(harness, never()).checkCliAvailable();
        verify(harness, never()).checkAuthentication();
    }

    private final WatchedRepoRepository repoRepository = mock(WatchedRepoRepository.class);
    private final WebhookDeliveryLog webhookDeliveryLog = new WebhookDeliveryLog();
    private final WebhookController webhookController = new WebhookController(
            new WebhookSignatureVerifier(), repoRepository, mock(IssuePollingService.class), webhookDeliveryLog, "");

    private SetupController controller(GitHubApiClient gitHub, String token) {
        return controller(gitHub, token, webhookController);
    }

    private SetupController controller(GitHubApiClient gitHub, String token, WebhookController webhooks) {
        IssueBotProperties props = new IssueBotProperties();
        props.getGithub().setToken(token);
        CodingHarnessService claude = mock(CodingHarnessService.class);
        when(claude.probeCliAvailability(anyString())).thenReturn(HarnessReadiness.READY);
        when(claude.probeSubscriptionAuthentication("claude")).thenReturn(HarnessReadiness.READY);
        lenient().when(repoRepository.findAll()).thenReturn(List.of());
        return new SetupController(claude, props, mock(IssuePollingService.class),
                mock(TrackedIssueRepository.class), gitHub, repoRepository, webhooks, webhookDeliveryLog,
                mock(NotificationRepository.class));
    }

    @Test
    void prereqs_tokenPresentButRejected_marksInvalidAndNotAllPassed() {
        GitHubApiClient gitHub = mock(GitHubApiClient.class);
        when(gitHub.validateToken()).thenReturn(
                new TokenStatus(TokenState.INVALID, "GitHub rejected the token (401)."));

        Model model = new ExtendedModelMap();
        controller(gitHub, "ghp_bad").recheck(model);

        assertThat(result(model, "GitHub Token")).isEqualTo(PrerequisiteStatusService.Result.UNMET);
        assertThat(model.getAttribute("prerequisiteState")).isEqualTo(com.dbbaskette.issuebot.service.ui.RecoveryGuidance.PrerequisiteState.KNOWN_UNMET);
    }

    @Test
    void prereqs_tokenValid_marksValid() {
        GitHubApiClient gitHub = mock(GitHubApiClient.class);
        when(gitHub.validateToken()).thenReturn(
                new TokenStatus(TokenState.VALID, "Token authenticated with GitHub."));

        Model model = new ExtendedModelMap();
        controller(gitHub, "ghp_good").recheck(model);

        assertThat(result(model, "GitHub Token")).isEqualTo(PrerequisiteStatusService.Result.READY);
    }

    @Test
    void prereqs_tokenMissing_doesNotCallGitHub() {
        GitHubApiClient gitHub = mock(GitHubApiClient.class);

        Model model = new ExtendedModelMap();
        controller(gitHub, null).recheck(model);

        assertThat(result(model, "GitHub Token")).isEqualTo(PrerequisiteStatusService.Result.UNMET);
        verify(gitHub, never()).validateToken();
    }

    // === Webhooks section ===

    @Test
    void setup_webhookSecretBlank_reportsNotConfigured() {
        Model model = new ExtendedModelMap();
        controller(mock(GitHubApiClient.class), null).setup(model, null);

        assertThat(model.getAttribute("webhookSecretConfigured")).isEqualTo(false);
    }

    @Test
    void setup_webhookSecretSet_reportsConfigured() {
        WebhookController secretConfigured = new WebhookController(
                new WebhookSignatureVerifier(), repoRepository, mock(IssuePollingService.class),
                webhookDeliveryLog, "a-real-secret");

        Model model = new ExtendedModelMap();
        controller(mock(GitHubApiClient.class), null, secretConfigured).setup(model, null);

        assertThat(model.getAttribute("webhookSecretConfigured")).isEqualTo(true);
    }

    @Test
    void setup_watchedRepoWithNoEvents_showsNever() {
        WatchedRepo repo = new WatchedRepo("acme", "widgets");
        SetupController controller = controller(mock(GitHubApiClient.class), null);
        when(repoRepository.findAll()).thenReturn(List.of(repo));

        Model model = new ExtendedModelMap();
        controller.setup(model, null);

        @SuppressWarnings("unchecked")
        List<SetupController.WebhookRepoStatus> statuses =
                (List<SetupController.WebhookRepoStatus>) model.getAttribute("webhookRepoStatuses");
        assertThat(statuses).hasSize(1);
        assertThat(statuses.get(0).fullName()).isEqualTo("acme/widgets");
        assertThat(statuses.get(0).lastEventDisplay()).isEqualTo("never");
    }

    @Test
    void setup_watchedRepoWithRecentEvent_showsTimestamp() {
        WatchedRepo repo = new WatchedRepo("acme", "widgets");
        SetupController controller = controller(mock(GitHubApiClient.class), null);
        when(repoRepository.findAll()).thenReturn(List.of(repo));
        webhookController.getLastEventTimestamps().put("acme/widgets", java.time.Instant.now());

        Model model = new ExtendedModelMap();
        controller.setup(model, null);

        @SuppressWarnings("unchecked")
        List<SetupController.WebhookRepoStatus> statuses =
                (List<SetupController.WebhookRepoStatus>) model.getAttribute("webhookRepoStatuses");
        assertThat(statuses).hasSize(1);
        assertThat(statuses.get(0).lastEventDisplay()).isNotEqualTo("never");
    }

    // === Delivery log: counters + deliveries table ===

    @Test
    void setup_noDeliveriesYet_countersZeroAndTableEmpty() {
        SetupController controller = controller(mock(GitHubApiClient.class), null);

        Model model = new ExtendedModelMap();
        controller.setup(model, null);

        assertThat(model.getAttribute("webhookTotalReceived")).isEqualTo(0L);
        assertThat(model.getAttribute("webhookSignatureFailures")).isEqualTo(0L);
        assertThat(model.getAttribute("webhookActionsTaken")).isEqualTo(0L);
        assertThat((List<?>) model.getAttribute("webhookDeliveries")).isEmpty();
    }

    @Test
    void setup_exposesCountersAndDeliveryRows() {
        webhookDeliveryLog.record("issues", "labeled", "acme/widgets", "started", "issue #12 started");
        webhookDeliveryLog.record(null, null, "—", "bad-signature", null);

        SetupController controller = controller(mock(GitHubApiClient.class), null);
        Model model = new ExtendedModelMap();
        controller.setup(model, null);

        assertThat(model.getAttribute("webhookTotalReceived")).isEqualTo(2L);
        assertThat(model.getAttribute("webhookSignatureFailures")).isEqualTo(1L);
        assertThat(model.getAttribute("webhookActionsTaken")).isEqualTo(1L);

        @SuppressWarnings("unchecked")
        List<SetupController.WebhookDeliveryRow> rows =
                (List<SetupController.WebhookDeliveryRow>) model.getAttribute("webhookDeliveries");
        assertThat(rows).hasSize(2);

        // Newest first: the bad-signature record was added after the labeled one.
        SetupController.WebhookDeliveryRow badSig = rows.get(0);
        assertThat(badSig.outcome()).isEqualTo("bad-signature");
        assertThat(badSig.repo()).isEqualTo("—");
        assertThat(badSig.badgeClass()).isEqualTo("status-failed");
        assertThat(badSig.eventAction()).isEqualTo("—");

        SetupController.WebhookDeliveryRow started = rows.get(1);
        assertThat(started.outcome()).isEqualTo("started");
        assertThat(started.eventAction()).isEqualTo("issues.labeled");
        assertThat(started.repo()).isEqualTo("acme/widgets");
        assertThat(started.badgeClass()).isEqualTo("status-completed");
        assertThat(started.detail()).isEqualTo("issue #12 started");
        assertThat(started.time()).matches("\\d{2}:\\d{2}:\\d{2}");
    }
}

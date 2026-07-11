package com.dbbaskette.issuebot.controller;

import com.dbbaskette.issuebot.config.IssueBotProperties;
import com.dbbaskette.issuebot.model.WatchedRepo;
import com.dbbaskette.issuebot.repository.TrackedIssueRepository;
import com.dbbaskette.issuebot.repository.WatchedRepoRepository;
import com.dbbaskette.issuebot.security.WebhookSignatureVerifier;
import com.dbbaskette.issuebot.service.claude.ClaudeCodeService;
import com.dbbaskette.issuebot.service.github.GitHubApiClient;
import com.dbbaskette.issuebot.service.github.GitHubApiClient.TokenState;
import com.dbbaskette.issuebot.service.github.GitHubApiClient.TokenStatus;
import com.dbbaskette.issuebot.service.polling.IssuePollingService;
import org.junit.jupiter.api.Test;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.ui.Model;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class SetupControllerTest {

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
        ClaudeCodeService claude = mock(ClaudeCodeService.class);
        when(claude.checkCliAvailable()).thenReturn(true);
        when(claude.checkAuthentication()).thenReturn(true);
        lenient().when(repoRepository.findAll()).thenReturn(List.of());
        return new SetupController(claude, props, mock(IssuePollingService.class),
                mock(TrackedIssueRepository.class), gitHub, repoRepository, webhooks, webhookDeliveryLog);
    }

    @Test
    void prereqs_tokenPresentButRejected_marksInvalidAndNotAllPassed() {
        GitHubApiClient gitHub = mock(GitHubApiClient.class);
        when(gitHub.validateToken()).thenReturn(
                new TokenStatus(TokenState.INVALID, "GitHub rejected the token (401)."));

        Model model = new ExtendedModelMap();
        controller(gitHub, "ghp_bad").prereqs(model);

        assertThat(model.getAttribute("githubTokenSet")).isEqualTo(true);
        assertThat(model.getAttribute("githubTokenValid")).isEqualTo(false);
        assertThat(model.getAttribute("githubTokenMessage")).asString().contains("401");
        assertThat(model.getAttribute("allPassed")).isEqualTo(false);
    }

    @Test
    void prereqs_tokenValid_marksValid() {
        GitHubApiClient gitHub = mock(GitHubApiClient.class);
        when(gitHub.validateToken()).thenReturn(
                new TokenStatus(TokenState.VALID, "Token authenticated with GitHub."));

        Model model = new ExtendedModelMap();
        controller(gitHub, "ghp_good").prereqs(model);

        assertThat(model.getAttribute("githubTokenSet")).isEqualTo(true);
        assertThat(model.getAttribute("githubTokenValid")).isEqualTo(true);
    }

    @Test
    void prereqs_tokenMissing_doesNotCallGitHub() {
        GitHubApiClient gitHub = mock(GitHubApiClient.class);

        Model model = new ExtendedModelMap();
        controller(gitHub, null).prereqs(model);

        assertThat(model.getAttribute("githubTokenSet")).isEqualTo(false);
        assertThat(model.getAttribute("githubTokenValid")).isEqualTo(false);
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

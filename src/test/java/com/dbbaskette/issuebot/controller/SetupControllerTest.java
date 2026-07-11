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
    private final WebhookController webhookController = new WebhookController(
            new WebhookSignatureVerifier(), repoRepository, mock(IssuePollingService.class), "");

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
                mock(TrackedIssueRepository.class), gitHub, repoRepository, webhooks);
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
                new WebhookSignatureVerifier(), repoRepository, mock(IssuePollingService.class), "a-real-secret");

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
}

package com.dbbaskette.issuebot.controller;

import com.dbbaskette.issuebot.config.IssueBotProperties;
import com.dbbaskette.issuebot.repository.TrackedIssueRepository;
import com.dbbaskette.issuebot.service.claude.ClaudeCodeService;
import com.dbbaskette.issuebot.service.github.GitHubApiClient;
import com.dbbaskette.issuebot.service.github.GitHubApiClient.TokenState;
import com.dbbaskette.issuebot.service.github.GitHubApiClient.TokenStatus;
import com.dbbaskette.issuebot.service.polling.IssuePollingService;
import org.junit.jupiter.api.Test;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.ui.Model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class SetupControllerTest {

    private SetupController controller(GitHubApiClient gitHub, String token) {
        IssueBotProperties props = new IssueBotProperties();
        props.getGithub().setToken(token);
        ClaudeCodeService claude = mock(ClaudeCodeService.class);
        when(claude.checkCliAvailable()).thenReturn(true);
        when(claude.checkAuthentication()).thenReturn(true);
        return new SetupController(claude, props, mock(IssuePollingService.class),
                mock(TrackedIssueRepository.class), gitHub);
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
}

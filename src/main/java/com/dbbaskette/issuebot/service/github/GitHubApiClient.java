package com.dbbaskette.issuebot.service.github;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.util.retry.Retry;
import java.time.Duration;
import java.util.List;
import java.util.Map;

@Service
public class GitHubApiClient {

    private static final Logger log = LoggerFactory.getLogger(GitHubApiClient.class);

    private final WebClient webClient;

    public GitHubApiClient(WebClient gitHubWebClient) {
        this.webClient = gitHubWebClient;
    }

    // --- Issues ---

    public List<JsonNode> listIssues(String owner, String repo, String label, String state) {
        log.debug("Listing issues for {}/{} with label={}, state={}", owner, repo, label, state);
        return webClient.get()
                .uri("/repos/{owner}/{repo}/issues?labels={label}&state={state}&per_page=30",
                        owner, repo, label, state)
                .retrieve()
                .bodyToFlux(JsonNode.class)
                .retryWhen(retryOnServerError())
                .collectList()
                .block(Duration.ofSeconds(30));
    }

    public JsonNode getIssue(String owner, String repo, int issueNumber) {
        log.debug("Getting issue {}/{} #{}", owner, repo, issueNumber);
        return webClient.get()
                .uri("/repos/{owner}/{repo}/issues/{number}", owner, repo, issueNumber)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .retryWhen(retryOnServerError())
                .block(Duration.ofSeconds(15));
    }

    public JsonNode addComment(String owner, String repo, int issueNumber, String body) {
        log.debug("Adding comment to {}/{} #{}", owner, repo, issueNumber);
        return webClient.post()
                .uri("/repos/{owner}/{repo}/issues/{number}/comments", owner, repo, issueNumber)
                .bodyValue(Map.of("body", body))
                .retrieve()
                .bodyToMono(JsonNode.class)
                .retryWhen(retryOnServerError())
                .block(Duration.ofSeconds(15));
    }

    public void assignIssue(String owner, String repo, int issueNumber, List<String> assignees) {
        log.debug("Assigning {}/{} #{} to {}", owner, repo, issueNumber, assignees);
        webClient.post()
                .uri("/repos/{owner}/{repo}/issues/{number}/assignees", owner, repo, issueNumber)
                .bodyValue(Map.of("assignees", assignees))
                .retrieve()
                .toBodilessEntity()
                .retryWhen(retryOnServerError())
                .block(Duration.ofSeconds(15));
    }

    public void addLabels(String owner, String repo, int issueNumber, List<String> labels) {
        log.debug("Adding labels {} to {}/{} #{}", labels, owner, repo, issueNumber);
        webClient.post()
                .uri("/repos/{owner}/{repo}/issues/{number}/labels", owner, repo, issueNumber)
                .bodyValue(Map.of("labels", labels))
                .retrieve()
                .toBodilessEntity()
                .retryWhen(retryOnServerError())
                .block(Duration.ofSeconds(15));
    }

    public void removeLabel(String owner, String repo, int issueNumber, String label) {
        log.debug("Removing label '{}' from {}/{} #{}", label, owner, repo, issueNumber);
        try {
            webClient.delete()
                    .uri("/repos/{owner}/{repo}/issues/{number}/labels/{label}",
                            owner, repo, issueNumber, label)
                    .retrieve()
                    .toBodilessEntity()
                    .block(Duration.ofSeconds(15));
        } catch (WebClientResponseException.NotFound e) {
            log.debug("Label '{}' not found on issue, ignoring", label);
        }
    }

    // --- Pull Requests ---

    public JsonNode createPullRequest(String owner, String repo, String title, String body,
                                       String head, String base, boolean draft) {
        log.info("Creating {} PR for {}/{}: {} -> {}", draft ? "draft" : "", owner, repo, head, base);
        Map<String, Object> payload = Map.of(
                "title", title,
                "body", body,
                "head", head,
                "base", base,
                "draft", draft
        );
        return webClient.post()
                .uri("/repos/{owner}/{repo}/pulls", owner, repo)
                .bodyValue(payload)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .retryWhen(retryOnServerError())
                .block(Duration.ofSeconds(30));
    }

    public JsonNode getPullRequest(String owner, String repo, int prNumber) {
        return webClient.get()
                .uri("/repos/{owner}/{repo}/pulls/{number}", owner, repo, prNumber)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block(Duration.ofSeconds(15));
    }

    // --- CI / Check Runs ---

    public JsonNode getCheckRuns(String owner, String repo, String ref) {
        log.debug("Getting check runs for {}/{} ref={}", owner, repo, ref);
        return webClient.get()
                .uri("/repos/{owner}/{repo}/commits/{ref}/check-runs", owner, repo, ref)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .retryWhen(retryOnServerError())
                .block(Duration.ofSeconds(15));
    }

    /**
     * Polls check runs until all complete or timeout.
     * Returns true if all checks passed, false otherwise.
     */
    public boolean waitForChecks(String owner, String repo, String ref, int timeoutMinutes) {
        log.info("Waiting for CI checks on {}/{} ref={} (timeout={}min)", owner, repo, ref, timeoutMinutes);
        long deadline = System.currentTimeMillis() + (long) timeoutMinutes * 60 * 1000;
        int pollIntervalMs = 30_000;

        while (System.currentTimeMillis() < deadline) {
            JsonNode checks = getCheckRuns(owner, repo, ref);
            if (checks == null || !checks.has("check_runs")) {
                sleep(pollIntervalMs);
                continue;
            }

            JsonNode checkRuns = checks.get("check_runs");
            if (checkRuns.isEmpty()) {
                sleep(pollIntervalMs);
                continue;
            }

            boolean allComplete = true;
            boolean anyFailed = false;
            for (JsonNode run : checkRuns) {
                String status = run.path("status").asText();
                if (!"completed".equals(status)) {
                    allComplete = false;
                    break;
                }
                String conclusion = run.path("conclusion").asText();
                if (!"success".equals(conclusion) && !"skipped".equals(conclusion)) {
                    anyFailed = true;
                }
            }

            if (allComplete) {
                log.info("All checks completed for {}/{} ref={}, passed={}", owner, repo, ref, !anyFailed);
                return !anyFailed;
            }

            sleep(pollIntervalMs);
        }

        log.warn("Timed out waiting for checks on {}/{} ref={}", owner, repo, ref);
        return false;
    }

    // --- Repository Info ---

    public JsonNode getRepository(String owner, String repo) {
        return webClient.get()
                .uri("/repos/{owner}/{repo}", owner, repo)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block(Duration.ofSeconds(15));
    }

    // --- Helpers ---

    private Retry retryOnServerError() {
        return Retry.backoff(3, Duration.ofSeconds(2))
                .filter(throwable -> {
                    if (throwable instanceof WebClientResponseException wcre) {
                        HttpStatusCode status = wcre.getStatusCode();
                        return status.is5xxServerError() || status.value() == 429;
                    }
                    return false;
                })
                .doBeforeRetry(signal -> log.warn("Retrying GitHub API call (attempt {}): {}",
                        signal.totalRetries() + 1, signal.failure().getMessage()));
    }

    private void sleep(int millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

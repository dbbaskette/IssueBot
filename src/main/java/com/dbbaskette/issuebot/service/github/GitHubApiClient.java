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
import java.util.HashMap;
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

    /**
     * List open pull requests whose head branch starts with the given prefix.
     * Used to detect active IssueBot PRs (prefix = "issuebot/").
     */
    public List<JsonNode> listOpenPullRequests(String owner, String repo, String headPrefix) {
        log.debug("Listing open PRs for {}/{} with head prefix '{}'", owner, repo, headPrefix);
        List<JsonNode> allPrs = webClient.get()
                .uri("/repos/{owner}/{repo}/pulls?state=open&per_page=30", owner, repo)
                .retrieve()
                .bodyToFlux(JsonNode.class)
                .retryWhen(retryOnServerError())
                .collectList()
                .block(Duration.ofSeconds(30));

        if (allPrs == null) return List.of();
        if (headPrefix == null || headPrefix.isBlank()) return allPrs;

        return allPrs.stream()
                .filter(pr -> pr.path("head").path("ref").asText("").startsWith(headPrefix))
                .toList();
    }

    public JsonNode mergePullRequest(String owner, String repo, int prNumber,
                                      String commitTitle, String mergeMethod) {
        log.info("Merging PR #{} in {}/{} via {}", prNumber, owner, repo, mergeMethod);
        Map<String, Object> payload = new HashMap<>();
        payload.put("merge_method", mergeMethod);
        if (commitTitle != null) {
            payload.put("commit_title", commitTitle);
        }
        return webClient.put()
                .uri("/repos/{owner}/{repo}/pulls/{number}/merge", owner, repo, prNumber)
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

    /**
     * Post a pull request review with inline file comments.
     * event: "APPROVE", "REQUEST_CHANGES", or "COMMENT"
     */
    public void createPullRequestReview(String owner, String repo, int prNumber,
                                         String body, String event,
                                         List<ReviewComment> comments) {
        log.info("Creating PR review on {}/{} #{} — event={}, comments={}",
                owner, repo, prNumber, event, comments.size());
        Map<String, Object> payload = new HashMap<>();
        payload.put("body", body);
        payload.put("event", event);
        if (comments != null && !comments.isEmpty()) {
            payload.put("comments", comments.stream()
                    .map(this::toCommentMap)
                    .toList());
        }
        webClient.post()
                .uri("/repos/{owner}/{repo}/pulls/{number}/reviews", owner, repo, prNumber)
                .bodyValue(payload)
                .retrieve()
                .toBodilessEntity()
                .retryWhen(retryOnServerError())
                .block(Duration.ofSeconds(30));
    }

    /**
     * Approve a pull request.
     */
    public void approvePullRequest(String owner, String repo, int prNumber, String message) {
        createPullRequestReview(owner, repo, prNumber,
                message != null ? message : "Approved by IssueBot independent review.",
                "APPROVE", List.of());
    }

    /**
     * Request changes on a pull request with review findings.
     */
    public void requestChanges(String owner, String repo, int prNumber,
                                String summary, List<ReviewComment> comments) {
        createPullRequestReview(owner, repo, prNumber, summary, "REQUEST_CHANGES", comments);
    }

    /**
     * A single inline comment on a PR review.
     */
    public record ReviewComment(String path, Integer line, String body) {}

    private Map<String, Object> toCommentMap(ReviewComment c) {
        Map<String, Object> m = new HashMap<>();
        m.put("path", c.path());
        m.put("body", c.body());
        if (c.line() != null) {
            m.put("line", c.line());
        }
        return m;
    }

    /**
     * Mark a draft PR as ready for review.
     */
    public void markPrReady(String owner, String repo, int prNumber) {
        log.info("Marking PR #{} as ready in {}/{}", prNumber, owner, repo);
        webClient.patch()
                .uri("/repos/{owner}/{repo}/pulls/{number}", owner, repo, prNumber)
                .bodyValue(Map.of("draft", false))
                .retrieve()
                .toBodilessEntity()
                .retryWhen(retryOnServerError())
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
        int noCiGracePeriodMs = 90_000; // Wait 90s for checks to appear before assuming no CI
        long firstPollTime = 0;

        while (System.currentTimeMillis() < deadline) {
            JsonNode checks = getCheckRuns(owner, repo, ref);
            if (checks == null || !checks.has("check_runs")) {
                sleep(pollIntervalMs);
                continue;
            }

            JsonNode checkRuns = checks.get("check_runs");
            if (checkRuns.isEmpty()) {
                if (firstPollTime == 0) {
                    firstPollTime = System.currentTimeMillis();
                } else if (System.currentTimeMillis() - firstPollTime > noCiGracePeriodMs) {
                    log.info("No CI checks found for {}/{} ref={} after {}s — treating as passed",
                            owner, repo, ref, noCiGracePeriodMs / 1000);
                    return true;
                }
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

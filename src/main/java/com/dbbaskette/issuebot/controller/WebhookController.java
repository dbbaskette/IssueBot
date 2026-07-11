package com.dbbaskette.issuebot.controller;

import com.dbbaskette.issuebot.model.WatchedRepo;
import com.dbbaskette.issuebot.repository.WatchedRepoRepository;
import com.dbbaskette.issuebot.security.WebhookSignatureVerifier;
import com.dbbaskette.issuebot.service.polling.IssuePollingService;
import com.dbbaskette.issuebot.service.polling.WebhookOutcome;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Receives GitHub webhook deliveries for instant issue pickup, replacing the
 * 60-second poll wait with an immediate evaluation. Polling continues to run
 * as the fallback/reconciliation path — this controller is purely an
 * accelerator.
 * <p>
 * Disabled (503) unless {@code ISSUEBOT_WEBHOOK_SECRET} is set. Bodies over
 * {@link #MAX_BODY_BYTES} are rejected with 413 before being materialized.
 * Every delivery must carry a valid {@code X-Hub-Signature-256} HMAC-SHA256
 * signature over the raw body, or it is rejected with 401. Once past those
 * gates, the handler never surfaces an error back to GitHub — any downstream
 * failure (malformed payload, evaluation exception) is logged and swallowed,
 * always answering 204, so GitHub does not disable the webhook after repeated
 * failures.
 */
@RestController
public class WebhookController {

    /** GitHub issue payloads are well under 1 MiB; anything bigger is not a legitimate delivery. */
    static final int MAX_BODY_BYTES = 1_048_576;

    private static final Logger log = LoggerFactory.getLogger(WebhookController.class);
    private static final String AGENT_READY_LABEL = "agent-ready";
    private static final String ISSUES_EVENT = "issues";

    /** Placeholder shown in the delivery log when the source of a field isn't trusted or known. */
    private static final String UNKNOWN = "—";

    private final WebhookSignatureVerifier signatureVerifier;
    private final WatchedRepoRepository repoRepository;
    private final IssuePollingService pollingService;
    private final WebhookDeliveryLog deliveryLog;
    private final String webhookSecret;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, Instant> lastEventAt = new ConcurrentHashMap<>();

    public WebhookController(WebhookSignatureVerifier signatureVerifier,
                              WatchedRepoRepository repoRepository,
                              IssuePollingService pollingService,
                              WebhookDeliveryLog deliveryLog,
                              @Value("${ISSUEBOT_WEBHOOK_SECRET:}") String webhookSecret) {
        this.signatureVerifier = signatureVerifier;
        this.repoRepository = repoRepository;
        this.pollingService = pollingService;
        this.deliveryLog = deliveryLog;
        this.webhookSecret = webhookSecret;
    }

    @PostMapping("/webhooks/github")
    public ResponseEntity<Void> handleWebhook(
            HttpServletRequest request,
            @RequestHeader(value = "X-Hub-Signature-256", required = false) String signature,
            @RequestHeader(value = "X-GitHub-Event", required = false) String eventType) throws IOException {

        if (!isSecretConfigured()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }

        // Bound the body BEFORE materializing it: fast-fail on a declared
        // Content-Length over the limit, then cap the actual read so chunked
        // requests (no Content-Length) can't exceed it either.
        if (request.getContentLengthLong() > MAX_BODY_BYTES) {
            deliveryLog.record(null, null, UNKNOWN, "oversized", null);
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).build();
        }
        byte[] rawBody = readBounded(request.getInputStream());
        if (rawBody == null) {
            deliveryLog.record(null, null, UNKNOWN, "oversized", null);
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).build();
        }

        if (!signatureVerifier.verify(rawBody, signature, webhookSecret)) {
            // Unsigned/forged request — record nothing derived from headers or body,
            // just the bare fact that an unauthenticated delivery arrived.
            deliveryLog.record(null, null, UNKNOWN, "bad-signature", null);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        dispatch(rawBody, eventType);

        return ResponseEntity.noContent().build();
    }

    /**
     * Reads at most {@link #MAX_BODY_BYTES} from the stream.
     *
     * @return the body bytes, or null if the stream held more than the limit
     */
    private static byte[] readBounded(InputStream in) throws IOException {
        byte[] body = in.readNBytes(MAX_BODY_BYTES + 1);
        return body.length > MAX_BODY_BYTES ? null : body;
    }

    /**
     * Parses and routes a verified delivery, always recording exactly one
     * {@link WebhookDeliveryLog.Delivery} before returning. Once the signature
     * has verified, the payload is trusted, so — unlike the 401/413 paths — its
     * contents are safe to record for the setup page's delivery log. Any
     * exception (malformed JSON, a downstream handler failure) is caught here
     * so the endpoint never surfaces an error back to GitHub; only the
     * exception's class name is recorded, never its message or the payload.
     */
    private void dispatch(byte[] rawBody, String eventType) {
        String fullName = null;
        String action = null;
        try {
            JsonNode payload = objectMapper.readTree(rawBody);
            fullName = payload.path("repository").path("full_name").asText(null);

            WatchedRepo repo = findWatchedRepo(fullName);
            if (repo == null) {
                deliveryLog.record(eventType, payload.path("action").asText(null), repoLabel(fullName),
                        "ignored", "repo not watched");
                return;
            }
            lastEventAt.put(repo.fullName(), Instant.now());

            if (!ISSUES_EVENT.equals(eventType)) {
                deliveryLog.record(eventType, payload.path("action").asText(null), repo.fullName(),
                        "ignored", "non-issues event");
                return;
            }

            action = payload.path("action").asText("");
            if ("labeled".equals(action)) {
                String labelName = payload.path("label").path("name").asText("");
                if (AGENT_READY_LABEL.equals(labelName)) {
                    int issueNumber = payload.path("issue").path("number").asInt();
                    WebhookOutcome outcome = pollingService.evaluateSingleIssueFromWebhook(repo, payload.path("issue"));
                    String outcomeLabel = outcome.name().toLowerCase();
                    deliveryLog.record(eventType, action, repo.fullName(), outcomeLabel,
                            "issue #" + issueNumber + " " + outcomeLabel);
                } else {
                    deliveryLog.record(eventType, action, repo.fullName(), "ignored",
                            "label '" + labelName + "' ignored");
                }
            } else if ("closed".equals(action)) {
                // recheckRepo makes synchronous GitHub API calls inside the webhook
                // request, so a slow recheck can eat into GitHub's 10s delivery timeout.
                // Acceptable at local-first scale (few repos, few blocked issues), and
                // the polling loop reconciles anything a timed-out delivery missed.
                int issueNumber = payload.path("issue").path("number").asInt();
                pollingService.recheckRepo(repo);
                deliveryLog.record(eventType, action, repo.fullName(), "recheck",
                        "issue #" + issueNumber + " closed");
            } else {
                deliveryLog.record(eventType, action, repo.fullName(), "ignored", "unhandled action");
            }
        } catch (Exception e) {
            log.warn("Failed to process GitHub webhook payload: {}", e.getMessage());
            deliveryLog.record(eventType, action, repoLabel(fullName), "error", e.getClass().getSimpleName());
        }
    }

    private static String repoLabel(String fullName) {
        return (fullName == null || fullName.isBlank()) ? "unknown" : fullName;
    }

    private WatchedRepo findWatchedRepo(String fullName) {
        if (fullName == null || fullName.isBlank()) {
            return null;
        }
        String[] parts = fullName.split("/", 2);
        if (parts.length != 2) {
            return null;
        }
        Optional<WatchedRepo> repo = repoRepository.findByOwnerAndName(parts[0], parts[1]);
        return repo.orElse(null);
    }

    public boolean isSecretConfigured() {
        return webhookSecret != null && !webhookSecret.isBlank();
    }

    /** Per-repo last-webhook-event timestamp, for display on the setup page. */
    public Map<String, Instant> getLastEventTimestamps() {
        return lastEventAt;
    }
}

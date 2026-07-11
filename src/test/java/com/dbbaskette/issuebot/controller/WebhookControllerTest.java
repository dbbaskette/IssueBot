package com.dbbaskette.issuebot.controller;

import com.dbbaskette.issuebot.model.WatchedRepo;
import com.dbbaskette.issuebot.repository.WatchedRepoRepository;
import com.dbbaskette.issuebot.security.WebhookSignatureVerifier;
import com.dbbaskette.issuebot.service.polling.IssuePollingService;
import com.dbbaskette.issuebot.service.polling.WebhookOutcome;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class WebhookControllerTest {

    private static final String SECRET = "test-webhook-secret";

    private WatchedRepoRepository repoRepository;
    private IssuePollingService pollingService;
    private WebhookDeliveryLog deliveryLog;
    private WatchedRepo testRepo;

    @BeforeEach
    void setUp() {
        repoRepository = mock(WatchedRepoRepository.class);
        pollingService = mock(IssuePollingService.class);
        deliveryLog = new WebhookDeliveryLog();
        testRepo = new WatchedRepo("acme", "widgets");
        when(repoRepository.findByOwnerAndName("acme", "widgets")).thenReturn(Optional.of(testRepo));
        when(repoRepository.findByOwnerAndName("stranger", "repo")).thenReturn(Optional.empty());
    }

    private WebhookController controllerWithSecret(String secret) {
        return new WebhookController(new WebhookSignatureVerifier(), repoRepository, pollingService, deliveryLog, secret);
    }

    private WebhookDeliveryLog.Delivery onlyDelivery() {
        List<WebhookDeliveryLog.Delivery> deliveries = deliveryLog.recentDeliveries();
        assertEquals(1, deliveries.size());
        return deliveries.get(0);
    }

    private static String sign(byte[] body, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static byte[] issuesPayload(String action, String repoFullName, String labelName) {
        String labelJson = labelName == null ? "" : ",\"label\":{\"name\":\"" + labelName + "\"}";
        String json = "{\"action\":\"" + action + "\","
                + "\"issue\":{\"number\":42,\"title\":\"Test issue\"},"
                + "\"repository\":{\"full_name\":\"" + repoFullName + "\"}"
                + labelJson
                + "}";
        return json.getBytes(StandardCharsets.UTF_8);
    }

    /** Wraps raw body bytes in a servlet request the way the container would deliver them. */
    private static HttpServletRequest request(byte[] body) {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/webhooks/github");
        req.setContentType("application/json");
        req.setContent(body);
        return req;
    }

    @Test
    void returns503WhenSecretBlank() throws Exception {
        WebhookController controller = controllerWithSecret("");
        byte[] body = issuesPayload("labeled", "acme/widgets", "agent-ready");

        ResponseEntity<Void> response = controller.handleWebhook(request(body), "sha256=irrelevant", "issues");

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        verifyNoInteractions(pollingService);
    }

    @Test
    void returns401ForBadSignature() throws Exception {
        WebhookController controller = controllerWithSecret(SECRET);
        byte[] body = issuesPayload("labeled", "acme/widgets", "agent-ready");

        ResponseEntity<Void> response = controller.handleWebhook(request(body), "sha256=deadbeef", "issues");

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        verifyNoInteractions(pollingService);

        WebhookDeliveryLog.Delivery delivery = onlyDelivery();
        assertEquals("bad-signature", delivery.outcome());
        assertEquals("—", delivery.repo());
        assertNull(delivery.event());
        assertNull(delivery.action());
        assertNull(delivery.detail());
        assertEquals(1L, deliveryLog.totalReceived());
        assertEquals(1L, deliveryLog.signatureFailures());
        assertEquals(0L, deliveryLog.actionsTaken());
    }

    @Test
    void returns401WhenSignatureHeaderMissing() throws Exception {
        WebhookController controller = controllerWithSecret(SECRET);
        byte[] body = issuesPayload("labeled", "acme/widgets", "agent-ready");

        ResponseEntity<Void> response = controller.handleWebhook(request(body), null, "issues");

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        verifyNoInteractions(pollingService);
        assertEquals("bad-signature", onlyDelivery().outcome());
    }

    @Test
    void agentReadyLabeledOnWatchedRepoTriggersEvaluation() throws Exception {
        WebhookController controller = controllerWithSecret(SECRET);
        when(pollingService.evaluateSingleIssueFromWebhook(eq(testRepo), any())).thenReturn(WebhookOutcome.STARTED);
        byte[] body = issuesPayload("labeled", "acme/widgets", "agent-ready");
        String sig = sign(body, SECRET);

        ResponseEntity<Void> response = controller.handleWebhook(request(body), sig, "issues");

        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
        verify(pollingService).evaluateSingleIssueFromWebhook(eq(testRepo), any());

        WebhookDeliveryLog.Delivery delivery = onlyDelivery();
        assertEquals("started", delivery.outcome());
        assertEquals("issues", delivery.event());
        assertEquals("labeled", delivery.action());
        assertEquals("acme/widgets", delivery.repo());
        assertEquals("issue #42 started", delivery.detail());
        assertEquals(1L, deliveryLog.totalReceived());
        assertEquals(0L, deliveryLog.signatureFailures());
        assertEquals(1L, deliveryLog.actionsTaken());
    }

    @Test
    void agentReadyLabeled_queuedOutcome_recordedAndCountsAsAction() throws Exception {
        WebhookController controller = controllerWithSecret(SECRET);
        when(pollingService.evaluateSingleIssueFromWebhook(eq(testRepo), any())).thenReturn(WebhookOutcome.QUEUED);
        byte[] body = issuesPayload("labeled", "acme/widgets", "agent-ready");
        String sig = sign(body, SECRET);

        controller.handleWebhook(request(body), sig, "issues");

        assertEquals("queued", onlyDelivery().outcome());
        assertEquals(1L, deliveryLog.actionsTaken());
    }

    @Test
    void agentReadyLabeled_alreadyTrackedOutcome_recordedButNotAnAction() throws Exception {
        WebhookController controller = controllerWithSecret(SECRET);
        when(pollingService.evaluateSingleIssueFromWebhook(eq(testRepo), any())).thenReturn(WebhookOutcome.ALREADY_TRACKED);
        byte[] body = issuesPayload("labeled", "acme/widgets", "agent-ready");
        String sig = sign(body, SECRET);

        controller.handleWebhook(request(body), sig, "issues");

        assertEquals("already_tracked", onlyDelivery().outcome());
        assertEquals(0L, deliveryLog.actionsTaken());
    }

    @Test
    void unwatchedRepoIgnored() throws Exception {
        WebhookController controller = controllerWithSecret(SECRET);
        byte[] body = issuesPayload("labeled", "stranger/repo", "agent-ready");
        String sig = sign(body, SECRET);

        ResponseEntity<Void> response = controller.handleWebhook(request(body), sig, "issues");

        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
        verifyNoInteractions(pollingService);

        WebhookDeliveryLog.Delivery delivery = onlyDelivery();
        assertEquals("ignored", delivery.outcome());
        assertEquals("stranger/repo", delivery.repo());
        assertEquals(0L, deliveryLog.actionsTaken());
    }

    @Test
    void nonAgentReadyLabelIgnored() throws Exception {
        WebhookController controller = controllerWithSecret(SECRET);
        byte[] body = issuesPayload("labeled", "acme/widgets", "bug");
        String sig = sign(body, SECRET);

        controller.handleWebhook(request(body), sig, "issues");

        verifyNoInteractions(pollingService);

        WebhookDeliveryLog.Delivery delivery = onlyDelivery();
        assertEquals("ignored", delivery.outcome());
        assertEquals("acme/widgets", delivery.repo());
        assertEquals("labeled", delivery.action());
    }

    @Test
    void otherActionsIgnored() throws Exception {
        WebhookController controller = controllerWithSecret(SECRET);
        byte[] body = issuesPayload("assigned", "acme/widgets", null);
        String sig = sign(body, SECRET);

        controller.handleWebhook(request(body), sig, "issues");

        verifyNoInteractions(pollingService);

        WebhookDeliveryLog.Delivery delivery = onlyDelivery();
        assertEquals("ignored", delivery.outcome());
        assertEquals("assigned", delivery.action());
    }

    @Test
    void closedEventTriggersRecheck() throws Exception {
        WebhookController controller = controllerWithSecret(SECRET);
        byte[] body = issuesPayload("closed", "acme/widgets", null);
        String sig = sign(body, SECRET);

        ResponseEntity<Void> response = controller.handleWebhook(request(body), sig, "issues");

        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
        verify(pollingService).recheckRepo(testRepo);

        WebhookDeliveryLog.Delivery delivery = onlyDelivery();
        assertEquals("recheck", delivery.outcome());
        assertEquals("acme/widgets", delivery.repo());
        assertEquals("issue #42 closed", delivery.detail());
        assertEquals(1L, deliveryLog.actionsTaken());
    }

    @Test
    void nonIssuesEventTypeIgnored() throws Exception {
        WebhookController controller = controllerWithSecret(SECRET);
        byte[] body = issuesPayload("labeled", "acme/widgets", "agent-ready");
        String sig = sign(body, SECRET);

        ResponseEntity<Void> response = controller.handleWebhook(request(body), sig, "ping");

        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
        verifyNoInteractions(pollingService);

        WebhookDeliveryLog.Delivery delivery = onlyDelivery();
        assertEquals("ignored", delivery.outcome());
        assertEquals("ping", delivery.event());
        assertEquals("acme/widgets", delivery.repo());
    }

    @Test
    void malformedJsonDoesNotThrowAndReturns204() {
        WebhookController controller = controllerWithSecret(SECRET);
        byte[] body = "{not valid json".getBytes(StandardCharsets.UTF_8);
        String sig = sign(body, SECRET);

        ResponseEntity<Void> response = assertDoesNotThrow(() -> controller.handleWebhook(request(body), sig, "issues"));

        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());

        WebhookDeliveryLog.Delivery delivery = onlyDelivery();
        assertEquals("error", delivery.outcome());
        assertEquals("unknown", delivery.repo());
        assertEquals(0L, deliveryLog.actionsTaken());
    }

    @Test
    void evaluationThrowingStillReturns204() {
        WebhookController controller = controllerWithSecret(SECRET);
        byte[] body = issuesPayload("labeled", "acme/widgets", "agent-ready");
        String sig = sign(body, SECRET);
        doThrow(new RuntimeException("boom")).when(pollingService).evaluateSingleIssueFromWebhook(any(), any());

        ResponseEntity<Void> response = assertDoesNotThrow(() -> controller.handleWebhook(request(body), sig, "issues"));

        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());

        WebhookDeliveryLog.Delivery delivery = onlyDelivery();
        assertEquals("error", delivery.outcome());
        assertEquals("RuntimeException", delivery.detail());
        assertEquals(0L, deliveryLog.actionsTaken());
        assertEquals(1L, deliveryLog.totalReceived());
    }

    @Test
    void lastEventTimestampRecordedForWatchedRepo() throws Exception {
        WebhookController controller = controllerWithSecret(SECRET);
        when(pollingService.evaluateSingleIssueFromWebhook(any(), any())).thenReturn(WebhookOutcome.STARTED);
        byte[] body = issuesPayload("labeled", "acme/widgets", "agent-ready");
        String sig = sign(body, SECRET);

        controller.handleWebhook(request(body), sig, "issues");

        assertEquals(1, controller.getLastEventTimestamps().size());
        org.junit.jupiter.api.Assertions.assertTrue(controller.getLastEventTimestamps().containsKey("acme/widgets"));
    }

    @Test
    void secretConfiguredReflectsBlankVsSetState() {
        assertEquals(false, controllerWithSecret("").isSecretConfigured());
        assertEquals(false, controllerWithSecret(null).isSecretConfigured());
        assertEquals(true, controllerWithSecret(SECRET).isSecretConfigured());
    }

    // === Body size limit ===

    @Test
    void oversizedPayload_returns413_withoutVerificationOrEvaluation() throws Exception {
        WebhookSignatureVerifier verifier = mock(WebhookSignatureVerifier.class);
        WebhookController controller = new WebhookController(verifier, repoRepository, pollingService, deliveryLog, SECRET);
        byte[] body = new byte[WebhookController.MAX_BODY_BYTES + 1];

        ResponseEntity<Void> response = controller.handleWebhook(request(body), "sha256=whatever", "issues");

        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, response.getStatusCode());
        verifyNoInteractions(verifier);
        verifyNoInteractions(pollingService);

        WebhookDeliveryLog.Delivery delivery = onlyDelivery();
        assertEquals("oversized", delivery.outcome());
        assertEquals("—", delivery.repo());
        assertNull(delivery.event());
        assertEquals(1L, deliveryLog.totalReceived());
        assertEquals(0L, deliveryLog.actionsTaken());
    }

    @Test
    void oversizedChunkedPayload_noContentLength_returns413() throws Exception {
        // Chunked transfer: no Content-Length header — the bounded read must
        // still cap the body instead of materializing it all.
        WebhookController controller = controllerWithSecret(SECRET);
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/webhooks/github") {
            @Override
            public long getContentLengthLong() {
                return -1L;
            }
        };
        req.setContentType("application/json");
        req.setContent(new byte[WebhookController.MAX_BODY_BYTES + 1]);

        ResponseEntity<Void> response = controller.handleWebhook(req, "sha256=whatever", "issues");

        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, response.getStatusCode());
        verifyNoInteractions(pollingService);
    }

    @Test
    void payloadExactlyAtLimit_isAccepted() throws Exception {
        // Boundary: exactly MAX_BODY_BYTES must pass the size gate (and then fail
        // signature verification with 401, proving it got past the 413 check).
        WebhookController controller = controllerWithSecret(SECRET);
        byte[] body = new byte[WebhookController.MAX_BODY_BYTES];

        ResponseEntity<Void> response = controller.handleWebhook(request(body), "sha256=deadbeef", "issues");

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
    }

    /**
     * End-to-end through Spring's dispatcher: verifies the HttpServletRequest
     * + {@code @RequestHeader} annotations actually bind a real "application/json"
     * POST correctly, which the direct method-call tests above can't catch.
     */
    @Test
    void realHttpPost_withValidSignature_returns204() throws Exception {
        WebhookController controller = controllerWithSecret(SECRET);
        when(pollingService.evaluateSingleIssueFromWebhook(eq(testRepo), any())).thenReturn(WebhookOutcome.STARTED);
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
        byte[] body = issuesPayload("labeled", "acme/widgets", "agent-ready");
        String sig = sign(body, SECRET);

        mockMvc.perform(post("/webhooks/github")
                        .contentType("application/json")
                        .header("X-Hub-Signature-256", sig)
                        .header("X-GitHub-Event", "issues")
                        .content(body))
                .andExpect(status().isNoContent());

        verify(pollingService).evaluateSingleIssueFromWebhook(eq(testRepo), any());
        assertEquals("started", onlyDelivery().outcome());
    }

    @Test
    void realHttpPost_withBadSignature_returns401() throws Exception {
        WebhookController controller = controllerWithSecret(SECRET);
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
        byte[] body = issuesPayload("labeled", "acme/widgets", "agent-ready");

        mockMvc.perform(post("/webhooks/github")
                        .contentType("application/json")
                        .header("X-Hub-Signature-256", "sha256=deadbeef")
                        .header("X-GitHub-Event", "issues")
                        .content(body))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void realHttpPost_oversizedBody_returns413() throws Exception {
        WebhookController controller = controllerWithSecret(SECRET);
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
        byte[] body = new byte[WebhookController.MAX_BODY_BYTES + 1];

        mockMvc.perform(post("/webhooks/github")
                        .contentType("application/json")
                        .header("X-Hub-Signature-256", "sha256=whatever")
                        .header("X-GitHub-Event", "issues")
                        .content(body))
                .andExpect(status().isPayloadTooLarge());

        verifyNoInteractions(pollingService);
    }
}

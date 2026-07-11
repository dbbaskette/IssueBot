package com.dbbaskette.issuebot.controller;

import com.dbbaskette.issuebot.model.WatchedRepo;
import com.dbbaskette.issuebot.repository.WatchedRepoRepository;
import com.dbbaskette.issuebot.security.WebhookSignatureVerifier;
import com.dbbaskette.issuebot.service.polling.IssuePollingService;
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
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class WebhookControllerTest {

    private static final String SECRET = "test-webhook-secret";

    private WatchedRepoRepository repoRepository;
    private IssuePollingService pollingService;
    private WatchedRepo testRepo;

    @BeforeEach
    void setUp() {
        repoRepository = mock(WatchedRepoRepository.class);
        pollingService = mock(IssuePollingService.class);
        testRepo = new WatchedRepo("acme", "widgets");
        when(repoRepository.findByOwnerAndName("acme", "widgets")).thenReturn(Optional.of(testRepo));
        when(repoRepository.findByOwnerAndName("stranger", "repo")).thenReturn(Optional.empty());
    }

    private WebhookController controllerWithSecret(String secret) {
        return new WebhookController(new WebhookSignatureVerifier(), repoRepository, pollingService, secret);
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
    }

    @Test
    void returns401WhenSignatureHeaderMissing() throws Exception {
        WebhookController controller = controllerWithSecret(SECRET);
        byte[] body = issuesPayload("labeled", "acme/widgets", "agent-ready");

        ResponseEntity<Void> response = controller.handleWebhook(request(body), null, "issues");

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        verifyNoInteractions(pollingService);
    }

    @Test
    void agentReadyLabeledOnWatchedRepoTriggersEvaluation() throws Exception {
        WebhookController controller = controllerWithSecret(SECRET);
        byte[] body = issuesPayload("labeled", "acme/widgets", "agent-ready");
        String sig = sign(body, SECRET);

        ResponseEntity<Void> response = controller.handleWebhook(request(body), sig, "issues");

        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
        verify(pollingService).evaluateSingleIssueFromWebhook(eq(testRepo), any());
    }

    @Test
    void unwatchedRepoIgnored() throws Exception {
        WebhookController controller = controllerWithSecret(SECRET);
        byte[] body = issuesPayload("labeled", "stranger/repo", "agent-ready");
        String sig = sign(body, SECRET);

        ResponseEntity<Void> response = controller.handleWebhook(request(body), sig, "issues");

        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
        verifyNoInteractions(pollingService);
    }

    @Test
    void nonAgentReadyLabelIgnored() throws Exception {
        WebhookController controller = controllerWithSecret(SECRET);
        byte[] body = issuesPayload("labeled", "acme/widgets", "bug");
        String sig = sign(body, SECRET);

        controller.handleWebhook(request(body), sig, "issues");

        verifyNoInteractions(pollingService);
    }

    @Test
    void otherActionsIgnored() throws Exception {
        WebhookController controller = controllerWithSecret(SECRET);
        byte[] body = issuesPayload("assigned", "acme/widgets", null);
        String sig = sign(body, SECRET);

        controller.handleWebhook(request(body), sig, "issues");

        verifyNoInteractions(pollingService);
    }

    @Test
    void closedEventTriggersRecheck() throws Exception {
        WebhookController controller = controllerWithSecret(SECRET);
        byte[] body = issuesPayload("closed", "acme/widgets", null);
        String sig = sign(body, SECRET);

        ResponseEntity<Void> response = controller.handleWebhook(request(body), sig, "issues");

        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
        verify(pollingService).recheckRepo(testRepo);
    }

    @Test
    void nonIssuesEventTypeIgnored() throws Exception {
        WebhookController controller = controllerWithSecret(SECRET);
        byte[] body = issuesPayload("labeled", "acme/widgets", "agent-ready");
        String sig = sign(body, SECRET);

        ResponseEntity<Void> response = controller.handleWebhook(request(body), sig, "ping");

        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
        verifyNoInteractions(pollingService);
    }

    @Test
    void malformedJsonDoesNotThrowAndReturns204() {
        WebhookController controller = controllerWithSecret(SECRET);
        byte[] body = "{not valid json".getBytes(StandardCharsets.UTF_8);
        String sig = sign(body, SECRET);

        ResponseEntity<Void> response = assertDoesNotThrow(() -> controller.handleWebhook(request(body), sig, "issues"));

        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
    }

    @Test
    void evaluationThrowingStillReturns204() {
        WebhookController controller = controllerWithSecret(SECRET);
        byte[] body = issuesPayload("labeled", "acme/widgets", "agent-ready");
        String sig = sign(body, SECRET);
        doThrow(new RuntimeException("boom")).when(pollingService).evaluateSingleIssueFromWebhook(any(), any());

        ResponseEntity<Void> response = assertDoesNotThrow(() -> controller.handleWebhook(request(body), sig, "issues"));

        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
    }

    @Test
    void lastEventTimestampRecordedForWatchedRepo() throws Exception {
        WebhookController controller = controllerWithSecret(SECRET);
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
        WebhookController controller = new WebhookController(verifier, repoRepository, pollingService, SECRET);
        byte[] body = new byte[WebhookController.MAX_BODY_BYTES + 1];

        ResponseEntity<Void> response = controller.handleWebhook(request(body), "sha256=whatever", "issues");

        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, response.getStatusCode());
        verifyNoInteractions(verifier);
        verifyNoInteractions(pollingService);
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

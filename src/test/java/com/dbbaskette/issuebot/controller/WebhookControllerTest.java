package com.dbbaskette.issuebot.controller;

import com.dbbaskette.issuebot.model.WatchedRepo;
import com.dbbaskette.issuebot.repository.WatchedRepoRepository;
import com.dbbaskette.issuebot.security.WebhookSignatureVerifier;
import com.dbbaskette.issuebot.service.polling.IssuePollingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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

    @Test
    void returns503WhenSecretBlank() {
        WebhookController controller = controllerWithSecret("");
        byte[] body = issuesPayload("labeled", "acme/widgets", "agent-ready");

        ResponseEntity<Void> response = controller.handleWebhook(body, "sha256=irrelevant", "issues");

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        verifyNoInteractions(pollingService);
    }

    @Test
    void returns401ForBadSignature() {
        WebhookController controller = controllerWithSecret(SECRET);
        byte[] body = issuesPayload("labeled", "acme/widgets", "agent-ready");

        ResponseEntity<Void> response = controller.handleWebhook(body, "sha256=deadbeef", "issues");

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        verifyNoInteractions(pollingService);
    }

    @Test
    void returns401WhenSignatureHeaderMissing() {
        WebhookController controller = controllerWithSecret(SECRET);
        byte[] body = issuesPayload("labeled", "acme/widgets", "agent-ready");

        ResponseEntity<Void> response = controller.handleWebhook(body, null, "issues");

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        verifyNoInteractions(pollingService);
    }

    @Test
    void agentReadyLabeledOnWatchedRepoTriggersEvaluation() {
        WebhookController controller = controllerWithSecret(SECRET);
        byte[] body = issuesPayload("labeled", "acme/widgets", "agent-ready");
        String sig = sign(body, SECRET);

        ResponseEntity<Void> response = controller.handleWebhook(body, sig, "issues");

        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
        verify(pollingService).evaluateSingleIssueFromWebhook(eq(testRepo), any());
    }

    @Test
    void unwatchedRepoIgnored() {
        WebhookController controller = controllerWithSecret(SECRET);
        byte[] body = issuesPayload("labeled", "stranger/repo", "agent-ready");
        String sig = sign(body, SECRET);

        ResponseEntity<Void> response = controller.handleWebhook(body, sig, "issues");

        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
        verifyNoInteractions(pollingService);
    }

    @Test
    void nonAgentReadyLabelIgnored() {
        WebhookController controller = controllerWithSecret(SECRET);
        byte[] body = issuesPayload("labeled", "acme/widgets", "bug");
        String sig = sign(body, SECRET);

        controller.handleWebhook(body, sig, "issues");

        verifyNoInteractions(pollingService);
    }

    @Test
    void otherActionsIgnored() {
        WebhookController controller = controllerWithSecret(SECRET);
        byte[] body = issuesPayload("assigned", "acme/widgets", null);
        String sig = sign(body, SECRET);

        controller.handleWebhook(body, sig, "issues");

        verifyNoInteractions(pollingService);
    }

    @Test
    void closedEventTriggersRecheck() {
        WebhookController controller = controllerWithSecret(SECRET);
        byte[] body = issuesPayload("closed", "acme/widgets", null);
        String sig = sign(body, SECRET);

        ResponseEntity<Void> response = controller.handleWebhook(body, sig, "issues");

        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
        verify(pollingService).recheckRepo(testRepo);
    }

    @Test
    void nonIssuesEventTypeIgnored() {
        WebhookController controller = controllerWithSecret(SECRET);
        byte[] body = issuesPayload("labeled", "acme/widgets", "agent-ready");
        String sig = sign(body, SECRET);

        ResponseEntity<Void> response = controller.handleWebhook(body, sig, "ping");

        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
        verifyNoInteractions(pollingService);
    }

    @Test
    void malformedJsonDoesNotThrowAndReturns204() {
        WebhookController controller = controllerWithSecret(SECRET);
        byte[] body = "{not valid json".getBytes(StandardCharsets.UTF_8);
        String sig = sign(body, SECRET);

        ResponseEntity<Void> response = assertDoesNotThrow(() -> controller.handleWebhook(body, sig, "issues"));

        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
    }

    @Test
    void evaluationThrowingStillReturns204() {
        WebhookController controller = controllerWithSecret(SECRET);
        byte[] body = issuesPayload("labeled", "acme/widgets", "agent-ready");
        String sig = sign(body, SECRET);
        doThrow(new RuntimeException("boom")).when(pollingService).evaluateSingleIssueFromWebhook(any(), any());

        ResponseEntity<Void> response = assertDoesNotThrow(() -> controller.handleWebhook(body, sig, "issues"));

        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
    }

    @Test
    void lastEventTimestampRecordedForWatchedRepo() {
        WebhookController controller = controllerWithSecret(SECRET);
        byte[] body = issuesPayload("labeled", "acme/widgets", "agent-ready");
        String sig = sign(body, SECRET);

        controller.handleWebhook(body, sig, "issues");

        assertEquals(1, controller.getLastEventTimestamps().size());
        org.junit.jupiter.api.Assertions.assertTrue(controller.getLastEventTimestamps().containsKey("acme/widgets"));
    }

    @Test
    void secretConfiguredReflectsBlankVsSetState() {
        assertEquals(false, controllerWithSecret("").isSecretConfigured());
        assertEquals(false, controllerWithSecret(null).isSecretConfigured());
        assertEquals(true, controllerWithSecret(SECRET).isSecretConfigured());
    }

    /**
     * End-to-end through Spring's dispatcher: verifies the {@code @RequestBody byte[]}
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
}

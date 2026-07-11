package com.dbbaskette.issuebot.security;

import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebhookSignatureVerifierTest {

    private static final String SECRET = "test-webhook-secret";

    private final WebhookSignatureVerifier verifier = new WebhookSignatureVerifier();

    /** Computes the expected "sha256=<hex>" header value via raw javax.crypto (no shared code with the class under test). */
    private static String sign(byte[] body, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void validSignaturePasses() {
        byte[] body = "{\"hello\":\"world\"}".getBytes(StandardCharsets.UTF_8);
        String signature = sign(body, SECRET);

        assertTrue(verifier.verify(body, signature, SECRET));
    }

    @Test
    void tamperedBodyFails() {
        byte[] body = "{\"hello\":\"world\"}".getBytes(StandardCharsets.UTF_8);
        String signature = sign(body, SECRET);
        byte[] tamperedBody = "{\"hello\":\"there\"}".getBytes(StandardCharsets.UTF_8);

        assertFalse(verifier.verify(tamperedBody, signature, SECRET));
    }

    @Test
    void wrongSecretFails() {
        byte[] body = "{\"hello\":\"world\"}".getBytes(StandardCharsets.UTF_8);
        String signature = sign(body, SECRET);

        assertFalse(verifier.verify(body, signature, "wrong-secret"));
    }

    @Test
    void missingShaPrefixFails() {
        byte[] body = "{\"hello\":\"world\"}".getBytes(StandardCharsets.UTF_8);
        String signature = sign(body, SECRET).replace("sha256=", "");

        assertFalse(verifier.verify(body, signature, SECRET));
    }

    @Test
    void nullBodyFails() {
        assertFalse(verifier.verify(null, "sha256=abc", SECRET));
    }

    @Test
    void nullSignatureHeaderFails() {
        assertFalse(verifier.verify("body".getBytes(StandardCharsets.UTF_8), null, SECRET));
    }

    @Test
    void nullSecretFails() {
        assertFalse(verifier.verify("body".getBytes(StandardCharsets.UTF_8), "sha256=abc", null));
    }

    @Test
    void blankSecretFails() {
        assertFalse(verifier.verify("body".getBytes(StandardCharsets.UTF_8), "sha256=abc", "   "));
    }

    @Test
    void malformedHexInSignatureFails() {
        byte[] body = "{\"hello\":\"world\"}".getBytes(StandardCharsets.UTF_8);

        assertFalse(verifier.verify(body, "sha256=not-hex-at-all!!", SECRET));
    }
}

package com.dbbaskette.issuebot.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LogSanitizerTest {

    @Test
    void redactsGitHubToken() {
        String input = "Using token ghp_ABCDEFghijklmnop1234567890abcdef12345";
        String result = LogSanitizer.sanitize(input);
        assertTrue(result.contains("ghp_***REDACTED***"));
        assertFalse(result.contains("ABCDEFghijklmnop"));
    }

    @Test
    void redactsAnthropicKey() {
        String input = "API key: sk-ant-api03-abcdefgh1234567890";
        String result = LogSanitizer.sanitize(input);
        assertTrue(result.contains("sk-ant-***REDACTED***"));
        assertFalse(result.contains("abcdefgh1234567890"));
    }

    @Test
    void redactsBearerToken() {
        String input = "Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.abc123";
        String result = LogSanitizer.sanitize(input);
        assertTrue(result.contains("Bearer ***REDACTED***"));
        assertFalse(result.contains("eyJhbGciOiJIUzI1NiJ9"));
    }

    @Test
    void nullInput() {
        assertNull(LogSanitizer.sanitize(null));
    }

    @Test
    void noSecrets() {
        String input = "Normal log message with no secrets";
        assertEquals(input, LogSanitizer.sanitize(input));
    }
}

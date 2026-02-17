package com.dbbaskette.issuebot.security;

import java.util.regex.Pattern;

/**
 * Sanitizes log output to redact API keys and tokens.
 */
public final class LogSanitizer {

    private static final Pattern GITHUB_TOKEN = Pattern.compile("(ghp_[A-Za-z0-9_]{36,})");
    private static final Pattern ANTHROPIC_KEY = Pattern.compile("(sk-ant-[A-Za-z0-9_-]{20,})");
    private static final Pattern BEARER_TOKEN = Pattern.compile("(Bearer\\s+)[A-Za-z0-9._-]+");
    private static final Pattern GENERIC_SECRET = Pattern.compile("((?:token|key|secret|password)\\s*[:=]\\s*)\\S+", Pattern.CASE_INSENSITIVE);

    private LogSanitizer() {}

    public static String sanitize(String input) {
        if (input == null) return null;
        String result = input;
        result = GITHUB_TOKEN.matcher(result).replaceAll("ghp_***REDACTED***");
        result = ANTHROPIC_KEY.matcher(result).replaceAll("sk-ant-***REDACTED***");
        result = BEARER_TOKEN.matcher(result).replaceAll("$1***REDACTED***");
        return result;
    }
}

package com.dbbaskette.issuebot.util;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Single source of truth for turning SCREAMING_SNAKE_CASE workflow phase names and event
 * types into human-readable copy (#80). Before this, the issue queue's Phase column and the
 * issue-detail Activity Log printed raw enum-ish strings (e.g. {@code CI_VERIFICATION},
 * {@code PHASE_LOCAL_CHECKS_FAILED}) while the dashboard event feed had its own bespoke
 * Thymeleaf humanization for unmapped event types — inconsistent and unpolished. Every
 * surface that renders a phase or event type should route through here.
 *
 * <p>General rule: split on {@code _}, Title Case each word, except a small set of
 * well-known acronyms/initialisms that stay fully uppercase (CI, PR, API, ID, URL).
 */
public final class Humanize {

    /** Tokens that stay fully uppercase instead of being Title Cased. */
    private static final Set<String> UPPER_TOKENS = Set.of("CI", "PR", "API", "ID", "URL");

    private static final String PHASE_EVENT_PREFIX = "PHASE_";

    private static final Map<String, String> STATUS_LABELS = Map.ofEntries(
            Map.entry("PENDING", "Pending"),
            Map.entry("QUEUED", "Queued"),
            Map.entry("BLOCKED", "Blocked"),
            Map.entry("IN_PROGRESS", "In progress"),
            Map.entry("AWAITING_APPROVAL", "Awaiting approval"),
            Map.entry("COMPLETED", "Completed"),
            Map.entry("FAILED", "Failed"),
            Map.entry("COOLDOWN", "Cooling down"),
            Map.entry("DECOMPOSED", "Decomposed"),
            Map.entry("AWAITING_DECOMPOSITION", "Awaiting split approval"),
            Map.entry("AWAITING_PLAN_APPROVAL", "Awaiting plan approval")
    );

    private Humanize() {
    }

    /**
     * Humanizes a workflow phase value (e.g. {@code CI_VERIFICATION} -&gt; "CI Verification").
     * Null-safe: returns {@code null} for {@code null} input, mirroring the callers' existing
     * null-check-then-render pattern in the templates.
     */
    public static String phase(String rawPhase) {
        return titleCase(rawPhase);
    }

    /**
     * Humanizes an event-log event type (e.g. {@code GUIDANCE_APPLIED} -&gt; "Guidance Applied").
     * Strips a leading {@code PHASE_} prefix first — it's redundant noise once the value is
     * shown as human text (e.g. {@code PHASE_LOCAL_CHECKS_FAILED} -&gt; "Local Checks Failed") —
     * but only when doing so leaves a non-blank remainder, so a value that is just the prefix
     * itself still humanizes to something rather than collapsing to an empty string.
     * Null-safe: returns {@code null} for {@code null} input.
     */
    public static String eventType(String rawEventType) {
        if (rawEventType == null) {
            return null;
        }
        String candidate = rawEventType;
        if (candidate.startsWith(PHASE_EVENT_PREFIX)) {
            String remainder = candidate.substring(PHASE_EVENT_PREFIX.length());
            if (!remainder.isBlank()) {
                candidate = remainder;
            }
        }
        return titleCase(candidate);
    }

    /** Turns internal workflow status names into concise operator-facing labels. */
    public static String status(String rawStatus) {
        if (rawStatus == null) {
            return null;
        }
        String known = STATUS_LABELS.get(rawStatus);
        if (known != null) {
            return known;
        }
        String titled = titleCase(rawStatus);
        return titled.isEmpty() ? titled
                : titled.substring(0, 1) + titled.substring(1).toLowerCase(Locale.ROOT);
    }

    private static String titleCase(String raw) {
        if (raw == null) {
            return null;
        }
        if (raw.isEmpty()) {
            return raw;
        }
        String[] words = raw.split("_");
        StringBuilder result = new StringBuilder();
        for (String word : words) {
            if (word.isEmpty()) {
                continue;
            }
            if (result.length() > 0) {
                result.append(' ');
            }
            String upper = word.toUpperCase(Locale.ROOT);
            if (UPPER_TOKENS.contains(upper)) {
                result.append(upper);
            } else {
                result.append(Character.toUpperCase(word.charAt(0)));
                if (word.length() > 1) {
                    result.append(word.substring(1).toLowerCase(Locale.ROOT));
                }
            }
        }
        return result.toString();
    }
}

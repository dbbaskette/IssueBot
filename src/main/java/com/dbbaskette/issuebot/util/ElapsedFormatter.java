package com.dbbaskette.issuebot.util;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * Formats the time elapsed since a workflow run started (#86 — Now Running strip) into a short
 * human string: "Xm" under an hour, "Xh Ym" once an hour has passed. Precomputed server-side
 * (in {@code DashboardController}) rather than in the template, so the "live" fragment's 10s
 * poll doesn't need Thymeleaf date arithmetic.
 *
 * <p>Takes an explicit reference time rather than reading the clock internally so it stays
 * trivially unit-testable; callers pass {@code LocalDateTime.now()}.
 */
public final class ElapsedFormatter {

    private ElapsedFormatter() {
    }

    /**
     * Null-safe: returns {@code null} when {@code start} is null (issue hasn't recorded a start
     * time — e.g. pre-migration rows). A negative duration (clock skew between reads) clamps to
     * zero rather than rendering a nonsensical negative elapsed time.
     */
    public static String format(LocalDateTime start, LocalDateTime now) {
        if (start == null) {
            return null;
        }
        long totalMinutes = Duration.between(start, now).toMinutes();
        if (totalMinutes < 0) {
            totalMinutes = 0;
        }
        long hours = totalMinutes / 60;
        long minutes = totalMinutes % 60;
        if (hours > 0) {
            return hours + "h " + minutes + "m";
        }
        return minutes + "m";
    }
}

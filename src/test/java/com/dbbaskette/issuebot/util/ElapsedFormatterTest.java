package com.dbbaskette.issuebot.util;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the small "elapsed since start" formatter (#86 — Now Running strip) that
 * turns a start timestamp + reference time into a human "Xm" / "Xh Ym" string, precomputed
 * server-side so the dashboard template never has to do date arithmetic.
 */
class ElapsedFormatterTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 7, 10, 12, 0, 0);

    @Test
    void nullStart_isNullSafe() {
        assertThat(ElapsedFormatter.format(null, NOW)).isNull();
    }

    @Test
    void zeroElapsed_formatsAsZeroMinutes() {
        assertThat(ElapsedFormatter.format(NOW, NOW)).isEqualTo("0m");
    }

    @Test
    void underOneMinute_roundsDownToZeroMinutes() {
        assertThat(ElapsedFormatter.format(NOW.minusSeconds(30), NOW)).isEqualTo("0m");
    }

    @Test
    void fiveMinutes_formatsAsMinutesOnly() {
        assertThat(ElapsedFormatter.format(NOW.minusMinutes(5), NOW)).isEqualTo("5m");
    }

    @Test
    void fiftyNineMinutes_staysMinutesOnly() {
        assertThat(ElapsedFormatter.format(NOW.minusMinutes(59), NOW)).isEqualTo("59m");
    }

    @Test
    void exactlyOneHour_formatsAsHoursAndZeroMinutes() {
        assertThat(ElapsedFormatter.format(NOW.minusMinutes(60), NOW)).isEqualTo("1h 0m");
    }

    @Test
    void ninetyMinutes_formatsAsHourAndMinutes() {
        assertThat(ElapsedFormatter.format(NOW.minusMinutes(90), NOW)).isEqualTo("1h 30m");
    }

    @Test
    void multipleHours_formatsCorrectly() {
        assertThat(ElapsedFormatter.format(NOW.minusMinutes(185), NOW)).isEqualTo("3h 5m");
    }

    @Test
    void clockSkew_negativeElapsedClampsToZero() {
        assertThat(ElapsedFormatter.format(NOW.plusMinutes(5), NOW)).isEqualTo("0m");
    }
}

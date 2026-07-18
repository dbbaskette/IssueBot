package com.dbbaskette.issuebot.controller;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class ReviewScoreResponsiveCssTest {

    @Test
    void longReviewContentWrapsAndNarrowLayoutUsesOneColumnWithoutHorizontalOverflow()
            throws Exception {
        String css;
        try (InputStream stream = getClass().getResourceAsStream("/static/css/style.css")) {
            assertThat(stream).isNotNull();
            css = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertThat(css).containsPattern("(?s)\\.review-score-summary \\{[^}]*overflow-wrap: anywhere;")
                .contains("@media (max-width: 700px)")
                .contains(".review-dimension-grid { grid-template-columns: minmax(0, 1fr); }")
                .contains(".review-attempt-selector { overflow-wrap: anywhere; }");
    }
}

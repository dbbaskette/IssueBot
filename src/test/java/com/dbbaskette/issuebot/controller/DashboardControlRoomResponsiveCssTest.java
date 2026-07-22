package com.dbbaskette.issuebot.controller;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class DashboardControlRoomResponsiveCssTest {

    @Test
    void statusBadgesAllowArbitrarilyLongPhaseLabelsToWrapInsideCards() throws IOException {
        String rule = ruleFor(".control-card-header .status");

        assertThat(rule).contains(
                "max-width: 100%;",
                "min-width: 0;",
                "white-space: normal;",
                "overflow-wrap: anywhere;");
    }

    @Test
    void runMetadataFlexChildrenAllowArbitrarilyLongModelLabelsToWrapInsideCards() throws IOException {
        String rule = ruleFor(".control-room-run-body .running-meta > span");

        assertThat(rule).contains(
                "max-width: 100%;",
                "min-width: 0;",
                "white-space: normal;",
                "overflow-wrap: anywhere;");
    }

    private String ruleFor(String selector) throws IOException {
        String css;
        try (InputStream stream = getClass().getResourceAsStream("/static/css/style.css")) {
            assertThat(stream).isNotNull();
            css = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        Matcher matcher = Pattern.compile(Pattern.quote(selector) + "\\s*\\{([^}]*)}", Pattern.DOTALL)
                .matcher(css);
        assertThat(matcher.find()).as("CSS rule for %s", selector).isTrue();
        return matcher.group(1);
    }
}

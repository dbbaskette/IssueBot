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
                "flex: 0 0 auto;",
                "max-width: 100%;",
                "min-width: 0;",
                "white-space: normal;",
                "overflow-wrap: anywhere;");
        assertThat(ruleFor(".control-card-header")).contains("flex-wrap: wrap;");
        assertThat(ruleFor(".control-card-identity")).contains("flex: 0 1 auto;", "width: 100%;");
    }

    @Test
    void mobileRepositoryWorkflowStacksLabelAndValues() throws IOException {
        assertThat(ruleFor(".repos-table td.repo-workflow-summary")).contains(
                "flex-direction: column;", "align-items: flex-start;", "min-width: 0;");
    }

    @Test
    void secondaryDisclosureTitlesAlignLeftWithTrailingExpandHint() throws IOException {
        assertThat(ruleFor(".secondary-section > .panel-header")).contains("justify-content: flex-start;");
        assertThat(ruleFor(".secondary-section > .panel-header::after")).contains("margin-left: auto;");
    }

    @Test
    void queueIdsStayWholeAndAllFilterControlsUseSharedHeight() throws IOException {
        assertThat(ruleFor(".queue-number-cell")).contains("white-space: nowrap;", "min-width: 5rem;", "overflow-wrap: normal;");
        assertThat(ruleFor(".filter-bar input")).contains("min-height: var(--control-height);");
        assertThat(ruleFor(".view-chip")).contains("min-height: var(--control-height);");
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
        StringBuilder rules = new StringBuilder();
        while (matcher.find()) rules.append(matcher.group(1));
        assertThat(rules).as("CSS rules for %s", selector).isNotEmpty();
        return rules.toString();
    }
}

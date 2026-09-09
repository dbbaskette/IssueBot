package com.dbbaskette.issuebot.controller;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class DecisionWorkflowCssTest {
    private String styles() throws IOException {
        try (var input = getClass().getResourceAsStream("/static/css/style.css")) {
            assertThat(input).isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private String declarationBlock(String css, String selector) {
        int selectorStart = css.indexOf(selector);
        assertThat(selectorStart).as("selector %s", selector).isGreaterThanOrEqualTo(0);
        int open = css.indexOf('{', selectorStart);
        int close = css.indexOf('}', open);
        assertThat(open).isGreaterThan(selectorStart);
        assertThat(close).isGreaterThan(open);
        return css.substring(selectorStart, close + 1);
    }

    private String mediaBlockContaining(String css, String query, String declaration) {
        int searchFrom = 0;
        while (true) {
            int start = css.indexOf(query, searchFrom);
            assertThat(start).as("media query %s containing %s", query, declaration).isGreaterThanOrEqualTo(0);
            int open = css.indexOf('{', start);
            int depth = 0;
            for (int i = open; i < css.length(); i++) {
                if (css.charAt(i) == '{') depth++;
                if (css.charAt(i) == '}' && --depth == 0) {
                    String block = css.substring(start, i + 1);
                    if (block.contains(declaration)) return block;
                    searchFrom = i + 1;
                    break;
                }
            }
        }
    }

    @Test
    void decisionIsElevatedAndNestedPanelsAreFlattened() throws IOException {
        String css = styles();
        assertThat(declarationBlock(css, ".issue-decision {")).contains("box-shadow: var(--decision-shadow);");
        assertThat(declarationBlock(css, ".issue-decision .panel {")).contains(
                "border: 0;", "border-radius: 0;", "box-shadow: none;");
        assertThat(declarationBlock(css, ".issue-decision:has(.recovery-card) .issue-decision__summary {")).contains(
                "border-left-color: var(--danger);");
        assertThat(declarationBlock(css, ".issue-decision__summary.next-action--action,"))
                .contains(".issue-decision__summary.next-action--waiting", "border-left-color: var(--warn);");
    }

    @Test
    void mobileProcessingControlsWrapWithoutCompetingWithState() throws IOException {
        assertThat(mediaBlockContaining(styles(), "@media (max-width: 768px)",
                ".processing-rail { flex-wrap: wrap; gap: .5rem; }")).contains(
                ".processing-rail { flex-wrap: wrap; gap: .5rem; }",
                ".processing-rail-state { flex: 1 0 100%; }",
                ".processing-rail > .flex { flex-wrap: wrap; width: 100%; }");
    }

    @Test
    void workflowSupportsKeyboardFocusHiddenDisclosuresAndConnectedMobileRail() throws IOException {
        String css = styles();
        assertThat(declarationBlock(css, ".workflow-policy-option:focus-within, .workflow-stage-row:focus-within"))
                .contains("outline: 2px solid var(--accent);", "outline-offset: 2px;");
        assertThat(declarationBlock(css, ".repository-workflow-editor .workflow-policy-option,"))
                .contains(".repository-workflow-editor .workflow-stage-row", "text-transform: none;", "letter-spacing: 0;");
        assertThat(declarationBlock(css, ".workflow-checkpoints[hidden], .workflow-existing-settings[hidden]"))
                .contains("display: none;");
        assertThat(mediaBlockContaining(css, "@media (max-width: 768px)",
                ".workflow-stage-list { grid-template-columns: 1fr; }"))
                .contains(".workflow-stage-list { grid-template-columns: 1fr; }");
        assertThat(mediaBlockContaining(css, "@media (prefers-reduced-motion: reduce)",
                "* { transition: none !important; animation: none !important; }"))
                .contains("* { transition: none !important; animation: none !important; }");
        assertThat(declarationBlock(css,
                ".page-header, .metrics-grid, .panel:nth-child(n), .metric-card:nth-child(n), .data-table,"))
                .contains(".stage--current .workflow-stage-icon", "animation: none;");
    }
}

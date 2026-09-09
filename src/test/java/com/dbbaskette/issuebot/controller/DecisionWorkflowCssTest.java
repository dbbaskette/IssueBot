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

    @Test
    void decisionIsElevatedAndNestedPanelsAreFlattened() throws IOException {
        assertThat(styles()).contains("box-shadow: var(--decision-shadow);",
                ".issue-decision .panel {\n    border: 0;\n    border-radius: 0;\n    box-shadow: none;",
                ".issue-decision:has(.recovery-card) .issue-decision__summary",
                ".issue-decision__summary.next-action--waiting");
    }

    @Test
    void mobileProcessingControlsWrapWithoutCompetingWithState() throws IOException {
        assertThat(styles()).contains(".processing-rail { flex-wrap: wrap; gap: .5rem; }",
                ".processing-rail-state { flex: 1 0 100%; }",
                ".processing-rail > .flex { flex-wrap: wrap; width: 100%; }");
    }

    @Test
    void workflowSupportsKeyboardFocusHiddenDisclosuresAndConnectedMobileRail() throws IOException {
        assertThat(styles()).contains(".workflow-policy-option:focus-within, .workflow-stage-row:focus-within",
                ".repository-workflow-editor .workflow-stage-row { text-transform: none; letter-spacing: 0; }",
                ".workflow-checkpoints[hidden], .workflow-existing-settings[hidden] { display: none; }",
                ".workflow-stage-list { grid-template-columns: 1fr; }",
                "@media (prefers-reduced-motion: reduce)",
                ".stage--current .workflow-stage-icon { animation: none; }");
    }
}

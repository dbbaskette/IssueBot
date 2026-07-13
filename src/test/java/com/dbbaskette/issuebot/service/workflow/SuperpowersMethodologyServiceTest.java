package com.dbbaskette.issuebot.service.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the superpowers-methodology prompts. The two methodology blocks are the
 * substance of the feature, so they're pinned here: the planning block must carry the
 * brainstorming/writing-plans design-then-plan structure, and the implementation block must
 * carry the TDD discipline AND the explicit "finish with committed code, not a design doc"
 * guard — that guard is what prevents the empty-commit failure the uncontrolled superpowers
 * hook caused. {@code buildPlanningPrompt} touches no dependencies, so a null-wired instance
 * is enough.
 */
class SuperpowersMethodologyServiceTest {

    private final SuperpowersMethodologyService service =
            new SuperpowersMethodologyService(null, null, null, null);

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void planningPrompt_carriesTheDesignThenPlanMethodologyAndTheIssue() {
        ObjectNode issue = mapper.createObjectNode();
        issue.put("title", "Add a built-in tool library");
        issue.put("body", "Provide shell, todo, and web-fetch tools.");

        String prompt = service.buildPlanningPrompt(issue);

        // Methodology: design/spec first, then a plan — and NO code in this phase.
        assertThat(prompt).contains("DESIGN SPEC and IMPLEMENTATION PLAN");
        assertThat(prompt).contains("Make NO code changes");
        assertThat(prompt).contains("brainstorming + writing-plans");
        assertThat(prompt).contains("test-driven development");
        // The issue is appended verbatim.
        assertThat(prompt).contains("Add a built-in tool library");
        assertThat(prompt).contains("Provide shell, todo, and web-fetch tools.");
    }

    @Test
    void planningPrompt_survivesAMissingBody() {
        ObjectNode issue = mapper.createObjectNode();
        issue.put("title", "Terse issue");

        String prompt = service.buildPlanningPrompt(issue);

        assertThat(prompt).contains("Terse issue");
        assertThat(prompt).contains("No description");
    }

    @Test
    void implementationMethodology_enforcesTddAndForbidsStoppingAtADoc() {
        String m = SuperpowersMethodologyService.IMPLEMENTATION_METHODOLOGY;

        assertThat(m).contains("test-driven development");
        assertThat(m).contains("write the test FIRST");
        assertThat(m).contains("watch it fail");
        // The anti-empty-commit guard — the crux of pairing the methodology with automation.
        assertThat(m).contains("finish with real, committed CODE changes");
        assertThat(m).contains("do NOT create spec/design/plan documents in the repo");
    }
}

package com.dbbaskette.issuebot.service.prompt;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import static org.assertj.core.api.Assertions.assertThat;

class PromptGuidanceTest {
    @Test
    void configuredVerificationPreservesExactCommandsAndTheirOrder() {
        String prompt = PromptGuidance.configuredVerification(java.util.List.of(
                "./mvnw verify -Poffline", "node --test 'test/*.js'"));
        assertThat(prompt).containsSubsequence("./mvnw verify -Poffline", "node --test 'test/*.js'")
                .contains("You own testing", "IssueBot does not execute or rerun local commands",
                        "Operator-suggested verification commands", "actual results");
    }

    @Test
    void noConfiguredCommandsDoesNotPromiseAutomaticTests() {
        assertThat(PromptGuidance.configuredVerification(java.util.List.of()))
                .contains("discover appropriate checks yourself")
                .doesNotContain("IssueBot will run these");
    }

    @Test
    void bundlesShareCommonAndFrontendResourcesButOnlyTheirOwnRole() throws Exception {
        for (var stage : PromptGuidance.Stage.values()) {
            String bundle = PromptGuidance.forStage(stage);
            for (String shared : new String[]{"common", "frontend"}) {
                try (var input = getClass().getResourceAsStream("/prompts/guidance/" + shared + ".md")) {
                    assertThat(input).isNotNull();
                    String resource = new String(input.readAllBytes(), StandardCharsets.UTF_8).strip();
                    assertThat(bundle).containsOnlyOnce(resource);
                }
            }
            for (var other : PromptGuidance.Stage.values()) {
                try (var input = getClass().getResourceAsStream("/prompts/guidance/"
                        + other.name().toLowerCase(java.util.Locale.ROOT) + ".md")) {
                    assertThat(input).isNotNull();
                    String resource = new String(input.readAllBytes(), StandardCharsets.UTF_8).strip();
                    if (stage == other) assertThat(bundle).containsOnlyOnce(resource);
                    else assertThat(bundle).doesNotContain(resource);
                }
            }
        }
    }
}

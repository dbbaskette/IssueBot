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
                .contains("not an instruction to execute them now")
                .contains("do not bypass IssueBot's configured gate");
    }

    @Test
    void noConfiguredCommandsDoesNotPromiseAutomaticTests() {
        assertThat(PromptGuidance.configuredVerification(java.util.List.of()))
                .contains("No local verification commands are configured")
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

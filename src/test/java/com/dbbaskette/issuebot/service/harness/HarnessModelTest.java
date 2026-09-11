package com.dbbaskette.issuebot.service.harness;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HarnessModelTest {

    @Test
    void rejectsReasoningThatModelDoesNotSupport() {
        var model = new HarnessModel("test", "Test", "", "high", List.of("low", "high"));

        assertThatCode(() -> model.validateReasoning("high")).doesNotThrowAnyException();
        assertThatThrownBy(() -> model.validateReasoning("medium"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void resolvesBlankReasoningToTheModelDefault() {
        var model = new HarnessModel("test", "Test", "", "high", List.of("low", "high"));

        assertThat(model.resolveReasoning(" ")).isEqualTo("high");
        assertThat(model.resolveReasoning("low")).isEqualTo("low");
        assertThat(model.reasoningLevelsCsv()).isEqualTo("low,high");
    }

    @Test
    void preservesAnImmutableSupportedReasoningCatalog() {
        var requestedLevels = new ArrayList<>(List.of("low", "high"));
        var model = new HarnessModel("test", "Test", "", "high", requestedLevels);
        requestedLevels.clear();

        assertThat(model.supportedReasoningLevels()).containsExactly("low", "high");
        assertThatUnsupportedMutation(model.supportedReasoningLevels());
    }

    @Test
    void rejectsAModelWhoseDefaultReasoningIsNotSupported() {
        assertThatIllegalArgumentException().isThrownBy(
                () -> new HarnessModel("test", "Test", "", "medium", List.of("low", "high")));
    }

    private void assertThatUnsupportedMutation(List<String> levels) {
        assertThatThrownBy(() -> levels.add("medium"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}

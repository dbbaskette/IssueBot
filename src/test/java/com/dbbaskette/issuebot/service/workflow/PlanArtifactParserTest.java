package com.dbbaskette.issuebot.service.workflow;

import com.dbbaskette.issuebot.service.workflow.PlanArtifactParser.InvalidPlanningArtifactException;
import com.dbbaskette.issuebot.service.workflow.PlanArtifactParser.PlanningArtifact;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlanArtifactParserTest {

    private final PlanArtifactParser parser = new PlanArtifactParser();

    @Test
    void parsesExactOrderedSections() {
        PlanningArtifact artifact = parser.parse("""
                # Design Spec
                A focused design.
                # Implementation Plan
                1. Write the failing test.
                """);

        assertThat(artifact.designSpec()).isEqualTo("A focused design.");
        assertThat(artifact.implementationPlan()).startsWith("1. Write");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "preface\n# Design Spec\nspec\n# Implementation Plan\nplan",
            "# Implementation Plan\nplan\n# Design Spec\nspec",
            "# Design Spec\n\n# Implementation Plan\nplan",
            "# Design Spec\nspec"
    })
    void rejectsAnythingOutsideTheExactContract(String output) {
        assertThatThrownBy(() -> parser.parse(output))
                .isInstanceOf(InvalidPlanningArtifactException.class);
    }

    @Test
    void rejectsOversizeInsteadOfTruncating() {
        String output = "# Design Spec\n" + "s".repeat(20_001)
                + "\n# Implementation Plan\nplan";

        assertThatThrownBy(() -> parser.parse(output))
                .hasMessageContaining("20,000");
    }

    @Test
    void rejectsDuplicateImplementationPlanHeadingInPlanBody() {
        String output = "# Design Spec\ns\n# Implementation Plan\np\n# Implementation Plan\np2";

        assertThatThrownBy(() -> parser.parse(output))
                .isInstanceOf(InvalidPlanningArtifactException.class)
                .hasMessageContaining("duplicate");
    }

    @Test
    void rejectsDuplicateDesignSpecHeadingInSpecBody() {
        String output = "# Design Spec\ns\n# Design Spec\ns2\n# Implementation Plan\np";

        assertThatThrownBy(() -> parser.parse(output))
                .isInstanceOf(InvalidPlanningArtifactException.class)
                .hasMessageContaining("duplicate");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "# Design Spec\nspec\n# Unexpected Section\nextra\n# Implementation Plan\nplan",
            "# Design Spec\nspec\n# Implementation Plan\nplan\n# Unexpected Section\nextra"
    })
    void rejectsAnyAdditionalTopLevelHeading(String output) {
        assertThatThrownBy(() -> parser.parse(output))
                .isInstanceOf(InvalidPlanningArtifactException.class)
                .hasMessageContaining("top-level");
    }

    @Test
    void allowsSecondLevelSubsectionsInsideBothArtifacts() {
        PlanningArtifact artifact = parser.parse("""
                # Design Spec
                ## Architecture
                A focused design.
                # Implementation Plan
                ## Task 1
                Write the failing test.
                """);

        assertThat(artifact.designSpec()).contains("## Architecture");
        assertThat(artifact.implementationPlan()).contains("## Task 1");
    }
}

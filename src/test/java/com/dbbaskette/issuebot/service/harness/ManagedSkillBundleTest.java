package com.dbbaskette.issuebot.service.harness;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import static org.assertj.core.api.Assertions.*;

class ManagedSkillBundleTest {
    private byte[] resource(String name) {
        try (var input = getClass().getResourceAsStream("/managed-skills/superpowers/" + name)) {
            return input == null ? null : input.readAllBytes();
        } catch (IOException error) { throw new IllegalStateException(error); }
    }

    @Test void validatesBundledProvenanceAndExcludesUtility() {
        var bundle = new ManagedSkillBundle(this::resource);
        assertThat(bundle.identity().version()).isEqualTo("6.3.0-custom.1");
        assertThat(bundle.identity().commit()).matches("[0-9a-f]{40}");
        assertThat(bundle.project(HarnessRole.ANALYSIS_CLASSIFICATION, "classify")).isEqualTo("classify");
    }

    @Test void projectsOnlyTheSelectedRoleAndPreservesTask() {
        var bundle = new ManagedSkillBundle(this::resource);
        assertThat(bundle.project(HarnessRole.DESIGN_PLANNING, "exact response contract"))
                .contains("# Implementation plans").doesNotContain("# Execute the approved plan")
                .endsWith("exact response contract");
        assertThat(bundle.project(HarnessRole.IMPLEMENTATION, "implement"))
                .contains("# Execute the approved plan").doesNotContain("# Independent code review brief");
        assertThat(bundle.project(HarnessRole.FINAL_REVIEW, "JSON only"))
                .contains("# Independent code review brief", "do not delegate another review")
                .doesNotContain("# Implementation plans").endsWith("JSON only");
        assertThat(bundle.project(HarnessRole.DEBUGGING_CORRECTIONS, "repair"))
                .contains("# Diagnose before changing behavior");
        assertThat(bundle.project(HarnessRole.TASK_REVIEW, "review"))
                .contains("# Independent code review brief");
    }

    @Test void rejectsMissingOrChangedResourcesIncludingLicense() {
        for (String name : java.util.List.of("planning.md", "implementation.md", "debugging.md", "review.md", "LICENSE")) {
            assertThatThrownBy(() -> new ManagedSkillBundle(file -> file.equals(name) ? null : resource(file)))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining(name);
            assertThatThrownBy(() -> new ManagedSkillBundle(file -> file.equals(name) ? new byte[]{1} : resource(file)))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining(name);
        }
    }

    @Test void rejectsModifiedManifestEvenIfResourceHashesCouldBeReplaced() {
        assertThatThrownBy(() -> new ManagedSkillBundle(file -> file.equals("manifest.properties")
                ? "schema=1\n".getBytes(java.nio.charset.StandardCharsets.UTF_8) : resource(file)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("manifest integrity");
    }
}

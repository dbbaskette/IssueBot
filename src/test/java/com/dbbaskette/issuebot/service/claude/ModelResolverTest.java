package com.dbbaskette.issuebot.service.claude;

import com.dbbaskette.issuebot.model.*;
import com.dbbaskette.issuebot.service.harness.HarnessSelectionFixture;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ModelResolverTest {
    final HarnessSelectionFixture fixture = new HarnessSelectionFixture();
    final ModelResolver resolver = new ModelResolver(fixture.properties, fixture.selections);
    final WatchedRepo repo = new WatchedRepo("owner", "repo");
    final TrackedIssue issue = new TrackedIssue(repo, 1, "issue");

    @Test void globalDefaultsUseTheSelectedHarnessCatalog() {
        assertThat(resolver.implementationModel(issue)).isEqualTo("claude-opus-4-8");
        assertThat(resolver.reviewModel(issue)).isEqualTo("claude-sonnet-5");
        assertThat(resolver.utilityModel()).isEqualTo("claude-haiku-4-5");
        fixture.properties.setAgentProvider("CODEX");
        assertThat(resolver.implementationModel(issue)).isEqualTo("gpt-5.6-sol");
        assertThat(resolver.reviewModel(issue)).isEqualTo("gpt-5.6-terra");
        assertThat(resolver.utilityModel()).isEqualTo("gpt-5.6-luna");
    }

    @Test void issueOverrideBeatsCompatibleRepositoryThenGlobalDefault() {
        repo.setImplementationModel("claude-opus-4-6");
        assertThat(resolver.implementationModel(issue)).isEqualTo("claude-opus-4-6");
        issue.setImplModelOverride("claude-sonnet-5");
        assertThat(resolver.implementationModel(issue)).isEqualTo("claude-sonnet-5");
        issue.setImplModelOverride(" ");
        assertThat(resolver.implementationModel(issue)).isEqualTo("claude-opus-4-6");
        repo.setImplementationModel("");
        assertThat(resolver.implementationModel(issue)).isEqualTo("claude-opus-4-8");
    }

    @Test void incompatibleRepositoryTupleDoesNotLeakAcrossHarnesses() {
        repo.setImplementationModel("gpt-6-astra");
        repo.setImplementationReasoningEffort("ultra");
        assertThat(resolver.implementationModel(issue, "claude")).isEqualTo("claude-opus-4-8");
        assertThat(fixture.selections.forStage(issue, "claude", WorkflowStage.IMPLEMENTATION).reasoningLevel()).isEqualTo("high");
    }

    @Test void explicitUnknownOrCrossHarnessIssueOverrideNeverFallsBack() {
        for (String model : new String[]{"invented", "gpt-6-astra"}) {
            issue.setImplModelOverride(model);
            assertThatThrownBy(() -> resolver.implementationModel(issue, "claude"))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining(model);
        }
        fixture.properties.getClaudeCode().setUtilityModel("invented");
        assertThatThrownBy(resolver::utilityModel).hasMessageContaining("invented");
    }

    @Test void explicitHarnessSurvivesGlobalChangeAndUnknownHarnessFailsClosed() {
        assertThat(resolver.implementationModel(issue, "codex")).isEqualTo("gpt-5.6-sol");
        fixture.properties.setAgentProvider("future_harness");
        assertThatThrownBy(() -> resolver.implementationModel(issue)).hasMessageContaining("future_harness");
        assertThat(resolver.reviewModel(issue, "codex")).isEqualTo("gpt-5.6-terra");
    }

    @Test void unknownRepositoryModelIsRejectedInsteadOfSilentlyUsingGlobalModel() {
        repo.setImplementationModel("invented");
        assertThatThrownBy(() -> resolver.implementationModel(issue)).hasMessageContaining("invented");
    }
}

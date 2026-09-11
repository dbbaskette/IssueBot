package com.dbbaskette.issuebot.service.harness;

import com.dbbaskette.issuebot.config.IssueBotProperties;
import com.dbbaskette.issuebot.model.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;
import org.springframework.boot.context.properties.bind.*;
import org.springframework.boot.context.properties.source.*;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import static org.assertj.core.api.Assertions.*;

class LegacyClaudeReasoningTest {
    static Stream<Arguments> legacySelections() {
        return Stream.of("java", "yaml", "repository", "issue").flatMap(source ->
                Stream.of("implementation", "planning", "review", "utility")
                        .filter(role -> !(source.equals("repository") || source.equals("issue")) || !role.equals("utility"))
                        .flatMap(role -> Stream.of(
                                Arguments.of(source, role, "claude-haiku-4-5", "default"),
                                Arguments.of(source, role, "claude-sonnet-5", "high"),
                                Arguments.of(source, role, "claude-opus-4-8", "high"))));
    }

    // Artificial role defaults break old configurations and persisted overrides when models differ.
    @ParameterizedTest @MethodSource("legacySelections")
    void absentLegacyReasoningUsesExactSelectedModelDefault(String source, String role, String model, String expected) throws Exception {
        var fixture = new HarnessSelectionFixture();
        var properties = fixture.properties;
        var issue = new TrackedIssue(new WatchedRepo("legacy", "models"), 1, "legacy selection");
        boolean review = role.equals("review");
        String propertyRole = role.equals("planning") ? "implementation" : role;
        if (source.equals("java") || source.equals("yaml")) {
            var config = new MapConfigurationPropertySource(Map.of("issuebot.claude-code." + propertyRole + "-model", model));
            List<ConfigurationPropertySource> sources = new ArrayList<>();
            sources.add(config);
            if (source.equals("yaml")) {
                for (var yaml : new YamlPropertySourceLoader().load("defaults", new ClassPathResource("application.yml"))) {
                    ConfigurationPropertySources.from(yaml).forEach(sources::add);
                }
            }
            properties = new Binder(sources).bind("issuebot", Bindable.of(IssueBotProperties.class)).get();
        } else if (source.equals("repository")) {
            if (review) issue.getRepo().setReviewModel(model);
            else issue.getRepo().setImplementationModel(model);
        } else {
            if (review) issue.setReviewModelOverride(model);
            else issue.setImplModelOverride(model);
        }
        var selections = new HarnessSelectionService(fixture.registry, properties, fixture.issues, fixture.stages);
        var actual = new AtomicReference<HarnessSelection>();
        assertThatCode(() -> actual.set(role.equals("utility") ? selections.utility("claude")
                : selections.forStage(issue, "claude", WorkflowStage.valueOf(role.toUpperCase(Locale.ROOT)))))
                .doesNotThrowAnyException();
        assertThat(actual.get()).isEqualTo(new HarnessSelection("claude", model, expected));
        if (!role.equals("utility")) {
            org.mockito.Mockito.when(fixture.issues.findById(1L)).thenReturn(Optional.of(issue));
            assertThat(selections.forExecution("claude", 1L, model, WorkflowStage.valueOf(role.toUpperCase(Locale.ROOT))))
                    .isEqualTo(actual.get());
        }
    }

    static Stream<Arguments> explicitSelections() {
        return Stream.of("global", "repository", "issue").flatMap(source ->
                Stream.of(WorkflowStage.PLANNING, WorkflowStage.IMPLEMENTATION, WorkflowStage.REVIEW)
                        .map(stage -> Arguments.of(source, stage)));
    }

    // Removing implicit defaults must not discard an operator's explicit reasoning choice.
    @ParameterizedTest @MethodSource("explicitSelections")
    void explicitReasoningRemainsExplicitAndValidated(String source, WorkflowStage stage) {
        var fixture = new HarnessSelectionFixture();
        var issue = new TrackedIssue(new WatchedRepo("legacy", "explicit"), 1, "explicit selection");
        boolean review = stage == WorkflowStage.REVIEW;
        if (source.equals("global")) {
            if (review) fixture.properties.getClaudeCode().setReviewReasoningEffort("max");
            else fixture.properties.getClaudeCode().setImplementationReasoningEffort("max");
        } else if (source.equals("repository")) {
            if (review) issue.getRepo().setReviewReasoningEffort("max");
            else issue.getRepo().setImplementationReasoningEffort("max");
        } else {
            if (review) issue.setReviewReasoningEffort("max");
            else issue.setImplementationReasoningEffort("max");
        }
        if (review) issue.setReviewModelOverride("claude-opus-4-8");
        else issue.setImplModelOverride("claude-opus-4-8");
        assertThat(fixture.selections.forStage(issue, "claude", stage).reasoningLevel()).isEqualTo("max");
        if (review) issue.setReviewModelOverride("claude-haiku-4-5");
        else issue.setImplModelOverride("claude-haiku-4-5");
        assertThatThrownBy(() -> fixture.selections.forStage(issue, "claude", stage))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("max");
    }
}

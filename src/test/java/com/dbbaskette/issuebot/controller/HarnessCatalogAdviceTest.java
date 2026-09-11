package com.dbbaskette.issuebot.controller;

import com.dbbaskette.issuebot.service.harness.HarnessSelectionFixture;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;

class HarnessCatalogAdviceTest {
    @Test
    void pickerEscapesAndSelectsEveryRejectedTupleValue() throws Exception {
        var fixture = new HarnessSelectionFixture();
        var advice = new HarnessCatalogAdvice(fixture.registry, new ObjectMapper(), fixture.properties);
        var resolver = new org.thymeleaf.templateresolver.ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        var engine = new org.thymeleaf.spring6.SpringTemplateEngine();
        engine.setTemplateResolver(resolver);
        var context = new org.thymeleaf.context.Context();
        context.setVariable("harnessCatalog", advice.harnessCatalog());
        context.setVariable("harnessCatalogJson", advice.harnessCatalogJson(advice.harnessCatalog()));
        context.setVariable("modelId", "test-model");
        context.setVariable("modelField", "model");
        context.setVariable("reasoningField", "reasoning");
        context.setVariable("harnessField", "harnessId");
        context.setVariable("inherit", false);
        context.setVariable("selectedHarness", "<unknown-harness>");
        context.setVariable("selectedModel", "<unknown-model>");
        context.setVariable("selectedReasoning", "<unknown-effort>");
        String html = engine.process("fragments/reasoning", context);
        String selected = java.util.regex.Pattern.compile("<option[^>]*selected[^>]*>").matcher(html)
                .results().map(java.util.regex.MatchResult::group).collect(java.util.stream.Collectors.joining());
        assertThat(selected).contains("value=\"&lt;unknown-harness&gt;\"", "value=\"&lt;unknown-model&gt;\"",
                "value=\"&lt;unknown-effort&gt;\"");
        assertThat(html).doesNotContain("<unknown-harness>", "<unknown-model>", "<unknown-effort>");
    }

    @Test
    void catalogPublishesAdapterCapabilitiesWithoutProbingAuthentication() throws Exception {
        var fixture = new HarnessSelectionFixture();
        var json = new ObjectMapper();
        var advice = new HarnessCatalogAdvice(fixture.registry, json, fixture.properties);
        var catalog = json.readTree(advice.harnessCatalogJson(advice.harnessCatalog()));
        assertThat(catalog.findValuesAsText("id")).contains("claude", "codex", "claude-opus-4-8", "gpt-6-astra");
        var claude = catalog.get(0);
        assertThat(claude.path("displayName").asText()).isEqualTo("Claude Code");
        assertThat(claude.path("authentication").asText()).isEqualTo("unchecked");
        assertThat(claude.path("capabilities").isObject()).isTrue();
        assertThat(claude.path("models").get(0).path("supportedReasoningLevels").toString()).contains("xhigh");
        assertThat(catalog.get(1).path("models").get(0).path("supportedReasoningLevels").toString()).contains("ultra");
        verifyNoInteractions(fixture.claude, fixture.codex);
    }
}

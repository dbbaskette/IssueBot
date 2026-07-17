package com.dbbaskette.issuebot.service.codex;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CodexModelCatalogTest {

    @Test
    void parsesOnlyVisibleModelsWithoutLargeInstructionPayloads() throws Exception {
        String json = """
                {"models":[
                  {"slug":"gpt-5.6-sol","display_name":"GPT-5.6-Sol","description":"Frontier","visibility":"list","base_instructions":"huge"},
                  {"slug":"hidden-model","display_name":"Hidden","description":"No","visibility":"hide"}
                ]}
                """;

        var models = CodexModelCatalog.parseCatalog(new ObjectMapper(), json);

        assertThat(models).containsExactly(
                new CodexModelCatalog.ModelInfo("gpt-5.6-sol", "GPT-5.6-Sol", "Frontier"));
    }
}

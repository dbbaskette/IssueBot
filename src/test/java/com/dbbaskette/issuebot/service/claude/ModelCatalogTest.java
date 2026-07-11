package com.dbbaskette.issuebot.service.claude;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import static org.assertj.core.api.Assertions.assertThat;

class ModelCatalogTest {

    @Test
    void findsKnownModel() {
        assertThat(ModelCatalog.find("claude-opus-4-8")).isPresent();
        assertThat(ModelCatalog.find("claude-opus-4-8").get().displayName()).isEqualTo("Claude Opus 4.8");
    }

    @Test
    void unknownModelIsEmpty() {
        assertThat(ModelCatalog.find("my-custom-model")).isEmpty();
        assertThat(ModelCatalog.estimateCost("my-custom-model", 1000, 1000)).isEmpty();
    }

    @Test
    void estimatesCostFromPerMTokPricing() {
        // Opus 4.8: $5/MTok in, $25/MTok out → 1M in + 1M out = $30
        assertThat(ModelCatalog.estimateCost("claude-opus-4-8", 1_000_000, 1_000_000))
                .contains(new BigDecimal("30.000000"));
    }
}

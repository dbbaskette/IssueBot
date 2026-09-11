package com.dbbaskette.issuebot.service.claude;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;

/**
 * Curated model list: single source of truth for UI dropdowns and
 * fallback pricing. Pricing is a fallback only — the CLI-reported
 * total_cost_usd is authoritative when present.
 */
public final class ModelCatalog {

    public record ModelInfo(String id, String displayName,
                            double inputPerMTok, double outputPerMTok,
                            String defaultReasoningLevel, List<String> supportedReasoningLevels) {
        public ModelInfo {
            supportedReasoningLevels = List.copyOf(supportedReasoningLevels);
        }

        public boolean supportsEffort() {
            return !supportedReasoningLevels.equals(List.of("default"));
        }
    }

    private static final List<String> EFFORT_LEVELS = List.of("low", "medium", "high", "xhigh", "max");

    public static final List<ModelInfo> MODELS = List.of(
            new ModelInfo("claude-opus-4-8", "Claude Opus 4.8", 5.0, 25.0, "high", EFFORT_LEVELS),
            new ModelInfo("claude-opus-4-6", "Claude Opus 4.6", 5.0, 25.0, "high", EFFORT_LEVELS),
            new ModelInfo("claude-sonnet-5", "Claude Sonnet 5", 3.0, 15.0, "high", EFFORT_LEVELS),
            new ModelInfo("claude-sonnet-4-6", "Claude Sonnet 4.6", 3.0, 15.0, "high", EFFORT_LEVELS),
            new ModelInfo("claude-haiku-4-5", "Claude Haiku 4.5", 1.0, 5.0, "default", List.of("default")));

    private ModelCatalog() {}

    public static Optional<ModelInfo> find(String modelId) {
        if (modelId == null) return Optional.empty();
        return MODELS.stream().filter(m -> m.id().equals(modelId)).findFirst();
    }

    public static Optional<BigDecimal> estimateCost(String modelId, long inputTokens, long outputTokens) {
        return find(modelId).map(m -> BigDecimal.valueOf(inputTokens)
                .multiply(BigDecimal.valueOf(m.inputPerMTok()))
                .divide(BigDecimal.valueOf(1_000_000), 6, RoundingMode.HALF_UP)
                .add(BigDecimal.valueOf(outputTokens)
                        .multiply(BigDecimal.valueOf(m.outputPerMTok()))
                        .divide(BigDecimal.valueOf(1_000_000), 6, RoundingMode.HALF_UP)));
    }
}

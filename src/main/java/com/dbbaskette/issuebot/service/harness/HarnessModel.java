package com.dbbaskette.issuebot.service.harness;

import java.util.List;
import java.util.Objects;

/** An immutable model catalog entry supplied by a coding-harness adapter. */
public record HarnessModel(
        String id,
        String displayName,
        String description,
        String defaultReasoningLevel,
        List<String> supportedReasoningLevels) {

    public HarnessModel {
        requireNonBlank(id, "Model id");
        requireNonBlank(displayName, "Model display name");
        description = Objects.requireNonNullElse(description, "");
        requireNonBlank(defaultReasoningLevel, "Default reasoning level");
        supportedReasoningLevels = List.copyOf(Objects.requireNonNull(
                supportedReasoningLevels, "Supported reasoning levels are required"));
        if (supportedReasoningLevels.isEmpty()) {
            throw new IllegalArgumentException("Supported reasoning levels must not be empty");
        }
        if (supportedReasoningLevels.stream().anyMatch(level -> level == null || level.isBlank())) {
            throw new IllegalArgumentException("Supported reasoning levels must not contain blank values");
        }
        if (!supportedReasoningLevels.contains(defaultReasoningLevel)) {
            throw new IllegalArgumentException("Default reasoning " + defaultReasoningLevel
                    + " is not supported by " + id);
        }
    }

    public String resolveReasoning(String requested) {
        String value = requested == null || requested.isBlank() ? defaultReasoningLevel : requested.trim();
        validateReasoning(value);
        return value;
    }

    public void validateReasoning(String value) {
        if (!supportedReasoningLevels.contains(value)) {
            throw new HarnessSelectionException(HarnessSelectionException.Problem.REASONING,
                    "Reasoning " + value + " is not supported by " + id);
        }
    }

    public String reasoningLevelsCsv() {
        return String.join(",", supportedReasoningLevels);
    }

    private static void requireNonBlank(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
    }
}

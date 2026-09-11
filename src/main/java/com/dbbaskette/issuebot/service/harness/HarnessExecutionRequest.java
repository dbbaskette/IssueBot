package com.dbbaskette.issuebot.service.harness;

import java.nio.file.Path;
import java.util.Objects;

/** Immutable execution input pinned by IssueBot before dispatching a harness. */
public record HarnessExecutionRequest(
        HarnessRole role,
        String prompt,
        Path workingDirectory,
        String model,
        String reasoningLevel,
        String resumeSessionId,
        Long issueId) {

    public HarnessExecutionRequest {
        Objects.requireNonNull(role, "Harness role is required");
        Objects.requireNonNull(prompt, "Harness prompt is required");
        Objects.requireNonNull(workingDirectory, "Harness working directory is required");
        requireNonBlank(model, "Harness model");
        requireNonBlank(reasoningLevel, "Harness reasoning level");
        if (resumeSessionId != null) {
            resumeSessionId = resumeSessionId.trim();
            if (resumeSessionId.isEmpty()) resumeSessionId = null;
        }
    }

    private static void requireNonBlank(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
    }
}

package com.dbbaskette.issuebot.service.harness;

/** A complete model-driven choice; all-null denotes a deterministic stage. */
public record HarnessSelection(String harnessId, String modelId, String reasoningLevel) { }

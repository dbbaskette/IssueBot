package com.dbbaskette.issuebot.service.workflow;

import org.springframework.stereotype.Component;

@Component
public final class PlanArtifactParser {
    static final String SPEC_HEADING = "# Design Spec";
    static final String PLAN_HEADING = "# Implementation Plan";
    static final int MAX_SECTION_CHARS = 20_000;

    public PlanningArtifact parse(String output) {
        if (output == null || output.isBlank()) {
            throw invalid("Planner returned no final output");
        }

        String normalized = output.strip();
        if (!normalized.startsWith(SPEC_HEADING + "\n")) {
            throw invalid("Planner output must begin with # Design Spec");
        }

        int planHeading = normalized.indexOf("\n" + PLAN_HEADING + "\n");
        if (planHeading < 0) {
            throw invalid("Planner output is missing # Implementation Plan");
        }

        String spec = normalized.substring(SPEC_HEADING.length(), planHeading).strip();
        String plan = normalized.substring(planHeading + PLAN_HEADING.length() + 2).strip();
        if (spec.isBlank()) {
            throw invalid("Design Spec is blank");
        }
        if (plan.isBlank()) {
            throw invalid("Implementation Plan is blank");
        }
        if (spec.contains("\n" + PLAN_HEADING)
                || plan.contains("\n" + SPEC_HEADING)
                || plan.contains("\n" + PLAN_HEADING)) {
            throw invalid("Planner output contains duplicate or reordered top-level sections");
        }
        if (spec.length() > MAX_SECTION_CHARS || plan.length() > MAX_SECTION_CHARS) {
            throw invalid("Each planning section must be 20,000 characters or fewer");
        }

        return new PlanningArtifact(spec, plan);
    }

    private InvalidPlanningArtifactException invalid(String message) {
        return new InvalidPlanningArtifactException(message);
    }

    public record PlanningArtifact(String designSpec, String implementationPlan) {
    }

    public static final class InvalidPlanningArtifactException extends IllegalArgumentException {
        public InvalidPlanningArtifactException(String message) {
            super(message);
        }
    }
}

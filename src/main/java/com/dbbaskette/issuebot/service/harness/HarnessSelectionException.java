package com.dbbaskette.issuebot.service.harness;

/** A rejected tuple with an operator message that never includes catalog or request data. */
public final class HarnessSelectionException extends IllegalArgumentException {
    public enum Problem {
        HARNESS("The selected coding harness is unavailable. Choose a supported coding harness and approve this stage."),
        MODEL("The selected model is unavailable in this harness catalog. Choose a listed model and approve this stage."),
        CATALOG("The coding harness model catalog is unavailable. Refresh the catalog, then choose a supported selection and approve this stage."),
        REASONING("The selected reasoning is unsupported by this model. Choose a supported reasoning level and approve this stage."),
        TUPLE("The stage selection is invalid. Choose an explicit harness, model, and supported reasoning level."),
        DETERMINISTIC_STAGE("This stage does not use a model. Refresh the page and approve the stage without a model selection.");

        private final String safeMessage;
        Problem(String safeMessage) { this.safeMessage = safeMessage; }
    }

    private final Problem problem;

    public HarnessSelectionException(Problem problem, String diagnostic) {
        super(diagnostic);
        this.problem = java.util.Objects.requireNonNull(problem);
    }

    public String safeMessage() { return problem.safeMessage; }
}

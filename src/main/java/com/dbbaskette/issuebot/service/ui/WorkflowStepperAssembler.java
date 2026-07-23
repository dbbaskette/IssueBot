package com.dbbaskette.issuebot.service.ui;

import com.dbbaskette.issuebot.model.IssueStatus;
import com.dbbaskette.issuebot.model.TrackedIssue;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Builds the compact, presentation-only workflow shown on issue detail.
 */
public final class WorkflowStepperAssembler {

    public enum StageKey {
        INTAKE("intake", "Intake"),
        PLAN("plan", "Plan"),
        WORK("work", "Work"),
        VERIFY("verify", "Verify"),
        REVIEW("review", "Review"),
        DONE("done", "Done");

        private final String key;
        private final String label;

        StageKey(String key, String label) {
            this.key = key;
            this.label = label;
        }

        public String key() { return key; }
        public String label() { return label; }
    }

    public enum StageState {
        COMPLETED("Completed", "ti-circle-check"),
        CURRENT("Current", "ti-player-play-filled"),
        UPCOMING("Upcoming", "ti-circle"),
        PAUSED("Paused", "ti-player-pause-filled"),
        FAILED("Failed", "ti-alert-triangle-filled");

        private final String text;
        private final String icon;

        StageState(String text, String icon) {
            this.text = text;
            this.icon = icon;
        }

        public String text() { return text; }
        public String icon() { return icon; }
        public String cssName() { return name().toLowerCase(Locale.ROOT); }
    }

    public record Stage(
            String key,
            String label,
            StageState state,
            String stateText,
            String icon,
            String accessibleLabel,
            String detail,
            boolean selected) {}

    public record WorkflowStepper(List<Stage> stages, Stage selectedStage) {
        public WorkflowStepper {
            stages = List.copyOf(stages);
        }
    }

    private record Selection(StageKey key, StageState state, String detail, boolean terminal) {}

    public WorkflowStepper assemble(TrackedIssue issue) {
        Selection selection = selection(issue);
        List<Stage> stages = Arrays.stream(StageKey.values())
                .map(key -> stage(key, selection))
                .toList();
        Stage selected = stages.stream().filter(Stage::selected).findFirst()
                .orElse(stages.getLast());
        return new WorkflowStepper(stages, selected);
    }

    private Stage stage(StageKey key, Selection selection) {
        StageState state;
        boolean selected = !selection.terminal() && key == selection.key();
        if (selection.terminal()) {
            state = StageState.COMPLETED;
        } else if (key.ordinal() < selection.key().ordinal()) {
            state = StageState.COMPLETED;
        } else if (selected) {
            state = selection.state();
        } else {
            state = StageState.UPCOMING;
        }
        String detail = selected || (selection.terminal() && key == StageKey.DONE)
                ? selection.detail() : null;
        return new Stage(key.key(), key.label(), state, state.text(), state.icon(),
                key.label() + ": " + state.text(), detail,
                selected || (selection.terminal() && key == StageKey.DONE));
    }

    private Selection selection(TrackedIssue issue) {
        IssueStatus status = issue.getStatus() == null ? IssueStatus.PENDING : issue.getStatus();
        return switch (status) {
            case PENDING -> current(StageKey.INTAKE, "Waiting to enter the queue");
            case QUEUED -> current(StageKey.INTAKE, "Queued");
            case BLOCKED -> paused(StageKey.INTAKE, "Blocked by dependencies");
            case AWAITING_DECOMPOSITION ->
                    paused(StageKey.INTAKE, "Waiting for decomposition decision");
            case DECOMPOSED -> terminal("Split into child issues");
            case IN_PROGRESS -> current(stageForPhase(issue.getCurrentPhase(), StageKey.WORK),
                    phaseDetail(issue.getCurrentPhase()));
            case AWAITING_PLAN_APPROVAL ->
                    paused(StageKey.PLAN, "Waiting for plan approval");
            case READY_TO_START -> paused(StageKey.PLAN, "Plan approved; waiting to start");
            case AWAITING_APPROVAL -> paused(StageKey.REVIEW, "Waiting for final approval");
            case COOLDOWN -> exceptional(issue, StageState.PAUSED, "Waiting for retry cooldown");
            case FAILED -> exceptional(issue, StageState.FAILED, "Workflow failed");
            case COMPLETED -> terminal("Workflow completed");
            case CANCELLED -> terminal("Decomposition child cancelled");
        };
    }

    private Selection exceptional(TrackedIssue issue, StageState state, String detail) {
        return new Selection(stageForPhase(issue.getCurrentPhase(), StageKey.WORK),
                state, detail, false);
    }

    private Selection current(StageKey key, String detail) {
        return new Selection(key, StageState.CURRENT, detail, false);
    }

    private Selection paused(StageKey key, String detail) {
        return new Selection(key, StageState.PAUSED, detail, false);
    }

    private Selection terminal(String detail) {
        return new Selection(StageKey.DONE, StageState.COMPLETED, detail, true);
    }

    private StageKey stageForPhase(String phase, StageKey fallback) {
        if (phase == null || phase.isBlank()) {
            return fallback;
        }
        return switch (phase.trim().toUpperCase(Locale.ROOT)) {
            case "PLANNING" -> StageKey.PLAN;
            case "SETUP", "IMPLEMENTATION" -> StageKey.WORK;
            case "LOCAL_CHECKS", "CI_VERIFICATION" -> StageKey.VERIFY;
            case "PR_CREATION", "INDEPENDENT_REVIEW", "COMPLETION" -> StageKey.REVIEW;
            default -> fallback;
        };
    }

    private String phaseDetail(String phase) {
        if (phase == null || phase.isBlank()) {
            return "Implementation in progress";
        }
        return switch (phase.trim().toUpperCase(Locale.ROOT)) {
            case "PLANNING" -> "Planning";
            case "SETUP" -> "Preparing the repository";
            case "IMPLEMENTATION" -> "Implementation in progress";
            case "LOCAL_CHECKS" -> "Running local checks";
            case "CI_VERIFICATION" -> "CI checks running";
            case "PR_CREATION" -> "Creating the pull request";
            case "INDEPENDENT_REVIEW" -> "Independent review in progress";
            case "COMPLETION" -> "Finalizing the workflow";
            default -> "Implementation in progress";
        };
    }
}

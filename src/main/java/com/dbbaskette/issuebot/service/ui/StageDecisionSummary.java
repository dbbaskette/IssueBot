package com.dbbaskette.issuebot.service.ui;

import com.dbbaskette.issuebot.model.WorkflowStage;
import com.dbbaskette.issuebot.model.PlanningVersion;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** Decision copy uses the issue's frozen policy, never today's repository settings. */
public final class StageDecisionSummary {
    private StageDecisionSummary() {}

    public static String boundPlanHref(Long issueId, Long artifactId, List<PlanningVersion> versions) {
        if (versions != null) {
            for (PlanningVersion version : versions) {
                if (Objects.equals(version.getId(), artifactId)) {
                    return "/issues/" + issueId + "?planVersion=" + version.getVersionNumber() + "#plan-first";
                }
            }
        }
        return "/issues/" + issueId + "#plan-first";
    }

    public static String describe(WorkflowStage stage, String approvalStages) {
        Set<String> checkpoints = Arrays.stream(
                        (approvalStages == null ? WorkflowStage.ALL : approvalStages).split(","))
                .map(String::trim).collect(Collectors.toSet());
        String starts = "Starts " + stage.name().toLowerCase(Locale.ROOT) + ". ";
        for (WorkflowStage next : WorkflowStage.values()) {
            if (next.ordinal() > stage.ordinal() && checkpoints.contains(next.name())) {
                return starts + "Continues automatically until approval is required for "
                        + next.name().toLowerCase(Locale.ROOT) + ".";
            }
        }
        return starts + "No further approval checkpoints; continues automatically to completion.";
    }
}

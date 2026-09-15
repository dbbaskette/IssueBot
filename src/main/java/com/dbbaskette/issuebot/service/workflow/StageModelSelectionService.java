package com.dbbaskette.issuebot.service.workflow;

import com.dbbaskette.issuebot.config.IssueBotProperties;
import com.dbbaskette.issuebot.model.TrackedIssue;
import com.dbbaskette.issuebot.model.WorkflowStage;
import com.dbbaskette.issuebot.service.harness.*;
import org.springframework.stereotype.Service;

/** Separates new-stage defaults from explicit or persisted stage selections. */
@Service
public class StageModelSelectionService {
    private final IssueBotProperties properties;
    private final HarnessSelectionService selections;

    public StageModelSelectionService(IssueBotProperties properties, HarnessSelectionService selections) {
        this.properties = properties;
        this.selections = selections;
    }

    public HarnessSelection defaults(TrackedIssue issue, WorkflowStage stage) {
        return stage.modelDriven() ? selections.forStage(issue, properties.getAgentProvider(), stage)
                : new HarnessSelection(null, null, null);
    }

    public HarnessSelection resolve(TrackedIssue issue, WorkflowStage stage, String harnessId, String model, String reasoning) {
        if (!stage.modelDriven()) {
            if (harnessId != null || model != null || reasoning != null) {
                throw new HarnessSelectionException(HarnessSelectionException.Problem.DETERMINISTIC_STAGE,
                        stage + " does not accept harness, model, or reasoning selections");
            }
            return new HarnessSelection(null, null, null);
        }
        HarnessSelection selected = selections.resolve(harnessId, model, reasoning);
        if (stage == WorkflowStage.REVIEW && issue.getResolvedImplModel() != null) {
            IndependentReviewPolicy.requireDistinct(issue.getResolvedHarnessId(), issue.getResolvedImplModel(),
                    selected.harnessId(), selected.modelId());
        }
        return selected;
    }

    public void validate(HarnessSelection selection) {
        if (selection.harnessId() == null && selection.modelId() == null && selection.reasoningLevel() == null) return;
        selections.validateReady(selection);
    }

}

package com.dbbaskette.issuebot.service.workflow;

import com.dbbaskette.issuebot.config.IssueBotProperties;
import com.dbbaskette.issuebot.model.TrackedIssue;
import com.dbbaskette.issuebot.model.WorkflowStage;
import com.dbbaskette.issuebot.service.harness.*;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;

/** Separates new-stage defaults from explicit or persisted stage selections. */
@Service
public class StageModelSelectionService {
    private final IssueBotProperties properties;
    private final HarnessSelectionService selections;
    private final CodingHarnessRegistry registry;

    public StageModelSelectionService(IssueBotProperties properties, HarnessSelectionService selections,
                                      CodingHarnessRegistry registry) {
        this.properties = properties;
        this.selections = selections;
        this.registry = registry;
    }

    public HarnessSelection defaults(TrackedIssue issue, WorkflowStage stage) {
        return stage.modelDriven() ? selections.forStage(issue, properties.getAgentProvider(), stage)
                : new HarnessSelection(null, null, null);
    }

    public HarnessSelection resolve(TrackedIssue issue, WorkflowStage stage, String harnessId, String model, String reasoning) {
        if (!stage.modelDriven()) {
            if (harnessId != null || model != null || reasoning != null) {
                throw new IllegalArgumentException(stage + " does not accept harness, model, or reasoning selections");
            }
            return new HarnessSelection(null, null, null);
        }
        return selections.resolve(harnessId, model, reasoning);
    }

    public void validate(HarnessSelection selection) {
        if (selection.harnessId() == null && selection.modelId() == null && selection.reasoningLevel() == null) return;
        selections.validateReady(selection);
    }

    /** Legacy view keys are retained until the capability-driven UI migration. */
    public Map<String, List<String>> modelsByProvider() {
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (CodingHarnessAdapter adapter : registry.adapters()) {
            String key = switch (adapter.id()) {
                case HarnessIds.CLAUDE -> "CLAUDE_CODE";
                case HarnessIds.CODEX -> "CODEX";
                default -> adapter.id();
            };
            result.put(key, adapter.models().stream().map(HarnessModel::id).toList());
        }
        return result;
    }
}

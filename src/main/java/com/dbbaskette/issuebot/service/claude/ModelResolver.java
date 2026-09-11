package com.dbbaskette.issuebot.service.claude;

import com.dbbaskette.issuebot.config.IssueBotProperties;
import com.dbbaskette.issuebot.model.TrackedIssue;
import com.dbbaskette.issuebot.model.WorkflowStage;
import com.dbbaskette.issuebot.service.harness.HarnessSelectionService;
import org.springframework.stereotype.Component;

/** Legacy model-only view over the shared tuple resolver. */
@Component
public class ModelResolver {
    private final IssueBotProperties properties;
    private final HarnessSelectionService selections;

    public ModelResolver(IssueBotProperties properties, HarnessSelectionService selections) {
        this.properties = properties;
        this.selections = selections;
    }

    public String implementationModel(TrackedIssue issue) {
        return implementationModel(issue, properties.getAgentProvider());
    }

    public String implementationModel(TrackedIssue issue, String harnessId) {
        return selections.forStage(issue, harnessId, WorkflowStage.IMPLEMENTATION).modelId();
    }

    public String reviewModel(TrackedIssue issue) {
        return reviewModel(issue, properties.getAgentProvider());
    }

    public String reviewModel(TrackedIssue issue, String harnessId) {
        return selections.forStage(issue, harnessId, WorkflowStage.REVIEW).modelId();
    }

    public String utilityModel() {
        return selections.utility(properties.getAgentProvider()).modelId();
    }
}

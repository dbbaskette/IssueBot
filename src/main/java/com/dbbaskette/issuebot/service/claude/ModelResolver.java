package com.dbbaskette.issuebot.service.claude;

import com.dbbaskette.issuebot.config.IssueBotProperties;
import com.dbbaskette.issuebot.model.TrackedIssue;
import org.springframework.stereotype.Component;

/** Resolves the model for each role: issue override > repo override > global default. */
@Component
public class ModelResolver {

    private final IssueBotProperties properties;

    public ModelResolver(IssueBotProperties properties) {
        this.properties = properties;
    }

    public String implementationModel(TrackedIssue issue) {
        String fromIssue = blankToNull(issue.getImplModelOverride());
        if (isCompatible(fromIssue)) return fromIssue;
        String fromRepo = blankToNull(issue.getRepo().getImplementationModel());
        if (isCompatible(fromRepo)) return fromRepo;
        return properties.getAgentProvider() == IssueBotProperties.AgentProvider.CODEX
                ? properties.getCodexCli().getImplementationModel()
                : properties.getClaudeCode().getImplementationModel();
    }

    public String reviewModel(TrackedIssue issue) {
        String fromIssue = blankToNull(issue.getReviewModelOverride());
        if (isCompatible(fromIssue)) return fromIssue;
        String fromRepo = blankToNull(issue.getRepo().getReviewModel());
        if (isCompatible(fromRepo)) return fromRepo;
        return properties.getAgentProvider() == IssueBotProperties.AgentProvider.CODEX
                ? properties.getCodexCli().getReviewModel()
                : properties.getClaudeCode().getReviewModel();
    }

    public String utilityModel() {
        return properties.getAgentProvider() == IssueBotProperties.AgentProvider.CODEX
                ? properties.getCodexCli().getUtilityModel()
                : properties.getClaudeCode().getUtilityModel();
    }

    private boolean isCompatible(String model) {
        if (model == null) return false;
        if (properties.getAgentProvider() == IssueBotProperties.AgentProvider.CODEX) {
            return !model.startsWith("claude-");
        }
        return !model.startsWith("gpt-") && !model.startsWith("o3") && !model.startsWith("o4");
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}

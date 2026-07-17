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
        return implementationModel(issue, properties.getAgentProvider());
    }

    public String implementationModel(TrackedIssue issue, IssueBotProperties.AgentProvider provider) {
        String fromIssue = blankToNull(issue.getImplModelOverride());
        if (isCompatible(fromIssue, provider)) return fromIssue;
        String fromRepo = blankToNull(issue.getRepo().getImplementationModel());
        if (isCompatible(fromRepo, provider)) return fromRepo;
        return provider == IssueBotProperties.AgentProvider.CODEX
                ? properties.getCodexCli().getImplementationModel()
                : properties.getClaudeCode().getImplementationModel();
    }

    public String reviewModel(TrackedIssue issue) {
        return reviewModel(issue, properties.getAgentProvider());
    }

    public String reviewModel(TrackedIssue issue, IssueBotProperties.AgentProvider provider) {
        String fromIssue = blankToNull(issue.getReviewModelOverride());
        if (isCompatible(fromIssue, provider)) return fromIssue;
        String fromRepo = blankToNull(issue.getRepo().getReviewModel());
        if (isCompatible(fromRepo, provider)) return fromRepo;
        return provider == IssueBotProperties.AgentProvider.CODEX
                ? properties.getCodexCli().getReviewModel()
                : properties.getClaudeCode().getReviewModel();
    }

    public String utilityModel() {
        return properties.getAgentProvider() == IssueBotProperties.AgentProvider.CODEX
                ? properties.getCodexCli().getUtilityModel()
                : properties.getClaudeCode().getUtilityModel();
    }

    private boolean isCompatible(String model, IssueBotProperties.AgentProvider provider) {
        if (model == null) return false;
        if (provider == IssueBotProperties.AgentProvider.CODEX) {
            return !model.startsWith("claude-");
        }
        return !model.startsWith("gpt-") && !model.startsWith("o3") && !model.startsWith("o4");
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}

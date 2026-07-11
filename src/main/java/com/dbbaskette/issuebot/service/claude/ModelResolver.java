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
        if (fromIssue != null) return fromIssue;
        String fromRepo = blankToNull(issue.getRepo().getImplementationModel());
        if (fromRepo != null) return fromRepo;
        return properties.getClaudeCode().getImplementationModel();
    }

    public String reviewModel(TrackedIssue issue) {
        String fromIssue = blankToNull(issue.getReviewModelOverride());
        if (fromIssue != null) return fromIssue;
        String fromRepo = blankToNull(issue.getRepo().getReviewModel());
        if (fromRepo != null) return fromRepo;
        return properties.getClaudeCode().getReviewModel();
    }

    public String utilityModel() {
        return properties.getClaudeCode().getUtilityModel();
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}

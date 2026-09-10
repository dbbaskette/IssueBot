package com.dbbaskette.issuebot.service.codex;

import com.dbbaskette.issuebot.config.IssueBotProperties;
import com.dbbaskette.issuebot.model.StageApproval;
import com.dbbaskette.issuebot.model.WorkflowStage;
import com.dbbaskette.issuebot.repository.StageApprovalRepository;
import com.dbbaskette.issuebot.repository.TrackedIssueRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Resolves persisted reasoning with the same role and model that execution uses. */
@Service
public class ReasoningSelectionService {
    private final IssueBotProperties properties;
    private final CodexModelCatalog models;
    private final TrackedIssueRepository issues;
    private final StageApprovalRepository stages;

    public ReasoningSelectionService(IssueBotProperties properties, CodexModelCatalog models,
            TrackedIssueRepository issues, StageApprovalRepository stages) {
        this.properties = properties;
        this.models = models;
        this.issues = issues;
        this.stages = stages;
    }

    public String validate(String model, String effort) {
        if (effort == null || effort.isBlank()) return null;
        String value = effort.trim();
        if (!CodexModelCatalog.REASONING_LEVELS.contains(value)) {
            throw new IllegalArgumentException("Choose a valid reasoning level");
        }
        if (model != null && !model.isBlank()) {
            if (model.startsWith("claude-")) return null;
            models.models().stream().filter(m -> m.id().equals(model)).findFirst().ifPresent(m -> {
                if (!m.supportedReasoningLevels().contains(value)) {
                    throw new IllegalArgumentException("Reasoning level " + value + " is not supported by " + model);
                }
            });
        }
        return value;
    }

    @Transactional(readOnly = true)
    public String resolve(Long issueId, String model, WorkflowStage stage) {
        boolean review = stage == WorkflowStage.REVIEW;
        String effort = review ? properties.getCodexCli().getReviewReasoningEffort()
                : properties.getCodexCli().getImplementationReasoningEffort();
        if (issueId != null) {
            var issue = issues.findById(issueId).orElseThrow();
            String repoValue = review ? issue.getRepo().getReviewReasoningEffort()
                    : issue.getRepo().getImplementationReasoningEffort();
            String issueValue = review ? issue.getReviewReasoningEffort()
                    : issue.getImplementationReasoningEffort();
            if (repoValue != null && !repoValue.isBlank()) effort = repoValue;
            if (issueValue != null && !issueValue.isBlank()) effort = issueValue;
            var decision = stages.findByIssueIdOrderByIdAsc(issueId).stream()
                    .filter(s -> s.getRunNumber() == issue.getWorkflowRun() && s.getStage() == stage
                            && s.getState() == StageApproval.State.APPROVED
                            && model.equals(s.getModel()))
                    .reduce((a, b) -> b);
            if (decision.isPresent() && decision.get().getReasoningEffort() != null) {
                effort = decision.get().getReasoningEffort();
            }
        }
        // Inherited effort can belong to a different model. Use this model's default
        // when necessary; explicit submissions are rejected by validate instead.
        String chosen = effort;
        return models.models().stream().filter(m -> m.id().equals(model)).findFirst()
                .map(m -> m.supportedReasoningLevels().contains(chosen)
                        ? chosen : m.defaultReasoningLevel()).orElse(chosen);
    }
}

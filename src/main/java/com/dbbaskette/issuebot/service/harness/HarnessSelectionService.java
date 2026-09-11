package com.dbbaskette.issuebot.service.harness;

import com.dbbaskette.issuebot.config.IssueBotProperties;
import com.dbbaskette.issuebot.model.*;
import com.dbbaskette.issuebot.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.Objects;

/** Resolves and validates the harness, model, and reasoning as one indivisible choice. */
@Service
public class HarnessSelectionService {
    private final CodingHarnessRegistry registry;
    private final IssueBotProperties properties;
    private final TrackedIssueRepository issues;
    private final StageApprovalRepository stages;

    public HarnessSelectionService(CodingHarnessRegistry registry, IssueBotProperties properties,
            TrackedIssueRepository issues, StageApprovalRepository stages) {
        this.registry = registry;
        this.properties = properties;
        this.issues = issues;
        this.stages = stages;
    }

    public HarnessSelection resolve(String harnessId, String modelId, String reasoningLevel) {
        CodingHarnessAdapter adapter = requireHarness(harnessId);
        String model = present(modelId) ? modelId.trim() : null;
        HarnessModel selected = adapter.models().stream().filter(candidate -> candidate.id().equals(model))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("Model " + modelId
                        + " is not available in the " + adapter.displayName() + " catalog; choose a listed model"));
        return new HarnessSelection(HarnessIds.normalize(adapter.id()), selected.id(), selected.resolveReasoning(reasoningLevel));
    }

    public void validateReady(HarnessSelection selection) {
        resolve(selection.harnessId(), selection.modelId(), selection.reasoningLevel());
        CodingHarnessAdapter adapter = requireHarness(selection.harnessId());
        if (!adapter.checkCliAvailable()) {
            throw new IllegalStateException(adapter.displayName()
                    + " is not installed or available on PATH. Install that CLI before approving this stage.");
        }
        if (!adapter.checkSubscriptionAuthentication()) {
            String command = switch (HarnessIds.normalize(adapter.id())) {
                case HarnessIds.CLAUDE -> "claude auth login";
                case HarnessIds.CODEX -> "codex login";
                default -> "the harness subscription login command";
            };
            throw new IllegalStateException(adapter.displayName() + " subscription authentication is unavailable. Run "
                    + command + " with your subscription account, then retry. API-key billing is not permitted.");
        }
    }

    public HarnessSelection forStage(TrackedIssue issue, String harnessId, WorkflowStage stage) {
        if (!stage.modelDriven()) throw new IllegalArgumentException(stage + " does not select a model");
        CodingHarnessAdapter adapter = requireHarness(harnessId);
        boolean review = stage == WorkflowStage.REVIEW;
        String issueModel = review ? issue.getReviewModelOverride() : issue.getImplModelOverride();
        String repoModel = review ? issue.getRepo().getReviewModel() : issue.getRepo().getImplementationModel();
        if (!present(issueModel) && present(repoModel)
                && registry.adapters().stream().noneMatch(candidate -> compatible(candidate, repoModel))) {
            throw new IllegalArgumentException("Repository model " + repoModel + " is not available in a harness catalog");
        }
        String model = present(issueModel) ? issueModel : compatible(adapter, repoModel) ? repoModel
                : globalModel(adapter.id(), review, false);
        return resolve(adapter.id(), model, inheritedReasoning(issue, adapter, stage));
    }

    public HarnessSelection utility(String harnessId) {
        CodingHarnessAdapter adapter = requireHarness(harnessId);
        return resolve(adapter.id(), globalModel(adapter.id(), false, true), globalReasoning(adapter.id(), false, true));
    }

    /** Validate a legacy override form, where a blank model inherits the role's global model. */
    public String validateOverride(String model, String effort, WorkflowStage stage) {
        String id = properties.getAgentProvider();
        if (!present(model) && !present(effort)) return null;
        HarnessSelection selected = resolve(id, present(model) ? model : globalModel(id, stage == WorkflowStage.REVIEW, false), effort);
        return present(effort) ? selected.reasoningLevel() : null;
    }

    /** Compatibility callers still execute the exact saved stage tuple, regardless of current defaults. */
    @Transactional(readOnly = true)
    public HarnessSelection forExecution(String harnessId, Long issueId, String model, WorkflowStage stage) {
        CodingHarnessAdapter adapter = requireHarness(harnessId);
        if (issueId == null) return resolve(adapter.id(), model, globalReasoning(adapter.id(), stage == WorkflowStage.REVIEW, false));
        TrackedIssue issue = issues.findById(issueId).orElseThrow();
        Long artifact = issue.getApprovedPlanningVersion() == null ? 0L : issue.getApprovedPlanningVersion().getId();
        var approved = stages.findByIssueIdOrderByIdAsc(issueId).stream()
                .filter(s -> s.getRunNumber() == issue.getWorkflowRun() && s.getStage() == stage
                        && s.getState() == StageApproval.State.APPROVED
                        && Objects.equals(s.getArtifactVersionId(), artifact)).reduce((a, b) -> b);
        if (approved.isPresent()) {
            StageApproval decision = approved.get();
            HarnessSelection saved = resolve(decision.getHarnessId(), decision.getModel(), decision.getReasoningEffort());
            if (!saved.harnessId().equals(HarnessIds.normalize(adapter.id())) || !saved.modelId().equals(model)) {
                throw new IllegalArgumentException("Execution selection differs from the approved harness/model tuple");
            }
            return saved;
        }
        return resolve(adapter.id(), model, inheritedReasoning(issue, adapter, stage));
    }

    private String inheritedReasoning(TrackedIssue issue, CodingHarnessAdapter adapter, WorkflowStage stage) {
        boolean review = stage == WorkflowStage.REVIEW;
        String effort = globalReasoning(adapter.id(), review, false);
        String repoModel = review ? issue.getRepo().getReviewModel() : issue.getRepo().getImplementationModel();
        String repoEffort = review ? issue.getRepo().getReviewReasoningEffort() : issue.getRepo().getImplementationReasoningEffort();
        String issueEffort = review ? issue.getReviewReasoningEffort() : issue.getImplementationReasoningEffort();
        if ((!present(repoModel) || compatible(adapter, repoModel)) && present(repoEffort)) effort = repoEffort;
        if (present(issueEffort)) effort = issueEffort;
        return effort;
    }

    private boolean compatible(CodingHarnessAdapter adapter, String model) {
        return present(model) && adapter.models().stream().anyMatch(candidate -> candidate.id().equals(model.trim()));
    }

    private String globalModel(String id, boolean review, boolean utility) {
        return switch (HarnessIds.normalize(id)) {
            case HarnessIds.CLAUDE -> utility ? properties.getClaudeCode().getUtilityModel()
                    : review ? properties.getClaudeCode().getReviewModel() : properties.getClaudeCode().getImplementationModel();
            case HarnessIds.CODEX -> utility ? properties.getCodexCli().getUtilityModel()
                    : review ? properties.getCodexCli().getReviewModel() : properties.getCodexCli().getImplementationModel();
            default -> throw new IllegalArgumentException("No global model default configured for coding harness: " + id);
        };
    }

    private String globalReasoning(String id, boolean review, boolean utility) {
        return switch (HarnessIds.normalize(id)) {
            case HarnessIds.CLAUDE -> utility ? properties.getClaudeCode().getUtilityReasoningEffort()
                    : review ? properties.getClaudeCode().getReviewReasoningEffort() : properties.getClaudeCode().getImplementationReasoningEffort();
            case HarnessIds.CODEX -> utility ? properties.getCodexCli().getUtilityReasoningEffort()
                    : review ? properties.getCodexCli().getReviewReasoningEffort() : properties.getCodexCli().getImplementationReasoningEffort();
            default -> null;
        };
    }

    private CodingHarnessAdapter requireHarness(String id) {
        if (!present(id)) throw new IllegalArgumentException("A harness identity is required");
        return registry.require(id);
    }

    private static boolean present(String value) { return value != null && !value.isBlank(); }
}

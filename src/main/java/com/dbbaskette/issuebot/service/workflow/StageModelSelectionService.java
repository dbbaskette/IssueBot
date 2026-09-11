package com.dbbaskette.issuebot.service.workflow;

import com.dbbaskette.issuebot.config.IssueBotProperties;
import com.dbbaskette.issuebot.config.IssueBotProperties.AgentProvider;
import com.dbbaskette.issuebot.model.TrackedIssue;
import com.dbbaskette.issuebot.model.WorkflowStage;
import com.dbbaskette.issuebot.service.harness.CodingHarnessService;
import com.dbbaskette.issuebot.service.harness.HarnessIds;
import com.dbbaskette.issuebot.service.claude.ModelCatalog;
import com.dbbaskette.issuebot.service.claude.ModelResolver;
import com.dbbaskette.issuebot.service.codex.CodexModelCatalog;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/** Stage-local selection. Never changes defaults or substitutes an explicit model choice. */
@Service
public class StageModelSelectionService {
    private final IssueBotProperties properties;
    private final ModelResolver resolver;
    private final CodexModelCatalog codexModels;
    private final CodingHarnessService agent;

    public StageModelSelectionService(IssueBotProperties properties, ModelResolver resolver,
                                      CodexModelCatalog codexModels, CodingHarnessService agent) {
        this.properties = properties;
        this.resolver = resolver;
        this.codexModels = codexModels;
        this.agent = agent;
    }

    public record Selection(AgentProvider provider, String model) { }

    public Selection resolve(TrackedIssue issue, WorkflowStage stage, String provider, String model) {
        if (!stage.modelDriven()) {
            if (present(provider) || present(model)) {
                throw new IllegalArgumentException(stage + " does not accept provider or model selections");
            }
            return new Selection(null, null);
        }
        AgentProvider selected = properties.getAgentProvider();
        if (present(provider)) {
            try {
                selected = AgentProvider.valueOf(provider.trim());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Choose a supported CLI provider", e);
            }
        }
        String selectedModel = present(model) ? model.trim()
                : stage == WorkflowStage.REVIEW ? resolver.reviewModel(issue, selected)
                : resolver.implementationModel(issue, selected);
        if (present(model) && !modelsByProvider().get(selected.name()).contains(selectedModel)) {
            throw new IllegalArgumentException("Model " + selectedModel + " is not available in the "
                    + selected.getDisplayName() + " catalog; choose a listed model");
        }
        return new Selection(selected, selectedModel);
    }

    public void validate(Selection selection) {
        if (selection.provider() == null && selection.model() == null) return;
        if (selection.provider() == null || !present(selection.model())) {
            throw new IllegalArgumentException("A provider and model are required for this stage");
        }
        if (!agent.checkCliAvailable(HarnessIds.normalize(selection.provider().name()))) {
            throw new IllegalStateException(selection.provider().getDisplayName()
                    + " is not installed or available on PATH. Install that CLI before approving this stage.");
        }
        if (!agent.checkSubscriptionAuthentication(HarnessIds.normalize(selection.provider().name()))) {
            String command = selection.provider() == AgentProvider.CODEX ? "codex login" : "claude auth login";
            throw new IllegalStateException(selection.provider().getDisplayName()
                    + " subscription authentication is unavailable. Run " + command
                    + " with your subscription account, then retry. API-key billing is not permitted.");
        }
    }

    public Map<String, List<String>> modelsByProvider() {
        return Map.of(AgentProvider.CLAUDE_CODE.name(), ModelCatalog.MODELS.stream().map(ModelCatalog.ModelInfo::id).toList(),
                AgentProvider.CODEX.name(), codexModels.models().stream().map(CodexModelCatalog.ModelInfo::id).toList());
    }

    private static boolean present(String value) { return value != null && !value.isBlank(); }
}

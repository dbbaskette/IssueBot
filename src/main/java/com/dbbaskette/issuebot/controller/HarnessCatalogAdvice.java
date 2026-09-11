package com.dbbaskette.issuebot.controller;

import com.dbbaskette.issuebot.config.IssueBotProperties;
import com.dbbaskette.issuebot.service.harness.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/** Model-picker metadata. Setup uses identity only, avoiding potentially slow catalog discovery. */
@ControllerAdvice(assignableTypes = {SettingsController.class,
        RepositoryController.class, IssueController.class, StageApprovalController.class})
public class HarnessCatalogAdvice {
    private final CodingHarnessRegistry registry;
    private final ObjectMapper json;
    private final IssueBotProperties properties;

    public HarnessCatalogAdvice(CodingHarnessRegistry registry, ObjectMapper json, IssueBotProperties properties) {
        this.registry = registry;
        this.json = json;
        this.properties = properties;
    }

    public record HarnessView(String id, String displayName, String availability, String authentication,
            HarnessCapabilities capabilities, List<HarnessModel> models) {
        static HarnessView from(CodingHarnessAdapter adapter) {
            // No availability/auth methods here: an adapter may implement those with a fresh subprocess.
            return new HarnessView(adapter.id(), adapter.displayName(), "unchecked", "unchecked",
                    adapter.capabilities(), List.copyOf(adapter.models()));
        }
    }

    @ModelAttribute("harnessCatalog")
    public List<HarnessView> harnessCatalog() {
        return registry.adapters().stream().map(HarnessView::from).toList();
    }

    @ModelAttribute("harnessCatalogJson")
    public String harnessCatalogJson(@ModelAttribute("harnessCatalog") List<HarnessView> catalog)
            throws JsonProcessingException {
        return json.writeValueAsString(catalog);
    }

    @ModelAttribute("effectiveHarnessId")
    public String effectiveHarnessId() { return properties.getAgentProvider(); }

    @ModelAttribute("globalImplementationModel")
    public String globalImplementationModel() {
        return switch (effectiveHarnessId()) {
            case HarnessIds.CLAUDE -> properties.getClaudeCode().getImplementationModel();
            case HarnessIds.CODEX -> properties.getCodexCli().getImplementationModel();
            default -> null;
        };
    }

    @ModelAttribute("globalReviewModel")
    public String globalReviewModel() {
        return switch (effectiveHarnessId()) {
            case HarnessIds.CLAUDE -> properties.getClaudeCode().getReviewModel();
            case HarnessIds.CODEX -> properties.getCodexCli().getReviewModel();
            default -> null;
        };
    }

    @ModelAttribute("effectiveHarnessName")
    public String effectiveHarnessName() {
        return registry.adapters().stream().filter(h -> h.id().equals(effectiveHarnessId()))
                .map(CodingHarnessAdapter::displayName).findFirst().orElse(effectiveHarnessId());
    }

    /** Template helpers preserve unknown submitted values instead of replacing them. */
    public static List<HarnessModel> models(List<HarnessView> catalog, String harnessId) {
        if (catalog == null) return List.of();
        return catalog.stream().filter(h -> h.id().equals(harnessId)).findFirst()
                .map(HarnessView::models).orElse(List.of());
    }

    public static HarnessModel model(List<HarnessModel> models, String modelId) {
        return models.stream().filter(m -> m.id().equals(modelId)).findFirst().orElse(null);
    }

    public static String inheritedModel(List<HarnessView> catalog, String harnessId, String repositoryModel, String globalModel) {
        return model(models(catalog, harnessId), repositoryModel) == null ? globalModel : repositoryModel;
    }

    public static String displayName(List<HarnessView> catalog, String harnessId) {
        if (catalog == null) return harnessId;
        return catalog.stream().filter(h -> h.id().equals(harnessId)).findFirst()
                .map(HarnessView::displayName).orElse(harnessId);
    }
}

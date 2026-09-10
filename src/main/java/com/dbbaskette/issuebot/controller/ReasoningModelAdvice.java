package com.dbbaskette.issuebot.controller;

import com.dbbaskette.issuebot.service.codex.CodexModelCatalog;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

@ControllerAdvice(assignableTypes = {IssueController.class, RepositoryController.class})
public class ReasoningModelAdvice {
    private final CodexModelCatalog catalog;
    private final ObjectMapper json;
    public ReasoningModelAdvice(CodexModelCatalog catalog, ObjectMapper json) {
        this.catalog = catalog;
        this.json = json;
    }
    @ModelAttribute("reasoningCatalogJson")
    public String catalog() throws JsonProcessingException {
        return json.writeValueAsString(catalog.models());
    }
}

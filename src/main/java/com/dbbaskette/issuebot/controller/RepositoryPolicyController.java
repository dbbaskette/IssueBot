package com.dbbaskette.issuebot.controller;

import com.dbbaskette.issuebot.model.WorkflowPolicy;
import com.dbbaskette.issuebot.model.WorkflowStage;
import com.dbbaskette.issuebot.repository.WatchedRepoRepository;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class RepositoryPolicyController {
    private final WatchedRepoRepository repositories;

    public RepositoryPolicyController(WatchedRepoRepository repositories) {
        this.repositories = repositories;
    }

    @Transactional
    @PostMapping("/repositories/{id}/policy")
    public String update(@PathVariable Long id, @RequestParam String policy,
                         @RequestParam(required = false) List<String> stages,
                         RedirectAttributes redirect) {
        WorkflowPolicy selected;
        String selectedStages;
        try {
            selected = WorkflowPolicy.valueOf(policy);
            selectedStages = stages == null ? "" : stages.stream()
                    .flatMap(value -> Arrays.stream(value.split(",")))
                    .map(String::trim).map(WorkflowStage::valueOf).distinct()
                    .sorted().map(Enum::name).collect(Collectors.joining(","));
        } catch (IllegalArgumentException ex) {
            redirect.addFlashAttribute("error", "Choose a valid workflow policy and approval stages.");
            return "redirect:/repositories";
        }
        var repo = repositories.findById(id).orElse(null);
        if (repo == null) {
            redirect.addFlashAttribute("error", "Repository not found.");
            return "redirect:/repositories";
        }
        repo.setWorkflowPolicy(selected);
        repo.setApprovalStages(selectedStages);
        repositories.save(repo);
        redirect.addFlashAttribute("message", "Workflow policy saved. Applies only to new runs; existing approvals are unchanged.");
        return "redirect:/repositories";
    }
}

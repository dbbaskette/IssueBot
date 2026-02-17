package com.dbbaskette.issuebot.controller;

import com.dbbaskette.issuebot.model.IssueStatus;
import com.dbbaskette.issuebot.model.RepoMode;
import com.dbbaskette.issuebot.model.WatchedRepo;
import com.dbbaskette.issuebot.repository.TrackedIssueRepository;
import com.dbbaskette.issuebot.repository.WatchedRepoRepository;
import com.dbbaskette.issuebot.service.polling.IssuePollingService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Controller
@RequestMapping("/repositories")
public class RepositoryController {

    private final WatchedRepoRepository repoRepository;
    private final TrackedIssueRepository issueRepository;
    private final IssuePollingService pollingService;

    public RepositoryController(WatchedRepoRepository repoRepository,
                                 TrackedIssueRepository issueRepository,
                                 IssuePollingService pollingService) {
        this.repoRepository = repoRepository;
        this.issueRepository = issueRepository;
        this.pollingService = pollingService;
    }

    @GetMapping
    public String list(Model model, @RequestParam(required = false) String message) {
        populateModel(model, message, null);
        return "layout";
    }

    @PostMapping
    public String addOrUpdate(Model model,
                               @RequestParam(required = false) Long id,
                               @RequestParam String owner,
                               @RequestParam String name,
                               @RequestParam String branch,
                               @RequestParam String mode,
                               @RequestParam int maxIterations,
                               @RequestParam int ciTimeoutMinutes,
                               @RequestParam(required = false) String allowedPaths) {
        WatchedRepo repo;
        if (id != null) {
            repo = repoRepository.findById(id).orElse(new WatchedRepo(owner, name));
        } else {
            repo = repoRepository.findByOwnerAndName(owner, name)
                    .orElse(new WatchedRepo(owner, name));
        }

        repo.setOwner(owner);
        repo.setName(name);
        repo.setBranch(branch);
        repo.setMode(RepoMode.valueOf(mode));
        repo.setMaxIterations(maxIterations);
        repo.setCiTimeoutMinutes(ciTimeoutMinutes);
        if (allowedPaths != null && !allowedPaths.isBlank()) {
            repo.setAllowedPaths("[\"" + String.join("\",\"", allowedPaths.split("\\s*,\\s*")) + "\"]");
        }

        repoRepository.save(repo);
        populateModel(model, "Repository " + repo.fullName() + " saved.", null);
        return "layout";
    }

    @DeleteMapping("/{id}")
    public String delete(Model model, @PathVariable Long id) {
        repoRepository.findById(id).ifPresent(repo -> {
            repoRepository.delete(repo);
        });
        populateModel(model, "Repository removed.", null);
        return "layout";
    }

    private void populateModel(Model model, String message, String error) {
        List<WatchedRepo> repos = repoRepository.findAll();
        Map<Long, Long> issueCounts = new HashMap<>();
        for (WatchedRepo repo : repos) {
            long count = issueRepository.findByRepo(repo).stream()
                    .filter(i -> i.getStatus() != IssueStatus.COMPLETED)
                    .count();
            issueCounts.put(repo.getId(), count);
        }

        model.addAttribute("activePage", "repositories");
        model.addAttribute("contentTemplate", "repositories");
        model.addAttribute("repos", repos);
        model.addAttribute("issueCounts", issueCounts);
        model.addAttribute("agentRunning", pollingService.isEnabled());
        model.addAttribute("pendingApprovals", issueRepository.countByStatus(IssueStatus.AWAITING_APPROVAL));
        if (message != null) model.addAttribute("message", message);
        if (error != null) model.addAttribute("error", error);
    }
}

package com.dbbaskette.issuebot.controller;

import com.dbbaskette.issuebot.model.IssueStatus;
import com.dbbaskette.issuebot.model.RepoMode;
import com.dbbaskette.issuebot.model.TrackedIssue;
import com.dbbaskette.issuebot.model.WatchedRepo;
import com.dbbaskette.issuebot.repository.*;
import com.dbbaskette.issuebot.service.polling.IssuePollingService;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Controller
@RequestMapping("/repositories")
public class RepositoryController {

    private final WatchedRepoRepository repoRepository;
    private final TrackedIssueRepository issueRepository;
    private final IterationRepository iterationRepository;
    private final CostTrackingRepository costRepository;
    private final EventRepository eventRepository;
    private final IssuePollingService pollingService;

    public RepositoryController(WatchedRepoRepository repoRepository,
                                 TrackedIssueRepository issueRepository,
                                 IterationRepository iterationRepository,
                                 CostTrackingRepository costRepository,
                                 EventRepository eventRepository,
                                 IssuePollingService pollingService) {
        this.repoRepository = repoRepository;
        this.issueRepository = issueRepository;
        this.iterationRepository = iterationRepository;
        this.costRepository = costRepository;
        this.eventRepository = eventRepository;
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
                               @RequestParam(required = false, defaultValue = "false") boolean ciEnabled,
                               @RequestParam int ciTimeoutMinutes,
                               @RequestParam(required = false, defaultValue = "false") boolean autoMerge,
                               @RequestParam(required = false, defaultValue = "false") boolean securityReviewEnabled,
                               @RequestParam(defaultValue = "2") int maxReviewIterations,
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
        repo.setCiEnabled(ciEnabled);
        repo.setCiTimeoutMinutes(ciTimeoutMinutes);
        repo.setAutoMerge(autoMerge);
        repo.setSecurityReviewEnabled(securityReviewEnabled);
        repo.setMaxReviewIterations(maxReviewIterations);
        if (allowedPaths != null && !allowedPaths.isBlank()) {
            try {
                List<String> paths = Arrays.stream(allowedPaths.split("\\s*,\\s*"))
                        .filter(s -> !s.isBlank())
                        .toList();
                repo.setAllowedPaths(new ObjectMapper().writeValueAsString(paths));
            } catch (JsonProcessingException e) {
                repo.setAllowedPaths("[]");
            }
        }

        repoRepository.save(repo);
        populateModel(model, "Repository " + repo.fullName() + " saved.", null);
        return "layout";
    }

    @DeleteMapping("/{id}")
    @Transactional
    public String delete(Model model, @PathVariable Long id) {
        repoRepository.findById(id).ifPresent(repo -> {
            // Delete children in FK order: events, cost_tracking, iterations, tracked_issues, repo
            eventRepository.deleteByRepo(repo);
            List<TrackedIssue> issues = issueRepository.findByRepo(repo);
            for (TrackedIssue issue : issues) {
                costRepository.deleteByIssue(issue);
                iterationRepository.deleteByIssue(issue);
            }
            issueRepository.deleteAll(issues);
            repoRepository.delete(repo);
        });
        populateModel(model, "Repository removed.", null);
        return "layout";
    }

    private void populateModel(Model model, String message, String error) {
        List<WatchedRepo> repos = repoRepository.findAll();
        Map<Long, Long> issueCounts = new HashMap<>();
        for (WatchedRepo repo : repos) {
            long count = issueRepository.countByRepoAndStatusNot(repo, IssueStatus.COMPLETED);
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

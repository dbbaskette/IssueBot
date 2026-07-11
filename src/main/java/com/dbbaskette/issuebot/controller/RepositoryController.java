package com.dbbaskette.issuebot.controller;

import com.dbbaskette.issuebot.model.DecompositionMode;
import com.dbbaskette.issuebot.model.FollowUpMode;
import com.dbbaskette.issuebot.model.IssueStatus;
import com.dbbaskette.issuebot.model.RepoMode;
import com.dbbaskette.issuebot.model.TrackedIssue;
import com.dbbaskette.issuebot.model.WatchedRepo;
import com.dbbaskette.issuebot.repository.*;
import com.dbbaskette.issuebot.service.polling.IssuePollingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(RepositoryController.class);

    private static final java.util.regex.Pattern GITHUB_SLUG =
            java.util.regex.Pattern.compile("^[A-Za-z0-9._-]+$");

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
    public String list(Model model, @RequestParam(required = false) String message,
                       @RequestHeader(value = "HX-Request", required = false) String hx) {
        populateModel(model, message, null);
        return ViewResolver.view("repositories", hx != null);
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
                               @RequestParam(defaultValue = "0.70") java.math.BigDecimal reviewPassThreshold,
                               @RequestParam(required = false, defaultValue = "false") boolean autoStart,
                               @RequestParam(required = false, defaultValue = "true") boolean followUpEnabled,
                               @RequestParam(required = false) String allowedPaths,
                               @RequestParam(required = false) String verificationCommands,
                               @RequestParam(required = false) String implementationModel,
                               @RequestParam(required = false) String reviewModel,
                               @RequestParam(defaultValue = "ROLLING_BACKLOG") String followUpMode,
                               @RequestParam(defaultValue = "PROPOSE") String decompositionMode,
                               @RequestParam(defaultValue = "false") boolean preScreenEnabled,
                               @RequestHeader(value = "HX-Request", required = false) String hx) {
        if (!GITHUB_SLUG.matcher(owner).matches() || !GITHUB_SLUG.matcher(name).matches()) {
            populateModel(model, null,
                    "Invalid repository owner/name. Use letters, numbers, '.', '_', '-' only.");
            return ViewResolver.view("repositories", hx != null);
        }
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
        repo.setReviewPassThreshold(clampReviewPassThreshold(reviewPassThreshold));
        repo.setAutoStart(autoStart);
        repo.setFollowUpEnabled(followUpEnabled);
        repo.setImplementationModel(normalize(implementationModel));
        repo.setReviewModel(normalize(reviewModel));
        repo.setVerificationCommands(normalize(verificationCommands));
        try {
            repo.setFollowUpMode(FollowUpMode.valueOf(followUpMode));
        } catch (IllegalArgumentException e) {
            log.warn("Invalid followUpMode '{}' for {} — keeping existing value", followUpMode, repo.fullName());
        }
        try {
            repo.setDecompositionMode(DecompositionMode.valueOf(decompositionMode));
        } catch (IllegalArgumentException e) {
            log.warn("Invalid decompositionMode '{}' for {} — keeping existing value", decompositionMode, repo.fullName());
        }
        repo.setPreScreenEnabled(preScreenEnabled);
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
        return ViewResolver.view("repositories", hx != null);
    }

    @DeleteMapping("/{id}")
    @Transactional
    public String delete(Model model, @PathVariable Long id,
                         @RequestHeader(value = "HX-Request", required = false) String hx) {
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
        return ViewResolver.view("repositories", hx != null);
    }

    private static String normalize(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    private static final java.math.BigDecimal REVIEW_THRESHOLD_MIN = new java.math.BigDecimal("0.50");
    private static final java.math.BigDecimal REVIEW_THRESHOLD_MAX = new java.math.BigDecimal("0.95");

    /**
     * Clamps the review pass threshold to the supported UI range [0.50, 0.95] rather
     * than rejecting out-of-range values — see issue #62.
     */
    private static java.math.BigDecimal clampReviewPassThreshold(java.math.BigDecimal value) {
        if (value == null) return new java.math.BigDecimal("0.70");
        if (value.compareTo(REVIEW_THRESHOLD_MIN) < 0) return REVIEW_THRESHOLD_MIN;
        if (value.compareTo(REVIEW_THRESHOLD_MAX) > 0) return REVIEW_THRESHOLD_MAX;
        return value;
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
        model.addAttribute("modelCatalog", com.dbbaskette.issuebot.service.claude.ModelCatalog.MODELS);
        model.addAttribute("agentRunning", pollingService.isEnabled());
        model.addAttribute("pendingApprovals", issueRepository.countByStatus(IssueStatus.AWAITING_APPROVAL));
        if (message != null) model.addAttribute("message", message);
        if (error != null) model.addAttribute("error", error);
    }
}

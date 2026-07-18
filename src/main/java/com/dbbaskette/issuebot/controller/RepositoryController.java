package com.dbbaskette.issuebot.controller;

import com.dbbaskette.issuebot.model.DecompositionMode;
import com.dbbaskette.issuebot.model.FollowUpMode;
import com.dbbaskette.issuebot.model.IssueStatus;
import com.dbbaskette.issuebot.model.RepoLesson;
import com.dbbaskette.issuebot.model.RepoMode;
import com.dbbaskette.issuebot.model.TrackedIssue;
import com.dbbaskette.issuebot.model.WatchedRepo;
import com.dbbaskette.issuebot.repository.*;
import com.dbbaskette.issuebot.service.polling.IssuePollingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.beans.factory.annotation.Autowired;
import com.dbbaskette.issuebot.config.IssueBotProperties;
import com.dbbaskette.issuebot.service.codex.CodexModelCatalog;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

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
    private final RepoLessonRepository lessonRepository;
    private final IssuePollingService pollingService;
    private final NotificationRepository notificationRepository;
    private final PlanningVersionRepository planningVersionRepository;

    @Autowired(required = false)
    private IssueBotProperties properties;

    @Autowired(required = false)
    private CodexModelCatalog codexModelCatalog;

    public RepositoryController(WatchedRepoRepository repoRepository,
                                 TrackedIssueRepository issueRepository,
                                 IterationRepository iterationRepository,
                                 CostTrackingRepository costRepository,
                                 EventRepository eventRepository,
                                 RepoLessonRepository lessonRepository,
                                 IssuePollingService pollingService,
                                 NotificationRepository notificationRepository,
                                 PlanningVersionRepository planningVersionRepository) {
        this.repoRepository = repoRepository;
        this.issueRepository = issueRepository;
        this.iterationRepository = iterationRepository;
        this.costRepository = costRepository;
        this.eventRepository = eventRepository;
        this.lessonRepository = lessonRepository;
        this.pollingService = pollingService;
        this.notificationRepository = notificationRepository;
        this.planningVersionRepository = planningVersionRepository;
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
                               @RequestParam(defaultValue = "true") boolean planFirst,
                               @RequestParam(required = false) java.math.BigDecimal issueBudgetUsd,
                               @RequestParam(required = false) String customInstructions,
                               @RequestParam(required = false, defaultValue = "false") boolean lessonsEnabled,
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
        // Comments-only / blank-effective command lists count as unset so the UI
        // (pipeline stage, goal-card row) and the workflow agree on "configured".
        String normalizedCommands = normalize(verificationCommands);
        if (normalizedCommands != null
                && com.dbbaskette.issuebot.service.workflow.LocalVerificationService
                        .parseCommands(normalizedCommands).isEmpty()) {
            normalizedCommands = null;
        }
        repo.setVerificationCommands(normalizedCommands);
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
        repo.setPlanFirst(planFirst);
        repo.setIssueBudgetUsd(normalizeBudget(issueBudgetUsd));
        repo.setCustomInstructions(normalize(customInstructions));
        repo.setLessonsEnabled(lessonsEnabled);
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
            // planning_versions and tracked_issues form an intentional FK cycle through the
            // approved-version pointer. Break and flush that pointer before deleting versions.
            eventRepository.deleteByRepo(repo);
            List<TrackedIssue> issues = issueRepository.findByRepo(repo);
            List<Long> issueIds = issues.stream().map(TrackedIssue::getId).toList();
            if (!issueIds.isEmpty()) {
                issues.forEach(issue -> issue.setApprovedPlanningVersion(null));
                issueRepository.saveAllAndFlush(issues);
                planningVersionRepository.deleteByIssueIds(issueIds);
                planningVersionRepository.flush();
            }
            for (TrackedIssue issue : issues) {
                costRepository.deleteByIssue(issue);
                iterationRepository.deleteByIssue(issue);
            }
            issueRepository.deleteAll(issues);
            issueRepository.flush();
            repoRepository.delete(repo);
        });
        populateModel(model, "Repository removed.", null);
        return ViewResolver.view("repositories", hx != null);
    }

    /**
     * Delete a single lesson from a repo's lessons list (operator curation, #69).
     * Guarded so a lesson id belonging to a different repo can't be deleted via a
     * crafted request — the id must actually belong to the {@code id} path repo.
     */
    @PostMapping("/{id}/lessons/{lessonId}/delete")
    public String deleteLesson(@PathVariable Long id, @PathVariable Long lessonId,
                                RedirectAttributes redirectAttributes) {
        RepoLesson lesson = lessonRepository.findById(lessonId).orElse(null);
        if (lesson == null || !lesson.getRepoId().equals(id)) {
            redirectAttributes.addFlashAttribute("error", "Lesson not found for this repository.");
            return "redirect:/repositories";
        }
        lessonRepository.delete(lesson);
        redirectAttributes.addFlashAttribute("message", "Lesson removed.");
        return "redirect:/repositories";
    }

    private static String normalize(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    /**
     * Blank input arrives as null (Spring can't bind an empty numeric field), and a
     * negative value is nonsensical for a spend ceiling — both mean "unlimited".
     */
    private static java.math.BigDecimal normalizeBudget(java.math.BigDecimal value) {
        if (value == null) return null;
        if (value.compareTo(java.math.BigDecimal.ZERO) < 0) return null;
        return value;
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
        Map<Long, Long> totalIssueCounts = new HashMap<>();
        Map<Long, List<RepoLesson>> lessonsByRepo = new HashMap<>();
        for (WatchedRepo repo : repos) {
            long count = issueRepository.countByRepoAndStatusNot(repo, IssueStatus.COMPLETED);
            issueCounts.put(repo.getId(), count);
            // Total tracked issues (all statuses) — what the Remove-repository confirmation
            // modal states will be cascade-deleted, matching what #delete actually removes
            // (findByRepo, unfiltered by status), unlike the open-issue count above.
            totalIssueCounts.put(repo.getId(), issueRepository.countByRepo(repo));
            // One query per repo is acceptable at this scale (small number of watched repos).
            lessonsByRepo.put(repo.getId(), lessonRepository.findByRepoIdOrderByCreatedAtAsc(repo.getId()));
        }

        model.addAttribute("activePage", "repositories");
        model.addAttribute("contentTemplate", "repositories");
        model.addAttribute("repos", repos);
        model.addAttribute("issueCounts", issueCounts);
        model.addAttribute("totalIssueCounts", totalIssueCounts);
        model.addAttribute("lessonsByRepo", lessonsByRepo);
        model.addAttribute("modelCatalog", selectedModelCatalog());
        model.addAttribute("agentRunning", pollingService.isEnabled());
        model.addAttribute("pendingApprovals", issueRepository.countByStatus(IssueStatus.AWAITING_APPROVAL));
        model.addAttribute("needsYouCount", issueRepository.countNeedsYou());
        model.addAttribute("unreadNotificationCount", notificationRepository.countByReadAtIsNull());
        if (message != null) model.addAttribute("message", message);
        if (error != null) model.addAttribute("error", error);
    }

    private List<?> selectedModelCatalog() {
        if (properties != null && properties.getAgentProvider() == IssueBotProperties.AgentProvider.CODEX) {
            return codexModelCatalog == null
                    ? CodexModelCatalog.fallbackModels() : codexModelCatalog.models();
        }
        return com.dbbaskette.issuebot.service.claude.ModelCatalog.MODELS;
    }
}

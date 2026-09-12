package com.dbbaskette.issuebot.controller;

import com.dbbaskette.issuebot.model.DecompositionMode;
import com.dbbaskette.issuebot.model.FollowUpMode;
import com.dbbaskette.issuebot.model.IssueStatus;
import com.dbbaskette.issuebot.model.RepoLesson;
import com.dbbaskette.issuebot.model.RepoMode;
import com.dbbaskette.issuebot.model.TrackedIssue;
import com.dbbaskette.issuebot.model.WatchedRepo;
import com.dbbaskette.issuebot.model.WorkflowPolicy;
import com.dbbaskette.issuebot.model.WorkflowStage;
import com.dbbaskette.issuebot.repository.*;
import com.dbbaskette.issuebot.service.polling.IssuePollingService;
import com.dbbaskette.issuebot.service.workflow.RepositoryDeletionTransactionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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
    private final RepositoryDeletionTransactionManager deletionTransactions;

    @Autowired
    public RepositoryController(WatchedRepoRepository repoRepository,
                                 TrackedIssueRepository issueRepository,
                                 IterationRepository iterationRepository,
                                 CostTrackingRepository costRepository,
                                 EventRepository eventRepository,
                                 RepoLessonRepository lessonRepository,
                                 IssuePollingService pollingService,
                                 NotificationRepository notificationRepository,
                                 PlanningVersionRepository planningVersionRepository,
                                 RepositoryDeletionTransactionManager deletionTransactions) {
        this.repoRepository = repoRepository;
        this.issueRepository = issueRepository;
        this.iterationRepository = iterationRepository;
        this.costRepository = costRepository;
        this.eventRepository = eventRepository;
        this.lessonRepository = lessonRepository;
        this.pollingService = pollingService;
        this.notificationRepository = notificationRepository;
        this.planningVersionRepository = planningVersionRepository;
        this.deletionTransactions = deletionTransactions;
    }

    /** Compatibility constructor for focused controller fixtures that never remove repositories. */
    public RepositoryController(WatchedRepoRepository repoRepository,
                                TrackedIssueRepository issueRepository,
                                IterationRepository iterationRepository,
                                CostTrackingRepository costRepository,
                                EventRepository eventRepository,
                                RepoLessonRepository lessonRepository,
                                IssuePollingService pollingService,
                                NotificationRepository notificationRepository,
                                PlanningVersionRepository planningVersionRepository) {
        this(repoRepository, issueRepository, iterationRepository, costRepository, eventRepository,
                lessonRepository, pollingService, notificationRepository, planningVersionRepository,
                null);
    }

    @GetMapping
    public String list(Model model, @RequestParam(required = false) String message,
                       @RequestHeader(value = "HX-Request", required = false) String hx) {
        populateModel(model, message, null);
        return ViewResolver.view("repositories", hx != null);
    }

    @Autowired(required = false)
    private com.dbbaskette.issuebot.service.harness.HarnessSelectionService reasoning;

    /** Change only the trusted gate; automation must not round-trip the entire repository form. */
    @PostMapping("/{id}/verification-commands")
    @Transactional
    public String saveVerificationCommands(@PathVariable Long id,
                                           @RequestParam String verificationCommands,
                                           RedirectAttributes redirects) {
        WatchedRepo repo = repoRepository.findById(id).orElse(null);
        if (repo == null) {
            redirects.addFlashAttribute("error", "Repository not found");
            return "redirect:/repositories";
        }
        String normalized = normalize(verificationCommands);
        if (normalized == null || com.dbbaskette.issuebot.service.workflow.LocalVerificationService
                .parseCommands(normalized).isEmpty()) {
            redirects.addFlashAttribute("error", "Enter at least one executable verification command");
            return "redirect:/repositories";
        }
        repo.setVerificationCommands(normalized);
        repoRepository.saveAndFlush(repo);
        redirects.addFlashAttribute("success", "Trusted verification commands saved for " + repo.fullName());
        return "redirect:/repositories";
    }

    @PostMapping
    @Transactional
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
                               @RequestParam(defaultValue = "OFF") String decompositionMode,
                               @RequestParam(defaultValue = "false") boolean preScreenEnabled,
                               @RequestParam(defaultValue = "true") boolean planFirst,
                               @RequestParam(required = false) java.math.BigDecimal issueBudgetUsd,
                               @RequestParam(required = false) String customInstructions,
                               @RequestParam(required = false, defaultValue = "false") boolean lessonsEnabled,
                               @RequestParam(required = false) String workflowPolicy,
                               @RequestParam(required = false) List<String> approvalStages,
                               @RequestParam(required = false) String implementationReasoningEffort,
                               @RequestParam(required = false) String reviewReasoningEffort,
                               @RequestHeader(value = "HX-Request", required = false) String hx) {
        // Preserve raw fields before any resolution, including errors in other form sections.
        preserveSubmittedForm(model, id, owner, name, branch, mode, maxIterations, ciEnabled,
                ciTimeoutMinutes, autoMerge, securityReviewEnabled, maxReviewIterations,
                reviewPassThreshold, autoStart, allowedPaths, verificationCommands,
                implementationModel, reviewModel, followUpMode, decompositionMode,
                preScreenEnabled, planFirst, issueBudgetUsd, customInstructions, lessonsEnabled,
                safeWorkflowPolicy(id, workflowPolicy), approvalStages, implementationReasoningEffort, reviewReasoningEffort);
        if (!GITHUB_SLUG.matcher(owner).matches() || !GITHUB_SLUG.matcher(name).matches()) {
            populateModel(model, null,
                    "Invalid repository owner/name. Use letters, numbers, '.', '_', '-' only.");
            return ViewResolver.view("repositories", hx != null);
        }
        if (reasoning != null) {
            try {
                String resolvedImplementationEffort = reasoning.validateOverride(implementationModel, implementationReasoningEffort, com.dbbaskette.issuebot.model.WorkflowStage.IMPLEMENTATION);
                String resolvedReviewEffort = reasoning.validateOverride(reviewModel, reviewReasoningEffort, com.dbbaskette.issuebot.model.WorkflowStage.REVIEW);
                implementationReasoningEffort = resolvedImplementationEffort;
                reviewReasoningEffort = resolvedReviewEffort;
            } catch (IllegalArgumentException ex) {
                populateModel(model, null, ex.getMessage());
                return ViewResolver.view("repositories", hx != null);
            }
        }
        WorkflowSettings workflow;
        try {
            workflow = parseWorkflowSettings(workflowPolicy, approvalStages);
        } catch (IllegalArgumentException ex) {
            populateModel(model, null, "Choose a valid workflow policy and approval stages.");
            return ViewResolver.view("repositories", hx != null);
        }
        WatchedRepo repo;
        if (id != null) {
            repo = repoRepository.findById(id).orElse(new WatchedRepo(owner, name));
        } else {
            repo = repoRepository.findByOwnerAndName(owner, name)
                    .orElse(new WatchedRepo(owner, name));
        }

        repo.setImplementationReasoningEffort(normalize(implementationReasoningEffort));
        repo.setReviewReasoningEffort(normalize(reviewReasoningEffort));
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
        if (workflow != null) {
            repo.setWorkflowPolicy(workflow.policy());
            repo.setApprovalStages(workflow.approvalStages());
        }
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
        model.asMap().remove("repositoryFormValues");
        model.asMap().remove("repositorySelection");
        populateModel(model, "Repository " + repo.fullName() + " saved.", null);
        return ViewResolver.view("repositories", hx != null);
    }

    public String addOrUpdate(Model model, Long id, String owner, String name, String branch,
            String mode, int maxIterations, boolean ciEnabled, int ciTimeoutMinutes,
            boolean autoMerge, boolean securityReviewEnabled, int maxReviewIterations,
            java.math.BigDecimal reviewPassThreshold, boolean autoStart, boolean followUpEnabled,
            String allowedPaths, String verificationCommands, String implementationModel, String reviewModel,
            String followUpMode, String decompositionMode, boolean preScreenEnabled, boolean planFirst,
            java.math.BigDecimal issueBudgetUsd, String customInstructions, boolean lessonsEnabled,
            String workflowPolicy, List<String> approvalStages, String hx) {
        return addOrUpdate(model, id, owner, name, branch, mode, maxIterations, ciEnabled,
                ciTimeoutMinutes, autoMerge, securityReviewEnabled, maxReviewIterations, reviewPassThreshold,
                autoStart, followUpEnabled, allowedPaths, verificationCommands, implementationModel, reviewModel,
                followUpMode, decompositionMode, preScreenEnabled, planFirst, issueBudgetUsd, customInstructions,
                lessonsEnabled, workflowPolicy, approvalStages, null, null, hx);
    }

    /** Compatibility overload for focused fixtures and clients predating workflow fields. */
    public String addOrUpdate(Model model, Long id, String owner, String name, String branch,
                              String mode, int maxIterations, boolean ciEnabled, int ciTimeoutMinutes,
                              boolean autoMerge, boolean securityReviewEnabled, int maxReviewIterations,
                              java.math.BigDecimal reviewPassThreshold, boolean autoStart,
                              boolean followUpEnabled, String allowedPaths, String verificationCommands,
                              String implementationModel, String reviewModel, String followUpMode,
                              String decompositionMode, boolean preScreenEnabled, boolean planFirst,
                              java.math.BigDecimal issueBudgetUsd, String customInstructions,
                              boolean lessonsEnabled, String hx) {
        return addOrUpdate(model, id, owner, name, branch, mode, maxIterations, ciEnabled,
                ciTimeoutMinutes, autoMerge, securityReviewEnabled, maxReviewIterations,
                reviewPassThreshold, autoStart, followUpEnabled, allowedPaths, verificationCommands,
                implementationModel, reviewModel, followUpMode, decompositionMode, preScreenEnabled,
                planFirst, issueBudgetUsd, customInstructions, lessonsEnabled, null, null, hx);
    }

    private record WorkflowSettings(WorkflowPolicy policy, String approvalStages) {}

    private static WorkflowSettings parseWorkflowSettings(String policy, List<String> stages) {
        if (policy == null) return null;
        WorkflowPolicy selected = WorkflowPolicy.valueOf(policy);
        String selectedStages = stages == null ? "" : stages.stream()
                .flatMap(value -> Arrays.stream(value.split(",")))
                .map(String::trim)
                .map(WorkflowStage::valueOf)
                .distinct()
                .sorted()
                .map(Enum::name)
                .collect(Collectors.joining(","));
        return new WorkflowSettings(selected, selectedStages);
    }

    private String safeWorkflowPolicy(Long id, String submittedPolicy) {
        try {
            return WorkflowPolicy.valueOf(submittedPolicy).name();
        } catch (IllegalArgumentException | NullPointerException invalidPolicy) {
            if (id == null) return WorkflowPolicy.LEGACY.name();
            return repoRepository.findById(id)
                    .map(WatchedRepo::getWorkflowPolicy)
                    .map(Enum::name)
                    .orElse(WorkflowPolicy.LEGACY.name());
        }
    }

    private static void preserveSubmittedForm(Model model, Long id, String owner, String name,
            String branch, String mode, int maxIterations, boolean ciEnabled, int ciTimeoutMinutes,
            boolean autoMerge, boolean securityReviewEnabled, int maxReviewIterations,
            java.math.BigDecimal reviewPassThreshold, boolean autoStart, String allowedPaths,
            String verificationCommands, String implementationModel, String reviewModel,
            String followUpMode, String decompositionMode, boolean preScreenEnabled,
            boolean planFirst, java.math.BigDecimal issueBudgetUsd, String customInstructions,
            boolean lessonsEnabled, String workflowPolicy, List<String> approvalStages,
            String implementationReasoningEffort, String reviewReasoningEffort) {
        Map<String, Object> values = new HashMap<>();
        values.put("id", id);
        values.put("owner", owner);
        values.put("name", name);
        values.put("branch", branch);
        values.put("mode", mode);
        values.put("maxIterations", maxIterations);
        values.put("ciEnabled", ciEnabled);
        values.put("ciTimeoutMinutes", ciTimeoutMinutes);
        values.put("autoMerge", autoMerge);
        values.put("securityReviewEnabled", securityReviewEnabled);
        values.put("maxReviewIterations", maxReviewIterations);
        values.put("reviewPassThreshold", reviewPassThreshold);
        values.put("autoStart", autoStart);
        values.put("allowedPaths", allowedPaths);
        values.put("verificationCommands", verificationCommands);
        values.put("implementationReasoningEffort", implementationReasoningEffort);
        values.put("reviewReasoningEffort", reviewReasoningEffort);
        values.put("implementationModel", implementationModel);
        values.put("reviewModel", reviewModel);
        values.put("followUpMode", followUpMode);
        values.put("decompositionMode", decompositionMode);
        values.put("preScreenEnabled", preScreenEnabled);
        values.put("planFirst", planFirst);
        values.put("issueBudgetUsd", issueBudgetUsd);
        values.put("customInstructions", customInstructions);
        values.put("lessonsEnabled", lessonsEnabled);
        values.put("workflowPolicy", workflowPolicy);
        values.put("approvalStages", approvalStages == null ? "" : String.join(",", approvalStages));
        model.addAttribute("repositorySelection", values);
        try {
            model.addAttribute("repositoryFormValues", new ObjectMapper().writeValueAsString(values));
        } catch (JsonProcessingException impossible) {
            throw new IllegalStateException("Could not preserve repository form values", impossible);
        }
    }

    @DeleteMapping("/{id}")
    public String delete(Model model, @PathVariable Long id,
                         @RequestHeader(value = "HX-Request", required = false) String hx) {
        if (deletionTransactions == null) {
            throw new IllegalStateException(
                    "Transactional repository deletion is required for repository removal");
        }
        deletionTransactions.delete(id);
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
        model.addAttribute("agentRunning", pollingService.isEnabled());
        model.addAttribute("pendingApprovals", issueRepository.countByStatus(IssueStatus.AWAITING_APPROVAL));
        if (message != null) model.addAttribute("message", message);
        if (error != null) model.addAttribute("error", error);
    }

}

package com.dbbaskette.issuebot.service.workflow;

import com.dbbaskette.issuebot.config.IssueBotProperties.AgentProvider;
import com.dbbaskette.issuebot.model.IssueStatus;
import com.dbbaskette.issuebot.model.PlanningVersion;
import com.dbbaskette.issuebot.model.PlanningVersionState;
import com.dbbaskette.issuebot.model.TrackedIssue;
import com.dbbaskette.issuebot.repository.PlanningVersionRepository;
import com.dbbaskette.issuebot.repository.TrackedIssueRepository;
import com.dbbaskette.issuebot.repository.WatchedRepoRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Transactional authority for the Plan First lifecycle.
 *
 * <p>This bean deliberately lives outside {@link PlanFirstService}: Spring invokes it through a
 * separate proxy, so the transaction has committed before the orchestrator publishes GitHub
 * comments, events, or notifications.</p>
 */
@Service
public class PlanFirstTransactionManager {

    private static final Set<IssueStatus> EARLIER_ORDERING_BLOCKERS = EnumSet.of(
            IssueStatus.PENDING,
            IssueStatus.QUEUED,
            IssueStatus.IN_PROGRESS,
            IssueStatus.AWAITING_APPROVAL,
            IssueStatus.AWAITING_PLAN_APPROVAL,
            IssueStatus.READY_TO_START);
    private static final Set<IssueStatus> PLANNING_RESETTABLE = EnumSet.of(
            IssueStatus.PENDING,
            IssueStatus.QUEUED,
            IssueStatus.BLOCKED,
            IssueStatus.FAILED,
            IssueStatus.COOLDOWN,
            IssueStatus.AWAITING_PLAN_APPROVAL,
            IssueStatus.READY_TO_START);
    private static final Set<IssueStatus> PROTECTED_RUNNING = EnumSet.of(
            IssueStatus.IN_PROGRESS, IssueStatus.AWAITING_APPROVAL);

    private final TrackedIssueRepository issues;
    private final PlanningVersionRepository versions;
    private final WatchedRepoRepository repos;

    @Autowired
    public PlanFirstTransactionManager(TrackedIssueRepository issues,
                                       PlanningVersionRepository versions,
                                       WatchedRepoRepository repos) {
        this.issues = issues;
        this.versions = versions;
        this.repos = repos;
    }

    /** Compatibility constructor for focused fixtures; mutation methods deliberately fail closed. */
    public PlanFirstTransactionManager(TrackedIssueRepository issues,
                                       PlanningVersionRepository versions) {
        this(issues, versions, null);
    }

    /** Captures an immutable generation token without retaining a managed entity across AI I/O. */
    @Transactional
    public GenerationContext prepareGeneration(Long issueId) {
        TrackedIssue issue = requireIssueForUpdate(issueId);
        PlanningVersion latest = latestForUpdate(issueId);
        String feedback = normalize(issue.getPlanFeedback());
        if (feedback != null && latest == null) {
            throw new IllegalStateException("Revision requested without a prior planning version");
        }
        String model = normalize(issue.getResolvedImplModel());
        if (model == null) {
            throw new IllegalStateException("Resolved implementation model is required for planning");
        }
        AgentProvider provider = issue.getResolvedAgentProvider();
        if (provider == null) {
            throw new IllegalStateException("Resolved implementation provider is required for planning");
        }
        PreviousVersion previous = latest == null ? null : new PreviousVersion(
                latest.getId(), latest.getVersionNumber(), latest.getDesignSpec(),
                latest.getImplementationPlan());
        return new GenerationContext(issue.getId(), model, provider, feedback, previous);
    }

    /** Atomically inserts the immutable version and advances its owning issue. */
    @Transactional
    public GenerationCommit persistGeneratedVersion(GenerationContext expected,
                                                     String designSpec,
                                                     String implementationPlan) {
        TrackedIssue issue = requireIssueForUpdate(expected.issueId());
        PlanningVersion latest = latestForUpdate(expected.issueId());
        requireUnchangedGenerationContext(expected, issue, latest);

        int nextNumber = latest == null ? 1 : latest.getVersionNumber() + 1;
        PlanningVersion version = PlanningVersion.pending(issue, nextNumber,
                designSpec, implementationPlan, expected.provider().name(), expected.model(),
                expected.feedback());
        versions.save(version);
        versions.flush();

        issue.setPlanFeedback(null);
        issue.setStatus(IssueStatus.AWAITING_PLAN_APPROVAL);
        issue.setCurrentPhase(null);
        issue.setLastFailureReason(null);
        issues.save(issue);
        issues.flush();
        return new GenerationCommit(issue, version, expected.feedback() != null);
    }

    /** Approves exactly the latest pending row and its pointer in one transaction. */
    @Transactional
    public LifecycleCommit approvePlan(Long issueId, Long expectedVersionId) {
        List<TrackedIssue> ordered = lockRepositoryIssuesForApproval(issueId);
        TrackedIssue issue = ordered.stream()
                .filter(candidate -> Objects.equals(candidate.getId(), issueId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Issue not found: " + issueId));
        PlanningVersion current = requireCurrentPending(issue);
        requireExpectedVersion(current, expectedVersionId);

        int candidateIndex = ordered.indexOf(issue);
        TrackedIssue earlierBlocker = ordered.subList(0, candidateIndex).stream()
                .filter(candidate -> EARLIER_ORDERING_BLOCKERS.contains(candidate.getStatus()))
                .findFirst()
                .orElse(null);
        if (earlierBlocker != null) {
            throw new IllegalStateException("Issue #" + earlierBlocker.getIssueNumber()
                    + " must finish before issue #" + issue.getIssueNumber()
                    + " can reserve this repository.");
        }

        List<TrackedIssue> later = ordered.subList(candidateIndex + 1, ordered.size());
        TrackedIssue protectedRunning = later.stream()
                .filter(candidate -> PROTECTED_RUNNING.contains(candidate.getStatus()))
                .findFirst()
                .orElse(null);
        if (protectedRunning != null) {
            throw new IllegalStateException("Issue #" + protectedRunning.getIssueNumber()
                    + " is already running later work in this repository. Finish or stop it "
                    + "before approving issue #" + issue.getIssueNumber() + ".");
        }
        List<TrackedIssue> invalidatedIssues = later.stream()
                .filter(candidate -> PLANNING_RESETTABLE.contains(candidate.getStatus()))
                .toList();

        current.approve(LocalDateTime.now());
        versions.save(current);
        issue.setApprovedPlanningVersion(current);
        issue.setPlanConformanceAttempt(0);
        issue.setPlanCorrectionPending(false);
        issue.setStatus(IssueStatus.READY_TO_START);
        issues.save(issue);
        invalidatedIssues.forEach(TrackedIssue::resetPlanningStateToQueued);
        issues.saveAll(invalidatedIssues);

        versions.flush();
        issues.flush();
        if (!invalidatedIssues.isEmpty()) {
            versions.deleteByIssueIds(invalidatedIssues.stream()
                    .map(TrackedIssue::getId)
                    .toList());
        }

        List<InvalidatedPlan> invalidatedPlans = invalidatedIssues.stream()
                .map(invalidated -> new InvalidatedPlan(invalidated, issue.getIssueNumber()))
                .toList();
        return new LifecycleCommit(issue, current, invalidatedPlans);
    }

    /** Supersedes exactly the latest pending row and queues its feedback atomically. */
    @Transactional
    public LifecycleCommit requestRevision(Long issueId, Long expectedVersionId, String guidance) {
        TrackedIssue issue = requireIssueForUpdate(issueId);
        PlanningVersion current = requireCurrentPending(issue);
        if (!Objects.equals(current.getId(), expectedVersionId)) {
            throw new IllegalStateException(
                    "Stale revision: current pending version is " + current.getVersionNumber());
        }

        current.supersede();
        versions.save(current);
        versions.flush();
        issue.setPlanFeedback(guidance);
        issue.setStatus(IssueStatus.PENDING);
        issues.save(issue);
        issues.flush();
        return new LifecycleCommit(issue, current, List.of());
    }

    /**
     * Records a provider/parser failure only while the exact generation token still owns the
     * planning checkpoint. A concurrent winning proposal is never overwritten with FAILED.
     */
    @Transactional
    public FailureCommit failPlanning(GenerationContext expected, String reason) {
        TrackedIssue issue = requireIssueForUpdate(expected.issueId());
        PlanningVersion latest = latestForUpdate(expected.issueId());
        if (!generationContextMatches(expected, issue, latest) || !isActivePlanning(issue)) {
            return new FailureCommit(false, issue);
        }
        issue.setStatus(IssueStatus.FAILED);
        issue.setCurrentPhase(null);
        issue.setLastFailureReason(reason);
        issues.save(issue);
        issues.flush();
        return new FailureCommit(true, issue);
    }

    /** Conditional failure path for validation errors raised before a generation token exists. */
    @Transactional
    public FailureCommit failPlanningIfActive(Long issueId, String reason) {
        TrackedIssue issue = requireIssueForUpdate(issueId);
        if (!isActivePlanning(issue)) {
            return new FailureCommit(false, issue);
        }
        issue.setStatus(IssueStatus.FAILED);
        issue.setCurrentPhase(null);
        issue.setLastFailureReason(reason);
        issues.save(issue);
        issues.flush();
        return new FailureCommit(true, issue);
    }

    private TrackedIssue requireIssueForUpdate(Long issueId) {
        requireRepositoryLocking("plan lifecycle mutations");
        Long repoId = issues.findRepoIdByIssueId(issueId)
                .orElseThrow(() -> new IllegalArgumentException("Issue not found: " + issueId));
        repos.findByIdForUpdate(repoId)
                .orElseThrow(() -> new IllegalStateException("Repository no longer exists"));
        return issues.findByIdForPlanning(issueId)
                .orElseThrow(() -> new IllegalArgumentException("Issue not found: " + issueId));
    }

    private List<TrackedIssue> lockRepositoryIssuesForApproval(Long issueId) {
        requireRepositoryLocking("plan approval");
        Long repoId = issues.findRepoIdByIssueId(issueId)
                .orElseThrow(() -> new IllegalArgumentException("Issue not found: " + issueId));
        repos.findByIdForUpdate(repoId)
                .orElseThrow(() -> new IllegalStateException("Repository no longer exists"));
        return issues.findByRepoIdForUpdateOrderByIssueNumber(repoId);
    }

    private void requireRepositoryLocking(String mutation) {
        if (repos == null) {
            throw new IllegalStateException(
                    "Repository locking is required for " + mutation);
        }
    }

    private PlanningVersion requireCurrentPending(TrackedIssue issue) {
        if (issue.getStatus() != IssueStatus.AWAITING_PLAN_APPROVAL) {
            throw new IllegalStateException(
                    "Issue is not awaiting plan approval: " + issue.getStatus());
        }
        PlanningVersion current = latestForUpdate(issue.getId());
        if (current == null) {
            throw new IllegalStateException("Issue has no planning version");
        }
        if (current.getState() != PlanningVersionState.PENDING) {
            throw new IllegalStateException("Issue has no current pending planning version");
        }
        return current;
    }

    private PlanningVersion latestForUpdate(Long issueId) {
        List<PlanningVersion> latest = versions.findLatestByIssueIdForUpdate(issueId);
        return latest.isEmpty() ? null : latest.getFirst();
    }

    private void requireExpectedVersion(PlanningVersion current, Long expectedVersionId) {
        if (!Objects.equals(current.getId(), expectedVersionId)) {
            throw new IllegalStateException(
                    "Stale approval: current pending version is " + current.getVersionNumber());
        }
    }

    private void requireUnchangedGenerationContext(GenerationContext expected,
                                                   TrackedIssue issue,
                                                   PlanningVersion latest) {
        if (!generationContextMatches(expected, issue, latest)) {
            throw new StalePlanningGenerationException(
                    "Stale planning generation: issue or current planning version changed");
        }
    }

    private boolean generationContextMatches(GenerationContext expected,
                                             TrackedIssue issue,
                                             PlanningVersion latest) {
        PreviousVersion previous = expected.previous();
        boolean sameLatest = previous == null
                ? latest == null
                : latest != null
                    && Objects.equals(previous.id(), latest.getId())
                    && previous.versionNumber() == latest.getVersionNumber();
        boolean sameFeedback = Objects.equals(expected.feedback(), normalize(issue.getPlanFeedback()));
        boolean sameRouting = Objects.equals(expected.model(), normalize(issue.getResolvedImplModel()))
                && expected.provider() == issue.getResolvedAgentProvider();
        return sameLatest && sameFeedback && sameRouting;
    }

    private boolean isActivePlanning(TrackedIssue issue) {
        String phase = normalize(issue.getCurrentPhase());
        return issue.getStatus() == IssueStatus.IN_PROGRESS
                && phase != null
                && phase.equalsIgnoreCase("PLANNING");
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    public record PreviousVersion(Long id, int versionNumber,
                                  String designSpec, String implementationPlan) {}

    public record GenerationContext(Long issueId, String model, AgentProvider provider,
                                    String feedback, PreviousVersion previous) {}

    public record GenerationCommit(TrackedIssue issue, PlanningVersion version,
                                   boolean revision) {}

    public record LifecycleCommit(TrackedIssue issue, PlanningVersion version,
                                  List<InvalidatedPlan> invalidatedPlans) {
        public LifecycleCommit {
            invalidatedPlans = List.copyOf(invalidatedPlans);
        }

        public LifecycleCommit(TrackedIssue issue, PlanningVersion version) {
            this(issue, version, List.of());
        }
    }

    public record InvalidatedPlan(TrackedIssue issue, int ownerIssueNumber) {}

    public record FailureCommit(boolean committed, TrackedIssue issue) {}

    public static final class StalePlanningGenerationException extends IllegalStateException {
        public StalePlanningGenerationException(String message) {
            super(message);
        }
    }
}

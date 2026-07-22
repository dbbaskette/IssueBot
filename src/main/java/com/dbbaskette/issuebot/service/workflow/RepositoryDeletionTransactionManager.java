package com.dbbaskette.issuebot.service.workflow;

import com.dbbaskette.issuebot.model.TrackedIssue;
import com.dbbaskette.issuebot.model.WatchedRepo;
import com.dbbaskette.issuebot.repository.CostTrackingRepository;
import com.dbbaskette.issuebot.repository.EventRepository;
import com.dbbaskette.issuebot.repository.IterationRepository;
import com.dbbaskette.issuebot.repository.PlanningVersionRepository;
import com.dbbaskette.issuebot.repository.TrackedIssueRepository;
import com.dbbaskette.issuebot.repository.WatchedRepoRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** Repository-first transactional boundary for destructive repository removal. */
@Service
public class RepositoryDeletionTransactionManager {

    private final WatchedRepoRepository repos;
    private final TrackedIssueRepository issues;
    private final IterationRepository iterations;
    private final CostTrackingRepository costs;
    private final EventRepository events;
    private final PlanningVersionRepository planningVersions;

    public RepositoryDeletionTransactionManager(WatchedRepoRepository repos,
                                                TrackedIssueRepository issues,
                                                IterationRepository iterations,
                                                CostTrackingRepository costs,
                                                EventRepository events,
                                                PlanningVersionRepository planningVersions) {
        this.repos = repos;
        this.issues = issues;
        this.iterations = iterations;
        this.costs = costs;
        this.events = events;
        this.planningVersions = planningVersions;
    }

    @Transactional
    public boolean delete(Long repoId) {
        WatchedRepo repo = repos.findByIdForUpdate(repoId).orElse(null);
        if (repo == null) {
            return false;
        }

        List<TrackedIssue> lockedIssues =
                issues.findByRepoIdForUpdateOrderByIssueNumber(repoId);
        events.deleteByRepo(repo);

        List<Long> issueIds = lockedIssues.stream().map(TrackedIssue::getId).toList();
        if (!issueIds.isEmpty()) {
            lockedIssues.forEach(issue -> issue.setApprovedPlanningVersion(null));
            issues.saveAllAndFlush(lockedIssues);
            planningVersions.deleteByIssueIds(issueIds);
            planningVersions.flush();
        }

        for (TrackedIssue issue : lockedIssues) {
            costs.deleteByIssue(issue);
            iterations.deleteByIssue(issue);
        }
        issues.deleteAll(lockedIssues);
        issues.flush();
        repos.delete(repo);
        repos.flush();
        return true;
    }
}

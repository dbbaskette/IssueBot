package com.dbbaskette.issuebot.service.workflow;

import com.dbbaskette.issuebot.model.Iteration;
import com.dbbaskette.issuebot.model.PlanningVersionState;
import com.dbbaskette.issuebot.model.TrackedIssue;

import java.util.List;

/** Shared persisted-verdict classification for generic versus guided Plan First retries. */
public final class PlanRetryClassification {

    private PlanRetryClassification() {}

    public static boolean isSecondPlanFirstMiss(TrackedIssue issue, List<Iteration> iterations) {
        return issue.effectivePlanFirst()
                && issue.getPlanConformanceAttempt() == 2
                && Boolean.FALSE.equals(latestPersistedReviewVerdict(iterations));
    }

    public static boolean requiresGuidedPlanRetry(TrackedIssue issue, List<Iteration> iterations) {
        return isSecondPlanFirstMiss(issue, iterations)
                && issue.getApprovedPlanningVersion() != null
                && issue.getApprovedPlanningVersion().getState() == PlanningVersionState.APPROVED;
    }

    static Boolean latestPersistedReviewVerdict(List<Iteration> iterations) {
        if (iterations == null) {
            return null;
        }
        Iteration latestPersisted = null;
        Iteration latestUnpersisted = null;
        for (Iteration iteration : iterations) {
            if (iteration.getReviewPassed() != null || iteration.getReviewJson() != null) {
                if (iteration.getId() == null) {
                    latestUnpersisted = iteration;
                } else if (latestPersisted == null
                        || iteration.getId() > latestPersisted.getId()) {
                    latestPersisted = iteration;
                }
            }
        }
        Iteration latest = latestPersisted != null ? latestPersisted : latestUnpersisted;
        return latest == null ? null : latest.getReviewPassed();
    }
}

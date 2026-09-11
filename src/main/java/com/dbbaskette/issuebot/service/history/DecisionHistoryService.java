package com.dbbaskette.issuebot.service.history;

import com.dbbaskette.issuebot.model.IssueDecision;
import com.dbbaskette.issuebot.repository.*;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.Objects;

@Service
public class DecisionHistoryService {
    private final IssueDecisionRepository decisions;
    private final TrackedIssueRepository issues;
    private final PlanningVersionRepository plans;
    private final IterationRepository iterations;
    private final StageApprovalRepository approvals;
    private final IssueGuidanceRepository guidance;

    public DecisionHistoryService(IssueDecisionRepository decisions, TrackedIssueRepository issues,
            PlanningVersionRepository plans, IterationRepository iterations,
            StageApprovalRepository approvals, IssueGuidanceRepository guidance) {
        this.decisions = decisions; this.issues = issues; this.plans = plans;
        this.iterations = iterations; this.approvals = approvals; this.guidance = guidance;
    }

    /**
     * Joins the caller transaction. Obtain ALL domain locks before calling append; this acquires
     * a final ledger-only lock held through commit. Never acquire controls/repo/issue locks after
     * append (including loops: lock every affected issue before appending the first decision).
     * Duplicate delivery is serialized before insertion, so no failed flush poisons the transaction.
     */
    @Transactional
    public IssueDecision append(DecisionDraft draft) {
        Objects.requireNonNull(draft, "draft");
        // Non-locking ownership reads precede the terminal ledger lock. Exact replay remains valid
        // after an artifact/issue has been deleted; only a new row requires current ownership.
        var existing = decisions.findBySourceKey(draft.sourceKey());
        if (existing.isPresent()) return identical(existing.get(), draft);
        validateOwnership(draft);
        decisions.lockAppendBoundary();
        return decisions.findBySourceKey(draft.sourceKey())
                .map(row -> identical(row, draft)).orElseGet(() -> decisions.insert(draft));
    }

    private IssueDecision identical(IssueDecision row, DecisionDraft draft) {
        if (!row.asDraft().equals(draft)) throw new IllegalStateException("Decision source key conflicts with an existing decision");
        return row;
    }

    private void validateOwnership(DecisionDraft d) {
        Long repoId = issues.findRepoIdByIssueId(d.issueId())
                .orElseThrow(() -> new IllegalArgumentException("Decision issue does not exist"));
        if (d.repoId() != null && !repoId.equals(d.repoId())) invalidArtifact();
        if (d.planVersionId() != null && !plans.findById(d.planVersionId())
                .map(p -> p.getIssue().getId().equals(d.issueId())).orElse(false)) invalidArtifact();
        if (d.iterationId() != null && !iterations.findById(d.iterationId())
                .map(i -> i.getIssue().getId().equals(d.issueId())).orElse(false)) invalidArtifact();
        if (d.stageApprovalId() != null && !approvals.findById(d.stageApprovalId())
                .map(a -> a.getIssue().getId().equals(d.issueId())).orElse(false)) invalidArtifact();
        if (d.guidanceId() != null && !guidance.findById(d.guidanceId())
                .map(g -> g.getIssueId().equals(d.issueId())).orElse(false)) invalidArtifact();
    }

    private static void invalidArtifact() { throw new IllegalArgumentException("Decision artifact does not belong to the issue"); }

    @Transactional(readOnly = true)
    public Page<IssueDecision> page(Long issueId, Pageable pageable) {
        if (issueId == null || issueId <= 0 || pageable == null || pageable.isUnpaged()
                || pageable.getPageSize() > 25 || pageable.getOffset() > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Invalid decision history page");
        }
        return decisions.page(issueId, PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(),
                Sort.by(Sort.Direction.DESC, "createdAt", "id")));
    }

    @Transactional(readOnly = true)
    public LocalDateTime trackingStartedAt() { return decisions.trackingStartedAt(); }
}

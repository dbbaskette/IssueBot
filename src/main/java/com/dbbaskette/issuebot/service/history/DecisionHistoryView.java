package com.dbbaskette.issuebot.service.history;

import com.dbbaskette.issuebot.model.IssueDecision;
import com.dbbaskette.issuebot.repository.IterationRepository;
import com.dbbaskette.issuebot.repository.PlanningVersionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.ArrayList;
import java.util.List;

/** Fixed copy plus local destinations verified against the current artifact owner. */
@Service
public class DecisionHistoryView {
    public record Artifact(String label, String href) {}
    public record Entry(IssueDecision decision, String actor, String action, String outcome,
                        String rationale, List<Artifact> artifacts) {}
    private final PlanningVersionRepository plans;
    private final IterationRepository iterations;

    public DecisionHistoryView(PlanningVersionRepository plans, IterationRepository iterations) {
        this.plans = plans; this.iterations = iterations;
    }

    @Transactional(readOnly = true)
    public List<Entry> entries(List<IssueDecision> decisions) {
        return decisions.stream().map(this::entry).toList();
    }

    private Entry entry(IssueDecision d) {
        List<Artifact> artifacts = new ArrayList<>();
        if (d.getPlanVersionId() != null) {
            String href = plans.findById(d.getPlanVersionId())
                    .filter(p -> p.getIssue().getId().equals(d.getIssueId()))
                    .map(p -> "/issues/" + d.getIssueId() + "?planVersion=" + p.getVersionNumber() + "#plan-review")
                    .orElse(null);
            artifacts.add(new Artifact("Plan artifact " + d.getPlanVersionId(), href));
        }
        if (d.getIterationId() != null) {
            String href = iterations.findById(d.getIterationId())
                    .filter(i -> i.getIssue().getId().equals(d.getIssueId()))
                    .map(i -> "/issues/" + d.getIssueId() + "#iteration-" + i.getId()).orElse(null);
            artifacts.add(new Artifact("Iteration artifact " + d.getIterationId(), href));
        }
        // No precise historical guidance/stage/PR viewer exists. IDs remain plain labels rather
        // than linking to an unrelated current action, external URL, or unverified artifact.
        if (d.getStageApprovalId() != null) artifacts.add(new Artifact("Stage approval " + d.getStageApprovalId(), null));
        if (d.getGuidanceId() != null) artifacts.add(new Artifact("Guidance " + d.getGuidanceId(), null));
        if (d.getPrNumber() != null) artifacts.add(new Artifact("PR #" + d.getPrNumber(), null));
        String actor = switch (d.getActor()) {
            case OPERATOR -> "Operator"; case AUTOMATION -> "Automation"; case LEGACY_UNKNOWN -> "Actor unavailable";
        };
        String action = switch (d.getAction()) {
            case APPROVE -> "Approved"; case REJECT -> "Rejected"; case GUIDE -> "Guidance submitted";
            case START -> "Start requested"; case RETRY -> "Retry requested"; case PAUSE -> "Paused";
            case RESUME -> "Resumed"; case STOP -> "Stopped"; case AUTO_STAGE -> "Automatic stage decision";
            case AUTO_RETRY -> "Automatic retry decision"; case EXTERNAL_RESULT -> "External result";
        };
        String outcome = switch (d.getOutcome()) {
            case ACCEPTED -> "Accepted"; case SUCCEEDED -> "Succeeded"; case FAILED -> "Failed"; case UNKNOWN -> "Unknown";
        };
        String rationale = d.getReason() == null ? "Rationale unavailable." : switch (d.getReason()) {
            case USER_REQUEST -> "Requested by an operator.";
            case POLICY_AUTOMATIC -> "Continued under the configured automatic workflow policy.";
            case GUIDANCE_ATTACHED -> "Operator guidance was attached to this transition.";
            case REVIEW_CHANGES_REQUIRED -> "Review required changes before continuing.";
            case LIMIT_REACHED -> "A configured workflow limit was reached.";
            case GLOBAL_CONTROL -> "Applied through the global processing control.";
            case EXTERNAL_CONFIRMED -> "The external result was confirmed.";
            case EXTERNAL_UNCERTAIN -> "The external result is not yet confirmed; reconciliation is required.";
        };
        return new Entry(d, actor, action, outcome, rationale, List.copyOf(artifacts));
    }
}

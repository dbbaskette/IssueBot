package com.dbbaskette.issuebot.service.ui;

import com.dbbaskette.issuebot.model.TrackedIssue;
import com.dbbaskette.issuebot.util.Humanize;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Component
public class IssueNextActionResolver {
    public IssueNextAction resolve(TrackedIssue issue) {
        if (issue == null || issue.getStatus() == null) {
            return action("Review the current issue state.", "View issue", baseHref(issue),
                    IssueNextAction.Tone.NEUTRAL, false);
        }
        return switch (issue.getStatus()) {
            case AWAITING_APPROVAL -> action(
                    issue.getPrNumber() == null ? "Review and decide the pull request."
                            : "Review and decide PR #" + issue.getPrNumber() + ".",
                    "Review approval", anchored(issue, "approval-decision"), IssueNextAction.Tone.ACTION, true);
            case AWAITING_PLAN_APPROVAL -> action("Review and approve the current plan.",
                    "Review plan", anchored(issue, "plan-review"), IssueNextAction.Tone.ACTION, true);
            case READY_TO_START -> action("Plan approved. Start implementation when ready or return it to the queue.",
                    "Open start controls", anchored(issue, "ready-to-start"), IssueNextAction.Tone.ACTION, true);
            case AWAITING_DECOMPOSITION -> action("Review the proposed issue split.",
                    "Review split", anchored(issue, "status-actions"), IssueNextAction.Tone.ACTION, true);
            case FAILED -> action("Review the failure, add guidance, or retry.",
                    "Resolve failure", anchored(issue, "recovery"), IssueNextAction.Tone.ACTION, true);
            case COOLDOWN -> action("Review the failed attempt before retrying.",
                    "Review recovery", anchored(issue, "recovery"), IssueNextAction.Tone.ACTION, true);
            case IN_PROGRESS -> action(inProgressSummary(issue), "View progress",
                    anchored(issue, "live-status"), IssueNextAction.Tone.ACTIVE, false);
            case QUEUED -> action("Queued and ready when processing capacity is available.",
                    "View issue", baseHref(issue), IssueNextAction.Tone.WAITING, false);
            case PENDING -> action("Ready to start manually or enter the processing queue.",
                    "Review and start", anchored(issue, "status-actions"), IssueNextAction.Tone.WAITING, false);
            case BLOCKED -> action(blockedSummary(issue), "View blockers",
                    anchored(issue, "status-actions"), IssueNextAction.Tone.WAITING, false);
            case COMPLETED -> action("No action needed — completed.", null, null,
                    IssueNextAction.Tone.SUCCESS, false);
            case DECOMPOSED -> action("No action needed — work continues in the split issues.", null, null,
                    IssueNextAction.Tone.NEUTRAL, false);
        };
    }

    private static String inProgressSummary(TrackedIssue issue) {
        String phase = Humanize.phase(issue.getCurrentPhase());
        return phase == null || phase.isBlank()
                ? "IssueBot is processing this issue." : "IssueBot is " + phase + ".";
    }

    private static String blockedSummary(TrackedIssue issue) {
        List<Integer> blockers = issue.getBlockerNumbers();
        if (blockers.isEmpty()) return "Waiting for blocking issues to complete.";
        if (blockers.size() == 1) return "Waiting for issue #" + blockers.getFirst() + ".";
        return "Waiting for issues " + blockers.stream().map(n -> "#" + n)
                .collect(Collectors.joining(", ")) + ".";
    }

    private static IssueNextAction action(String summary, String label, String href,
                                          IssueNextAction.Tone tone, boolean required) {
        if (href == null) label = null;
        return new IssueNextAction(summary, label, href, tone, required);
    }

    private static String anchored(TrackedIssue issue, String anchor) {
        String base = baseHref(issue);
        return base == null ? null : base + "#" + anchor;
    }

    private static String baseHref(TrackedIssue issue) {
        return issue == null || issue.getId() == null ? null : "/issues/" + issue.getId();
    }
}

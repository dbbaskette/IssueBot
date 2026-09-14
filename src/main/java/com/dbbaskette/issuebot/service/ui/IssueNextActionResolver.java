package com.dbbaskette.issuebot.service.ui;

import com.dbbaskette.issuebot.model.IssueStatus;
import com.dbbaskette.issuebot.model.TrackedIssue;
import com.dbbaskette.issuebot.util.Humanize;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

@Component
public class IssueNextActionResolver {
    public IssueNextAction resolve(TrackedIssue issue) {
        return resolve(issue, null);
    }

    public IssueNextAction resolve(TrackedIssue issue, TrackedIssue readyReservation) {
        if (issue == null || issue.getStatus() == null) {
            return action("Review the current issue state.", "View issue", baseHref(issue),
                    IssueNextAction.Tone.NEUTRAL, false);
        }
        if (isHeldByReadyReservation(issue, readyReservation)) {
            return action(
                    "Waiting for issue #" + readyReservation.getIssueNumber()
                            + " to start or release the repository slot.",
                    "Open issue #" + readyReservation.getIssueNumber(),
                    anchored(readyReservation, "ready-to-start"),
                    IssueNextAction.Tone.WAITING, false);
        }
        if (com.dbbaskette.issuebot.service.workflow.StageApprovalService.isStageWaiting(issue)) {
            String stage = issue.getCurrentPhase().substring("STAGE_APPROVAL_".length()).toLowerCase();
            return action("Choose the model where applicable and approve " + stage + " to continue.",
                    "Review stage", anchored(issue, "stage-approval"), IssueNextAction.Tone.ACTION, true);
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
            case FAILED -> "COMPLETION".equals(issue.getCurrentPhase())
                    && issue.getLastFailureReason() != null
                    && issue.getLastFailureReason().startsWith("Completion failed: Managed merge failed:")
                    ? action("The review passed. Resolve the merge blocker, then resume this PR without recoding.",
                            "Resume merge", anchored(issue, "recovery"), IssueNextAction.Tone.ACTION, true)
                    : action("Review the failure, add guidance, or retry.",
                            "Resolve failure", anchored(issue, "recovery"), IssueNextAction.Tone.ACTION, true);
            case COOLDOWN -> action("Review the failed attempt before retrying.",
                    "Review recovery", anchored(issue, "recovery"), IssueNextAction.Tone.ACTION, true);
            case IN_PROGRESS -> action(inProgressSummary(issue), "View progress",
                    anchored(issue, "live-status"), IssueNextAction.Tone.ACTIVE, false);
            case QUEUED -> action("Queued and ready when processing capacity is available.",
                    "View issue", baseHref(issue), IssueNextAction.Tone.WAITING, false);
            case PENDING -> action("Waiting to resume or start manually.",
                    "View issue", baseHref(issue), IssueNextAction.Tone.WAITING, false);
            case BLOCKED -> action(blockedSummary(issue), "View blockers",
                    anchored(issue, "status-actions"), IssueNextAction.Tone.WAITING, false);
            case COMPLETED -> action("No action needed — completed.", null, null,
                    IssueNextAction.Tone.SUCCESS, false);
            case DECOMPOSED -> action("No action needed — work continues in the split issues.", null, null,
                    IssueNextAction.Tone.NEUTRAL, false);
            case CANCELLED -> action("No action needed — cancelled.", null, null,
                    IssueNextAction.Tone.NEUTRAL, false);
        };
    }

    private static boolean isHeldByReadyReservation(TrackedIssue issue,
                                                     TrackedIssue readyReservation) {
        if (issue.getStatus() != IssueStatus.QUEUED
                && issue.getStatus() != IssueStatus.PENDING) {
            return false;
        }
        if (readyReservation == null
                || readyReservation.getStatus()
                != IssueStatus.READY_TO_START
                || issue.getId() == null || readyReservation.getId() == null
                || Objects.equals(issue.getId(), readyReservation.getId())) {
            return false;
        }
        if (issue.getRepo() == null || readyReservation.getRepo() == null) {
            return false;
        }
        Long issueRepoId = issue.getRepo().getId();
        Long reservationRepoId = readyReservation.getRepo().getId();
        return issue.getRepo() == readyReservation.getRepo()
                || issueRepoId != null && Objects.equals(issueRepoId, reservationRepoId);
    }

    private static String inProgressSummary(TrackedIssue issue) {
        return switch (issue.getCurrentPhase() == null ? "" : issue.getCurrentPhase().toUpperCase(java.util.Locale.ROOT)) {
            case "SETUP" -> "Preparing the repository.";
            case "PLANNING" -> "Preparing the plan.";
            case "IMPLEMENTATION" -> "The coding agent is working.";
            case "LOCAL_CHECKS" -> "Preparing the coding harness's test evidence for review.";
            case "CI_VERIFICATION" -> "Waiting for CI checks.";
            case "PR_CREATION" -> "Creating the pull request.";
            case "INDEPENDENT_REVIEW" -> "Independent review is in progress.";
            case "COMPLETION" -> "Checking and merging the reviewed PR.";
            default -> {
                String phase = Humanize.phase(issue.getCurrentPhase());
                yield phase == null || phase.isBlank()
                        ? "IssueBot is processing this issue." : "Current stage: " + phase + ".";
            }
        };
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

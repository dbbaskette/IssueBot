package com.dbbaskette.issuebot.service.ui;

import com.dbbaskette.issuebot.model.IssueStatus;
import com.dbbaskette.issuebot.model.TrackedIssue;
import com.dbbaskette.issuebot.model.WatchedRepo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class IssueNextActionResolverTest {
    private final IssueNextActionResolver resolver = new IssueNextActionResolver();

    static Stream<Arguments> statuses() {
        return Stream.of(
                Arguments.of(IssueStatus.AWAITING_APPROVAL, "Review and decide the pull request.", "Review approval", "/issues/7#approval-decision", IssueNextAction.Tone.ACTION, true),
                Arguments.of(IssueStatus.AWAITING_PLAN_APPROVAL, "Review and approve the current plan.", "Review plan", "/issues/7#plan-review", IssueNextAction.Tone.ACTION, true),
                Arguments.of(IssueStatus.AWAITING_DECOMPOSITION, "Review the proposed issue split.", "Review split", "/issues/7#status-actions", IssueNextAction.Tone.ACTION, true),
                Arguments.of(IssueStatus.FAILED, "Review the failure, add guidance, or retry.", "Resolve failure", "/issues/7#recovery", IssueNextAction.Tone.ACTION, true),
                Arguments.of(IssueStatus.COOLDOWN, "Review the failed attempt before retrying.", "Review recovery", "/issues/7#recovery", IssueNextAction.Tone.ACTION, true),
                Arguments.of(IssueStatus.IN_PROGRESS, "IssueBot is processing this issue.", "View progress", "/issues/7#live-status", IssueNextAction.Tone.ACTIVE, false),
                Arguments.of(IssueStatus.QUEUED, "Queued and ready when processing capacity is available.", "View issue", "/issues/7", IssueNextAction.Tone.WAITING, false),
                Arguments.of(IssueStatus.PENDING, "Ready to start manually or enter the processing queue.", "Review and start", "/issues/7#status-actions", IssueNextAction.Tone.WAITING, false),
                Arguments.of(IssueStatus.BLOCKED, "Waiting for blocking issues to complete.", "View blockers", "/issues/7#status-actions", IssueNextAction.Tone.WAITING, false),
                Arguments.of(IssueStatus.COMPLETED, "No action needed — completed.", null, null, IssueNextAction.Tone.SUCCESS, false),
                Arguments.of(IssueStatus.DECOMPOSED, "No action needed — work continues in the split issues.", null, null, IssueNextAction.Tone.NEUTRAL, false));
    }

    @ParameterizedTest
    @MethodSource("statuses")
    void resolvesEveryStatus(IssueStatus status, String summary, String label, String href,
                             IssueNextAction.Tone tone, boolean required) {
        TrackedIssue issue = issue(status);
        IssueNextAction action = resolver.resolve(issue);
        assertThat(action).extracting(IssueNextAction::summary, IssueNextAction::ctaLabel,
                        IssueNextAction::href, IssueNextAction::tone, IssueNextAction::actionRequired)
                .containsExactly(summary, label, href, tone, required);
        assertThat(action.hasAction()).isEqualTo(label != null && href != null);
    }

    @Test
    void usesPrPhaseAndBlockerDetailsWhenPresent() {
        TrackedIssue approval = issue(IssueStatus.AWAITING_APPROVAL);
        approval.setPrNumber(158);
        assertThat(resolver.resolve(approval).summary()).isEqualTo("Review and decide PR #158.");

        TrackedIssue running = issue(IssueStatus.IN_PROGRESS);
        running.setCurrentPhase("CI_VERIFICATION");
        assertThat(resolver.resolve(running).summary()).isEqualTo("IssueBot is CI Verification.");

        TrackedIssue oneBlocker = issue(IssueStatus.BLOCKED);
        oneBlocker.setBlockedByIssues("12");
        assertThat(resolver.resolve(oneBlocker).summary()).isEqualTo("Waiting for issue #12.");

        TrackedIssue manyBlockers = issue(IssueStatus.BLOCKED);
        manyBlockers.setBlockedByIssues("12, 19");
        assertThat(resolver.resolve(manyBlockers).summary()).isEqualTo("Waiting for issues #12, #19.");
    }

    @Test
    void nullStatusAndMissingIdUseSafeFallbacks() {
        TrackedIssue unknown = issue(IssueStatus.PENDING);
        unknown.setStatus(null);
        assertThat(resolver.resolve(unknown)).isEqualTo(new IssueNextAction(
                "Review the current issue state.", "View issue", "/issues/7",
                IssueNextAction.Tone.NEUTRAL, false));

        TrackedIssue unsaved = issue(IssueStatus.FAILED);
        unsaved.setId(null);
        IssueNextAction action = resolver.resolve(unsaved);
        assertThat(action.summary()).isEqualTo("Review the failure, add guidance, or retry.");
        assertThat(action.ctaLabel()).isNull();
        assertThat(action.href()).isNull();
        assertThat(action.hasAction()).isFalse();
    }

    private static TrackedIssue issue(IssueStatus status) {
        TrackedIssue issue = new TrackedIssue(new WatchedRepo("acme", "widgets"), 42, "Title");
        issue.setId(7L);
        issue.setStatus(status);
        return issue;
    }
}

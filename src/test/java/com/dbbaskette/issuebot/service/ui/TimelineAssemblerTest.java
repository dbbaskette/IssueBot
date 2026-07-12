package com.dbbaskette.issuebot.service.ui;

import com.dbbaskette.issuebot.model.CostTracking;
import com.dbbaskette.issuebot.model.Event;
import com.dbbaskette.issuebot.model.IssueStatus;
import com.dbbaskette.issuebot.model.Iteration;
import com.dbbaskette.issuebot.model.TrackedIssue;
import com.dbbaskette.issuebot.model.WatchedRepo;
import com.dbbaskette.issuebot.service.ui.TimelineAssembler.IterationTimeline;
import com.dbbaskette.issuebot.service.ui.TimelineAssembler.Segment;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link TimelineAssembler}, using realistic event sequences copied from the
 * exact {@code eventService.log("PHASE_...")} call sites in {@code IssueWorkflowService} (see
 * that class's phaseSetup/phaseImplementation/phaseCiVerification/phasePrCreation/
 * phaseIndependentReview/phaseCompletion methods and the main processIssue loop).
 */
class TimelineAssemblerTest {

    private final TimelineAssembler assembler = new TimelineAssembler();
    private static final LocalDateTime T0 = LocalDateTime.of(2026, 7, 10, 9, 0, 0);

    private static WatchedRepo repo() {
        return new WatchedRepo("acme", "widgets");
    }

    private static TrackedIssue issue(WatchedRepo repo, IssueStatus status) {
        TrackedIssue issue = new TrackedIssue(repo, 42, "Some issue");
        issue.setId(1L);
        issue.setStatus(status);
        return issue;
    }

    private static Event ev(String type, LocalDateTime ts) {
        Event event = new Event(type, "msg");
        setCreatedAt(event, ts);
        return event;
    }

    /** Event#createdAt has no setter (stamped at construction) — reflection sets a fixed time
     *  for deterministic tests, mirroring how the real column is immutable after insert. */
    private static void setCreatedAt(Event event, LocalDateTime ts) {
        try {
            Field f = Event.class.getDeclaredField("createdAt");
            f.setAccessible(true);
            f.set(event, ts);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private static Iteration iteration(TrackedIssue issue, int num, LocalDateTime startedAt) {
        Iteration iteration = new Iteration(issue, num);
        setStartedAt(iteration, startedAt);
        return iteration;
    }

    private static void setStartedAt(Iteration iteration, LocalDateTime ts) {
        try {
            Field f = Iteration.class.getDeclaredField("startedAt");
            f.setAccessible(true);
            f.set(iteration, ts);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private static CostTracking cost(TrackedIssue issue, int iterationNum, String amount, String phase) {
        CostTracking c = new CostTracking(issue, iterationNum, 1000, 500, new BigDecimal(amount), "claude-opus-4-8");
        c.setPhase(phase);
        return c;
    }

    // =====================================================
    // Happy path: 2 iterations — iteration 1 fails at CI, iteration 2 passes through Review
    // =====================================================

    @Test
    void happyPath_twoIterations_firstFailsAtCi_secondPassesThroughReview() {
        WatchedRepo repo = repo();
        TrackedIssue issue = issue(repo, IssueStatus.COMPLETED);

        // --- Iteration 1: implementation ok, local checks ok, CI fails ---
        LocalDateTime iter1Start = T0;
        Iteration iter1 = iteration(issue, 1, iter1Start);
        iter1.setLocalCheckResult("PASSED");
        iter1.setCiResult("FAILED");

        // --- Iteration 2: implementation ok, local checks ok, CI passes, review passes ---
        LocalDateTime iter2Start = T0.plusMinutes(5);
        Iteration iter2 = iteration(issue, 2, iter2Start);
        iter2.setLocalCheckResult("PASSED");
        iter2.setCiResult("PASSED");
        iter2.setReviewPassed(true);

        List<Event> events = List.of(
                ev("ITERATION_STARTED", iter1Start),
                ev("PHASE_IMPLEMENTATION", iter1Start.plusSeconds(1)),
                ev("PHASE_IMPLEMENTATION_COMPLETE", iter1Start.plusSeconds(61)),
                ev("PHASE_LOCAL_CHECKS", iter1Start.plusSeconds(62)),
                ev("PHASE_LOCAL_CHECKS_COMPLETE", iter1Start.plusSeconds(82)),
                ev("PHASE_CI_VERIFICATION", iter1Start.plusSeconds(83)),
                ev("PHASE_CI_COMPLETE", iter1Start.plusSeconds(143)),

                ev("ITERATION_STARTED", iter2Start),
                ev("PHASE_IMPLEMENTATION", iter2Start.plusSeconds(1)),
                ev("PHASE_IMPLEMENTATION_COMPLETE", iter2Start.plusSeconds(91)),
                ev("PHASE_LOCAL_CHECKS", iter2Start.plusSeconds(92)),
                ev("PHASE_LOCAL_CHECKS_COMPLETE", iter2Start.plusSeconds(112)),
                ev("PHASE_CI_VERIFICATION", iter2Start.plusSeconds(113)),
                ev("PHASE_CI_COMPLETE", iter2Start.plusSeconds(173)),
                ev("PHASE_PR_CREATION", iter2Start.plusSeconds(174)),
                ev("PHASE_PR_CREATION_COMPLETE", iter2Start.plusSeconds(178)),
                ev("PHASE_INDEPENDENT_REVIEW", iter2Start.plusSeconds(179)),
                ev("PHASE_REVIEW_COMPLETE", iter2Start.plusSeconds(239)),
                ev("PHASE_COMPLETION", iter2Start.plusSeconds(240)),
                ev("WORKFLOW_COMPLETED", iter2Start.plusSeconds(245))
        );

        List<CostTracking> costs = List.of(
                cost(issue, 1, "0.50", "IMPLEMENTATION"),
                cost(issue, 2, "0.60", "IMPLEMENTATION"),
                cost(issue, 2, "0.25", "REVIEW")
        );

        List<IterationTimeline> timeline = assembler.assemble(issue, events, List.of(iter1, iter2), costs);

        assertThat(timeline).hasSize(2);

        IterationTimeline t1 = timeline.get(0);
        assertThat(t1.iterationNum()).isEqualTo(1);
        assertThat(t1.segments()).extracting(Segment::stageName)
                .containsExactly("Implementation", "Local Checks", "CI");
        assertThat(t1.segments()).extracting(Segment::outcome)
                .containsExactly("ok", "ok", "fail");
        assertThat(t1.segments()).extracting(Segment::durationSecs)
                .containsExactly(60L, 20L, 60L);
        assertThat(t1.totalCost()).isEqualByComparingTo("0.50");
        assertThat(t1.outcomeBadge()).isEqualTo("FAILED");
        // Widths sum to exactly 100
        double sum1 = t1.segments().stream().mapToDouble(Segment::widthPct).sum();
        assertThat(sum1).isCloseTo(100.0, org.assertj.core.data.Offset.offset(0.001));

        IterationTimeline t2 = timeline.get(1);
        assertThat(t2.iterationNum()).isEqualTo(2);
        assertThat(t2.segments()).extracting(Segment::stageName)
                .containsExactly("Implementation", "Local Checks", "CI", "Review");
        assertThat(t2.segments()).extracting(Segment::outcome)
                .containsExactly("ok", "ok", "ok", "ok");
        assertThat(t2.totalCost()).isEqualByComparingTo("0.85");
        assertThat(t2.outcomeBadge()).isEqualTo("PASSED");
        double sum2 = t2.segments().stream().mapToDouble(Segment::widthPct).sum();
        assertThat(sum2).isCloseTo(100.0, org.assertj.core.data.Offset.offset(0.001));
    }

    // =====================================================
    // Missing-events omission
    // =====================================================

    @Test
    void localChecksSegmentOmitted_whenRepoHasNoVerificationCommandsConfigured() {
        WatchedRepo repo = repo();
        TrackedIssue issue = issue(repo, IssueStatus.COMPLETED);
        Iteration iter = iteration(issue, 1, T0);
        iter.setCiResult("PASSED");
        iter.setReviewPassed(true);

        List<Event> events = List.of(
                ev("PHASE_IMPLEMENTATION", T0.plusSeconds(1)),
                ev("PHASE_IMPLEMENTATION_COMPLETE", T0.plusSeconds(61)),
                // no PHASE_LOCAL_CHECKS at all — not configured for this repo
                ev("PHASE_CI_VERIFICATION", T0.plusSeconds(62)),
                ev("PHASE_CI_COMPLETE", T0.plusSeconds(122)),
                ev("PHASE_INDEPENDENT_REVIEW", T0.plusSeconds(130)),
                ev("PHASE_REVIEW_COMPLETE", T0.plusSeconds(190))
        );

        List<IterationTimeline> timeline = assembler.assemble(issue, events, List.of(iter), List.of());

        assertThat(timeline.get(0).segments()).extracting(Segment::stageName)
                .containsExactly("Implementation", "CI", "Review")
                .doesNotContain("Local Checks");
    }

    @Test
    void implementationSegmentOmitted_whenNoCompleteOrFailedEventAndIterationIsNotRunning() {
        // Simulates gappy old-issue data or a process crash mid-phase: a start event with
        // no terminal event, and the iteration is not the currently-running one.
        WatchedRepo repo = repo();
        TrackedIssue issue = issue(repo, IssueStatus.FAILED);
        Iteration iter = iteration(issue, 1, T0);

        List<Event> events = List.of(
                ev("PHASE_IMPLEMENTATION", T0.plusSeconds(1))
                // no complete/failed event — issue ended up FAILED via an unrelated path
        );

        List<IterationTimeline> timeline = assembler.assemble(issue, events, List.of(iter), List.of());

        assertThat(timeline.get(0).segments()).isEmpty();
        assertThat(timeline.get(0).outcomeBadge()).isEqualTo("UNKNOWN");
    }

    @Test
    void ciSegmentAnchorsToPreviousStageEnd_whenCiDisabled() {
        // CI-disabled path: commitAndPush runs silently, only PHASE_CI_SKIPPED is logged —
        // no PHASE_CI_VERIFICATION start event exists at all.
        WatchedRepo repo = repo();
        TrackedIssue issue = issue(repo, IssueStatus.COMPLETED);
        Iteration iter = iteration(issue, 1, T0);
        iter.setCiResult("SKIPPED");

        LocalDateTime implComplete = T0.plusSeconds(61);
        List<Event> events = List.of(
                ev("PHASE_IMPLEMENTATION", T0.plusSeconds(1)),
                ev("PHASE_IMPLEMENTATION_COMPLETE", implComplete),
                ev("PHASE_CI_SKIPPED", T0.plusSeconds(65))
        );

        List<IterationTimeline> timeline = assembler.assemble(issue, events, List.of(iter), List.of());

        List<Segment> segments = timeline.get(0).segments();
        assertThat(segments).extracting(Segment::stageName).containsExactly("Implementation", "CI");
        Segment ci = segments.get(1);
        assertThat(ci.outcome()).isEqualTo("skipped");
        // Anchored to the end of Implementation (61s in) through the skip event (65s in) = 4s.
        assertThat(ci.durationSecs()).isEqualTo(4L);
    }

    @Test
    void ciSegmentRecoveredFromStructuredErrorResult_whenExceptionSkippedTheCompleteEvent() {
        WatchedRepo repo = repo();
        TrackedIssue issue = issue(repo, IssueStatus.FAILED);
        Iteration iter = iteration(issue, 1, T0);
        iter.setCiResult("ERROR");
        // completedAt IS stamped in the exception-catch block even without a PHASE_CI_COMPLETE event.
        setCompletedAt(iter, T0.plusSeconds(90));

        List<Event> events = List.of(
                ev("PHASE_IMPLEMENTATION", T0.plusSeconds(1)),
                ev("PHASE_IMPLEMENTATION_COMPLETE", T0.plusSeconds(30)),
                ev("PHASE_CI_VERIFICATION", T0.plusSeconds(31))
                // no PHASE_CI_COMPLETE — the polling call threw
        );

        List<IterationTimeline> timeline = assembler.assemble(issue, events, List.of(iter), List.of());

        List<Segment> segments = timeline.get(0).segments();
        assertThat(segments).extracting(Segment::stageName).containsExactly("Implementation", "CI");
        Segment ci = segments.get(1);
        assertThat(ci.outcome()).isEqualTo("fail");
        assertThat(ci.durationSecs()).isEqualTo(59L); // 90 - 31
    }

    private static void setCompletedAt(Iteration iteration, LocalDateTime ts) {
        iteration.setCompletedAt(ts);
    }

    // =====================================================
    // Running open segment
    // =====================================================

    @Test
    void runningIssue_lastIterationGetsOpenRunningSegment_forInFlightStage() {
        WatchedRepo repo = repo();
        TrackedIssue issue = issue(repo, IssueStatus.IN_PROGRESS);
        Iteration iter = iteration(issue, 1, T0);

        List<Event> events = List.of(
                ev("PHASE_IMPLEMENTATION", T0.plusSeconds(1))
                // Claude Code is still running — no complete event yet
        );

        List<IterationTimeline> timeline = assembler.assemble(issue, events, List.of(iter), List.of());

        List<Segment> segments = timeline.get(0).segments();
        assertThat(segments).hasSize(1);
        assertThat(segments.get(0).stageName()).isEqualTo("Implementation");
        assertThat(segments.get(0).outcome()).isEqualTo("running");
        assertThat(segments.get(0).widthPct()).isEqualTo(100.0);
        assertThat(timeline.get(0).outcomeBadge()).isEqualTo("RUNNING");
    }

    @Test
    void runningIssue_noEventsYetForCurrentIteration_getsSyntheticWholeBarRunningSegment() {
        WatchedRepo repo = repo();
        TrackedIssue issue = issue(repo, IssueStatus.IN_PROGRESS);
        Iteration iter = iteration(issue, 1, T0);

        // ITERATION_STARTED just logged; PHASE_IMPLEMENTATION hasn't been logged yet.
        List<Event> events = List.of(ev("ITERATION_STARTED", T0));

        List<IterationTimeline> timeline = assembler.assemble(issue, events, List.of(iter), List.of());

        List<Segment> segments = timeline.get(0).segments();
        assertThat(segments).hasSize(1);
        assertThat(segments.get(0).stageName()).isEqualTo("Running");
        assertThat(segments.get(0).outcome()).isEqualTo("running");
        assertThat(segments.get(0).widthPct()).isEqualTo(100.0);
        assertThat(timeline.get(0).outcomeBadge()).isEqualTo("RUNNING");
    }

    @Test
    void notRunningIssue_lastIterationWithNoTerminalEvent_omitsRatherThanGuessingRunning() {
        // Issue is FAILED (not IN_PROGRESS) — even though the last iteration's implementation
        // never got a terminal event, it must NOT show as "running".
        WatchedRepo repo = repo();
        TrackedIssue issue = issue(repo, IssueStatus.FAILED);
        Iteration iter = iteration(issue, 1, T0);

        List<Event> events = List.of(ev("PHASE_IMPLEMENTATION", T0.plusSeconds(1)));

        List<IterationTimeline> timeline = assembler.assemble(issue, events, List.of(iter), List.of());

        assertThat(timeline.get(0).segments()).isEmpty();
    }

    // =====================================================
    // Duration clamps
    // =====================================================

    @Test
    void zeroDurationSegment_clampsToOneSecond() {
        WatchedRepo repo = repo();
        TrackedIssue issue = issue(repo, IssueStatus.FAILED);
        Iteration iter = iteration(issue, 1, T0);

        // Start and failed event carry the identical timestamp (same-millisecond logging).
        List<Event> events = List.of(
                ev("PHASE_IMPLEMENTATION", T0),
                ev("PHASE_IMPL_FAILED", T0)
        );

        List<IterationTimeline> timeline = assembler.assemble(issue, events, List.of(iter), List.of());

        Segment seg = timeline.get(0).segments().get(0);
        assertThat(seg.durationSecs()).isEqualTo(1L);
        assertThat(seg.widthPct()).isEqualTo(100.0);
    }

    // =====================================================
    // Min-width normalization
    // =====================================================

    @Test
    void minWidthFloor_appliedToTinySegment_othersShrinkProportionally_sumStays100() {
        WatchedRepo repo = repo();
        TrackedIssue issue = issue(repo, IssueStatus.COMPLETED);
        Iteration iter = iteration(issue, 1, T0);
        iter.setLocalCheckResult("PASSED");
        iter.setCiResult("PASSED");

        // Implementation: 990s: Local Checks: 1s (would be ~0.1% raw): CI: 9s.
        // Total = 1000s. Local Checks' raw share is far under the 6% floor.
        List<Event> events = List.of(
                ev("PHASE_IMPLEMENTATION", T0),
                ev("PHASE_IMPLEMENTATION_COMPLETE", T0.plusSeconds(990)),
                ev("PHASE_LOCAL_CHECKS", T0.plusSeconds(990)),
                ev("PHASE_LOCAL_CHECKS_COMPLETE", T0.plusSeconds(991)),
                ev("PHASE_CI_VERIFICATION", T0.plusSeconds(991)),
                ev("PHASE_CI_COMPLETE", T0.plusSeconds(1000))
        );

        List<IterationTimeline> timeline = assembler.assemble(issue, events, List.of(iter), List.of());
        List<Segment> segments = timeline.get(0).segments();

        Segment localChecks = segments.stream()
                .filter(s -> s.stageName().equals("Local Checks")).findFirst().orElseThrow();
        assertThat(localChecks.widthPct()).isGreaterThanOrEqualTo(6.0);

        double sum = segments.stream().mapToDouble(Segment::widthPct).sum();
        assertThat(sum).isCloseTo(100.0, org.assertj.core.data.Offset.offset(0.01));

        // The dominant Implementation segment still has the largest share.
        Segment impl = segments.stream()
                .filter(s -> s.stageName().equals("Implementation")).findFirst().orElseThrow();
        assertThat(impl.widthPct()).isGreaterThan(localChecks.widthPct());
    }

    // =====================================================
    // Cost summing per iteration
    // =====================================================

    @Test
    void costsAreSummedPerIteration_andOtherIterationsAreExcluded() {
        WatchedRepo repo = repo();
        TrackedIssue issue = issue(repo, IssueStatus.IN_PROGRESS);
        Iteration iter1 = iteration(issue, 1, T0);
        Iteration iter2 = iteration(issue, 2, T0.plusMinutes(10));

        List<CostTracking> costs = List.of(
                cost(issue, 1, "0.10", "IMPLEMENTATION"),
                cost(issue, 1, "0.05", "REVIEW"),
                cost(issue, 2, "0.30", "IMPLEMENTATION")
        );

        List<IterationTimeline> timeline = assembler.assemble(issue, List.of(), List.of(iter1, iter2), costs);

        assertThat(timeline.get(0).totalCost()).isEqualByComparingTo("0.15");
        assertThat(timeline.get(1).totalCost()).isEqualByComparingTo("0.30");
    }

    @Test
    void iterationWithNoCostRows_totalsZero() {
        WatchedRepo repo = repo();
        TrackedIssue issue = issue(repo, IssueStatus.IN_PROGRESS);
        Iteration iter = iteration(issue, 1, T0);

        List<IterationTimeline> timeline = assembler.assemble(issue, List.of(), List.of(iter), List.of());

        assertThat(timeline.get(0).totalCost()).isEqualByComparingTo("0.00");
    }

    // =====================================================
    // Guard: no iterations yet
    // =====================================================

    @Test
    void noIterations_returnsEmptyList() {
        WatchedRepo repo = repo();
        TrackedIssue issue = issue(repo, IssueStatus.QUEUED);

        List<IterationTimeline> timeline = assembler.assemble(issue, List.of(), List.of(), List.of());

        assertThat(timeline).isEmpty();
    }

    // =====================================================
    // Review outcome: invocation failure maps to skipped, not fail
    // =====================================================

    @Test
    void reviewInvocationFailure_mapsToSkippedOutcome_usingLatestTerminalEvent() {
        WatchedRepo repo = repo();
        TrackedIssue issue = issue(repo, IssueStatus.FAILED);
        Iteration iter = iteration(issue, 1, T0);
        // reviewPassed stays null — codeReviewService.reviewCode threw, phaseIndependentReview
        // returned null without ever setting iteration.reviewPassed.

        List<Event> events = List.of(
                ev("PHASE_INDEPENDENT_REVIEW", T0),
                ev("PHASE_REVIEW_FAILED", T0.plusSeconds(5)),
                ev("PHASE_REVIEW_SKIPPED", T0.plusSeconds(6))
        );

        List<IterationTimeline> timeline = assembler.assemble(issue, events, List.of(iter), List.of());

        Segment review = timeline.get(0).segments().get(0);
        assertThat(review.outcome()).isEqualTo("skipped");
        assertThat(review.durationSecs()).isEqualTo(6L); // anchored to the LATEST terminal event
    }
}

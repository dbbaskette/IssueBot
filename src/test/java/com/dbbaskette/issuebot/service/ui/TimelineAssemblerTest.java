package com.dbbaskette.issuebot.service.ui;

import com.dbbaskette.issuebot.model.CostTracking;
import com.dbbaskette.issuebot.model.Event;
import com.dbbaskette.issuebot.model.IssueStatus;
import com.dbbaskette.issuebot.model.Iteration;
import com.dbbaskette.issuebot.model.TrackedIssue;
import com.dbbaskette.issuebot.model.WatchedRepo;
import com.dbbaskette.issuebot.service.ui.TimelineAssembler.IterationTimeline;
import com.dbbaskette.issuebot.service.ui.TimelineAssembler.RunTimeline;
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
 *
 * <p>All tests pass a FIXED "now" into {@link TimelineAssembler#assemble} — the assembler never
 * reads the wall clock, so every assertion (including open running-segment durations) is
 * deterministic.
 */
class TimelineAssemblerTest {

    private final TimelineAssembler assembler = new TimelineAssembler();
    private static final LocalDateTime T0 = LocalDateTime.of(2026, 7, 10, 9, 0, 0);
    /** Fixed "now" for every test — two hours after T0, comfortably after all fixture events. */
    private static final LocalDateTime NOW = T0.plusHours(2);

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

    /** Unwraps the single expected run — most fixtures have no retries. */
    private static List<IterationTimeline> singleRun(List<RunTimeline> timeline) {
        assertThat(timeline).hasSize(1);
        return timeline.get(0).iterations();
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

        List<IterationTimeline> timeline = singleRun(
                assembler.assemble(issue, events, List.of(iter1, iter2), costs, NOW));

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
    // Runs: retry after failure keeps old rows and resets iterationNum (Sonnet review critical)
    // =====================================================

    /**
     * The reviewer's exact repro: a 2-iteration FAILED run followed by a later 1-iteration
     * successful retry. IssueController#performRetry resets currentIteration to 0 WITHOUT
     * deleting the first run's Iteration/Event/CostTracking rows, so this issue holds two
     * "iteration 1" rows. Sorting by iterationNum would put the two run-starts adjacent and
     * derive impossible windows (the retry's card came out empty, and run 1's review-failure
     * event bled into the wrong card). Chronological sort + run grouping must yield three
     * correct cards across two runs, with no cross-run bleed and no empty successful card.
     */
    @Test
    void retryAcrossRuns_groupsChronologicallyIntoRuns_noBleed_noEmptySuccessfulCard() {
        WatchedRepo repo = repo();
        TrackedIssue issue = issue(repo, IssueStatus.COMPLETED);

        // --- Run 1, iteration 1 (T0): impl ok, CI fails ---
        LocalDateTime r1i1 = T0;
        Iteration run1Iter1 = iteration(issue, 1, r1i1);
        run1Iter1.setCiResult("FAILED");

        // --- Run 1, iteration 2 (T0+5m): impl ok, CI ok, review FAILS -> run ends FAILED ---
        LocalDateTime r1i2 = T0.plusMinutes(5);
        Iteration run1Iter2 = iteration(issue, 2, r1i2);
        run1Iter2.setCiResult("PASSED");
        run1Iter2.setReviewPassed(false);

        // --- Run 2 (manual retry an hour later), iteration 1: everything passes ---
        LocalDateTime r2i1 = T0.plusMinutes(60);
        Iteration run2Iter1 = iteration(issue, 1, r2i1);
        run2Iter1.setCiResult("PASSED");
        run2Iter1.setReviewPassed(true);

        List<Event> events = List.of(
                ev("PHASE_IMPLEMENTATION", r1i1.plusSeconds(1)),
                ev("PHASE_IMPLEMENTATION_COMPLETE", r1i1.plusSeconds(61)),
                ev("PHASE_CI_VERIFICATION", r1i1.plusSeconds(62)),
                ev("PHASE_CI_COMPLETE", r1i1.plusSeconds(122)),

                ev("PHASE_IMPLEMENTATION", r1i2.plusSeconds(1)),
                ev("PHASE_IMPLEMENTATION_COMPLETE", r1i2.plusSeconds(91)),
                ev("PHASE_CI_VERIFICATION", r1i2.plusSeconds(92)),
                ev("PHASE_CI_COMPLETE", r1i2.plusSeconds(152)),
                ev("PHASE_INDEPENDENT_REVIEW", r1i2.plusSeconds(160)),
                ev("PHASE_REVIEW_COMPLETE", r1i2.plusSeconds(220)), // review FAILED

                ev("MANUAL_RETRY", r2i1.minusSeconds(5)),
                ev("PHASE_IMPLEMENTATION", r2i1.plusSeconds(1)),
                ev("PHASE_IMPLEMENTATION_COMPLETE", r2i1.plusSeconds(51)),
                ev("PHASE_CI_VERIFICATION", r2i1.plusSeconds(52)),
                ev("PHASE_CI_COMPLETE", r2i1.plusSeconds(112)),
                ev("PHASE_INDEPENDENT_REVIEW", r2i1.plusSeconds(120)),
                ev("PHASE_REVIEW_COMPLETE", r2i1.plusSeconds(180)),
                ev("WORKFLOW_COMPLETED", r2i1.plusSeconds(190))
        );

        // Deliberately unsorted input (run 2's iteration first) — assemble must not rely on
        // any input ordering.
        List<RunTimeline> timeline = assembler.assemble(
                issue, events, List.of(run2Iter1, run1Iter1, run1Iter2), List.of(), NOW);

        // Three cards across two runs.
        assertThat(timeline).hasSize(2);
        assertThat(timeline.get(0).runNum()).isEqualTo(1);
        assertThat(timeline.get(0).iterations()).extracting(IterationTimeline::iterationNum)
                .containsExactly(1, 2);
        assertThat(timeline.get(1).runNum()).isEqualTo(2);
        assertThat(timeline.get(1).iterations()).extracting(IterationTimeline::iterationNum)
                .containsExactly(1);

        // Run 1, iteration 1: exactly Impl + CI — run 1 iteration 2's review-failure event
        // must NOT bleed in (that fabricated "Review: FAIL" was the reviewer's repro).
        IterationTimeline card1 = timeline.get(0).iterations().get(0);
        assertThat(card1.segments()).extracting(Segment::stageName)
                .containsExactly("Implementation", "CI");
        assertThat(card1.segments()).extracting(Segment::outcome)
                .containsExactly("ok", "fail");
        assertThat(card1.outcomeBadge()).isEqualTo("FAILED");

        // Run 1, iteration 2: its own review failure, timed by its own events (60s).
        IterationTimeline card2 = timeline.get(0).iterations().get(1);
        assertThat(card2.segments()).extracting(Segment::stageName)
                .containsExactly("Implementation", "CI", "Review");
        assertThat(card2.segments()).extracting(Segment::outcome)
                .containsExactly("ok", "ok", "fail");
        assertThat(card2.segments().get(2).durationSecs()).isEqualTo(60L);
        assertThat(card2.outcomeBadge()).isEqualTo("FAILED");

        // Run 2, iteration 1: the real successful attempt — NOT an empty card.
        IterationTimeline card3 = timeline.get(1).iterations().get(0);
        assertThat(card3.segments()).extracting(Segment::stageName)
                .containsExactly("Implementation", "CI", "Review");
        assertThat(card3.segments()).extracting(Segment::outcome)
                .containsExactly("ok", "ok", "ok");
        assertThat(card3.segments()).extracting(Segment::durationSecs)
                .containsExactly(50L, 60L, 60L);
        assertThat(card3.outcomeBadge()).isEqualTo("PASSED");
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

        List<IterationTimeline> timeline = singleRun(
                assembler.assemble(issue, events, List.of(iter), List.of(), NOW));

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

        List<IterationTimeline> timeline = singleRun(
                assembler.assemble(issue, events, List.of(iter), List.of(), NOW));

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

        List<IterationTimeline> timeline = singleRun(
                assembler.assemble(issue, events, List.of(iter), List.of(), NOW));

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
        iter.setCompletedAt(T0.plusSeconds(90));

        List<Event> events = List.of(
                ev("PHASE_IMPLEMENTATION", T0.plusSeconds(1)),
                ev("PHASE_IMPLEMENTATION_COMPLETE", T0.plusSeconds(30)),
                ev("PHASE_CI_VERIFICATION", T0.plusSeconds(31))
                // no PHASE_CI_COMPLETE — the polling call threw
        );

        List<IterationTimeline> timeline = singleRun(
                assembler.assemble(issue, events, List.of(iter), List.of(), NOW));

        List<Segment> segments = timeline.get(0).segments();
        assertThat(segments).extracting(Segment::stageName).containsExactly("Implementation", "CI");
        Segment ci = segments.get(1);
        assertThat(ci.outcome()).isEqualTo("fail");
        assertThat(ci.durationSecs()).isEqualTo(59L); // 90 - 31
    }

    // =====================================================
    // Running open segment / running badge (Sonnet review important #2)
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

        List<IterationTimeline> timeline = singleRun(
                assembler.assemble(issue, events, List.of(iter), List.of(), NOW));

        List<Segment> segments = timeline.get(0).segments();
        assertThat(segments).hasSize(1);
        assertThat(segments.get(0).stageName()).isEqualTo("Implementation");
        assertThat(segments.get(0).outcome()).isEqualTo("running");
        assertThat(segments.get(0).widthPct()).isEqualTo(100.0);
        // Open end is the fixed now (7199s after the start event) — deterministic by construction.
        assertThat(segments.get(0).durationSecs()).isEqualTo(7199L);
        assertThat(timeline.get(0).outcomeBadge()).isEqualTo("RUNNING");
    }

    @Test
    void runningIssue_noEventsYetForCurrentIteration_getsSyntheticWholeBarRunningSegment() {
        WatchedRepo repo = repo();
        TrackedIssue issue = issue(repo, IssueStatus.IN_PROGRESS);
        Iteration iter = iteration(issue, 1, T0);

        // ITERATION_STARTED just logged; PHASE_IMPLEMENTATION hasn't been logged yet.
        List<Event> events = List.of(ev("ITERATION_STARTED", T0));

        List<IterationTimeline> timeline = singleRun(
                assembler.assemble(issue, events, List.of(iter), List.of(), NOW));

        List<Segment> segments = timeline.get(0).segments();
        assertThat(segments).hasSize(1);
        assertThat(segments.get(0).stageName()).isEqualTo("Running"); // currentPhase unset
        assertThat(segments.get(0).outcome()).isEqualTo("running");
        assertThat(segments.get(0).widthPct()).isEqualTo(100.0);
        assertThat(timeline.get(0).outcomeBadge()).isEqualTo("RUNNING");
    }

    /**
     * The CI-passed-awaiting-review gap (Sonnet review #2): every tracked stage completed but
     * the workflow is between stages (here: review phase entered but its start event not yet
     * logged, currentPhase = INDEPENDENT_REVIEW). The badge must still be RUNNING — never
     * "PASSED" for an in-flight iteration — and the bar must end with a synthesized open
     * segment named after the current phase, spanning the last tracked stage's end to now.
     */
    @Test
    void runningIssue_lastTrackedStageComplete_appendsOpenGapSegment_andBadgeIsRunning() {
        WatchedRepo repo = repo();
        TrackedIssue issue = issue(repo, IssueStatus.IN_PROGRESS);
        issue.setCurrentPhase("INDEPENDENT_REVIEW");
        Iteration iter = iteration(issue, 1, T0);
        iter.setCiResult("PASSED");

        LocalDateTime now = T0.plusSeconds(180);
        List<Event> events = List.of(
                ev("PHASE_IMPLEMENTATION", T0.plusSeconds(1)),
                ev("PHASE_IMPLEMENTATION_COMPLETE", T0.plusSeconds(61)),
                ev("PHASE_CI_VERIFICATION", T0.plusSeconds(62)),
                ev("PHASE_CI_COMPLETE", T0.plusSeconds(122))
                // no PHASE_INDEPENDENT_REVIEW event yet — the gap
        );

        List<IterationTimeline> timeline = singleRun(
                assembler.assemble(issue, events, List.of(iter), List.of(), now));

        IterationTimeline card = timeline.get(0);
        assertThat(card.outcomeBadge()).isEqualTo("RUNNING");
        assertThat(card.segments()).extracting(Segment::stageName)
                .containsExactly("Implementation", "CI", "Review");
        assertThat(card.segments()).extracting(Segment::outcome)
                .containsExactly("ok", "ok", "running");
        // The gap segment spans CI's end (122s) to now (180s) = 58s.
        assertThat(card.segments().get(2).durationSecs()).isEqualTo(58L);
    }

    @Test
    void notRunningIssue_lastIterationWithNoTerminalEvent_omitsRatherThanGuessingRunning() {
        // Issue is FAILED (not IN_PROGRESS) — even though the last iteration's implementation
        // never got a terminal event, it must NOT show as "running".
        WatchedRepo repo = repo();
        TrackedIssue issue = issue(repo, IssueStatus.FAILED);
        Iteration iter = iteration(issue, 1, T0);

        List<Event> events = List.of(ev("PHASE_IMPLEMENTATION", T0.plusSeconds(1)));

        List<IterationTimeline> timeline = singleRun(
                assembler.assemble(issue, events, List.of(iter), List.of(), NOW));

        assertThat(timeline.get(0).segments()).isEmpty();
    }

    // =====================================================
    // Realistic sequences (Sonnet review important #3)
    // =====================================================

    /**
     * Cold-fallback after a failed session resume (#67): phaseImplementation logs ONE
     * PHASE_IMPLEMENTATION at entry, then SESSION_RESUME_FAILED mid-flight, retries cold
     * inside the same call, and logs ONE PHASE_IMPLEMENTATION_COMPLETE at exit. This test
     * documents that single-pair-brackets-both-attempts assumption: exactly one Implementation
     * segment, spanning the combined resumed-attempt + cold-retry wall clock.
     */
    @Test
    void coldFallbackDoubleImplementation_singleSegmentSpansBothAttempts() {
        WatchedRepo repo = repo();
        TrackedIssue issue = issue(repo, IssueStatus.COMPLETED);
        Iteration iter = iteration(issue, 1, T0);
        iter.setCiResult("PASSED");
        iter.setReviewPassed(true);

        List<Event> events = List.of(
                ev("PHASE_IMPLEMENTATION", T0.plusSeconds(1)),
                ev("SESSION_RESUME_FAILED", T0.plusSeconds(30)),   // resumed attempt discarded
                ev("PHASE_IMPLEMENTATION_COMPLETE", T0.plusSeconds(90)), // cold retry finished
                ev("PHASE_CI_VERIFICATION", T0.plusSeconds(91)),
                ev("PHASE_CI_COMPLETE", T0.plusSeconds(151)),
                ev("PHASE_INDEPENDENT_REVIEW", T0.plusSeconds(160)),
                ev("PHASE_REVIEW_COMPLETE", T0.plusSeconds(220))
        );

        List<IterationTimeline> timeline = singleRun(
                assembler.assemble(issue, events, List.of(iter), List.of(), NOW));

        List<Segment> segments = timeline.get(0).segments();
        // Exactly ONE Implementation segment — the discarded resume attempt is inside it,
        // not a second segment.
        assertThat(segments).extracting(Segment::stageName)
                .containsExactly("Implementation", "CI", "Review");
        assertThat(segments.get(0).durationSecs()).isEqualTo(89L); // 1s -> 90s, both attempts
        assertThat(segments.get(0).outcome()).isEqualTo("ok");
    }

    /**
     * Review-failure feedback loop: iteration N's review fails, the findings feed the next
     * implementation prompt, and iteration N+1 passes. Each card's review segment must be
     * timed by ITS OWN window's events — the differing durations prove no cross-iteration
     * bleed.
     */
    @Test
    void reviewFailureFeedbackLoop_acrossConsecutiveIterations_segmentsStayInTheirWindows() {
        WatchedRepo repo = repo();
        TrackedIssue issue = issue(repo, IssueStatus.COMPLETED);

        Iteration iter1 = iteration(issue, 1, T0);
        iter1.setCiResult("PASSED");
        iter1.setReviewPassed(false); // review failed -> feedback loop

        LocalDateTime iter2Start = T0.plusSeconds(300);
        Iteration iter2 = iteration(issue, 2, iter2Start);
        iter2.setCiResult("PASSED");
        iter2.setReviewPassed(true);

        List<Event> events = List.of(
                ev("PHASE_IMPLEMENTATION", T0.plusSeconds(1)),
                ev("PHASE_IMPLEMENTATION_COMPLETE", T0.plusSeconds(101)),
                ev("PHASE_CI_VERIFICATION", T0.plusSeconds(102)),
                ev("PHASE_CI_COMPLETE", T0.plusSeconds(162)),
                ev("PHASE_INDEPENDENT_REVIEW", T0.plusSeconds(200)),
                ev("PHASE_REVIEW_COMPLETE", T0.plusSeconds(260)), // FAILED review, 60s

                ev("ITERATION_STARTED", iter2Start),
                ev("PHASE_IMPLEMENTATION", iter2Start.plusSeconds(1)),
                ev("PHASE_IMPLEMENTATION_COMPLETE", iter2Start.plusSeconds(101)),
                ev("PHASE_CI_VERIFICATION", iter2Start.plusSeconds(102)),
                ev("PHASE_CI_COMPLETE", iter2Start.plusSeconds(162)),
                ev("PHASE_INDEPENDENT_REVIEW", iter2Start.plusSeconds(170)),
                ev("PHASE_REVIEW_COMPLETE", iter2Start.plusSeconds(290)) // PASSED review, 120s
        );

        List<IterationTimeline> timeline = singleRun(
                assembler.assemble(issue, events, List.of(iter1, iter2), List.of(), NOW));

        assertThat(timeline).hasSize(2);

        IterationTimeline first = timeline.get(0);
        assertThat(first.segments()).extracting(Segment::stageName)
                .containsExactly("Implementation", "CI", "Review");
        assertThat(first.segments()).extracting(Segment::outcome)
                .containsExactly("ok", "ok", "fail");
        assertThat(first.segments().get(2).durationSecs()).isEqualTo(60L); // its own review
        assertThat(first.outcomeBadge()).isEqualTo("FAILED");

        IterationTimeline second = timeline.get(1);
        assertThat(second.segments()).extracting(Segment::outcome)
                .containsExactly("ok", "ok", "ok");
        assertThat(second.segments().get(2).durationSecs()).isEqualTo(120L); // its own review
        assertThat(second.outcomeBadge()).isEqualTo("PASSED");
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

        List<IterationTimeline> timeline = singleRun(
                assembler.assemble(issue, events, List.of(iter), List.of(), NOW));

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

        List<IterationTimeline> timeline = singleRun(
                assembler.assemble(issue, events, List.of(iter), List.of(), NOW));
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

        List<IterationTimeline> timeline = singleRun(
                assembler.assemble(issue, List.of(), List.of(iter1, iter2), costs, NOW));

        assertThat(timeline.get(0).totalCost()).isEqualByComparingTo("0.15");
        assertThat(timeline.get(1).totalCost()).isEqualByComparingTo("0.30");
    }

    /**
     * A discarded session-resume attempt's tokens are tracked as a SECOND IMPLEMENTATION cost
     * row for the same iteration (see phaseImplementation's cold-fallback path, which calls
     * trackCost for the failed resumed result before retrying cold). Both rows must be summed.
     */
    @Test
    void twoCostRowsSameIterationAndPhase_discardedResumePlusColdRetry_areSummed() {
        WatchedRepo repo = repo();
        TrackedIssue issue = issue(repo, IssueStatus.IN_PROGRESS);
        Iteration iter = iteration(issue, 1, T0);

        List<CostTracking> costs = List.of(
                cost(issue, 1, "0.30", "IMPLEMENTATION"), // discarded resumed attempt
                cost(issue, 1, "0.20", "IMPLEMENTATION"), // cold retry
                cost(issue, 1, "0.05", "REVIEW")
        );

        List<IterationTimeline> timeline = singleRun(
                assembler.assemble(issue, List.of(), List.of(iter), costs, NOW));

        assertThat(timeline.get(0).totalCost()).isEqualByComparingTo("0.55");
    }

    @Test
    void iterationWithNoCostRows_totalsZero() {
        WatchedRepo repo = repo();
        TrackedIssue issue = issue(repo, IssueStatus.IN_PROGRESS);
        Iteration iter = iteration(issue, 1, T0);

        List<IterationTimeline> timeline = singleRun(
                assembler.assemble(issue, List.of(), List.of(iter), List.of(), NOW));

        assertThat(timeline.get(0).totalCost()).isEqualByComparingTo("0.00");
    }

    // =====================================================
    // Guard: no iterations yet
    // =====================================================

    @Test
    void noIterations_returnsEmptyList() {
        WatchedRepo repo = repo();
        TrackedIssue issue = issue(repo, IssueStatus.QUEUED);

        List<RunTimeline> timeline = assembler.assemble(issue, List.of(), List.of(), List.of(), NOW);

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

        List<IterationTimeline> timeline = singleRun(
                assembler.assemble(issue, events, List.of(iter), List.of(), NOW));

        Segment review = timeline.get(0).segments().get(0);
        assertThat(review.outcome()).isEqualTo("skipped");
        assertThat(review.durationSecs()).isEqualTo(6L); // anchored to the LATEST terminal event
    }
}

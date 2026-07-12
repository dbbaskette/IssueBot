package com.dbbaskette.issuebot.service.ui;

import com.dbbaskette.issuebot.model.CostTracking;
import com.dbbaskette.issuebot.model.Event;
import com.dbbaskette.issuebot.model.IssueStatus;
import com.dbbaskette.issuebot.model.Iteration;
import com.dbbaskette.issuebot.model.TrackedIssue;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Builds the per-iteration "loop timeline" (issue #88): for each {@link Iteration} of a
 * {@link TrackedIssue}, a small horizontal bar of stage segments (Implementation, Local Checks,
 * CI, Review) with durations, proportional widths, and an outcome, plus that iteration's total
 * cost and an overall outcome badge. Pure, side-effect-free view-model assembly — no repository
 * access here; the caller ({@code IssueController}) fetches the three input lists with one
 * query each and hands them in.
 *
 * <h2>Derivation strategy</h2>
 * <b>Iteration windows.</b> {@code IssueWorkflowService} logs an {@code ITERATION_STARTED} event
 * at the top of every pass through its iteration loop, but it also saves the new {@link Iteration}
 * row immediately before that log call — so {@link Iteration#getStartedAt()} is already a precise,
 * always-present anchor and is used directly instead of matching events by ordinal position or
 * message text. Each iteration's window is {@code [iteration.startedAt, nextIteration.startedAt)};
 * the last iteration's window is open-ended (no upper bound needed — there's nothing after it to
 * bleed into). {@link Iteration#getCompletedAt()} is deliberately NOT used as a window boundary:
 * the workflow stamps it right after the CI phase concludes (see the unconditional
 * {@code iteration.setCompletedAt(...)} in {@code IssueWorkflowService#processIssue} that runs
 * before checking whether CI passed), which is BEFORE PR creation and Independent Review even
 * start on a successful iteration — using it as a window end would silently truncate the Review
 * segment.
 *
 * <p><b>Stage segments.</b> Only four stage kinds are rendered per iteration — Implementation,
 * Local Checks, CI, Review — matching the issue's spec. {@code SETUP} and {@code COMPLETION} are
 * workflow-level phases that run once outside the iteration loop (setup before iteration 1's
 * window even opens, completion after the last iteration's review passes) and are intentionally
 * never matched here: since this class only looks for the specific event-type strings named
 * below, {@code PHASE_SETUP*}/{@code PHASE_COMPLETION*}/{@code WORKFLOW_*} events are simply
 * ignored wherever they happen to fall, with no special-case filtering required.
 *
 * <p>Exact event-type pairs consumed (grepped from {@code eventService.log("PHASE_...")} call
 * sites in {@code IssueWorkflowService}):
 * <ul>
 *   <li><b>Implementation</b> — start {@code PHASE_IMPLEMENTATION}; end
 *       {@code PHASE_IMPL_FAILED} (outcome {@code fail}, thrown exception) or
 *       {@code PHASE_IMPLEMENTATION_COMPLETE} (logged whether or not the Claude Code invocation
 *       itself reported success — there is no structured "impl succeeded" field on
 *       {@code Iteration}, so outcome is inferred: {@code ok} if a later stage in the same
 *       iteration started (Local Checks or CI), else {@code fail} — the loop only continues past
 *       implementation on success).</li>
 *   <li><b>Local Checks</b> (segment omitted entirely when the repo has no verification commands
 *       configured, i.e. no start event) — start {@code PHASE_LOCAL_CHECKS}; end
 *       {@code PHASE_LOCAL_CHECKS_COMPLETE}/{@code _FAILED}, outcome preferring the structured
 *       {@link Iteration#getLocalCheckResult()} ({@code PASSED}/{@code FAILED}) over the event's
 *       own type.</li>
 *   <li><b>CI</b> — start {@code PHASE_CI_VERIFICATION} (CI enabled) or, when CI is disabled,
 *       no start event exists at all (the workflow pushes and skips straight to
 *       {@code PHASE_CI_SKIPPED}) — the segment's start is then anchored to the end of the
 *       previous present segment (Local Checks, else Implementation). End is
 *       {@code PHASE_CI_COMPLETE} or {@code PHASE_CI_SKIPPED}; if CI errored (an exception during
 *       polling, which skips the complete-event log line entirely) the structured
 *       {@link Iteration#getCiResult()} ({@code ERROR}) plus {@link Iteration#getCompletedAt()}
 *       (which IS stamped in that exception's catch block) recover the segment. Outcome prefers
 *       {@link Iteration#getCiResult()} ({@code PASSED}/{@code FAILED}/{@code ERROR}/
 *       {@code SKIPPED}) over event text.</li>
 *   <li><b>Review</b> — start {@code PHASE_INDEPENDENT_REVIEW}; end is the LATEST of
 *       {@code PHASE_REVIEW_COMPLETE}/{@code PHASE_REVIEW_FAILED}/{@code PHASE_REVIEW_SKIPPED}
 *       present (an invocation error logs both {@code _FAILED} and, back in the caller,
 *       {@code _SKIPPED} — both mark the same failure, so the later timestamp is the honest end).
 *       Outcome prefers the structured {@link Iteration#getReviewPassed()} ({@code true}/
 *       {@code false} → {@code ok}/{@code fail}); when that's null (invocation error) the
 *       terminal event type maps to {@code skipped}.</li>
 * </ul>
 *
 * <p><b>Robustness.</b> A stage with no discoverable start event is omitted — never guessed.
 * A stage with a start event but no terminal event: if this is the last iteration of an issue
 * currently {@link IssueStatus#IN_PROGRESS}, it becomes an open {@code running} segment ending at
 * "now"; otherwise (stale data — e.g. an old issue with gappy events, or a process crash) it is
 * omitted. If NO segment is derivable at all for the current running iteration, a single
 * whole-bar {@code running} placeholder segment is synthesized so the bar isn't blank.
 * Zero/negative durations (clock skew, same-millisecond events) clamp to 1 second. Widths are
 * proportional to duration within the bar, with a 6% floor per segment for label legibility
 * (always feasible since at most 4 segments exist per iteration: 4 × 6% = 24% ≤ 100%) and the
 * remainder rescaled so the bar always sums to exactly 100%.
 */
@Component
public class TimelineAssembler {

    // Implementation
    private static final String PHASE_IMPLEMENTATION = "PHASE_IMPLEMENTATION";
    private static final String PHASE_IMPLEMENTATION_COMPLETE = "PHASE_IMPLEMENTATION_COMPLETE";
    private static final String PHASE_IMPL_FAILED = "PHASE_IMPL_FAILED";

    // Local Checks
    private static final String PHASE_LOCAL_CHECKS = "PHASE_LOCAL_CHECKS";
    private static final String PHASE_LOCAL_CHECKS_COMPLETE = "PHASE_LOCAL_CHECKS_COMPLETE";
    private static final String PHASE_LOCAL_CHECKS_FAILED = "PHASE_LOCAL_CHECKS_FAILED";

    // CI
    private static final String PHASE_CI_VERIFICATION = "PHASE_CI_VERIFICATION";
    private static final String PHASE_CI_COMPLETE = "PHASE_CI_COMPLETE";
    private static final String PHASE_CI_SKIPPED = "PHASE_CI_SKIPPED";

    // Review
    private static final String PHASE_INDEPENDENT_REVIEW = "PHASE_INDEPENDENT_REVIEW";
    private static final String PHASE_REVIEW_COMPLETE = "PHASE_REVIEW_COMPLETE";
    private static final String PHASE_REVIEW_FAILED = "PHASE_REVIEW_FAILED";
    private static final String PHASE_REVIEW_SKIPPED = "PHASE_REVIEW_SKIPPED";

    private static final double MIN_WIDTH_PCT = 6.0;

    /** Outcome values — see the class javadoc's Robustness section. */
    public static final String OK = "ok";
    public static final String FAIL = "fail";
    public static final String SKIPPED = "skipped";
    public static final String RUNNING = "running";

    /**
     * One stage segment within an iteration's bar.
     *
     * @param stageName   display label ("Implementation", "Local Checks", "CI", "Review", or the
     *                    synthesized "Running" whole-bar placeholder)
     * @param durationSecs wall-clock seconds spent in this stage, clamped to a minimum of 1
     * @param widthPct    this segment's share of the bar's width, 0–100, floored at 6% and
     *                    normalized so all segments in an iteration sum to exactly 100
     * @param outcome     one of {@link #OK}, {@link #FAIL}, {@link #SKIPPED}, {@link #RUNNING}
     */
    public record Segment(String stageName, long durationSecs, double widthPct, String outcome) {}

    /**
     * The assembled timeline for one iteration.
     *
     * @param iterationNum matches {@link Iteration#getIterationNum()}
     * @param segments     stage segments in chronological order; empty when nothing is derivable
     * @param totalCost    sum of {@link CostTracking#getEstimatedCost()} for rows with this
     *                     iteration's number
     * @param outcomeBadge a short display verdict: {@code RUNNING}, {@code PASSED},
     *                     {@code FAILED}, {@code SKIPPED}, or {@code UNKNOWN} (no segments at all)
     */
    public record IterationTimeline(int iterationNum, List<Segment> segments,
                                     BigDecimal totalCost, String outcomeBadge) {}

    /** A segment before duration-clamping/width-normalization are applied. */
    private record RawSegment(String stageName, LocalDateTime start, LocalDateTime end, String outcome) {}

    /**
     * Assembles one {@link IterationTimeline} per iteration. Returns an empty list when there
     * are no iterations yet (nothing to draw).
     *
     * @param issue      the tracked issue (its {@link IssueStatus} drives the running-segment
     *                   detection for the last iteration)
     * @param events     ALL events for this issue, any order (sorted internally by
     *                   {@link Event#getCreatedAt()})
     * @param iterations this issue's iterations, any order (sorted internally by
     *                   {@link Iteration#getIterationNum()})
     * @param costRows   this issue's cost-tracking rows, any order
     */
    public List<IterationTimeline> assemble(TrackedIssue issue, List<Event> events,
                                             List<Iteration> iterations, List<CostTracking> costRows) {
        if (iterations == null || iterations.isEmpty()) {
            return List.of();
        }

        List<Event> sortedEvents = events == null ? List.of() : events.stream()
                .filter(e -> e.getCreatedAt() != null)
                .sorted(Comparator.comparing(Event::getCreatedAt))
                .toList();

        List<Iteration> sortedIterations = iterations.stream()
                .sorted(Comparator.comparingInt(Iteration::getIterationNum))
                .toList();

        Map<Integer, BigDecimal> costByIteration = groupCostsByIteration(costRows);
        boolean issueRunning = issue != null && issue.getStatus() == IssueStatus.IN_PROGRESS;
        LocalDateTime now = LocalDateTime.now();

        List<IterationTimeline> result = new ArrayList<>(sortedIterations.size());
        for (int i = 0; i < sortedIterations.size(); i++) {
            Iteration iteration = sortedIterations.get(i);
            boolean isLast = i == sortedIterations.size() - 1;
            LocalDateTime windowStart = iteration.getStartedAt();
            LocalDateTime windowEnd = isLast ? null : sortedIterations.get(i + 1).getStartedAt();

            List<Event> windowEvents = sortedEvents.stream()
                    .filter(e -> windowStart == null || !e.getCreatedAt().isBefore(windowStart))
                    .filter(e -> windowEnd == null || e.getCreatedAt().isBefore(windowEnd))
                    .toList();

            boolean runningIteration = isLast && issueRunning;
            List<RawSegment> raw = buildRawSegments(iteration, windowEvents, runningIteration, now);

            if (raw.isEmpty() && runningIteration) {
                LocalDateTime start = windowStart != null ? windowStart : now;
                raw = List.of(new RawSegment("Running", start, now, RUNNING));
            }

            List<Segment> segments = normalize(raw);
            BigDecimal cost = costByIteration.getOrDefault(iteration.getIterationNum(), BigDecimal.ZERO);
            String badge = outcomeBadge(segments, runningIteration);

            result.add(new IterationTimeline(iteration.getIterationNum(), segments, cost, badge));
        }
        return result;
    }

    // =====================================================
    // Stage derivation
    // =====================================================

    private List<RawSegment> buildRawSegments(Iteration iteration, List<Event> windowEvents,
                                               boolean runningIteration, LocalDateTime now) {
        List<RawSegment> segments = new ArrayList<>(4);

        addImplementationSegment(segments, windowEvents, runningIteration, now);
        addLocalChecksSegment(segments, iteration, windowEvents, runningIteration, now);
        addCiSegment(segments, iteration, windowEvents, runningIteration, now, segments.isEmpty() ? null
                : segments.get(segments.size() - 1).end());
        addReviewSegment(segments, iteration, windowEvents, runningIteration, now);

        return segments;
    }

    private void addImplementationSegment(List<RawSegment> segments, List<Event> events,
                                           boolean runningIteration, LocalDateTime now) {
        Optional<Event> start = firstOf(events, PHASE_IMPLEMENTATION);
        if (start.isEmpty()) return;
        LocalDateTime startTs = start.get().getCreatedAt();

        Optional<Event> failed = firstOf(events, PHASE_IMPL_FAILED);
        if (failed.isPresent()) {
            segments.add(new RawSegment("Implementation", startTs, failed.get().getCreatedAt(), FAIL));
            return;
        }

        Optional<Event> complete = firstOf(events, PHASE_IMPLEMENTATION_COMPLETE);
        if (complete.isPresent()) {
            boolean progressed = firstOf(events, PHASE_LOCAL_CHECKS).isPresent()
                    || firstOf(events, PHASE_CI_VERIFICATION).isPresent()
                    || firstOf(events, PHASE_CI_SKIPPED).isPresent();
            segments.add(new RawSegment("Implementation", startTs, complete.get().getCreatedAt(),
                    progressed ? OK : FAIL));
            return;
        }

        if (runningIteration) {
            segments.add(new RawSegment("Implementation", startTs, now, RUNNING));
        }
        // else: started but no terminal event and not running — stale/incomplete data, omit.
    }

    private void addLocalChecksSegment(List<RawSegment> segments, Iteration iteration, List<Event> events,
                                        boolean runningIteration, LocalDateTime now) {
        Optional<Event> start = firstOf(events, PHASE_LOCAL_CHECKS);
        if (start.isEmpty()) return; // not configured for this repo — no segment at all
        LocalDateTime startTs = start.get().getCreatedAt();

        String structured = iteration.getLocalCheckResult();
        Optional<Event> complete = firstOf(events, PHASE_LOCAL_CHECKS_COMPLETE);
        Optional<Event> failed = firstOf(events, PHASE_LOCAL_CHECKS_FAILED);

        if (complete.isPresent()) {
            segments.add(new RawSegment("Local Checks", startTs, complete.get().getCreatedAt(),
                    "FAILED".equals(structured) ? FAIL : OK));
            return;
        }
        if (failed.isPresent()) {
            segments.add(new RawSegment("Local Checks", startTs, failed.get().getCreatedAt(), FAIL));
            return;
        }
        if (runningIteration) {
            segments.add(new RawSegment("Local Checks", startTs, now, RUNNING));
        }
        // else: started but no terminal event and not running — omit.
    }

    private void addCiSegment(List<RawSegment> segments, Iteration iteration, List<Event> events,
                               boolean runningIteration, LocalDateTime now, LocalDateTime previousSegmentEnd) {
        String ciResult = iteration.getCiResult();
        Optional<Event> startEvent = firstOf(events, PHASE_CI_VERIFICATION);
        Optional<Event> complete = firstOf(events, PHASE_CI_COMPLETE);
        Optional<Event> skipped = firstOf(events, PHASE_CI_SKIPPED);

        LocalDateTime startTs = startEvent.map(Event::getCreatedAt).orElse(null);
        if (startTs == null && skipped.isPresent()) {
            // CI disabled: no PHASE_CI_VERIFICATION is ever logged — anchor to whatever
            // stage ended just before, so the segment isn't guessed out of thin air.
            startTs = previousSegmentEnd;
        }
        if (startTs == null) return; // can't anchor a start at all — omit.

        if (complete.isPresent()) {
            segments.add(new RawSegment("CI", startTs, complete.get().getCreatedAt(), ciOutcome(ciResult)));
            return;
        }
        if (skipped.isPresent()) {
            segments.add(new RawSegment("CI", startTs, skipped.get().getCreatedAt(), SKIPPED));
            return;
        }
        if (ciResult != null && iteration.getCompletedAt() != null) {
            // No terminal event (e.g. an exception during polling skips the log line), but the
            // structured result + completedAt (stamped in that catch block) recover the segment.
            segments.add(new RawSegment("CI", startTs, iteration.getCompletedAt(), ciOutcome(ciResult)));
            return;
        }
        if (runningIteration && startEvent.isPresent()) {
            segments.add(new RawSegment("CI", startTs, now, RUNNING));
        }
        // else: started but nothing conclusive and not running — omit.
    }

    private static String ciOutcome(String ciResult) {
        if ("PASSED".equals(ciResult)) return OK;
        if ("SKIPPED".equals(ciResult)) return SKIPPED;
        return FAIL; // FAILED, ERROR, or unexpectedly null with an event present
    }

    private void addReviewSegment(List<RawSegment> segments, Iteration iteration, List<Event> events,
                                   boolean runningIteration, LocalDateTime now) {
        Optional<Event> start = firstOf(events, PHASE_INDEPENDENT_REVIEW);
        if (start.isEmpty()) return;
        LocalDateTime startTs = start.get().getCreatedAt();

        Optional<Event> terminal = latestOf(events, PHASE_REVIEW_COMPLETE, PHASE_REVIEW_FAILED, PHASE_REVIEW_SKIPPED);
        if (terminal.isPresent()) {
            Boolean reviewPassed = iteration.getReviewPassed();
            String outcome;
            if (reviewPassed != null) {
                outcome = reviewPassed ? OK : FAIL;
            } else if (PHASE_REVIEW_SKIPPED.equals(terminal.get().getEventType())
                    || PHASE_REVIEW_FAILED.equals(terminal.get().getEventType())) {
                outcome = SKIPPED;
            } else {
                outcome = FAIL;
            }
            segments.add(new RawSegment("Review", startTs, terminal.get().getCreatedAt(), outcome));
            return;
        }
        if (runningIteration) {
            segments.add(new RawSegment("Review", startTs, now, RUNNING));
        }
        // else: started but no terminal event and not running — omit.
    }

    private static Optional<Event> firstOf(List<Event> events, String eventType) {
        return events.stream().filter(e -> eventType.equals(e.getEventType())).findFirst();
    }

    private static Optional<Event> latestOf(List<Event> events, String... eventTypes) {
        Set<String> types = Set.of(eventTypes);
        return events.stream()
                .filter(e -> types.contains(e.getEventType()))
                .max(Comparator.comparing(Event::getCreatedAt));
    }

    // =====================================================
    // Cost, width normalization, outcome badge
    // =====================================================

    private static Map<Integer, BigDecimal> groupCostsByIteration(List<CostTracking> costRows) {
        Map<Integer, BigDecimal> map = new HashMap<>();
        if (costRows == null) return map;
        for (CostTracking c : costRows) {
            BigDecimal amount = c.getEstimatedCost() == null ? BigDecimal.ZERO : c.getEstimatedCost();
            map.merge(c.getIterationNum(), amount, BigDecimal::add);
        }
        return map;
    }

    /**
     * Clamps each raw segment's duration to a minimum of 1 second, then computes proportional
     * widths with a 6% floor per segment (see class javadoc), rescaling so the bar sums to
     * exactly 100%.
     */
    private static List<Segment> normalize(List<RawSegment> raw) {
        int n = raw.size();
        if (n == 0) return List.of();

        long[] durations = new long[n];
        for (int i = 0; i < n; i++) {
            long secs = Duration.between(raw.get(i).start(), raw.get(i).end()).getSeconds();
            durations[i] = Math.max(1, secs);
        }

        double total = 0;
        for (long d : durations) total += d;

        double[] pct = new double[n];
        if (n == 1) {
            pct[0] = 100.0;
        } else {
            for (int i = 0; i < n; i++) {
                pct[i] = durations[i] / total * 100.0;
            }
            double deficit = 0, surplus = 0;
            boolean[] under = new boolean[n];
            for (int i = 0; i < n; i++) {
                if (pct[i] < MIN_WIDTH_PCT) {
                    deficit += MIN_WIDTH_PCT - pct[i];
                    under[i] = true;
                } else {
                    surplus += pct[i] - MIN_WIDTH_PCT;
                }
            }
            if (deficit > 0) {
                double factor = surplus > deficit ? (surplus - deficit) / surplus : 0.0;
                for (int i = 0; i < n; i++) {
                    pct[i] = under[i] ? MIN_WIDTH_PCT : MIN_WIDTH_PCT + (pct[i] - MIN_WIDTH_PCT) * factor;
                }
            }
        }

        // Rounding fix-up: nudge the last segment so the bar sums to exactly 100.0.
        double sum = 0;
        for (int i = 0; i < n - 1; i++) sum += pct[i];
        if (n > 0) pct[n - 1] = 100.0 - sum;

        List<Segment> result = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            result.add(new Segment(raw.get(i).stageName(), durations[i], pct[i], raw.get(i).outcome()));
        }
        return result;
    }

    private static String outcomeBadge(List<Segment> segments, boolean runningIteration) {
        if (segments.isEmpty()) return "UNKNOWN";
        if (runningIteration && segments.stream().anyMatch(s -> RUNNING.equals(s.outcome()))) {
            return "RUNNING";
        }
        String lastOutcome = segments.get(segments.size() - 1).outcome();
        return switch (lastOutcome) {
            case OK -> "PASSED";
            case FAIL -> "FAILED";
            case SKIPPED -> "SKIPPED";
            case RUNNING -> "RUNNING";
            default -> "UNKNOWN";
        };
    }
}

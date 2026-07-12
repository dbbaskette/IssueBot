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
 * cost and an overall outcome badge. Iterations are grouped into {@link RunTimeline runs}
 * (see below). Pure view-model assembly — no repository access and no wall-clock reads ("now"
 * is threaded in by the caller, so the same inputs always produce the same output); the caller
 * ({@code IssueController}) fetches the three input lists with one query each and hands them in.
 *
 * <h2>Derivation strategy</h2>
 * <b>Runs.</b> A manual retry of a FAILED/COOLDOWN issue resets {@code currentIteration} to 0
 * WITHOUT deleting the previous run's {@code Iteration}/{@code Event}/{@code CostTracking} rows
 * (see {@code IssueController#performRetry}), so one issue can hold several complete runs whose
 * iterations share duplicate {@code iterationNum} values (two different "iteration 1" rows).
 * Sorting by {@code iterationNum} would interleave runs and corrupt window adjacency, so
 * iterations are sorted <b>chronologically by {@link Iteration#getStartedAt()}</b> and then
 * grouped into runs by detecting the counter reset: a later-started iteration whose
 * {@code iterationNum} is &le; its chronological predecessor's marks the start of a new run.
 * All runs are rendered (history is preserved honestly); the template labels them
 * ("Run 2 · Iteration 1") only when more than one run exists.
 *
 * <p><b>Iteration windows.</b> {@code IssueWorkflowService} saves each new {@link Iteration} row
 * (stamping {@code startedAt}) immediately before logging {@code ITERATION_STARTED}, so
 * {@link Iteration#getStartedAt()} is a precise, always-present anchor. Each iteration's window
 * is {@code [iteration.startedAt, nextIteration.startedAt)} where "next" is the next iteration
 * <b>in chronological order across runs</b> — the last iteration of run K is bounded by run
 * K+1's first {@code startedAt}, so one run's events can never bleed into another run's cards.
 * Only the last iteration of the LAST run is open-ended. {@link Iteration#getCompletedAt()} is
 * deliberately NOT used as a window boundary: the workflow stamps it right after the CI phase
 * concludes (see the unconditional {@code iteration.setCompletedAt(...)} in
 * {@code IssueWorkflowService#processIssue} that runs before checking whether CI passed), which
 * is BEFORE PR creation and Independent Review even start on a successful iteration — using it
 * as a window end would silently truncate the Review segment.
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
 *       implementation on success). A failed session resume ({@code SESSION_RESUME_FAILED})
 *       triggers a cold retry INSIDE the same {@code phaseImplementation} call, so a single
 *       start/complete pair brackets both attempts — the segment honestly covers the combined
 *       wall-clock time of the discarded resumed attempt plus the cold retry.</li>
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
 * <p><b>Robustness / running iteration.</b> A stage with no discoverable start event is omitted —
 * never guessed. A stage with a start event but no terminal event: if this is the last iteration
 * of the last run on an issue currently {@link IssueStatus#IN_PROGRESS}, it becomes an open
 * {@code running} segment ending at the caller-supplied "now"; otherwise (stale data — e.g. an
 * old issue with gappy events, or a process crash) it is omitted. For the running iteration the
 * outcome badge is always {@code RUNNING}, and the bar always ends with an open segment: when
 * the last tracked stage has already completed (e.g. CI passed and the workflow is between
 * stages — PR creation, or waiting on the review to log its start event), an open segment named
 * after {@link TrackedIssue#getCurrentPhase()} is synthesized from the last segment's end to
 * now; when NO segment is derivable at all, a single whole-bar open segment is synthesized so
 * the bar isn't blank. Zero/negative durations (clock skew, same-millisecond events) clamp to
 * 1 second. Widths are proportional to duration within the bar, with a 6% floor per segment for
 * label legibility (always feasible: at most 5 segments per bar — 4 stages plus the synthesized
 * gap — 5 × 6% = 30% ≤ 100%) and the remainder rescaled so the bar always sums to exactly 100%.
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
     * @param stageName   display label ("Implementation", "Local Checks", "CI", "Review", or —
     *                    for the synthesized open segment on the running iteration — the display
     *                    name of the workflow's current phase, falling back to "Running")
     * @param durationSecs wall-clock seconds spent in this stage, clamped to a minimum of 1
     * @param widthPct    this segment's share of the bar's width, 0–100, floored at 6% and
     *                    normalized so all segments in an iteration sum to exactly 100
     * @param outcome     one of {@link #OK}, {@link #FAIL}, {@link #SKIPPED}, {@link #RUNNING}
     */
    public record Segment(String stageName, long durationSecs, double widthPct, String outcome) {}

    /**
     * The assembled timeline for one iteration.
     *
     * @param iterationNum matches {@link Iteration#getIterationNum()} (unique within a run,
     *                     NOT across runs — retries reset the counter)
     * @param segments     stage segments in chronological order; empty when nothing is derivable
     * @param totalCost    sum of {@link CostTracking#getEstimatedCost()} for rows with this
     *                     iteration's number (cost rows carry only the iteration number, so on a
     *                     multi-run issue the total is shared across the runs' same-numbered
     *                     iterations — an accepted imprecision, since CostTracking has no run
     *                     discriminator)
     * @param outcomeBadge a short display verdict: {@code RUNNING} (always, for the currently
     *                     running iteration), {@code PASSED}, {@code FAILED}, {@code SKIPPED},
     *                     or {@code UNKNOWN} (no segments at all)
     */
    public record IterationTimeline(int iterationNum, List<Segment> segments,
                                     BigDecimal totalCost, String outcomeBadge) {}

    /**
     * One run's worth of iterations (see the class javadoc's Runs section). {@code runNum} is
     * 1-based in chronological order; the template shows it only when the issue has &gt;1 run.
     */
    public record RunTimeline(int runNum, List<IterationTimeline> iterations) {}

    /** A segment before duration-clamping/width-normalization are applied. */
    private record RawSegment(String stageName, LocalDateTime start, LocalDateTime end, String outcome) {}

    /**
     * Assembles the runs' timelines. Returns an empty list when there are no iterations yet
     * (nothing to draw).
     *
     * @param issue      the tracked issue (its {@link IssueStatus} drives the running-segment
     *                   detection for the last iteration; {@code currentPhase} names the
     *                   synthesized open segment)
     * @param events     ALL events for this issue, any order (sorted internally by
     *                   {@link Event#getCreatedAt()})
     * @param iterations this issue's iterations, any order (sorted internally by
     *                   {@link Iteration#getStartedAt()} — chronological, NOT by iteration
     *                   number, which repeats across runs)
     * @param costRows   this issue's cost-tracking rows, any order
     * @param now        the caller's "now" (normally {@code LocalDateTime.now()}) — the open end
     *                   of the running iteration's in-flight segment; threaded in rather than
     *                   read here so assembly stays deterministic and testable
     */
    public List<RunTimeline> assemble(TrackedIssue issue, List<Event> events,
                                       List<Iteration> iterations, List<CostTracking> costRows,
                                       LocalDateTime now) {
        if (iterations == null || iterations.isEmpty()) {
            return List.of();
        }

        List<Event> sortedEvents = events == null ? List.of() : events.stream()
                .filter(e -> e.getCreatedAt() != null)
                .sorted(Comparator.comparing(Event::getCreatedAt))
                .toList();

        // Chronological across runs — iterationNum repeats between runs, startedAt never goes
        // backwards. Tie-breaks (same startedAt) fall back to iterationNum for determinism.
        List<Iteration> chronological = iterations.stream()
                .sorted(Comparator.comparing(Iteration::getStartedAt,
                                Comparator.nullsFirst(Comparator.naturalOrder()))
                        .thenComparingInt(Iteration::getIterationNum))
                .toList();

        List<List<Iteration>> runs = groupIntoRuns(chronological);

        Map<Integer, BigDecimal> costByIteration = groupCostsByIteration(costRows);
        boolean issueRunning = issue != null && issue.getStatus() == IssueStatus.IN_PROGRESS;

        List<RunTimeline> result = new ArrayList<>(runs.size());
        for (int r = 0; r < runs.size(); r++) {
            List<Iteration> run = runs.get(r);
            boolean lastRun = r == runs.size() - 1;
            // The last iteration of run K is bounded by run K+1's first startedAt so a finished
            // run's window can never swallow the next run's events.
            LocalDateTime nextRunStart = lastRun ? null : runs.get(r + 1).get(0).getStartedAt();

            List<IterationTimeline> iterationTimelines = new ArrayList<>(run.size());
            for (int i = 0; i < run.size(); i++) {
                Iteration iteration = run.get(i);
                boolean lastInRun = i == run.size() - 1;
                LocalDateTime windowStart = iteration.getStartedAt();
                LocalDateTime windowEnd = lastInRun ? nextRunStart : run.get(i + 1).getStartedAt();

                List<Event> windowEvents = sortedEvents.stream()
                        .filter(e -> windowStart == null || !e.getCreatedAt().isBefore(windowStart))
                        .filter(e -> windowEnd == null || e.getCreatedAt().isBefore(windowEnd))
                        .toList();

                boolean runningIteration = lastRun && lastInRun && issueRunning;
                List<RawSegment> raw = buildRawSegments(iteration, windowEvents, runningIteration, now);

                if (runningIteration) {
                    // The running bar must always end open: either nothing is derivable yet
                    // (whole-bar placeholder), or every tracked stage has already completed and
                    // the workflow is between stages (CI passed, review not yet logged) — either
                    // way an open segment named after the current phase is appended.
                    if (raw.isEmpty()) {
                        LocalDateTime start = windowStart != null ? windowStart : now;
                        raw = List.of(new RawSegment(phaseLabel(issue), start, now, RUNNING));
                    } else if (!RUNNING.equals(raw.get(raw.size() - 1).outcome())) {
                        raw = new ArrayList<>(raw);
                        raw.add(new RawSegment(phaseLabel(issue),
                                raw.get(raw.size() - 1).end(), now, RUNNING));
                    }
                }

                List<Segment> segments = normalize(raw);
                BigDecimal cost = costByIteration.getOrDefault(iteration.getIterationNum(), BigDecimal.ZERO);
                String badge = runningIteration ? "RUNNING" : outcomeBadge(segments);

                iterationTimelines.add(new IterationTimeline(
                        iteration.getIterationNum(), segments, cost, badge));
            }
            result.add(new RunTimeline(r + 1, iterationTimelines));
        }
        return result;
    }

    /**
     * Splits chronologically ordered iterations into runs: the counter restarting (an iteration
     * whose {@code iterationNum} is &le; its predecessor's) marks a retry, i.e. a new run.
     */
    private static List<List<Iteration>> groupIntoRuns(List<Iteration> chronological) {
        List<List<Iteration>> runs = new ArrayList<>();
        List<Iteration> current = new ArrayList<>();
        int previousNum = Integer.MIN_VALUE;
        for (Iteration iteration : chronological) {
            if (!current.isEmpty() && iteration.getIterationNum() <= previousNum) {
                runs.add(current);
                current = new ArrayList<>();
            }
            current.add(iteration);
            previousNum = iteration.getIterationNum();
        }
        if (!current.isEmpty()) {
            runs.add(current);
        }
        return runs;
    }

    /**
     * Display name for the synthesized open segment on the running iteration, from the
     * workflow's own structured {@link TrackedIssue#getCurrentPhase()} (the values written by
     * {@code IssueWorkflowService}'s {@code setCurrentPhase} calls). Falls back to "Running"
     * when unset/unknown — never guesses a stage.
     */
    private static String phaseLabel(TrackedIssue issue) {
        String phase = issue == null ? null : issue.getCurrentPhase();
        if (phase == null) return "Running";
        return switch (phase) {
            case "SETUP" -> "Setup";
            case "IMPLEMENTATION" -> "Implementation";
            case "LOCAL_CHECKS" -> "Local Checks";
            case "CI_VERIFICATION" -> "CI";
            case "PR_CREATION" -> "PR Creation";
            case "INDEPENDENT_REVIEW" -> "Review";
            case "COMPLETION" -> "Completion";
            default -> "Running";
        };
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

    /** Badge for a NON-running iteration (the running one is unconditionally "RUNNING"). */
    private static String outcomeBadge(List<Segment> segments) {
        if (segments.isEmpty()) return "UNKNOWN";
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

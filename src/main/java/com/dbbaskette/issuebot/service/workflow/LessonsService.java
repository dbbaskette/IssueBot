package com.dbbaskette.issuebot.service.workflow;

import com.dbbaskette.issuebot.model.RepoLesson;
import com.dbbaskette.issuebot.model.TrackedIssue;
import com.dbbaskette.issuebot.model.WatchedRepo;
import com.dbbaskette.issuebot.repository.RepoLessonRepository;
import com.dbbaskette.issuebot.service.harness.HarnessExecutionResult;
import com.dbbaskette.issuebot.service.harness.CodingHarnessService;
import com.dbbaskette.issuebot.service.event.EventService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Cross-issue lessons (#69, opt-in via {@link WatchedRepo#isLessonsEnabled()}):
 * when a run reaches a workflow-visible end (completed, or escalated after
 * exhausting its iteration budget), a cheap utility-model call summarizes 1-3
 * short, transferable lessons from that run's context and appends them to a
 * per-repo store, capped at {@link #MAX_LESSONS} (oldest evicted first). Active
 * lessons are later injected into future implementation prompts for the same
 * repo by {@code IssueWorkflowService}.
 *
 * {@link #capture} is the single entry point and is deliberately exception-proof:
 * a lessons-capture hiccup (utility model down, parse failure, whatever) must
 * never affect the outcome of the issue that just finished.
 */
@Service
public class LessonsService {

    private static final Logger log = LoggerFactory.getLogger(LessonsService.class);

    static final int MAX_LESSONS = 30;
    static final int MAX_LESSON_CHARS = 1000;
    static final int MAX_LESSONS_PER_CAPTURE = 3;

    private final CodingHarnessService harnessService;
    private final RepoLessonRepository lessonRepository;
    private final EventService eventService;

    public LessonsService(CodingHarnessService harnessService, RepoLessonRepository lessonRepository,
                           EventService eventService) {
        this.harnessService = harnessService;
        this.lessonRepository = lessonRepository;
        this.eventService = eventService;
    }

    /**
     * Capture lessons from a just-finished issue run. No-op (and no CLI call) unless
     * the repo has lessons enabled. Never throws — any failure is logged (and, when
     * possible, recorded as a LESSONS_FAILED event) and swallowed.
     *
     * @param issue          the tracked issue that just finished
     * @param outcome        short human-readable outcome, e.g. "completed successfully"
     *                       or "failed after max iterations"
     * @param contextSummary the last iteration's feedback/CI-log context (already
     *                       truncated by the caller), or a clean-run placeholder
     * @param repoPath       the repo's local checkout — the utility-model call's working dir
     */
    public void capture(TrackedIssue issue, String outcome, String contextSummary, Path repoPath) {
        if (issue == null) return;
        WatchedRepo repo = issue.getRepo();
        if (repo == null || !repo.isLessonsEnabled()) return;

        try {
            List<String> existing = RepoLessonQuality.forPrompt(
                    lessonRepository.findByRepoIdOrderByCreatedAtAsc(repo.getId()));
            String prompt = buildPrompt(issue.getIssueNumber(), outcome, contextSummary, existing);
            HarnessExecutionResult result = harnessService.executeUtility(prompt, repoPath, null);
            if (result == null || !result.isSuccess()) {
                log.warn("Lessons capture: utility call failed for {} #{}: {}",
                        repo.fullName(), issue.getIssueNumber(),
                        result != null ? result.getErrorMessage() : "no result");
                return;
            }

            List<String> lessons = parseLessons(result.getOutput());
            if (lessons.isEmpty()) {
                log.debug("Lessons capture: nothing transferable for {} #{}",
                        repo.fullName(), issue.getIssueNumber());
                return;
            }

            Set<String> seen = new HashSet<>();
            existing.forEach(lesson -> seen.add(RepoLessonQuality.key(lesson)));
            int stored = 0;
            for (String lesson : lessons) {
                if (!RepoLessonQuality.reusable(lesson)
                        || !seen.add(RepoLessonQuality.key(lesson))) continue;
                lessonRepository.save(new RepoLesson(repo.getId(), lesson, issue.getIssueNumber()));
                stored++;
            }
            if (stored == 0) return;
            evictOverCap(repo.getId());

            log.info("Lessons capture: stored {} lesson(s) for {} #{}",
                    stored, repo.fullName(), issue.getIssueNumber());
        } catch (Exception e) {
            log.warn("Lessons capture failed for {} #{}: {}",
                    repo.fullName(), issue.getIssueNumber(), e.getMessage(), e);
            try {
                eventService.log("LESSONS_FAILED",
                        "Lessons capture failed: " + e.getMessage(), repo, issue);
            } catch (Exception ignored) {
                // Never let a logging failure surface either.
            }
        }
    }

    String buildPrompt(int issueNumber, String outcome, String contextSummary) {
        return buildPrompt(issueNumber, outcome, contextSummary, List.of());
    }

    String buildPrompt(int issueNumber, String outcome, String contextSummary,
                       List<String> existing) {
        StringBuilder prompt = new StringBuilder("An automated coding agent just finished working on issue #")
                .append(issueNumber)
                .append(" (").append(outcome).append(") in this repository. Context:\n")
                .append(contextSummary)
                .append("\nExtract at most 3 durable, repository-wide rules that would help with DIFFERENT future issues. "
                + "Prefer a general principle or stable repository convention over the steps taken for this issue. "
                + "A path to an existing repository document is useful when it is a durable source of truth; "
                + "do not invent paths or claim a document exists without checking. "
                + "Do not include issue or PR numbers, commit hashes, line numbers, one-off file edits, "
                + "failure descriptions, or instructions that only make sense for this issue. "
                + "Do not turn a one-time workaround into a permanent rule. "
                + "Examples of good lessons: 'Follow docs/architecture.md for module boundaries' "
                + "(only if that file exists); 'Run ./mvnw test before submitting Java changes' "
                + "(only if this repo uses Maven). Bad: 'Fix issue #42 by changing FooService.java:97'. "
                + "If the evidence does not support a reusable rule, respond exactly NONE. "
                + "Otherwise respond with ONLY the rules, one short imperative sentence per line, "
                + "no numbering or bullets.");
        if (existing != null && !existing.isEmpty()) {
            prompt.append("\nAlready saved; do not repeat these lessons:\n");
            existing.stream().skip(Math.max(0, existing.size() - 10))
                    .forEach(lesson -> prompt.append("- ")
                            .append(lesson, 0, Math.min(lesson.length(), 240)).append('\n'));
        }
        return prompt.toString();
    }

    /**
     * Lenient parse: splits on lines, strips bullet/numbering markers, drops blanks
     * and "NONE", caps each lesson at {@link #MAX_LESSON_CHARS} chars, and takes at
     * most {@link #MAX_LESSONS_PER_CAPTURE}. Package-private for direct unit testing.
     */
    List<String> parseLessons(String output) {
        if (output == null || output.isBlank()) return List.of();

        List<String> lessons = new ArrayList<>();
        for (String rawLine : output.split("\\R")) {
            String cleaned = cleanLine(rawLine);
            if (cleaned.isEmpty() || "NONE".equalsIgnoreCase(cleaned)) continue;
            if (cleaned.length() > MAX_LESSON_CHARS) {
                cleaned = cleaned.substring(0, MAX_LESSON_CHARS);
            }
            lessons.add(cleaned);
            if (lessons.size() >= MAX_LESSONS_PER_CAPTURE) break;
        }
        return lessons;
    }

    /** Strip leading bullet markers ("-", "*", "•") and numbering ("1.", "1)") from one line. */
    private String cleanLine(String line) {
        String s = line.strip();
        s = s.replaceFirst("^[-*•]\\s+", "");
        s = s.replaceFirst("^\\d+[.)]\\s+", "");
        return s.strip();
    }

    /**
     * FIFO cap-eviction: compute the excess once (relative to {@link #MAX_LESSONS})
     * and delete that many oldest rows. Computing the excess once (rather than
     * re-checking the count in a while-loop) keeps this safe against a repository
     * stub whose count doesn't reflect prior deletes.
     */
    private void evictOverCap(Long repoId) {
        long excess = lessonRepository.countByRepoId(repoId) - MAX_LESSONS;
        for (long i = 0; i < excess; i++) {
            lessonRepository.findFirstByRepoIdOrderByCreatedAtAsc(repoId)
                    .ifPresent(lessonRepository::delete);
        }
    }
}

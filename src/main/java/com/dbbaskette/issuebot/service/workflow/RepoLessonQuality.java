package com.dbbaskette.issuebot.service.workflow;

import com.dbbaskette.issuebot.model.RepoLesson;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Conservative, deterministic guard against carrying obvious one-off details into later issues. */
public final class RepoLessonQuality {
    private static final Pattern ISSUE_REFERENCE = Pattern.compile(
            "(?i)(?:#\\d+\\b|\\b(?:issue|ticket|pull request|pr)\\s*#?\\d+\\b)");
    private static final Pattern TEMPORARY_CONTEXT = Pattern.compile(
            "(?i)\\b(?:this|that|the current)\\s+(?:issue|ticket|pull request|pr|run|attempt|task|change|failure)\\b");
    private static final Pattern COMMIT_HASH = Pattern.compile("(?i)\\b[0-9a-f]{12,40}\\b");
    private static final Pattern FILE_LINE = Pattern.compile("\\.[A-Za-z0-9]{1,8}:\\d+\\b");
    private static final Pattern ISSUE_BRANCH = Pattern.compile("(?i)\\bissuebot/\\d+\\b");
    private static final Pattern LINE_REFERENCE = Pattern.compile("(?i)\\bline\\s+\\d+\\b");
    private static final Pattern ISSUE_URL = Pattern.compile("(?i)/(?:issues|pull)/\\d+\\b");
    private static final Pattern TASK_CODE = Pattern.compile("(?i)\\bT\\d{2,3}\\b");
    private static final Pattern AGENT_NARRATION = Pattern.compile(
            "(?i)^\\s*(?:I|we)\\s*(?:['’]ll|['’]m|will|am|are|plan to|intend to)\\b");

    private RepoLessonQuality() {}

    /** Stable paths to project documentation and test commands are deliberately allowed. */
    public static boolean reusable(String lesson) {
        return lesson != null && !lesson.isBlank()
                && !ISSUE_REFERENCE.matcher(lesson).find()
                && !TEMPORARY_CONTEXT.matcher(lesson).find()
                && !COMMIT_HASH.matcher(lesson).find()
                && !FILE_LINE.matcher(lesson).find()
                && !ISSUE_BRANCH.matcher(lesson).find()
                && !LINE_REFERENCE.matcher(lesson).find()
                && !ISSUE_URL.matcher(lesson).find()
                && !TASK_CODE.matcher(lesson).find()
                && !AGENT_NARRATION.matcher(lesson).find();
    }

    /** Never silently remove saved rows: omit clear one-off notes from future prompts instead. */
    public static List<String> forPrompt(List<RepoLesson> rows) {
        if (rows == null || rows.isEmpty()) return List.of();
        Set<String> seen = new HashSet<>();
        return rows.stream().map(RepoLesson::getLesson)
                .filter(RepoLessonQuality::reusable)
                .filter(lesson -> seen.add(key(lesson)))
                .toList();
    }

    public static String key(String lesson) {
        return lesson.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
}

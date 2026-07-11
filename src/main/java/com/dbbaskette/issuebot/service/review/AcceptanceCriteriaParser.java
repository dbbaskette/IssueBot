package com.dbbaskette.issuebot.service.review;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses acceptance criteria out of a GitHub issue body (issue #61) so the
 * independent review can score the implementation against the issue's own
 * verifiable checklist rather than a vibe.
 *
 * <p>Two sources are recognized:
 * <ul>
 *   <li>Markdown task-list items ({@code - [ ]} / {@code - [x]} / {@code * [ ]})
 *       anywhere in the body.</li>
 *   <li>Plain bullet ({@code -}/{@code *}) or numbered ({@code 1.}) lines found
 *       directly under a heading matching "Acceptance Criteria" (case-insensitive,
 *       any {@code #} level) — the format IssueBot's own decomposition sub-issues
 *       use.</li>
 * </ul>
 */
public final class AcceptanceCriteriaParser {

    private static final int MAX_CRITERIA = 20;
    private static final int MAX_LENGTH = 300;

    private static final Pattern HEADING =
            Pattern.compile("#{1,6}\\s*acceptance criteria", Pattern.CASE_INSENSITIVE);
    private static final Pattern TASK_LIST =
            Pattern.compile("^\\s*[-*]\\s*\\[([ xX])]\\s*(.+)$");
    private static final Pattern BULLET =
            Pattern.compile("^\\s*[-*]\\s+(.+)$");
    private static final Pattern NUMBERED =
            Pattern.compile("^\\s*\\d+\\.\\s+(.+)$");

    private AcceptanceCriteriaParser() {
    }

    public static List<String> parse(String issueBody) {
        if (issueBody == null || issueBody.isBlank()) {
            return List.of();
        }

        List<String> collected = new ArrayList<>();
        boolean inHeadingSection = false;

        for (String line : issueBody.split("\n", -1)) {
            String trimmed = line.trim();

            if (HEADING.matcher(trimmed).lookingAt()) {
                inHeadingSection = true;
                continue;
            }

            Matcher taskMatch = TASK_LIST.matcher(line);
            if (taskMatch.matches()) {
                collected.add(taskMatch.group(2).trim());
                continue;
            }

            if (!inHeadingSection) {
                continue;
            }

            if (trimmed.isEmpty()) {
                // Blank lines within a heading section don't end it (e.g. a blank
                // line right after the heading, before the bullets start).
                continue;
            }

            Matcher bulletMatch = BULLET.matcher(line);
            Matcher numberedMatch = NUMBERED.matcher(line);
            if (bulletMatch.matches() || numberedMatch.matches()) {
                // Only TOP-LEVEL bullets are criteria (indentation < 2 spaces).
                // Indented sub-bullets are detail lines under a criterion:
                // skipped, but they do NOT terminate the section.
                if (!isNested(line)) {
                    collected.add((bulletMatch.matches()
                            ? bulletMatch.group(1) : numberedMatch.group(1)).trim());
                }
            } else {
                // Any other non-blank content (prose, or the next heading) ends
                // the section — covers both "next heading" and "blank-line-then-
                // heading" termination since the heading line itself lands here.
                inHeadingSection = false;
            }
        }

        List<String> trimmedToLength = collected.stream()
                .map(s -> s.length() > MAX_LENGTH ? s.substring(0, MAX_LENGTH) : s)
                .toList();

        LinkedHashSet<String> deduped = new LinkedHashSet<>(trimmedToLength);

        return deduped.stream().limit(MAX_CRITERIA).toList();
    }

    /** A list line is nested (a sub-bullet) when indented by 2+ spaces or a tab. */
    private static boolean isNested(String line) {
        return line.startsWith("  ") || line.startsWith("\t");
    }
}

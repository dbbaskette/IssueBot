package com.dbbaskette.issuebot.service.workflow;

import com.dbbaskette.issuebot.model.WatchedRepo;
import com.dbbaskette.issuebot.service.github.GitHubApiClient;
import com.dbbaskette.issuebot.service.event.EventService;
import com.dbbaskette.issuebot.service.review.CodeReviewResult.ReviewFinding;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Maintains one rolling "IssueBot Backlog" issue per repo. Findings are
 * checklist items, deduplicated by a stable key stored in an HTML comment.
 */
@Service
public class BacklogService {

    private static final Logger log = LoggerFactory.getLogger(BacklogService.class);
    static final String BACKLOG_LABEL = "issuebot-backlog";
    static final String BACKLOG_TITLE = "IssueBot Backlog";
    static final int MAX_ITEMS = 50;
    private static final String KEYS_PREFIX = "<!-- issuebot-keys:";
    /** Trailing per-item key annotation, e.g. "... (from #1 / PR #2) <!-- k:ab12cd34 -->". */
    private static final Pattern ITEM_KEY = Pattern.compile("<!-- k:(\\S+) -->\\s*$");

    private final GitHubApiClient gitHubApi;
    private final EventService eventService;

    public BacklogService(GitHubApiClient gitHubApi, EventService eventService) {
        this.gitHubApi = gitHubApi;
        this.eventService = eventService;
    }

    /**
     * Append findings to the repo's backlog issue, creating/reopening it as needed.
     * Safety invariant: the workflow processes at most one issue per repo at a time,
     * so backlog body read-merge-write cycles are never concurrent for a given repo.
     */
    public void addFindings(WatchedRepo repo, List<ReviewFinding> findings,
                            int sourceIssueNumber, int prNumber) {
        if (findings.isEmpty()) return;
        try {
            JsonNode backlog = findBacklogIssue(repo);
            if (backlog == null) {
                MergeResult fresh = merge(initialBody(), findings, sourceIssueNumber, prNumber);
                JsonNode created = gitHubApi.createIssue(repo.getOwner(), repo.getName(),
                        BACKLOG_TITLE, fresh.body(), List.of(BACKLOG_LABEL));
                eventService.log("BACKLOG_UPDATED", "Created backlog issue #"
                        + created.path("number").asInt() + " with " + fresh.added() + " findings", repo);
                return;
            }
            int number = backlog.path("number").asInt();
            if ("closed".equals(backlog.path("state").asText())) {
                gitHubApi.reopenIssue(repo.getOwner(), repo.getName(), number);
            }
            MergeResult result = merge(backlog.path("body").asText(""), findings,
                    sourceIssueNumber, prNumber);
            if (result.added() > 0) {
                gitHubApi.updateIssueBody(repo.getOwner(), repo.getName(), number, result.body());
            }
            eventService.log("BACKLOG_UPDATED", "Backlog #" + number + ": +"
                    + result.added() + " findings (" + (findings.size() - result.added()) + " duplicates skipped)", repo);
        } catch (Exception e) {
            log.warn("Failed to update backlog for {}: {}", repo.fullName(), e.getMessage());
            eventService.log("BACKLOG_UPDATE_FAILED",
                    "Failed to update backlog: " + e.getMessage(), repo);
        }
    }

    private JsonNode findBacklogIssue(WatchedRepo repo) {
        List<JsonNode> issues = gitHubApi.listIssues(repo.getOwner(), repo.getName(),
                BACKLOG_LABEL, "all");
        return (issues == null || issues.isEmpty()) ? null : issues.get(0);
    }

    private static String initialBody() {
        return "Non-blocking findings from IssueBot code reviews. Check items off as they are addressed, "
                + "or promote them to `agent-ready` issues.\n\n"
                + KEYS_PREFIX + " -->\n";
    }

    /** Stable dedup key: file + category + normalized finding text (line numbers drift, so excluded). */
    static String dedupKey(ReviewFinding f) {
        String normalized = (f.file() + "|" + f.category() + "|"
                + f.finding().toLowerCase().replaceAll("\\s+", " ").trim());
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(normalized.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 8; i++) hex.append(String.format("%02x", hash[i]));
            return hex.toString();
        } catch (Exception e) {
            return Integer.toHexString(normalized.hashCode());
        }
    }

    record MergeResult(String body, int added) {}

    static MergeResult merge(String existingBody, List<ReviewFinding> findings,
                             int sourceIssueNumber, int prNumber) {
        Set<String> keys = parseKeys(existingBody);
        List<String> items = new ArrayList<>(existingBody.lines()
                .filter(l -> l.startsWith("- [")).toList());
        String header = existingBody.lines()
                .takeWhile(l -> !l.startsWith("- [") && !l.startsWith(KEYS_PREFIX))
                .reduce("", (a, b) -> a.isEmpty() ? b : a + "\n" + b)
                // Trim surrounding blank lines so repeated merges don't grow the header:
                // the builder below always inserts exactly one blank line after it.
                .strip();

        int added = 0;
        for (ReviewFinding f : findings) {
            String key = dedupKey(f);
            if (!keys.add(key)) continue;
            // oneLine() prevents newline injection: finding/file text containing "\n- [ ]"
            // or "\n<!-- issuebot-keys:" must not create phantom items or hijack the key store.
            // The trailing "<!-- k:... -->" comment (invisible in rendered markdown) ties the
            // item to its dedup key so pruning can retire the key with the item.
            items.add("- [ ] **[" + f.severity().toUpperCase() + " — " + f.category() + "]** `"
                    + oneLine(f.file()) + (f.line() != null ? ":" + f.line() : "") + "` — " + oneLine(f.finding())
                    + (f.suggestion() == null || f.suggestion().isBlank() ? "" : " Suggested improvement: " + oneLine(f.suggestion()))
                    + " (from #" + sourceIssueNumber + " / PR #" + prNumber + ") <!-- k:" + key + " -->");
            added++;
        }

        // Prune: drop oldest checked items first, then oldest unchecked, down to MAX_ITEMS.
        // Pruned items surrender their dedup key so the finding can resurface later.
        while (items.size() > MAX_ITEMS) {
            int checkedIdx = -1;
            for (int i = 0; i < items.size(); i++) {
                if (items.get(i).startsWith("- [x]")) { checkedIdx = i; break; }
            }
            String removed = items.remove(checkedIdx >= 0 ? checkedIdx : 0);
            Matcher m = ITEM_KEY.matcher(removed);
            if (m.find()) keys.remove(m.group(1));
        }

        StringBuilder body = new StringBuilder(header).append("\n\n");
        items.forEach(i -> body.append(i).append("\n"));
        body.append("\n").append(KEYS_PREFIX).append(" ")
            .append(String.join(",", keys)).append(" -->\n");
        return new MergeResult(body.toString(), added);
    }

    /** Collapse all line breaks to single spaces so untrusted text cannot span checklist lines. */
    private static String oneLine(String s) {
        return s == null ? "" : s.replaceAll("\\R+", " ").strip();
    }

    private static Set<String> parseKeys(String body) {
        Set<String> keys = new LinkedHashSet<>();
        body.lines().filter(l -> l.startsWith(KEYS_PREFIX)).findFirst().ifPresent(line -> {
            String inner = line.substring(KEYS_PREFIX.length()).replace("-->", "").trim();
            for (String k : inner.split(",")) if (!k.isBlank()) keys.add(k.trim());
        });
        return keys;
    }
}

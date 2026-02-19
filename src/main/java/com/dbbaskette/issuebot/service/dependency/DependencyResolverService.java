package com.dbbaskette.issuebot.service.dependency;

import com.dbbaskette.issuebot.model.IssueStatus;
import com.dbbaskette.issuebot.model.TrackedIssue;
import com.dbbaskette.issuebot.model.WatchedRepo;
import com.dbbaskette.issuebot.repository.TrackedIssueRepository;
import com.dbbaskette.issuebot.service.github.GitHubApiClient;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class DependencyResolverService {

    private static final Logger log = LoggerFactory.getLogger(DependencyResolverService.class);

    private static final Pattern BLOCKED_BY_LINE = Pattern.compile(
            "\\*\\*Blocked by:\\*\\*\\s*(.+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern STRIKETHROUGH = Pattern.compile("~~[^~]+~~");
    private static final Pattern ISSUE_REF = Pattern.compile("#(\\d+)");

    private final GitHubApiClient gitHubApiClient;
    private final TrackedIssueRepository issueRepository;

    public DependencyResolverService(GitHubApiClient gitHubApiClient,
                                      TrackedIssueRepository issueRepository) {
        this.gitHubApiClient = gitHubApiClient;
        this.issueRepository = issueRepository;
    }

    public record DependencyResult(
            List<Integer> unresolvedBlockers,
            List<Integer> allBlockers,
            String chainDescription,
            boolean hasCycle
    ) {}

    /**
     * Fetches issue body, parses Blocked by line, walks chain recursively with cycle detection.
     */
    public DependencyResult resolve(WatchedRepo repo, int issueNumber) {
        Set<Integer> visited = new HashSet<>();
        List<Integer> allBlockers = new ArrayList<>();
        List<Integer> unresolvedBlockers = new ArrayList<>();
        boolean hasCycle = false;

        List<Integer> directBlockers = fetchAndParseBlockers(repo, issueNumber);
        if (directBlockers.isEmpty()) {
            return new DependencyResult(List.of(), List.of(), "", false);
        }

        Deque<Integer> stack = new ArrayDeque<>(directBlockers);
        visited.add(issueNumber);

        while (!stack.isEmpty()) {
            int blocker = stack.pop();
            if (visited.contains(blocker)) {
                hasCycle = true;
                continue;
            }
            visited.add(blocker);
            allBlockers.add(blocker);

            if (!isIssueClosed(repo, blocker) && !isCompletedInBot(repo, blocker)) {
                unresolvedBlockers.add(blocker);
            }

            // Walk deeper — check if this blocker also has blockers
            List<Integer> transitive = fetchAndParseBlockers(repo, blocker);
            for (int t : transitive) {
                if (t == issueNumber) {
                    // True cycle: a transitive blocker depends back on the original issue
                    hasCycle = true;
                } else if (!visited.contains(t)) {
                    stack.push(t);
                }
                // If visited but not the root, it's a diamond (shared dep) — not a cycle
            }
        }

        Collections.sort(unresolvedBlockers);
        Collections.sort(allBlockers);

        String chain = buildChainDescription(issueNumber, allBlockers, unresolvedBlockers, hasCycle);
        return new DependencyResult(unresolvedBlockers, allBlockers, chain, hasCycle);
    }

    /**
     * Checks if each stored blocker is closed on GitHub or COMPLETED in TrackedIssue DB.
     */
    public boolean allBlockersResolved(WatchedRepo repo, String blockedByIssues) {
        if (blockedByIssues == null || blockedByIssues.isBlank()) {
            return true;
        }

        List<Integer> blockerNumbers = Arrays.stream(blockedByIssues.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(Integer::parseInt)
                .toList();

        for (int blockerNum : blockerNumbers) {
            if (!isIssueClosed(repo, blockerNum) && !isCompletedInBot(repo, blockerNum)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Kahn's algorithm topological sort on queued issues, ties broken by ascending issue number.
     */
    public List<TrackedIssue> topologicalSort(List<TrackedIssue> issues) {
        if (issues.isEmpty()) return List.of();

        // Build a map of issueNumber -> TrackedIssue for quick lookup
        Map<Integer, TrackedIssue> issueMap = new HashMap<>();
        for (TrackedIssue issue : issues) {
            issueMap.put(issue.getIssueNumber(), issue);
        }

        // Build in-degree counts (only for edges within the queued set)
        Map<Integer, Integer> inDegree = new HashMap<>();
        Map<Integer, List<Integer>> dependents = new HashMap<>();
        for (TrackedIssue issue : issues) {
            inDegree.put(issue.getIssueNumber(), 0);
            dependents.put(issue.getIssueNumber(), new ArrayList<>());
        }

        for (TrackedIssue issue : issues) {
            List<Integer> blockers = issue.getBlockerNumbers();
            for (int blocker : blockers) {
                if (issueMap.containsKey(blocker)) {
                    // blocker -> issue (blocker must come first)
                    inDegree.merge(issue.getIssueNumber(), 1, Integer::sum);
                    dependents.get(blocker).add(issue.getIssueNumber());
                }
                // External blockers (not in the queued set) are ignored for ordering
            }
        }

        // Priority queue: lowest issue number first among those with in-degree 0
        PriorityQueue<Integer> ready = new PriorityQueue<>();
        for (var entry : inDegree.entrySet()) {
            if (entry.getValue() == 0) {
                ready.add(entry.getKey());
            }
        }

        List<TrackedIssue> sorted = new ArrayList<>();
        while (!ready.isEmpty()) {
            int next = ready.poll();
            sorted.add(issueMap.get(next));
            for (int dependent : dependents.get(next)) {
                int newDegree = inDegree.merge(dependent, -1, Integer::sum);
                if (newDegree == 0) {
                    ready.add(dependent);
                }
            }
        }

        // If cycle exists among queued issues, append any remaining (shouldn't normally happen)
        if (sorted.size() < issues.size()) {
            Set<Integer> sortedNumbers = sorted.stream()
                    .map(TrackedIssue::getIssueNumber)
                    .collect(Collectors.toSet());
            issues.stream()
                    .filter(i -> !sortedNumbers.contains(i.getIssueNumber()))
                    .sorted(Comparator.comparingInt(TrackedIssue::getIssueNumber))
                    .forEach(sorted::add);
        }

        return sorted;
    }

    // --- Package-private for testing ---

    List<Integer> parseBlockedBy(String body) {
        if (body == null || body.isEmpty()) {
            return List.of();
        }

        for (String line : body.split("\\R")) {
            Matcher lineMatcher = BLOCKED_BY_LINE.matcher(line.trim());
            if (lineMatcher.find()) {
                String refs = lineMatcher.group(1);
                // Strip strikethrough sections
                refs = STRIKETHROUGH.matcher(refs).replaceAll("");

                List<Integer> result = new ArrayList<>();
                Matcher refMatcher = ISSUE_REF.matcher(refs);
                while (refMatcher.find()) {
                    result.add(Integer.parseInt(refMatcher.group(1)));
                }
                return result;
            }
        }
        return List.of();
    }

    // --- Private helpers ---

    private List<Integer> fetchAndParseBlockers(WatchedRepo repo, int issueNumber) {
        try {
            JsonNode issue = gitHubApiClient.getIssue(repo.getOwner(), repo.getName(), issueNumber);
            if (issue == null) return List.of();
            String body = issue.path("body").asText("");
            return parseBlockedBy(body);
        } catch (Exception e) {
            log.warn("Failed to fetch issue #{} from {}: {}", issueNumber, repo.fullName(), e.getMessage());
            return List.of();
        }
    }

    private boolean isIssueClosed(WatchedRepo repo, int issueNumber) {
        try {
            JsonNode issue = gitHubApiClient.getIssue(repo.getOwner(), repo.getName(), issueNumber);
            return issue != null && "closed".equals(issue.path("state").asText());
        } catch (Exception e) {
            log.warn("Failed to check issue #{} state: {}", issueNumber, e.getMessage());
            return false;
        }
    }

    private boolean isCompletedInBot(WatchedRepo repo, int issueNumber) {
        return issueRepository.findByRepoAndIssueNumber(repo, issueNumber)
                .map(t -> t.getStatus() == IssueStatus.COMPLETED)
                .orElse(false);
    }

    private String buildChainDescription(int issueNumber, List<Integer> allBlockers,
                                          List<Integer> unresolvedBlockers, boolean hasCycle) {
        StringBuilder sb = new StringBuilder();
        sb.append("**Dependency Chain for #").append(issueNumber).append("**\n\n");

        if (hasCycle) {
            sb.append("> :warning: Cycle detected in dependency chain\n\n");
        }

        if (!unresolvedBlockers.isEmpty()) {
            sb.append("Waiting on: ");
            sb.append(unresolvedBlockers.stream()
                    .map(n -> "#" + n)
                    .collect(Collectors.joining(", ")));
            sb.append("\n\n");
        }

        sb.append("Processing order: ");
        List<Integer> order = new ArrayList<>(allBlockers);
        order.add(issueNumber);
        Collections.sort(order);
        sb.append(order.stream()
                .map(n -> "#" + n)
                .collect(Collectors.joining(" → ")));

        return sb.toString();
    }
}

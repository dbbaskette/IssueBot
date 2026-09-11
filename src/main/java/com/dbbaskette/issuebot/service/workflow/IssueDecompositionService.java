package com.dbbaskette.issuebot.service.workflow;

import com.dbbaskette.issuebot.model.DecompositionMode;
import com.dbbaskette.issuebot.model.IssueStatus;
import com.dbbaskette.issuebot.model.TrackedIssue;
import com.dbbaskette.issuebot.model.WatchedRepo;
import com.dbbaskette.issuebot.repository.TrackedIssueRepository;
import com.dbbaskette.issuebot.repository.DecompositionChildRepository;
import com.dbbaskette.issuebot.service.harness.HarnessExecutionResult;
import com.dbbaskette.issuebot.service.claude.ClaudeCodeService;
import com.dbbaskette.issuebot.service.event.EventService;
import com.dbbaskette.issuebot.service.github.GitHubApiClient;
import com.dbbaskette.issuebot.service.notification.NotificationService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Decomposes large issues into smaller sub-issues when the implementation
 * times out or is too complex for a single pass.
 *
 * Also provides pre-screening: before attempting implementation, the configured
 * utility model can rate an issue's complexity and, if it's too large, decompose
 * it upfront with implementation hints — saving expensive implementation-model tokens.
 *
 * Uses the configured utility model to analyze the original issue and suggest a
 * breakdown, then creates the sub-issues on GitHub and closes the original.
 */
@Service
public class IssueDecompositionService {

    private static final Logger log = LoggerFactory.getLogger(IssueDecompositionService.class);
    private static final int MAX_SUB_ISSUES = 5;
    private static final int MIN_SUB_ISSUES = 2;
    static final String DECOMPOSED_LABEL = "issuebot-decomposed";
    static final int MAX_OPEN_SUB_ISSUES = 10;

    private final ClaudeCodeService claudeCode;
    private final GitHubApiClient gitHubApi;
    private final TrackedIssueRepository issueRepository;
    private final EventService eventService;
    private final NotificationService notificationService;
    private final ObjectMapper objectMapper;
    private final IterationManager iterationManager;
    private DecompositionGroupTransactionManager groupTransactions;
    private DecompositionGroupService groupService;
    private DecompositionChildRepository decompositionChildren;

    public IssueDecompositionService(ClaudeCodeService claudeCode,
                                      GitHubApiClient gitHubApi,
                                      TrackedIssueRepository issueRepository,
                                      EventService eventService,
                                      NotificationService notificationService,
                                      ObjectMapper objectMapper,
                                      IterationManager iterationManager) {
        this.claudeCode = claudeCode;
        this.gitHubApi = gitHubApi;
        this.issueRepository = issueRepository;
        this.eventService = eventService;
        this.notificationService = notificationService;
        this.objectMapper = objectMapper;
        this.iterationManager = iterationManager;
    }

    @org.springframework.beans.factory.annotation.Autowired
    void configureDurableDecomposition(
            DecompositionGroupTransactionManager groupTransactions,
            DecompositionGroupService groupService,
            DecompositionChildRepository decompositionChildren) {
        this.groupTransactions = groupTransactions;
        this.groupService = groupService;
        this.decompositionChildren = decompositionChildren;
    }

    /**
     * Attempt to decompose a large issue into smaller sub-issues.
     *
     * @param trackedIssue the issue that timed out or was too complex
     * @param issueDetails the GitHub issue JSON (title, body, labels)
     * @param repoPath     the local repo checkout path (for Claude context)
     * @param skipReason   why the retry was skipped (for context in the comment)
     * @return true if decomposition succeeded and sub-issues were created
     */
    public boolean decompose(TrackedIssue trackedIssue, JsonNode issueDetails,
                              Path repoPath, String skipReason) {
        WatchedRepo repo = trackedIssue.getRepo();
        int issueNumber = trackedIssue.getIssueNumber();

        if (repo.getDecompositionMode() == DecompositionMode.OFF) return false;

        if (hasLabel(issueDetails, DECOMPOSED_LABEL)) {
            log.info("Refusing to decompose {} #{} — already a decomposed sub-issue", repo.fullName(), issueNumber);
            eventService.log("DECOMPOSITION_REFUSED",
                    "Sub-issues are never decomposed further", repo, trackedIssue);
            return false;
        }

        List<JsonNode> openSubs = gitHubApi.listIssues(repo.getOwner(), repo.getName(), DECOMPOSED_LABEL, "open");
        if (openSubs != null && openSubs.size() >= MAX_OPEN_SUB_ISSUES) {
            log.warn("Refusing to decompose {} #{} — {} open sub-issues (cap {})",
                    repo.fullName(), issueNumber, openSubs.size(), MAX_OPEN_SUB_ISSUES);
            eventService.log("DECOMPOSITION_REFUSED",
                    "Open sub-issue cap reached (" + openSubs.size() + "/" + MAX_OPEN_SUB_ISSUES + ")",
                    repo, trackedIssue);
            return false;
        }

        // All entry points, including timeout recovery, must qualify by actual scope.
        if (!preScreen(issueDetails, repoPath).tooLarge()) {
            eventService.log("DECOMPOSITION_SKIPPED",
                    "Issue does not meet the large, independently deliverable scope threshold",
                    repo, trackedIssue);
            return false;
        }

        log.info("Attempting to decompose {} #{} into sub-issues", repo.fullName(), issueNumber);
        eventService.log("DECOMPOSITION_STARTED",
                "Attempting to decompose issue into sub-tasks", repo, trackedIssue);

        // Ask Claude to analyze and suggest decomposition
        List<SubIssue> subIssues;
        try {
            subIssues = analyzeAndDecompose(issueDetails, repoPath);
        } catch (Exception e) {
            log.warn("Decomposition analysis failed for {} #{}: {}",
                    repo.fullName(), issueNumber, e.getMessage());
            eventService.log("DECOMPOSITION_FAILED",
                    "Analysis failed: " + e.getMessage(), repo, trackedIssue);
            return false;
        }

        if (subIssues.size() < MIN_SUB_ISSUES) {
            log.info("Decomposition produced fewer than {} sub-issues for {} #{}, skipping",
                    MIN_SUB_ISSUES, repo.fullName(), issueNumber);
            eventService.log("DECOMPOSITION_SKIPPED",
                    "Analysis produced only " + subIssues.size() + " sub-tasks — not enough to decompose",
                    repo, trackedIssue);
            return false;
        }

        boolean managed = StageWorkflowCoordinator.managed(trackedIssue);
        boolean needsSplitApproval = repo.getDecompositionMode() == DecompositionMode.PROPOSE
                || (managed
                    && trackedIssue.getWorkflowPolicy() == com.dbbaskette.issuebot.model.WorkflowPolicy.STAGED
                    && java.util.Arrays.asList(java.util.Optional.ofNullable(trackedIssue.getApprovalStages())
                            .orElse(com.dbbaskette.issuebot.model.WorkflowStage.ALL).split(",")).contains("PLANNING"));
        if (needsSplitApproval) {
            try {
                trackedIssue.setDecompositionProposal(objectMapper.writeValueAsString(subIssues));
            } catch (Exception e) {
                log.warn("Failed to serialize proposal for {} #{}: {}", repo.fullName(), issueNumber, e.getMessage());
                return false;
            }
            trackedIssue.setStatus(IssueStatus.AWAITING_DECOMPOSITION);
            trackedIssue.setCurrentPhase(null);
            issueRepository.save(trackedIssue);

            try {
                gitHubApi.addComment(repo.getOwner(), repo.getName(), issueNumber,
                        buildProposalComment(subIssues, skipReason));
            } catch (Exception e) {
                log.warn("Failed to post proposal comment: {}", e.getMessage());
            }

            eventService.log("DECOMPOSITION_PROPOSED",
                    "Proposed split into " + subIssues.size() + " sub-issues — awaiting approval",
                    repo, trackedIssue);
            notificationService.info("Decomposition Proposed",
                    repo.fullName() + " #" + issueNumber + " — approve or reject in the dashboard", trackedIssue);
            return true;
        }

        if (groupService != null) {
            return createDurableGroup(trackedIssue, subIssues);
        }

        // Compatibility path for isolated unit fixtures; production uses durable groups.
        List<Integer> createdNumbers = createSubIssues(repo, subIssues, issueNumber);

        if (createdNumbers.isEmpty()) {
            log.warn("No sub-issues could be created for {} #{}", repo.fullName(), issueNumber);
            eventService.log("DECOMPOSITION_FAILED",
                    "All sub-issue creations failed", repo, trackedIssue);
            return false;
        }

        // Post comment on original issue linking sub-issues
        String comment = buildDecompositionComment(trackedIssue, createdNumbers, skipReason);
        try {
            gitHubApi.addComment(repo.getOwner(), repo.getName(), issueNumber, comment);
        } catch (Exception e) {
            log.warn("Failed to post decomposition comment to {} #{}: {}",
                    repo.fullName(), issueNumber, e.getMessage());
        }

        convertToTrackingIssue(repo, issueNumber);

        // Mark tracked issue as DECOMPOSED
        trackedIssue.setStatus(IssueStatus.DECOMPOSED);
        trackedIssue.setCurrentPhase(null);
        issueRepository.save(trackedIssue);

        eventService.log("DECOMPOSITION_COMPLETED",
                "Decomposed into " + createdNumbers.size() + " sub-issues: " + createdNumbers,
                repo, trackedIssue);

        notificationService.info("Issue Decomposed",
                repo.fullName() + " #" + issueNumber + " split into "
                        + createdNumbers.size() + " sub-issues", trackedIssue);

        log.info("Successfully decomposed {} #{} into {} sub-issues: {}",
                repo.fullName(), issueNumber, createdNumbers.size(), createdNumbers);
        return true;
    }

    /**
     * Create sub-issues on GitHub from the given decomposition, labeling each
     * {@code agent-ready} + {@code issuebot-decomposed}. Failures creating an
     * individual sub-issue are logged and skipped; the caller checks whether
     * the returned list is empty.
     */
    private List<Integer> createSubIssues(WatchedRepo repo, List<SubIssue> subIssues, int parentIssueNumber) {
        List<Integer> createdNumbers = new ArrayList<>();
        for (SubIssue sub : subIssues) {
            try {
                String body = buildSubIssueBody(sub, parentIssueNumber);
                JsonNode created = gitHubApi.createIssue(
                        repo.getOwner(), repo.getName(), sub.title(), body,
                        List.of("agent-ready", DECOMPOSED_LABEL));
                int subNumber = created.path("number").asInt();
                createdNumbers.add(subNumber);
                log.info("Created sub-issue #{}: {}", subNumber, sub.title());
            } catch (Exception e) {
                log.warn("Failed to create sub-issue '{}': {}", sub.title(), e.getMessage());
            }
        }
        return createdNumbers;
    }

    /**
     * Converts the parent issue into a tracking issue: label it {@code issuebot-parent}
     * and remove {@code agent-ready} so it stays open (instead of being closed) until
     * all its sub-issues are closed.
     */
    private void convertToTrackingIssue(WatchedRepo repo, int issueNumber) {
        try {
            gitHubApi.addLabels(repo.getOwner(), repo.getName(), issueNumber, List.of("issuebot-parent"));
        } catch (Exception e) {
            log.warn("Failed to add issuebot-parent label to {} #{}: {}",
                    repo.fullName(), issueNumber, e.getMessage());
        }
        try {
            gitHubApi.removeLabel(repo.getOwner(), repo.getName(), issueNumber, "agent-ready");
        } catch (Exception e) {
            log.warn("Failed to remove agent-ready label from {} #{}: {}",
                    repo.fullName(), issueNumber, e.getMessage());
        }
    }

    /**
     * Approve a previously proposed decomposition: create the sub-issues, post the
     * decomposition comment, convert the parent into a tracking issue, and mark the
     * tracked issue DECOMPOSED.
     *
     * Synchronized (single-JVM app) and re-reads the issue from the database so a
     * second rapid submit hits the status guard instead of duplicating sub-issues.
     *
     * @throws IllegalStateException if the issue is not awaiting decomposition, has no
     *                               proposal, or no sub-issue could be created on GitHub
     */
    public synchronized void approveProposal(TrackedIssue trackedIssue) throws Exception {
        TrackedIssue issue = issueRepository.findById(trackedIssue.getId()).orElse(trackedIssue);
        if (issue.getStatus() != IssueStatus.AWAITING_DECOMPOSITION
                || issue.getDecompositionProposal() == null) {
            throw new IllegalStateException(
                    "Issue is not awaiting decomposition approval: " + issue.getStatus());
        }

        WatchedRepo repo = issue.getRepo();
        int issueNumber = issue.getIssueNumber();

        List<JsonNode> openSubs = gitHubApi.listIssues(repo.getOwner(), repo.getName(), DECOMPOSED_LABEL, "open");
        if (openSubs != null && openSubs.size() >= MAX_OPEN_SUB_ISSUES) {
            log.warn("Refusing to approve decomposition for {} #{} — {} open sub-issues (cap {})",
                    repo.fullName(), issueNumber, openSubs.size(), MAX_OPEN_SUB_ISSUES);
            eventService.log("DECOMPOSITION_REFUSED",
                    "Open sub-issue cap reached (" + openSubs.size() + "/" + MAX_OPEN_SUB_ISSUES + ")",
                    repo, issue);
            throw new IllegalStateException("Open sub-issue cap reached (" + openSubs.size() + "/"
                    + MAX_OPEN_SUB_ISSUES + ") — close some sub-issues and approve again");
        }

        List<SubIssue> subIssues = objectMapper.readValue(
                issue.getDecompositionProposal(), new TypeReference<List<SubIssue>>() {});

        if (groupService != null) {
            if (!createDurableGroup(issue, subIssues)) {
                throw new IllegalStateException("Could not finish creating the decomposition group; it will resume automatically");
            }
            return;
        }

        List<Integer> createdNumbers = createSubIssues(repo, subIssues, issueNumber);

        if (createdNumbers.isEmpty()) {
            log.warn("No sub-issues could be created for {} #{} — keeping proposal for retry",
                    repo.fullName(), issueNumber);
            eventService.log("DECOMPOSITION_FAILED",
                    "All sub-issue creations failed — proposal retained, try again",
                    repo, issue);
            throw new IllegalStateException(
                    "Could not create any sub-issues on GitHub — try approving again");
        }

        String comment = buildDecompositionComment(issue, createdNumbers, null);
        try {
            gitHubApi.addComment(repo.getOwner(), repo.getName(), issueNumber, comment);
        } catch (Exception e) {
            log.warn("Failed to post decomposition comment to {} #{}: {}",
                    repo.fullName(), issueNumber, e.getMessage());
        }

        convertToTrackingIssue(repo, issueNumber);

        issue.setStatus(IssueStatus.DECOMPOSED);
        issue.setCurrentPhase(null);
        issue.setDecompositionProposal(null);
        issueRepository.save(issue);

        eventService.log("DECOMPOSITION_COMPLETED",
                "Decomposed into " + createdNumbers.size() + " sub-issues: " + createdNumbers,
                repo, issue);

        notificationService.info("Issue Decomposed",
                repo.fullName() + " #" + issueNumber + " split into "
                        + createdNumbers.size() + " sub-issues", issue);
    }

    private boolean createDurableGroup(TrackedIssue issue, List<SubIssue> subIssues) {
        List<DecompositionGroupTransactionManager.ChildIntent> intents = new ArrayList<>();
        for (int index = 0; index < subIssues.size(); index++) {
            SubIssue sub = subIssues.get(index);
            intents.add(new DecompositionGroupTransactionManager.ChildIntent(
                    index + 1, sub.title(), buildSubIssueBody(sub, issue.getIssueNumber())));
        }
        var group = groupTransactions.beginGroup(issue.getId(), intents);
        groupService.createOrResume(group.getId());
        var refreshed = groupTransactions == null ? group : group;
        boolean complete = decompositionChildren.findByGroupOrderBySequencePositionAsc(refreshed).stream()
                .allMatch(child -> child.getCreationState()
                        == com.dbbaskette.issuebot.model.DecompositionChildState.CREATED);
        if (complete) {
            eventService.log("DECOMPOSITION_COMPLETED",
                    "Durable decomposition group created with " + subIssues.size() + " ordered children",
                    issue.getRepo(), issue);
            notificationService.info("Issue Decomposed",
                    issue.getRepo().fullName() + " #" + issue.getIssueNumber() + " split into "
                            + subIssues.size() + " ordered sub-issues", issue);
        }
        // Once the durable group exists, the workflow must stop. A partial GitHub failure
        // remains CREATING and reconciliation resumes it; returning false would incorrectly
        // fall through into implementation of the original parent.
        return true;
    }

    /**
     * Reject a previously proposed decomposition: clear the proposal and escalate
     * the issue to needs-human via the operator-rejection flow.
     *
     * Synchronized (single-JVM app) and re-reads the issue from the database so a
     * second rapid submit hits the status guard instead of escalating twice.
     *
     * @throws IllegalStateException if the issue is not awaiting decomposition or has no proposal
     */
    public synchronized void rejectProposal(TrackedIssue trackedIssue) {
        TrackedIssue issue = issueRepository.findById(trackedIssue.getId()).orElse(trackedIssue);
        if (issue.getStatus() != IssueStatus.AWAITING_DECOMPOSITION
                || issue.getDecompositionProposal() == null) {
            throw new IllegalStateException(
                    "Issue is not awaiting decomposition approval: " + issue.getStatus());
        }

        issue.setDecompositionProposal(null);
        issueRepository.save(issue);

        iterationManager.handleProposalRejected(issue);
    }

    /**
     * Pre-screen an issue before implementation to determine if it's too large.
     * Uses the configured utility model to quickly analyze the issue against the codebase and rate complexity.
     *
     * @param issueDetails the GitHub issue JSON
     * @param repoPath     the local repo checkout path
     * @return a PreScreenResult with the verdict and optional reason
     */
    public PreScreenResult preScreen(JsonNode issueDetails, Path repoPath) {
        if (hasLabel(issueDetails, DECOMPOSED_LABEL)) {
            return new PreScreenResult(false, null); // sub-issues are never re-screened
        }

        String prompt = buildPreScreenPrompt(issueDetails);

        try {
            HarnessExecutionResult result = claudeCode.executeUtility(prompt, repoPath, null);

            if (result == null || result.getOutput() == null || result.getOutput().isBlank()) {
                log.warn("Pre-screen returned empty response, allowing implementation");
                return new PreScreenResult(false, null);
            }

            return parsePreScreenResult(result.getOutput());
        } catch (Exception e) {
            log.warn("Pre-screen analysis failed, allowing implementation: {}", e.getMessage());
            return new PreScreenResult(false, null);
        }
    }

    /**
     * Use the configured utility model to analyze the issue and produce a decomposition.
     */
    List<SubIssue> analyzeAndDecompose(JsonNode issueDetails, Path repoPath) {
        String prompt = buildDecompositionPrompt(issueDetails);

        HarnessExecutionResult result = claudeCode.executeUtility(prompt, repoPath, null);

        if (result == null || result.getOutput() == null || result.getOutput().isBlank()) {
            throw new RuntimeException("Claude returned empty response for decomposition");
        }

        return parseSubIssues(result.getOutput());
    }

    String buildPreScreenPrompt(JsonNode issueDetails) {
        String title = issueDetails.path("title").asText();
        String body = issueDetails.path("body").asText("No description");

        return """
                You are a complexity estimator for an automated coding agent (IssueBot).
                Analyze the following GitHub issue and the codebase to determine if this issue
                is a genuinely large epic that benefits from separate, independently deliverable issues.

                ## Issue
                **Title:** %s
                **Description:**
                %s

                ## Analysis Instructions
                1. Read the codebase structure to understand the scope
                2. Default to keeping the issue whole. Normal features spanning model, service,
                   controller, UI and tests belong together, even with multiple implementation steps.
                3. Mark too_large only with high complexity, at least 20 substantively changed files,
                   AND at least 2 independently deliverable capabilities. Exclude generated files,
                   mechanical edits and test fixtures from the file estimate.
                4. A timeout, failed attempt, long checklist, or tool budget is not sufficient.
                   Do not invent a 10-minute limit. When uncertain, return too_large=false.
                5. Explain the concrete scope evidence and independent capabilities in reason.

                ## Output Format
                Respond with ONLY a JSON object:
                ```json
                {
                  "too_large": true or false,
                  "reason": "brief explanation of why",
                  "estimated_files": number of files that would need changes,
                  "estimated_complexity": "low", "medium", or "high",
                  "independent_capabilities": number of independently deliverable capabilities
                }
                ```
                """.formatted(title, body);
    }

    PreScreenResult parsePreScreenResult(String output) {
        String json = extractJsonObject(output);
        if (json == null) {
            return new PreScreenResult(false, null);
        }

        try {
            JsonNode node = objectMapper.readTree(json);
            boolean tooLarge = node.path("too_large").asBoolean(false)
                    && node.path("estimated_files").asInt(0) >= 20
                    && node.path("independent_capabilities").asInt(0) >= 2
                    && "high".equalsIgnoreCase(node.path("estimated_complexity").asText(""))
                    && !node.path("reason").asText("").isBlank();
            String reason = node.path("reason").asText(null);
            return new PreScreenResult(tooLarge, reason);
        } catch (Exception e) {
            log.warn("Failed to parse pre-screen JSON: {}", e.getMessage());
            return new PreScreenResult(false, null);
        }
    }

    String buildDecompositionPrompt(JsonNode issueDetails) {
        String title = issueDetails.path("title").asText();
        String body = issueDetails.path("body").asText("No description");

        return """
                You are analyzing a potentially large GitHub issue. Prefer keeping a cohesive feature whole.
                Your task is to break it down into smaller, independently implementable sub-issues.
                For each sub-issue, provide implementation hints based on the actual codebase.

                ## Original Issue
                **Title:** %s
                **Description:**
                %s

                ## Instructions
                1. Read the codebase to understand the architecture and relevant files
                2. Identify independently deliverable capabilities, NOT layers of one feature.
                   Keep each capability's model, service, UI and tests together. Do not split
                   setup, implementation and testing into separate issues.
                   Return [] if there are not at least two substantial independent capabilities.
                   A timeout or failed implementation alone is never a reason to split.
                3. Each sub-task should be small enough to implement in a single Claude Code session
                4. Sub-tasks should be ordered by dependency (implement prerequisite tasks first)
                5. Create between %d and %d sub-tasks
                6. For each sub-task, provide concrete implementation hints from the codebase

                ## Output Format
                Respond with ONLY a JSON array. No other text before or after. Each element must have:
                - "title": a concise issue title (prefix with the step number, e.g., "1/3: ...")
                - "description": a detailed description of what to implement
                - "acceptance_criteria": a bullet list of what "done" looks like
                - "hints": implementation guidance including relevant file paths, patterns to follow,
                  and specific classes/methods to modify or use as reference

                Example:
                ```json
                [
                  {
                    "title": "1/2: Deliver account import",
                    "description": "Implement account import end to end, including storage, API, UI and tests...",
                    "acceptance_criteria": "- Accounts import successfully\\n- UI and API tests pass",
                    "hints": "- Follow the pattern in TrackedIssue.java for the entity\\n- Add repository like TrackedIssueRepository.java\\n- Add migration as V8__add_feature_x.sql"
                  }
                ]
                ```
                """.formatted(title, body, MIN_SUB_ISSUES, MAX_SUB_ISSUES);
    }

    /**
     * Parse Claude's response into a list of sub-issues.
     * Tries JSON parsing first, falls back to regex extraction.
     */
    List<SubIssue> parseSubIssues(String output) {
        List<SubIssue> result = new ArrayList<>();

        // Try to extract JSON array from the output
        String json = extractJsonArray(output);
        if (json == null) {
            log.warn("Could not extract JSON array from decomposition output");
            throw new RuntimeException("No JSON array found in decomposition response");
        }

        try {
            JsonNode array = objectMapper.readTree(json);
            if (!array.isArray()) {
                throw new RuntimeException("Decomposition response is not a JSON array");
            }

            for (JsonNode item : array) {
                String title = item.path("title").asText("").trim();
                String description = item.path("description").asText("").trim();
                String criteria = item.path("acceptance_criteria").asText("").trim();
                String hints = item.path("hints").asText("").trim();

                if (!title.isEmpty() && !description.isEmpty()) {
                    result.add(new SubIssue(title, description, criteria, hints));
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse decomposition JSON: " + e.getMessage(), e);
        }

        // Cap at MAX_SUB_ISSUES
        if (result.size() > MAX_SUB_ISSUES) {
            result = new ArrayList<>(result.subList(0, MAX_SUB_ISSUES));
        }

        return result;
    }

    private String extractJsonArray(String text) {
        return extractJsonBlock(text, '[', ']');
    }

    private String extractJsonObject(String text) {
        return extractJsonBlock(text, '{', '}');
    }

    private String extractJsonBlock(String text, char open, char close) {
        int start = text.indexOf(open);
        if (start < 0) return null;

        int depth = 0;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == open) depth++;
            else if (c == close) {
                depth--;
                if (depth == 0) {
                    return text.substring(start, i + 1);
                }
            }
        }
        return null;
    }

    private String buildSubIssueBody(SubIssue sub, int parentIssueNumber) {
        StringBuilder body = new StringBuilder();
        body.append("_This sub-issue was automatically created by IssueBot from #")
            .append(parentIssueNumber).append("._\n\n");
        body.append("## Description\n").append(sub.description()).append("\n\n");
        if (sub.acceptanceCriteria() != null && !sub.acceptanceCriteria().isBlank()) {
            body.append("## Acceptance Criteria\n").append(sub.acceptanceCriteria()).append("\n\n");
        }
        if (sub.hints() != null && !sub.hints().isBlank()) {
            body.append("## Implementation Hints\n").append(sub.hints()).append("\n\n");
        }
        body.append("---\n*Auto-created by [IssueBot](https://github.com/dbbaskette/IssueBot) ")
            .append("— decomposed from #").append(parentIssueNumber).append("*");
        return body.toString();
    }

    private String buildDecompositionComment(TrackedIssue trackedIssue,
                                               List<Integer> subIssueNumbers,
                                               String skipReason) {
        StringBuilder sb = new StringBuilder();
        sb.append("## IssueBot: Issue Decomposed\n\n");
        sb.append("This issue was too large to resolve in a single pass");
        if (skipReason != null) {
            sb.append(":\n> ").append(skipReason);
        }
        sb.append("\n\n");
        sb.append("IssueBot has automatically split this into **")
          .append(subIssueNumbers.size()).append("** smaller sub-issues:\n\n");

        for (int num : subIssueNumbers) {
            sb.append("- #").append(num).append("\n");
        }

        sb.append("\nThis issue stays open as a tracking issue and will close automatically ")
          .append("when all sub-issues are done.\n\n");

        if (trackedIssue.getBranchName() != null) {
            sb.append("Any partial progress is available on branch `")
              .append(trackedIssue.getBranchName()).append("`.\n\n");
        }

        sb.append("---\n*Generated by [IssueBot](https://github.com/dbbaskette/IssueBot)*");
        return sb.toString();
    }

    private String buildProposalComment(List<SubIssue> subIssues, String skipReason) {
        StringBuilder sb = new StringBuilder();
        sb.append("## IssueBot: Proposed Split\n\n");
        if (skipReason != null) {
            sb.append("> ").append(skipReason).append("\n\n");
        }
        sb.append("IssueBot proposes splitting this issue into **")
          .append(subIssues.size()).append("** smaller sub-issues:\n\n");

        int i = 1;
        for (SubIssue sub : subIssues) {
            sb.append(i++).append(". **").append(sub.title()).append("**\n")
              .append("   ").append(sub.description()).append("\n\n");
        }

        sb.append("Approve or reject this split from the IssueBot dashboard.\n\n");
        sb.append("---\n*Generated by [IssueBot](https://github.com/dbbaskette/IssueBot)*");
        return sb.toString();
    }

    /**
     * Check whether a skip reason indicates a timeout or complexity issue
     * that warrants decomposition.
     */
    public boolean isDecomposable(String skipReason) {
        if (skipReason == null) return false;
        return skipReason.contains("timed out")
                || skipReason.contains("too complex")
                || skipReason.contains("too large");
    }

    /**
     * Check whether the issue's GitHub labels include the given label name (case-insensitive).
     * Used to detect sub-issues previously created by decomposition, so they are never
     * decomposed further (one level only) and never re-screened.
     */
    static boolean hasLabel(JsonNode issueDetails, String labelName) {
        JsonNode labels = issueDetails.path("labels");
        if (!labels.isArray()) return false;
        for (JsonNode label : labels) {
            if (labelName.equalsIgnoreCase(label.path("name").asText())) return true;
        }
        return false;
    }

    record SubIssue(
            @JsonProperty("title") String title,
            @JsonProperty("description") String description,
            @JsonProperty("acceptance_criteria") String acceptanceCriteria,
            @JsonProperty("hints") String hints) {}

    record PreScreenResult(boolean tooLarge, String reason) {}
}

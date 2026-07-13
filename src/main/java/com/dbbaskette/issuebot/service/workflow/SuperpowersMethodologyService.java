package com.dbbaskette.issuebot.service.workflow;

import com.dbbaskette.issuebot.model.TrackedIssue;
import com.dbbaskette.issuebot.model.WatchedRepo;
import com.dbbaskette.issuebot.repository.TrackedIssueRepository;
import com.dbbaskette.issuebot.service.claude.ClaudeCodeResult;
import com.dbbaskette.issuebot.service.claude.ClaudeCodeService;
import com.dbbaskette.issuebot.service.event.EventService;
import com.dbbaskette.issuebot.service.github.GitHubApiClient;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Path;

/**
 * "Superpowers methodology" mode (autonomous) — for a repo that opts in
 * ({@link WatchedRepo#isSuperpowersMethodology()}), IssueBot runs a design/spec +
 * implementation-plan pass up front (mirroring the {@code brainstorming} + {@code writing-plans}
 * skills the operator uses interactively at the Claude Code prompt), then implements against
 * that plan with test-driven development and the {@code executing-plans} methodology.
 *
 * <p>Unlike {@link PlanFirstService} (#64) this does NOT gate on operator approval: the plan is
 * generated, recorded (stored on the issue + posted as a GitHub comment), and implementation
 * proceeds immediately.
 *
 * <p>The methodology is embedded as prompt text — deliberately NOT via the superpowers plugin's
 * {@code SessionStart} hook. That hook, firing uncontrolled on every headless invocation,
 * derailed the implementation agent into writing a spec doc and committing nothing (empty PRs);
 * see the {@code --setting-sources project,local} isolation in
 * {@link ClaudeCodeService#buildCommand}. Here the spec/plan work happens in its OWN pass, and
 * the implementation pass carries an explicit "end with committed code, not a design doc" guard.
 */
@Service
public class SuperpowersMethodologyService {

    private static final Logger log = LoggerFactory.getLogger(SuperpowersMethodologyService.class);
    private static final int MAX_PLAN_CHARS = 20_000;

    /**
     * Planning-pass prompt: produce a design/spec + implementation plan, no code changes.
     * Based on the {@code brainstorming} and {@code writing-plans} skills, adapted for
     * unattended (non-interactive) use — there is no human to answer questions mid-pass, so
     * the agent states assumptions and proceeds rather than asking.
     */
    static final String PLANNING_METHODOLOGY = """
            You are producing a DESIGN SPEC and IMPLEMENTATION PLAN for a GitHub issue,
            before any code is written. Make NO code changes and create NO files in this phase.

            Follow this methodology (the brainstorming + writing-plans approach):

            1. Understand intent. Read the issue and the relevant parts of the codebase. Where
               the issue is ambiguous, STATE your assumptions explicitly and proceed — this runs
               unattended, so there is no one to ask.
            2. Design first. Write a concise spec: the problem, the chosen approach, the key
               decisions and trade-offs, and what is explicitly OUT of scope. Prefer the simplest
               design that satisfies the issue (YAGNI); avoid duplication (DRY).
            3. Then a plan that a skilled engineer with zero context for this codebase could
               execute: break the work into small, ordered, independently-testable tasks. For each
               task name the exact files to touch, the change, and how to test it. Assume
               test-driven development — each task names the test to write first.
            4. Frequent commits: size tasks so each is one small, coherent commit.

            Output the spec, then the numbered task plan, as markdown.""";

    /**
     * Implementation-pass preamble: execute the plan with TDD + {@code executing-plans}
     * discipline. The explicit "finish with committed code, not a design doc" guard is what
     * prevents the failure mode where the agent spends the whole run (re-)planning and commits
     * nothing.
     */
    static final String IMPLEMENTATION_METHODOLOGY = """
            Implement this issue using test-driven development and the executing-plans methodology.
            An implementation plan may be provided below (under "## Approved Plan"); if so, execute
            it task by task. If not, proceed directly, but keep the same discipline:

            - Work the plan in order. Review each task critically against the actual codebase before
              starting it; adapt if the plan is wrong, but stay in scope.
            - TDD for every behavioral change: write the test FIRST, run it, watch it fail for the
              right reason, then write the minimal code to make it pass, then refactor. If you did
              not watch it fail, you do not know it tests the right thing.
            - Commit frequently — one small, coherent commit per completed task.
            - You MUST finish with real, committed CODE changes. The plan already exists: do NOT
              re-plan, and do NOT create spec/design/plan documents in the repo. Do not stop after
              analysis.
            - Before finishing, verify the issue's acceptance criteria are met and the tests you
              wrote actually run and pass.
            """;

    private final ClaudeCodeService claudeCode;
    private final GitHubApiClient gitHubApi;
    private final TrackedIssueRepository issueRepository;
    private final EventService eventService;

    public SuperpowersMethodologyService(ClaudeCodeService claudeCode,
                                          GitHubApiClient gitHubApi,
                                          TrackedIssueRepository issueRepository,
                                          EventService eventService) {
        this.claudeCode = claudeCode;
        this.gitHubApi = gitHubApi;
        this.issueRepository = issueRepository;
        this.eventService = eventService;
    }

    /**
     * Run the autonomous design+plan pass and store the result on the issue (and post it as a
     * GitHub comment for the record). Never throws and never blocks the issue: on any failure it
     * logs an event and returns, leaving the issue to proceed to implementation without a stored
     * plan — the implementation methodology still applies. Runs on the issue's resolved
     * implementation model (a good spec needs a strong model), not the cheap utility model.
     */
    public void generatePlan(TrackedIssue trackedIssue, JsonNode issueDetails, Path repoPath) {
        WatchedRepo repo = trackedIssue.getRepo();
        int issueNumber = trackedIssue.getIssueNumber();
        String prompt = buildPlanningPrompt(issueDetails);

        String plan;
        try {
            ClaudeCodeResult result = claudeCode.executePlanning(prompt, repoPath,
                    trackedIssue.getResolvedImplModel(), trackedIssue.getId(), null);
            if (result == null || result.getOutput() == null || result.getOutput().isBlank()) {
                log.warn("Superpowers plan pass returned empty output for {} #{}, implementing without a stored plan",
                        repo.fullName(), issueNumber);
                eventService.log("PLAN_FAILED",
                        "Design/plan pass returned no output — implementing without a stored plan",
                        repo, trackedIssue);
                return;
            }
            plan = result.getOutput();
        } catch (Exception e) {
            log.warn("Superpowers plan pass failed for {} #{}: {}", repo.fullName(), issueNumber, e.getMessage());
            eventService.log("PLAN_FAILED",
                    "Design/plan pass failed: " + e.getMessage() + " — implementing without a stored plan",
                    repo, trackedIssue);
            return;
        }

        String truncated = truncate(plan, MAX_PLAN_CHARS);
        trackedIssue.setImplementationPlan(truncated);
        issueRepository.save(trackedIssue);

        try {
            gitHubApi.addComment(repo.getOwner(), repo.getName(), issueNumber, buildPlanComment(truncated));
        } catch (Exception e) {
            log.warn("Failed to post superpowers plan comment to {} #{}: {}",
                    repo.fullName(), issueNumber, e.getMessage());
        }

        eventService.log("PLAN_GENERATED",
                "Generated a design + implementation plan (superpowers methodology) — implementing now",
                repo, trackedIssue);
    }

    String buildPlanningPrompt(JsonNode issueDetails) {
        String title = issueDetails.path("title").asText();
        String body = issueDetails.path("body").asText("No description");
        return PLANNING_METHODOLOGY + "\n\n## Issue\nTitle: " + title + "\nBody:\n" + body + "\n";
    }

    private String buildPlanComment(String plan) {
        return "## IssueBot: Design & Implementation Plan\n\n" + plan
                + "\n\n---\n*Generated by [IssueBot](https://github.com/dbbaskette/IssueBot) — superpowers methodology*";
    }

    private String truncate(String text, int maxLength) {
        if (text == null) return null;
        return text.length() <= maxLength ? text : text.substring(0, maxLength) + "\n... (truncated)";
    }
}

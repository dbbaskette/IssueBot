package com.dbbaskette.issuebot.service.workflow;

import com.dbbaskette.issuebot.model.TrackedIssue;
import com.dbbaskette.issuebot.model.WatchedRepo;
import com.dbbaskette.issuebot.repository.TrackedIssueRepository;
import com.dbbaskette.issuebot.service.claude.ClaudeCodeResult;
import com.dbbaskette.issuebot.service.claude.ClaudeCodeService;
import com.dbbaskette.issuebot.service.event.EventService;
import com.dbbaskette.issuebot.service.github.GitHubApiClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for the superpowers-methodology prompts and the autonomous plan pass. The two
 * methodology blocks are the substance of the feature (design-then-plan, then TDD with an
 * explicit "finish with committed code, not a design doc" guard), so they're pinned here.
 * {@link SuperpowersMethodologyService#generatePlan} is also covered — in particular that a
 * cancelled/failed planning run is NOT accepted as a plan (it must not persist partial text or
 * post it as a public GitHub comment).
 */
class SuperpowersMethodologyServiceTest {

    private ClaudeCodeService claudeCode;
    private GitHubApiClient gitHubApi;
    private TrackedIssueRepository issueRepository;
    private EventService eventService;
    private SuperpowersMethodologyService service;

    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        claudeCode = mock(ClaudeCodeService.class);
        gitHubApi = mock(GitHubApiClient.class);
        issueRepository = mock(TrackedIssueRepository.class);
        eventService = mock(EventService.class);
        service = new SuperpowersMethodologyService(claudeCode, gitHubApi, issueRepository, eventService);
    }

    private TrackedIssue issue() {
        WatchedRepo repo = new WatchedRepo("owner", "repo");
        TrackedIssue issue = new TrackedIssue(repo, 42, "Add a built-in tool library");
        issue.setResolvedImplModel("claude-opus-4-8");
        return issue;
    }

    private ObjectNode details() {
        ObjectNode n = mapper.createObjectNode();
        n.put("title", "Add a built-in tool library");
        n.put("body", "Provide shell, todo, and web-fetch tools.");
        return n;
    }

    // === methodology prompt content ===

    @Test
    void planningPrompt_carriesTheDesignThenPlanMethodologyAndTheIssue() {
        String prompt = service.buildPlanningPrompt(details());

        assertThat(prompt).contains("DESIGN SPEC and IMPLEMENTATION PLAN");
        assertThat(prompt).contains("Make NO code changes");
        assertThat(prompt).contains("brainstorming + writing-plans");
        assertThat(prompt).contains("test-driven development");
        assertThat(prompt).contains("Add a built-in tool library");
        assertThat(prompt).contains("Provide shell, todo, and web-fetch tools.");
    }

    @Test
    void planningPrompt_survivesAMissingBody() {
        ObjectNode issue = mapper.createObjectNode();
        issue.put("title", "Terse issue");

        String prompt = service.buildPlanningPrompt(issue);

        assertThat(prompt).contains("Terse issue");
        assertThat(prompt).contains("No description");
    }

    @Test
    void implementationMethodology_enforcesTddAndForbidsStoppingAtADoc() {
        String m = SuperpowersMethodologyService.IMPLEMENTATION_METHODOLOGY;

        assertThat(m).contains("test-driven development");
        assertThat(m).contains("write the test FIRST");
        assertThat(m).contains("watch it fail");
        // The anti-empty-commit guard — the crux of pairing the methodology with automation.
        assertThat(m).contains("finish with real, committed CODE changes");
        assertThat(m).contains("do NOT create spec/design/plan documents in the repo");
    }

    // === generatePlan ===

    @Test
    void generatePlan_onSuccess_storesPlanAndPostsComment() {
        TrackedIssue issue = issue();
        ClaudeCodeResult ok = new ClaudeCodeResult();
        ok.setSuccess(true);
        ok.setOutput("## Spec\nThe design.\n\n## Plan\n1. First task.");
        when(claudeCode.executePlanning(any(), any(), any(), any(), any())).thenReturn(ok);

        service.generatePlan(issue, details(), Path.of("/tmp/repo"));

        assertThat(issue.getImplementationPlan()).contains("## Spec").contains("## Plan");
        verify(issueRepository).save(issue);
        verify(gitHubApi).addComment(eq("owner"), eq("repo"), eq(42), contains("Design & Implementation Plan"));
        verify(eventService).log(eq("PLAN_GENERATED"), any(), any(), any());
    }

    @Test
    void generatePlan_onCancelledRun_isNotAcceptedAsAPlan() {
        // A killed run (operator Stop) can leave partial, non-blank output that is NOT a plan.
        TrackedIssue issue = issue();
        ClaudeCodeResult cancelled = new ClaudeCodeResult();
        cancelled.setSuccess(false);
        cancelled.setOutput("I'll start by reading the codebase and then...");
        when(claudeCode.executePlanning(any(), any(), any(), any(), any())).thenReturn(cancelled);

        service.generatePlan(issue, details(), Path.of("/tmp/repo"));

        assertThat(issue.getImplementationPlan()).isNull();
        verify(issueRepository, never()).save(any());
        verify(gitHubApi, never()).addComment(any(), any(), anyInt(), any());
        verify(eventService).log(eq("PLAN_FAILED"), any(), any(), any());
    }

    @Test
    void generatePlan_onEmptyOutput_isNotAcceptedAsAPlan() {
        TrackedIssue issue = issue();
        ClaudeCodeResult empty = new ClaudeCodeResult();
        empty.setSuccess(true);
        empty.setOutput("   ");
        when(claudeCode.executePlanning(any(), any(), any(), any(), any())).thenReturn(empty);

        service.generatePlan(issue, details(), Path.of("/tmp/repo"));

        assertThat(issue.getImplementationPlan()).isNull();
        verify(issueRepository, never()).save(any());
        verify(gitHubApi, never()).addComment(any(), any(), anyInt(), any());
    }
}

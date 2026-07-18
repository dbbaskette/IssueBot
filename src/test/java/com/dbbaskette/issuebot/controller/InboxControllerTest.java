package com.dbbaskette.issuebot.controller;

import com.dbbaskette.issuebot.model.IssueStatus;
import com.dbbaskette.issuebot.model.PlanningVersion;
import com.dbbaskette.issuebot.model.PlanningVersionState;
import com.dbbaskette.issuebot.model.TrackedIssue;
import com.dbbaskette.issuebot.model.WatchedRepo;
import com.dbbaskette.issuebot.repository.IterationRepository;
import com.dbbaskette.issuebot.repository.NotificationRepository;
import com.dbbaskette.issuebot.repository.PlanningVersionRepository;
import com.dbbaskette.issuebot.repository.TrackedIssueRepository;
import com.dbbaskette.issuebot.service.github.GitHubApiClient;
import com.dbbaskette.issuebot.service.polling.IssuePollingService;
import com.dbbaskette.issuebot.service.ui.ApprovalCardAssembler;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.ui.Model;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the Needs You inbox's (#91) model population: the four status groups, the
 * total/active/queued counts, the plan excerpt, and the split-proposal titles. Follows this
 * codebase's existing pattern (see {@code IssueControllerTest}) of constructing the controller
 * directly with mocks rather than a full Spring context.
 */
class InboxControllerTest {

    private static InboxController controller(TrackedIssueRepository issues, NotificationRepository notifications) {
        return controller(issues, notifications, mock(PlanningVersionRepository.class));
    }

    private static InboxController controller(TrackedIssueRepository issues,
                                              NotificationRepository notifications,
                                              PlanningVersionRepository planningVersions) {
        return new InboxController(issues, planningVersions, mock(IssuePollingService.class), notifications,
                new ApprovalCardAssembler(mock(IterationRepository.class), mock(GitHubApiClient.class)),
                new ObjectMapper());
    }

    private static WatchedRepo repo() {
        return new WatchedRepo("acme", "widgets");
    }

    @Test
    void emptyInbox_totalCountIsZero() {
        TrackedIssueRepository issues = mock(TrackedIssueRepository.class);
        when(issues.findByStatusOrderByIdDesc(org.mockito.ArgumentMatchers.any())).thenReturn(List.of());
        when(issues.findByStatusInOrderByIdDesc(org.mockito.ArgumentMatchers.any())).thenReturn(List.of());

        Model model = new ExtendedModelMap();
        controller(issues, mock(NotificationRepository.class)).inbox(model, null);

        assertThat(model.getAttribute("totalCount")).isEqualTo(0);
        assertThat((List<?>) model.getAttribute("approvals")).isEmpty();
        assertThat((List<?>) model.getAttribute("planApprovals")).isEmpty();
        assertThat((List<?>) model.getAttribute("splitProposals")).isEmpty();
        assertThat((List<?>) model.getAttribute("needsHuman")).isEmpty();
    }

    @SuppressWarnings("unchecked")
    @Test
    void groupsIssuesByStatusIntoFourBuckets() {
        TrackedIssueRepository issues = mock(TrackedIssueRepository.class);

        TrackedIssue approval = new TrackedIssue(repo(), 1, "Approval issue");
        approval.setId(1L);
        approval.setStatus(IssueStatus.AWAITING_APPROVAL);

        TrackedIssue planApproval = new TrackedIssue(repo(), 2, "Plan issue");
        planApproval.setId(2L);
        planApproval.setStatus(IssueStatus.AWAITING_PLAN_APPROVAL);

        TrackedIssue splitProposal = new TrackedIssue(repo(), 3, "Split issue");
        splitProposal.setId(3L);
        splitProposal.setStatus(IssueStatus.AWAITING_DECOMPOSITION);

        TrackedIssue failed = new TrackedIssue(repo(), 4, "Failed issue");
        failed.setId(4L);
        failed.setStatus(IssueStatus.FAILED);

        TrackedIssue cooldown = new TrackedIssue(repo(), 5, "Cooldown issue");
        cooldown.setId(5L);
        cooldown.setStatus(IssueStatus.COOLDOWN);

        when(issues.findByStatusOrderByIdDesc(IssueStatus.AWAITING_APPROVAL)).thenReturn(List.of(approval));
        when(issues.findByStatusOrderByIdDesc(IssueStatus.AWAITING_PLAN_APPROVAL)).thenReturn(List.of(planApproval));
        when(issues.findByStatusOrderByIdDesc(IssueStatus.AWAITING_DECOMPOSITION)).thenReturn(List.of(splitProposal));
        when(issues.findByStatusInOrderByIdDesc(List.of(IssueStatus.FAILED, IssueStatus.COOLDOWN)))
                .thenReturn(List.of(cooldown, failed));

        Model model = new ExtendedModelMap();
        controller(issues, mock(NotificationRepository.class)).inbox(model, null);

        assertThat((List<TrackedIssue>) model.getAttribute("approvals")).containsExactly(approval);
        assertThat((List<TrackedIssue>) model.getAttribute("planApprovals")).containsExactly(planApproval);
        assertThat((List<TrackedIssue>) model.getAttribute("splitProposals")).containsExactly(splitProposal);
        assertThat((List<TrackedIssue>) model.getAttribute("needsHuman")).containsExactly(cooldown, failed);
        assertThat(model.getAttribute("totalCount")).isEqualTo(5);
    }

    @Test
    void activeAndQueuedCountsFeedTheEmptyStateCopy() {
        TrackedIssueRepository issues = mock(TrackedIssueRepository.class);
        when(issues.findByStatusOrderByIdDesc(org.mockito.ArgumentMatchers.any())).thenReturn(List.of());
        when(issues.findByStatusInOrderByIdDesc(org.mockito.ArgumentMatchers.any())).thenReturn(List.of());
        when(issues.countByStatus(IssueStatus.IN_PROGRESS)).thenReturn(3L);
        when(issues.countByStatus(IssueStatus.QUEUED)).thenReturn(7L);

        Model model = new ExtendedModelMap();
        controller(issues, mock(NotificationRepository.class)).inbox(model, null);

        assertThat(model.getAttribute("activeCount")).isEqualTo(3L);
        assertThat(model.getAttribute("queuedCount")).isEqualTo(7L);
    }

    @Test
    void needsYouCountAndPendingApprovalsArePopulated() {
        TrackedIssueRepository issues = mock(TrackedIssueRepository.class);

        TrackedIssue approvalA = new TrackedIssue(repo(), 1, "A");
        approvalA.setId(1L);
        TrackedIssue approvalB = new TrackedIssue(repo(), 2, "B");
        approvalB.setId(2L);

        when(issues.findByStatusOrderByIdDesc(org.mockito.ArgumentMatchers.any())).thenReturn(List.of());
        when(issues.findByStatusOrderByIdDesc(IssueStatus.AWAITING_APPROVAL))
                .thenReturn(List.of(approvalA, approvalB));
        when(issues.findByStatusInOrderByIdDesc(org.mockito.ArgumentMatchers.any())).thenReturn(List.of());
        when(issues.countNeedsYou()).thenReturn(9L);

        Model model = new ExtendedModelMap();
        controller(issues, mock(NotificationRepository.class)).inbox(model, null);

        // pendingApprovals mirrors the approvals list already fetched for this page (no
        // redundant COUNT query) — same pattern as ApprovalController#populateModel.
        assertThat(model.getAttribute("pendingApprovals")).isEqualTo(2L);
        assertThat(model.getAttribute("needsYouCount")).isEqualTo(9L);
    }

    @SuppressWarnings("unchecked")
    @Test
    void pendingPlanVersionsAreLoadedOnceAndKeyedByIssueId() {
        TrackedIssueRepository issues = mock(TrackedIssueRepository.class);
        PlanningVersionRepository versions = mock(PlanningVersionRepository.class);

        TrackedIssue first = new TrackedIssue(repo(), 1, "First plan");
        first.setId(1L);

        TrackedIssue second = new TrackedIssue(repo(), 2, "Second plan");
        second.setId(2L);

        PlanningVersion firstVersion = PlanningVersion.pending(
                first, 2, "spec 2", "plan 2", "CODEX", "gpt-5.6-sol", null);
        PlanningVersion secondVersion = PlanningVersion.pending(
                second, 3, "spec 3", "plan 3", "CLAUDE", "claude-sonnet-5", null);

        when(issues.findByStatusOrderByIdDesc(org.mockito.ArgumentMatchers.any())).thenReturn(List.of());
        when(issues.findByStatusOrderByIdDesc(IssueStatus.AWAITING_PLAN_APPROVAL))
                .thenReturn(List.of(first, second));
        when(issues.findByStatusInOrderByIdDesc(org.mockito.ArgumentMatchers.any())).thenReturn(List.of());
        when(versions.findByIssueIdInAndState(List.of(1L, 2L), PlanningVersionState.PENDING))
                .thenReturn(List.of(firstVersion, secondVersion));

        Model model = new ExtendedModelMap();
        controller(issues, mock(NotificationRepository.class), versions).inbox(model, null);

        Map<Long, PlanningVersion> pending =
                (Map<Long, PlanningVersion>) model.getAttribute("planVersions");
        Map<Long, String> ages = (Map<Long, String>) model.getAttribute("planAges");

        assertThat(pending).containsExactlyInAnyOrderEntriesOf(
                Map.of(1L, firstVersion, 2L, secondVersion));
        assertThat(ages).containsKeys(1L, 2L);
        verify(versions).findByIssueIdInAndState(List.of(1L, 2L), PlanningVersionState.PENDING);
    }

    @SuppressWarnings("unchecked")
    @Test
    void splitProposalTitlesAreParsedFromProposalJson() {
        TrackedIssueRepository issues = mock(TrackedIssueRepository.class);

        TrackedIssue issue = new TrackedIssue(repo(), 1, "Big issue");
        issue.setId(1L);
        issue.setDecompositionProposal("""
                [{"title": "Sub A", "description": "do A"},
                 {"title": "Sub B", "description": "do B"}]
                """);

        when(issues.findByStatusOrderByIdDesc(org.mockito.ArgumentMatchers.any())).thenReturn(List.of());
        when(issues.findByStatusOrderByIdDesc(IssueStatus.AWAITING_DECOMPOSITION)).thenReturn(List.of(issue));
        when(issues.findByStatusInOrderByIdDesc(org.mockito.ArgumentMatchers.any())).thenReturn(List.of());

        Model model = new ExtendedModelMap();
        controller(issues, mock(NotificationRepository.class)).inbox(model, null);

        Map<Long, List<String>> titles = (Map<Long, List<String>>) model.getAttribute("proposalTitles");
        assertThat(titles.get(1L)).containsExactly("Sub A", "Sub B");
    }

    @SuppressWarnings("unchecked")
    @Test
    void splitProposalTitles_emptyWhenProposalUnparseable() {
        TrackedIssueRepository issues = mock(TrackedIssueRepository.class);

        TrackedIssue issue = new TrackedIssue(repo(), 1, "Big issue");
        issue.setId(1L);
        issue.setDecompositionProposal("not json");

        when(issues.findByStatusOrderByIdDesc(org.mockito.ArgumentMatchers.any())).thenReturn(List.of());
        when(issues.findByStatusOrderByIdDesc(IssueStatus.AWAITING_DECOMPOSITION)).thenReturn(List.of(issue));
        when(issues.findByStatusInOrderByIdDesc(org.mockito.ArgumentMatchers.any())).thenReturn(List.of());

        Model model = new ExtendedModelMap();
        controller(issues, mock(NotificationRepository.class)).inbox(model, null);

        Map<Long, List<String>> titles = (Map<Long, List<String>>) model.getAttribute("proposalTitles");
        assertThat(titles.get(1L)).isEmpty();
    }

    @SuppressWarnings("unchecked")
    @Test
    void needsHumanCarriesFailureReasonAndCooldown() {
        TrackedIssueRepository issues = mock(TrackedIssueRepository.class);

        TrackedIssue cooldownIssue = new TrackedIssue(repo(), 1, "Cooling down");
        cooldownIssue.setId(1L);
        cooldownIssue.setStatus(IssueStatus.COOLDOWN);
        cooldownIssue.setLastFailureReason("Budget exceeded");
        LocalDateTime until = LocalDateTime.now().plusHours(24);
        cooldownIssue.setCooldownUntil(until);

        when(issues.findByStatusOrderByIdDesc(org.mockito.ArgumentMatchers.any())).thenReturn(List.of());
        when(issues.findByStatusInOrderByIdDesc(org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(cooldownIssue));

        Model model = new ExtendedModelMap();
        controller(issues, mock(NotificationRepository.class)).inbox(model, null);

        List<TrackedIssue> needsHuman = (List<TrackedIssue>) model.getAttribute("needsHuman");
        assertThat(needsHuman).containsExactly(cooldownIssue);
        assertThat(needsHuman.get(0).getLastFailureReason()).isEqualTo("Budget exceeded");
        assertThat(needsHuman.get(0).getCooldownUntil()).isEqualTo(until);
    }
}

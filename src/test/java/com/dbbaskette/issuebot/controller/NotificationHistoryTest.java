package com.dbbaskette.issuebot.controller;

import com.dbbaskette.issuebot.config.*;
import com.dbbaskette.issuebot.model.*;
import com.dbbaskette.issuebot.repository.*;
import com.dbbaskette.issuebot.service.notification.NotificationSnapshot;
import com.dbbaskette.issuebot.service.claude.ClaudeCodeService;
import com.dbbaskette.issuebot.service.codex.CodexModelCatalog;
import com.dbbaskette.issuebot.service.github.GitHubApiClient;
import com.dbbaskette.issuebot.service.harness.CodingHarnessService;
import com.dbbaskette.issuebot.service.polling.IssuePollingService;
import com.dbbaskette.issuebot.service.workflow.ProcessingControlService;
import com.dbbaskette.issuebot.validation.StartupValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.config.import=", "issuebot.github.token=test-token", "issuebot.auth.username=", "issuebot.auth.password=",
        "spring.datasource.url=jdbc:h2:mem:notification-history;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
})
@AutoConfigureMockMvc
@Transactional
class NotificationHistoryTest {
    @Autowired MockMvc mvc;
    @Autowired NotificationRepository notifications;
    @Autowired TrackedIssueRepository issues;
    @Autowired WatchedRepoRepository repos;
    @Autowired NotificationPreferenceRepository preferences;
    @MockitoBean IssuePollingService polling;
    @MockitoBean StartupValidator startupValidator;
    @MockitoBean ClaudeCodeService claude;
    @MockitoBean CodingHarnessService harness;
    @MockitoBean GitHubApiClient github;
    @MockitoBean CodexModelCatalog catalog;
    @MockitoBean ProcessingControlService processing;
    @MockitoBean ConfigInitializer initializer;
    @BeforeEach void setup() { when(processing.mode()).thenReturn(ProcessingState.STOPPED); }

    private Notification event(TrackedIssue issue, String title) {
        var n = new Notification(Notification.Severity.INFO, title, "<script>alert('x')</script>", issue == null ? null : issue.getId());
        if (issue != null) {
            n.setCategory(Notification.Category.APPROVAL);
            n.setRepoId(issue.getRepo().getId());
            n.setGroupKey("issue:" + n.getRepoId() + ":" + issue.getId());
        }
        return notifications.saveAndFlush(n);
    }
    @Test void historyAndPanelAreReadOnlyEscapedAndUseSnapshotBadge() throws Exception {
        var repo = repos.saveAndFlush(new WatchedRepo("owner", "repo"));
        var issue = new TrackedIssue(repo, 42, "Approval");
        issue.setStatus(IssueStatus.AWAITING_APPROVAL);
        issue = issues.saveAndFlush(issue);
        var event = event(issue, "<img src=x onerror=alert(1)>");
        event(issue, "Latest overall header");
        var result = mvc.perform(get("/notifications").param("query", "onerror"))
                .andExpect(status().isOk()).andReturn();
        var snapshot = (NotificationSnapshot) result.getModelAndView().getModel().get("notificationSnapshot");
        assertThat(result.getModelAndView().getModel().get("unreadNotificationCount")).isEqualTo(snapshot.unreadActionGroupCount());
        assertThat(snapshot.unreadActionGroupCount()).isEqualTo(1);
        String html = result.getResponse().getContentAsString();
        assertThat(html).contains("Latest overall header", "Review approval", "name=\"_csrf\"", "Shared notification delivery preferences")
                .doesNotContain("<script>alert('x')</script>");
        String panel = mvc.perform(get("/notifications/panel")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(panel).contains("data-unread-count=\"1\"", "name=\"throughId\"");
        String entries = mvc.perform(get("/notifications/group").param("groupKey", event.getGroupKey()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(entries).contains("&lt;img", "&lt;script&gt;", "Page 1 · 2 events");
        assertThat(notifications.countByReadAtIsNull()).isEqualTo(2);
        verifyNoInteractions(github, harness, claude);
    }
    @Test void postsRequireCsrfAndRejectMalformedInputs() throws Exception {
        mvc.perform(post("/notifications/read").param("throughId", "1")).andExpect(status().isForbidden());
        mvc.perform(post("/notifications/group/read").param("groupKey", "legacy:1").param("throughId", "1")).andExpect(status().isForbidden());
        mvc.perform(post("/notifications/mute").param("category", "PROGRESS").param("muted", "true")).andExpect(status().isForbidden());
        for (String cutoff : new String[]{"-1", "banana", "9223372036854775808"})
            mvc.perform(post("/notifications/read").with(csrf()).param("throughId", cutoff)).andExpect(status().isBadRequest());
        mvc.perform(post("/notifications/read").with(csrf())).andExpect(status().isBadRequest());
        for (String category : new String[]{"APPROVAL", "RECOVERY", "SYSTEM", "NOPE"})
            mvc.perform(post("/notifications/mute").with(csrf()).param("category", category).param("muted", "true"))
                    .andExpect(status().isBadRequest());
        mvc.perform(get("/notifications").param("query", "x".repeat(201))).andExpect(status().isBadRequest());
        mvc.perform(get("/notifications").param("category", "NOPE")).andExpect(status().isBadRequest());
        mvc.perform(get("/notifications").param("readFilter", "NOPE")).andExpect(status().isBadRequest());
        mvc.perform(get("/notifications").param("repoId", "-2")).andExpect(status().isBadRequest());
        mvc.perform(get("/notifications/group").param("groupKey", "../issues")).andExpect(status().isBadRequest());
        mvc.perform(get("/notifications/group").param("groupKey", "legacy:1").param("page", "-1")).andExpect(status().isBadRequest());
    }
    @Test void readCutoffAndSharedMutePostsAreReversible() throws Exception {
        var old = event(null, "Old event");
        var newOne = event(null, "New event one");
        var newTwo = event(null, "New event two");
        mvc.perform(post("/notifications/read").with(csrf()).param("throughId", old.getId().toString()).param("returnTo", "history"))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/notifications"));
        assertThat(notifications.countByReadAtIsNull()).isEqualTo(2);
        assertThat(notifications.findById(newOne.getId()).orElseThrow().isUnread()).isTrue();
        assertThat(notifications.findById(newTwo.getId()).orElseThrow().isUnread()).isTrue();
        for (String muted : new String[]{"true", "false"}) {
            mvc.perform(post("/notifications/mute").with(csrf()).param("category", "COMPLETION").param("muted", muted))
                    .andExpect(status().is3xxRedirection());
            assertThat(preferences.findById(Notification.Category.COMPLETION).orElseThrow().isMuted()).isEqualTo(Boolean.parseBoolean(muted));
        }
    }
}

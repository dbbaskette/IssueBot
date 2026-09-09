package com.dbbaskette.issuebot.controller;

import com.dbbaskette.issuebot.model.IssueStatus;
import com.dbbaskette.issuebot.model.TrackedIssue;
import com.dbbaskette.issuebot.model.WatchedRepo;
import com.dbbaskette.issuebot.repository.TrackedIssueRepository;
import com.dbbaskette.issuebot.repository.WatchedRepoRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Real database, MVC interceptors, and Thymeleaf: one last item disappearing updates both surfaces. */
@SpringBootTest(properties = {
        "issuebot.github.token=test-token", "issuebot.auth.username=", "issuebot.auth.password=",
        "spring.datasource.url=jdbc:h2:mem:needs-you-mvc;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
})
@AutoConfigureMockMvc
@Transactional
class NeedsYouMvcIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired TrackedIssueRepository issues;
    @Autowired WatchedRepoRepository repos;

    @Test
    void lastItemCompletionRefreshesBadgeAndInboxTogether() throws Exception {
        WatchedRepo repo = repos.saveAndFlush(new WatchedRepo("snapshot-owner", "snapshot-repo"));
        TrackedIssue issue = new TrackedIssue(repo, 12345, "Unique needs-you regression item");
        issue.setStatus(IssueStatus.FAILED);
        issue = issues.saveAndFlush(issue);

        String full = mvc.perform(get("/inbox")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(full).contains("Unique needs-you regression item", "id=\"needs-you-badge\"")
                .containsPattern("(?s)id=\"needs-you-badge\"[^>]*>\\s*<span class=\"badge\">1</span>")
                .doesNotContain("Nothing needs you — the loop is running itself.");

        issue.setStatus(IssueStatus.COMPLETED);
        issues.saveAndFlush(issue);
        String live = mvc.perform(get("/inbox/live").param("includeInbox", "true"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(live).contains("Nothing needs you — the loop is running itself.", "id=\"needs-you-content\"")
                .containsPattern("(?s)id=\"needs-you-badge\"[^>]*hidden[^>]*>\\s*<span class=\"badge\">0</span>")
                .doesNotContain("Unique needs-you regression item");
    }

    @Test
    void dashboardGetsCanonicalCountWithoutAControllerSpecificCountQuery() throws Exception {
        WatchedRepo repo = repos.saveAndFlush(new WatchedRepo("snapshot-other", "snapshot-other"));
        TrackedIssue issue = new TrackedIssue(repo, 12, "needs guidance");
        issue.setStatus(IssueStatus.FAILED);
        issues.saveAndFlush(issue);

        String html = mvc.perform(get("/")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).containsPattern("(?s)id=\"needs-you-badge\"[^>]*>\\s*<span class=\"badge\">1</span>");
    }
}

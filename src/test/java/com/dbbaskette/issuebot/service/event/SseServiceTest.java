package com.dbbaskette.issuebot.service.event;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.dbbaskette.issuebot.controller.SseController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import static org.assertj.core.api.Assertions.assertThat;

class SseServiceTest {

    @Test
    void recentOutputIsIssueScopedBoundedAndClearedForNewRun() {
        SseService service = new SseService();
        service.broadcastClaudeLog(1L, "other issue");
        for (int i = 0; i < 160; i++) service.broadcastClaudeLog(2L, "line " + i);

        assertThat(service.recentOutput(1L)).hasSize(1);
        assertThat(service.recentOutput(2L)).hasSize(150);
        assertThat(service.recentOutput(2L).getFirst().text()).isEqualTo("line 10");
        assertThat(service.recentOutputText(2L, 2, 100)).isEqualTo("line 158\nline 159");
        assertThat(service.recentOutput(2L).getLast().at()).isNotNull();
        assertThat(service.recentOutput(2L).getLast().id()).contains(":");

        service.beginIssueRun(2L);
        assertThat(service.recentOutput(2L)).isEmpty();
        assertThat(service.recentOutput(1L)).hasSize(1);
    }

    @Test
    void streamReplaysOnlyRequestedIssueAndRespectsReconnectCursor() throws Exception {
        SseService service = new SseService();
        service.broadcastClaudeLog(1L, "private other issue");
        service.broadcastClaudeLog(2L, "first line");
        String firstId = service.recentOutput(2L).getFirst().id();
        service.broadcastClaudeLog(2L, "second line");
        var mvc = MockMvcBuilders.standaloneSetup(new SseController(service)).build();

        String replay = mvc.perform(get("/api/events/stream").param("issueId", "2"))
                .andReturn().getResponse().getContentAsString();
        assertThat(replay).contains("first line", "second line")
                .doesNotContain("private other issue");

        String resumed = mvc.perform(get("/api/events/stream").param("issueId", "2")
                        .header("Last-Event-ID", firstId))
                .andReturn().getResponse().getContentAsString();
        assertThat(resumed).contains("second line").doesNotContain("first line");
    }
}

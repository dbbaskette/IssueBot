package com.dbbaskette.issuebot.controller;

import com.dbbaskette.issuebot.model.TrackedIssue;
import com.dbbaskette.issuebot.service.ui.NeedsYouService;
import com.dbbaskette.issuebot.service.ui.NeedsYouSnapshot;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.ui.ExtendedModelMap;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class NeedsYouLiveControllerTest {
    private final NeedsYouService service = mock(NeedsYouService.class);
    private final InboxController inbox = mock(InboxController.class);
    private final NeedsYouLiveController controller = new NeedsYouLiveController(service, inbox);

    @Test
    void badgeOnlyReadsOneSnapshotAndDoesNotBuildInboxCards() {
        var snapshot = new NeedsYouSnapshot(List.of(new TrackedIssue()), List.of(), List.of(),
                List.of(), List.of(), List.of(), 0, 0);
        when(service.snapshot()).thenReturn(snapshot);
        var model = new ExtendedModelMap();
        var response = new MockHttpServletResponse();
        assertThat(controller.live(model, false, response)).isEqualTo("fragments/needs-you :: live");
        assertThat(model.getAttribute("needsYouCount")).isEqualTo(1L);
        assertThat(model.getAttribute("needsYouSnapshot")).isSameAs(snapshot);
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        verify(service, times(1)).snapshot();
        verifyNoInteractions(inbox);
    }

    @Test
    void inboxAndBadgeShareExactSnapshotIncludingZero() {
        var snapshot = new NeedsYouSnapshot(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), 2, 3);
        when(service.snapshot()).thenReturn(snapshot);
        var model = new ExtendedModelMap();
        controller.live(model, true, new MockHttpServletResponse());
        verify(inbox).populate(model, snapshot);
        verify(service, times(1)).snapshot();
        assertThat(model.getAttribute("needsYouCount")).isEqualTo(0L);
        assertThat(model.getAttribute("includeInbox")).isEqualTo(true);
    }
}

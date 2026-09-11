package com.dbbaskette.issuebot.controller;

import com.dbbaskette.issuebot.repository.WatchedRepoRepository;
import com.dbbaskette.issuebot.service.notification.*;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.*;
import org.springframework.ui.ExtendedModelMap;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class NotificationControllerTest {
    @Test void panelUsesOneSnapshotWithoutReadOnOpen() {
        var triage = mock(NotificationTriageService.class);
        var snapshot = new NotificationSnapshot(Page.empty(), 3, 8);
        when(triage.snapshot("", null, "ALL", "ALL", false, PageRequest.of(0, 10))).thenReturn(snapshot);
        var controller = new NotificationController(triage, mock(WatchedRepoRepository.class));
        var model = new ExtendedModelMap();
        assertThat(controller.panel(model)).isEqualTo("notifications :: panel");
        assertThat(model.get("notificationSnapshot")).isSameAs(snapshot);
        verify(triage, never()).markAllRead(anyLong());
    }
    @Test void readsOnlyThroughSubmittedSnapshotAndRejectsNegative() {
        var triage = mock(NotificationTriageService.class);
        var controller = new NotificationController(triage, mock(WatchedRepoRepository.class));
        assertThat(controller.markRead(new ExtendedModelMap(), 8, "history")).isEqualTo("redirect:/notifications");
        verify(triage).markAllRead(8);
        assertThatThrownBy(() -> controller.markRead(new ExtendedModelMap(), -1, "history"))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        verifyNoMoreInteractions(triage);
    }
}

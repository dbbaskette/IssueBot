package com.dbbaskette.issuebot.controller;

import com.dbbaskette.issuebot.model.Notification;
import com.dbbaskette.issuebot.repository.NotificationRepository;
import org.junit.jupiter.api.Test;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.ui.Model;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class NotificationControllerTest {

    @Test
    void panel_populatesTop20AndUnreadCount() {
        NotificationRepository repository = mock(NotificationRepository.class);
        List<Notification> notifications = List.of(
                new Notification(Notification.Severity.INFO, "Title", "Detail", null));
        when(repository.findTop20ByOrderByCreatedAtDesc()).thenReturn(notifications);
        when(repository.countByReadAtIsNull()).thenReturn(3L);
        NotificationController controller = new NotificationController(repository);
        Model model = new ExtendedModelMap();

        String view = controller.panel(model);

        assertThat(view).isEqualTo("notifications :: panel");
        assertThat(model.getAttribute("notifications")).isEqualTo(notifications);
        assertThat(model.getAttribute("unreadCount")).isEqualTo(3L);
        verify(repository, never()).markAllRead(any());
    }

    @Test
    void markRead_marksAllReadThenReturnsRefreshedPanel() {
        NotificationRepository repository = mock(NotificationRepository.class);
        when(repository.findTop20ByOrderByCreatedAtDesc()).thenReturn(List.of());
        when(repository.countByReadAtIsNull()).thenReturn(0L);
        NotificationController controller = new NotificationController(repository);
        Model model = new ExtendedModelMap();

        String view = controller.markRead(model);

        assertThat(view).isEqualTo("notifications :: panel");
        verify(repository).markAllRead(any(LocalDateTime.class));
        assertThat(model.getAttribute("unreadCount")).isEqualTo(0L);
    }
}

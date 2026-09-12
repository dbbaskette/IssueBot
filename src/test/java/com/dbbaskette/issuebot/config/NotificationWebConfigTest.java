package com.dbbaskette.issuebot.config;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.dbbaskette.issuebot.service.notification.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.domain.*;
import org.springframework.mock.web.*;
import org.springframework.web.servlet.*;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class NotificationWebConfigTest {
    private HandlerInterceptor interceptor(NotificationTriageService service) {
        var registry = mock(InterceptorRegistry.class);
        new NotificationWebConfig(service).addInterceptors(registry);
        var captured = ArgumentCaptor.forClass(HandlerInterceptor.class);
        verify(registry).addInterceptor(captured.capture());
        return captured.getValue();
    }
    private void render(HandlerInterceptor interceptor, ModelAndView view) throws Exception {
        interceptor.postHandle(new MockHttpServletRequest(), new MockHttpServletResponse(), new Object(), view);
    }
    @Test void reusesExactPanelAndHistorySnapshotForBadge() throws Exception {
        var service = mock(NotificationTriageService.class);
        var interceptor = interceptor(service);
        var snapshot = new NotificationSnapshot(Page.empty(), 7, 42);
        for (String name : new String[]{"layout", "notification-history :: content", "notifications :: panel"}) {
            var view = new ModelAndView(name);
            view.addObject("notificationSnapshot", snapshot);
            render(interceptor, view);
            assertThat(view.getModel().get("unreadNotificationCount")).isEqualTo(7L);
            assertThat(view.getModel().get("notificationSnapshot")).isSameAs(snapshot);
        }
        verifyNoInteractions(service);
    }
    @Test void onlyRenderedPagesFetchSnapshotAndNeverInventZeroOnFailure() throws Exception {
        var service = mock(NotificationTriageService.class);
        var interceptor = interceptor(service);
        render(interceptor, null);
        render(interceptor, new ModelAndView("redirect:/issues"));
        render(interceptor, new ModelAndView("dashboard :: live"));
        verifyNoInteractions(service);
        var snapshot = new NotificationSnapshot(Page.empty(), 3, 8);
        when(service.snapshot("", null, "ALL", "ALL", false, PageRequest.of(0, 10))).thenReturn(snapshot);
        var page = new ModelAndView("layout");
        render(interceptor, page);
        assertThat(page.getModel().get("unreadNotificationCount")).isEqualTo(3L);
        when(service.snapshot("", null, "ALL", "ALL", false, PageRequest.of(0, 10))).thenThrow(new IllegalStateException("Unavailable"));
        var unavailable = new ModelAndView("layout");
        render(interceptor, unavailable);
        assertThat(unavailable.getModel().get("unreadNotificationCount")).isNull();
        assertThat(unavailable.getModel().get("notificationStateAvailable")).isEqualTo(false);
        doReturn(snapshot).when(service).snapshot("", null, "ALL", "ALL", false, PageRequest.of(0, 10));
        var recovered = new ModelAndView("layout");
        render(interceptor, recovered);
        assertThat(recovered.getModel().get("notificationStateAvailable")).isEqualTo(true);
        assertThat(recovered.getModel().get("unreadNotificationCount")).isEqualTo(3L);
    }

    @Test void degradationLogsOneBoundedClassificationWithoutExceptionPayload() throws Exception {
        var service = mock(NotificationTriageService.class);
        when(service.snapshot("", null, "ALL", "ALL", false, PageRequest.of(0, 10)))
                .thenThrow(new DataAccessResourceFailureException("secret-token-in-query"));
        Logger logger = (Logger) LoggerFactory.getLogger(NotificationWebConfig.class);
        ListAppender<ILoggingEvent> events = new ListAppender<>();
        events.start();
        logger.addAppender(events);
        try {
            var interceptor = interceptor(service);
            for (int index = 0; index < 3; index++) render(interceptor, new ModelAndView("layout"));
            assertThat(events.list).hasSize(1);
            assertThat(events.list.getFirst().getFormattedMessage())
                    .isEqualTo("Notification snapshot unavailable (classification=DATA_ACCESS)")
                    .doesNotContain("secret-token-in-query");
            assertThat(events.list.getFirst().getThrowableProxy()).isNull();
        } finally {
            logger.detachAppender(events);
            events.stop();
        }
    }
}

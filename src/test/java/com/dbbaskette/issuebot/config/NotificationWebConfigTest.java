package com.dbbaskette.issuebot.config;

import com.dbbaskette.issuebot.service.notification.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
    }
}

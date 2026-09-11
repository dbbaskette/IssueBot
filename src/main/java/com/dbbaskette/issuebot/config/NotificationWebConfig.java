package com.dbbaskette.issuebot.config;

import com.dbbaskette.issuebot.service.notification.*;
import jakarta.servlet.http.*;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.servlet.*;
import org.springframework.web.servlet.config.annotation.*;

/** Query rendered pages only, not webhook/SSE handlers. Reuse the panel/history snapshot. */
@Configuration
public class NotificationWebConfig implements WebMvcConfigurer {
    private final NotificationTriageService triage;
    public NotificationWebConfig(NotificationTriageService triage) { this.triage = triage; }
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public void postHandle(HttpServletRequest request, HttpServletResponse response, Object handler, ModelAndView view) {
                if (view == null || view.getViewName() == null) return;
                String name = view.getViewName();
                if (!(name.equals("layout") || name.endsWith(" :: content") || name.equals("notifications :: panel"))) return;
                try {
                    Object supplied = view.getModel().get("notificationSnapshot");
                    var snapshot = supplied instanceof NotificationSnapshot existing ? existing
                            : triage.snapshot("", null, "ALL", "ALL", false, PageRequest.of(0, 10));
                    view.addObject("notificationSnapshot", snapshot);
                    view.addObject("unreadNotificationCount", snapshot.unreadActionGroupCount());
                    view.addObject("notificationStateAvailable", true);
                } catch (RuntimeException unavailable) {
                    view.addObject("unreadNotificationCount", null);
                    view.addObject("notificationStateAvailable", false);
                }
            }
        });
    }
}

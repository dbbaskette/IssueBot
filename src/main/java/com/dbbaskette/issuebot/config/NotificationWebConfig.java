package com.dbbaskette.issuebot.config;

import com.dbbaskette.issuebot.service.notification.*;
import jakarta.servlet.http.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.servlet.*;
import org.springframework.web.servlet.config.annotation.*;

import java.util.concurrent.atomic.AtomicLong;

/** Query rendered pages only, not webhook/SSE handlers. Reuse the panel/history snapshot. */
@Configuration
public class NotificationWebConfig implements WebMvcConfigurer {
    private static final Logger log = LoggerFactory.getLogger(NotificationWebConfig.class);
    private static final long DIAGNOSTIC_INTERVAL_MS = 60_000;
    private final NotificationTriageService triage;
    private final AtomicLong nextDiagnosticAt = new AtomicLong();
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
                    logSnapshotFailure(unavailable);
                }
            }
        });
    }

    private void logSnapshotFailure(RuntimeException failure) {
        long now = System.currentTimeMillis();
        long next = nextDiagnosticAt.get();
        if (now >= next && nextDiagnosticAt.compareAndSet(next, now + DIAGNOSTIC_INTERVAL_MS)) {
            String classification = failure instanceof DataAccessException ? "DATA_ACCESS" : "UNEXPECTED";
            // Never attach the exception: provider responses and query text can occur in its payload.
            log.warn("Notification snapshot unavailable (classification={})", classification);
        }
    }
}

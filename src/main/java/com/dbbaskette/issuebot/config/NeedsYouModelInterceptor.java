package com.dbbaskette.issuebot.config;

import com.dbbaskette.issuebot.service.ui.NeedsYouService;
import com.dbbaskette.issuebot.service.ui.NeedsYouSnapshot;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;

/** Populate navigation after handler mutations, reusing the inbox's exact rendered snapshot. */
public final class NeedsYouModelInterceptor implements HandlerInterceptor {
    private final NeedsYouService needsYou;

    public NeedsYouModelInterceptor(NeedsYouService needsYou) { this.needsYou = needsYou; }

    @Override
    public void postHandle(HttpServletRequest request, HttpServletResponse response,
                           Object handler, ModelAndView view) {
        if (view == null) return;
        String name = view.getViewName();
        if (name == null || !(name.equals("layout") || name.endsWith(" :: content"))) return;
        Object existing = view.getModel().get("needsYouSnapshot");
        NeedsYouSnapshot snapshot = existing instanceof NeedsYouSnapshot supplied
                ? supplied : needsYou.snapshot();
        view.addObject("needsYouSnapshot", snapshot);
        view.addObject("needsYouCount", snapshot.totalCount());
    }
}

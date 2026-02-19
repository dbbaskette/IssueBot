package com.dbbaskette.issuebot.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Automatically returns just the content fragment for HTMX partial requests
 * instead of the full layout. This prevents the sidebar and layout from being
 * nested inside #content when navigating via HTMX.
 */
@Configuration
public class HtmxConfig implements WebMvcConfigurer {

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HtmxFragmentInterceptor());
    }

    static class HtmxFragmentInterceptor implements HandlerInterceptor {

        @Override
        public void postHandle(HttpServletRequest request, HttpServletResponse response,
                               Object handler, ModelAndView modelAndView) {
            if (modelAndView == null) return;

            // Only intercept HTMX requests that would render the full layout
            String hxRequest = request.getHeader("HX-Request");
            if (!"true".equals(hxRequest)) return;
            if (!"layout".equals(modelAndView.getViewName())) return;

            // Return just the content fragment instead of the full layout
            Object contentTemplate = modelAndView.getModel().get("contentTemplate");
            if (contentTemplate != null) {
                modelAndView.setViewName(contentTemplate + " :: content");
            }
        }
    }
}

package com.dbbaskette.issuebot.config;

import com.dbbaskette.issuebot.service.ui.NeedsYouService;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class NeedsYouWebConfig implements WebMvcConfigurer {
    private final NeedsYouService needsYou;

    public NeedsYouWebConfig(NeedsYouService needsYou) { this.needsYou = needsYou; }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new NeedsYouModelInterceptor(needsYou));
    }
}

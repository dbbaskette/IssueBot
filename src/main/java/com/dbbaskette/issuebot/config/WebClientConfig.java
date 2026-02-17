package com.dbbaskette.issuebot.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class WebClientConfig {

    @Bean
    public WebClient gitHubWebClient(IssueBotProperties properties) {
        return WebClient.builder()
                .baseUrl("https://api.github.com")
                .defaultHeader(HttpHeaders.ACCEPT, "application/vnd.github.v3+json")
                .defaultHeader(HttpHeaders.USER_AGENT, "IssueBot/0.1")
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.getGithub().getToken())
                .build();
    }
}

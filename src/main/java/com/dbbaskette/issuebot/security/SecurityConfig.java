package com.dbbaskette.issuebot.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Value("${issuebot.auth.username:#{null}}")
    private String username;

    @Value("${issuebot.auth.password:#{null}}")
    private String password;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        if (isAuthEnabled()) {
            http
                .authorizeHttpRequests(auth -> auth
                    .requestMatchers("/css/**", "/js/**", "/images/**").permitAll()
                    .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                    // Webhook auth is the HMAC signature, not the dashboard login —
                    // GitHub can't present a browser session or basic auth credentials.
                    .requestMatchers("/webhooks/**").permitAll()
                    .anyRequest().authenticated()
                )
                .formLogin(form -> form
                    .defaultSuccessUrl("/", true)
                    .permitAll()
                )
                .httpBasic(basic -> {});
        } else {
            http
                .authorizeHttpRequests(auth -> auth
                    .anyRequest().permitAll()
                );
        }
        http
            // Browser mutations use Spring Security's normal CSRF protection. The
            // one machine-to-machine endpoint authenticates the raw request body
            // with GitHub's HMAC signature and therefore cannot carry a CSRF token.
            .csrf(csrf -> csrf.ignoringRequestMatchers("/webhooks/github"))
            .headers(headers -> headers
                .frameOptions(frame -> frame.sameOrigin())
            );
        return http.build();
    }

    @Bean
    public UserDetailsService userDetailsService() {
        if (isAuthEnabled()) {
            return new InMemoryUserDetailsManager(
                    User.builder()
                            .username(username)
                            .password(passwordEncoder().encode(password))
                            .roles("ADMIN")
                            .build()
            );
        }
        return new InMemoryUserDetailsManager();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    private boolean isAuthEnabled() {
        return username != null && !username.isBlank()
                && password != null && !password.isBlank();
    }
}

package com.dbbaskette.issuebot.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "issuebot.auth.username=dashboard-user",
        "issuebot.auth.password=dashboard-password",
        "issuebot.github.token=test-token",
        "spring.datasource.url=jdbc:h2:mem:security-health-test;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
})
@AutoConfigureMockMvc
class SecurityConfigHealthEndpointTest {

    @Autowired
    MockMvc mockMvc;

    @Test
    void groupedHealthEndpointsArePublic() throws Exception {
        mockMvc.perform(get("/actuator/health/liveness"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/actuator/health/readiness"))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isIn(200, 503));
    }

    @Test
    void buildInfoRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/actuator/info"))
                .andExpect(status().isUnauthorized());
    }
}

package com.dbbaskette.issuebot;

import com.dbbaskette.issuebot.service.harness.CodingHarnessRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@TestPropertySource(properties = {
        "issuebot.github.token=test-token",
        "spring.datasource.url=jdbc:h2:mem:issuebot-context-test;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
})
class IssueBotApplicationTests {

    @Autowired
    CodingHarnessRegistry registry;

    @Test
    void registersCurrentCodingHarnesses() {
        assertThat(registry.require("claude")).isNotNull();
        assertThat(registry.require("codex")).isNotNull();
    }

    @Test
    void contextLoads() {
    }
}

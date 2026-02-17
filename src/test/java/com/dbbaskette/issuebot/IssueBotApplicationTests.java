package com.dbbaskette.issuebot;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@TestPropertySource(properties = {
        "spring.ai.anthropic.api-key=test-key",
        "issuebot.github.token=test-token"
})
class IssueBotApplicationTests {

    @Test
    void contextLoads() {
    }
}

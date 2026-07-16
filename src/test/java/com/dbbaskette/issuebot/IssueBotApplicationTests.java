package com.dbbaskette.issuebot;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@TestPropertySource(properties = {
        "issuebot.github.token=test-token",
        "spring.datasource.url=jdbc:h2:mem:issuebot-context-test;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
})
class IssueBotApplicationTests {

    @Test
    void contextLoads() {
    }
}

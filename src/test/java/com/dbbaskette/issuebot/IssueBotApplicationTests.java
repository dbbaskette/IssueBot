package com.dbbaskette.issuebot;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@TestPropertySource(properties = {
        "issuebot.github.token=test-token"
})
class IssueBotApplicationTests {

    @Test
    void contextLoads() {
    }
}

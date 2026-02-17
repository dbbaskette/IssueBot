package com.dbbaskette.issuebot.service.git;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GitOperationsServiceTest {

    @Test
    void slugifySimpleTitle() {
        assertEquals("add-pagination-to-users-endpoint",
                GitOperationsService.slugify("Add pagination to /users endpoint"));
    }

    @Test
    void slugifyTitleWithSpecialChars() {
        assertEquals("fix-bug-in-logincontroller",
                GitOperationsService.slugify("Fix bug in LoginController!!"));
    }

    @Test
    void slugifyLongTitle() {
        String longTitle = "This is a very long issue title that should be truncated to fifty characters to keep branch names manageable";
        String slug = GitOperationsService.slugify(longTitle);
        assertTrue(slug.length() <= 50);
    }

    @Test
    void slugifyEmptyTitle() {
        assertEquals("untitled", GitOperationsService.slugify(""));
        assertEquals("untitled", GitOperationsService.slugify(null));
    }
}

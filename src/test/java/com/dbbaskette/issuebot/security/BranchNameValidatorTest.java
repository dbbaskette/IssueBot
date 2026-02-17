package com.dbbaskette.issuebot.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BranchNameValidatorTest {

    @Test
    void validBranch() {
        assertTrue(BranchNameValidator.isValid("issuebot/issue-42-fix-login-bug"));
        assertTrue(BranchNameValidator.isValid("issuebot/issue-1-a"));
        assertTrue(BranchNameValidator.isValid("issuebot/issue-999-add-feature-123"));
    }

    @Test
    void invalidBranch_wrongPrefix() {
        assertFalse(BranchNameValidator.isValid("feature/issue-42-fix-login-bug"));
        assertFalse(BranchNameValidator.isValid("main"));
        assertFalse(BranchNameValidator.isValid("master"));
    }

    @Test
    void invalidBranch_noNumber() {
        assertFalse(BranchNameValidator.isValid("issuebot/issue--fix"));
        assertFalse(BranchNameValidator.isValid("issuebot/issue-abc-fix"));
    }

    @Test
    void invalidBranch_null() {
        assertFalse(BranchNameValidator.isValid(null));
    }

    @Test
    void isSafeToPush_valid() {
        assertTrue(BranchNameValidator.isSafeToPush("issuebot/issue-42-fix-login-bug", "main"));
    }

    @Test
    void isSafeToPush_protectedBranch() {
        assertFalse(BranchNameValidator.isSafeToPush("main", "main"));
        assertFalse(BranchNameValidator.isSafeToPush("master", "main"));
    }

    @Test
    void isSafeToPush_defaultBranch() {
        assertFalse(BranchNameValidator.isSafeToPush("develop", "develop"));
    }

    @Test
    void isSafeToPush_null() {
        assertFalse(BranchNameValidator.isSafeToPush(null, "main"));
    }
}

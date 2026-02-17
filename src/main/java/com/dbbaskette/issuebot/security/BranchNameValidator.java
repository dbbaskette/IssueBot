package com.dbbaskette.issuebot.security;

import java.util.regex.Pattern;

/**
 * Validates branch names to ensure IssueBot only operates on its own branches.
 */
public final class BranchNameValidator {

    private static final Pattern ISSUEBOT_BRANCH = Pattern.compile("^issuebot/issue-\\d+-[a-z0-9-]+$");

    private BranchNameValidator() {}

    /**
     * Check if a branch name matches the IssueBot naming convention.
     */
    public static boolean isValid(String branchName) {
        return branchName != null && ISSUEBOT_BRANCH.matcher(branchName).matches();
    }

    /**
     * Ensure a branch is not the default/protected branch.
     */
    public static boolean isSafeToPush(String branchName, String defaultBranch) {
        if (branchName == null) return false;
        if (branchName.equals(defaultBranch)) return false;
        if ("main".equals(branchName) || "master".equals(branchName)) return false;
        return isValid(branchName);
    }
}

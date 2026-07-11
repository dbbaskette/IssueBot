package com.dbbaskette.issuebot.model;

/** How non-blocking review findings are captured after a passing review. */
public enum FollowUpMode {
    OFF,              // findings live only in the PR review comment
    COMMENT_ONLY,     // also summarized as a comment on the original issue
    ROLLING_BACKLOG,  // appended (deduped) to the single per-repo backlog issue
    PER_ISSUE         // legacy: one new follow-up issue per completed issue
}

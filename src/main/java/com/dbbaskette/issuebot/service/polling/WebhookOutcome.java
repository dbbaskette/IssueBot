package com.dbbaskette.issuebot.service.polling;

/**
 * Outcome of evaluating a single issue through the webhook fast path
 * ({@link IssuePollingService#evaluateSingleIssueFromWebhook}).
 * <p>
 * Also doubles as the outcome label recorded in the webhook delivery log
 * shown on the setup page (lower-cased — e.g. {@code STARTED} becomes
 * {@code "started"}).
 */
public enum WebhookOutcome {
    /** The issue's workflow was started immediately (set to IN_PROGRESS). */
    STARTED,
    /** Saved as QUEUED — will run once capacity, the per-repo gate, or auto-start allows it. */
    QUEUED,
    /** Saved as BLOCKED — unresolved dependencies must resolve before this issue can run. */
    BLOCKED,
    /** The issue was already tracked (any status) and was not re-evaluated. */
    ALREADY_TRACKED,
    /** Not something this evaluation acts on (e.g. the payload described a pull request). */
    IGNORED
}

package com.dbbaskette.issuebot.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Centralized Micrometer metrics for IssueBot.
 */
@Component
public class IssueBotMetrics {

    private final Counter issuesCompleted;
    private final Counter issuesFailed;
    private final Counter issuesDetected;
    private final Counter claudeInvocations;
    private final Counter inputTokensTotal;
    private final Counter outputTokensTotal;
    private final Counter githubApiCalls;
    private final Timer claudeCodeDuration;
    private final Timer ciWaitDuration;
    private final AtomicInteger activeSessionsGauge;
    private final MeterRegistry registry;

    public IssueBotMetrics(MeterRegistry registry) {
        this.registry = registry;

        this.issuesCompleted = Counter.builder("issuebot.issues.completed")
                .description("Total issues completed successfully")
                .register(registry);

        this.issuesFailed = Counter.builder("issuebot.issues.failed")
                .description("Total issues that failed after max iterations")
                .register(registry);

        this.issuesDetected = Counter.builder("issuebot.issues.detected")
                .description("Total issues detected for processing")
                .register(registry);

        this.claudeInvocations = Counter.builder("issuebot.claude.invocations")
                .description("Total Claude Code CLI invocations")
                .register(registry);

        this.inputTokensTotal = Counter.builder("issuebot.claude.tokens.input")
                .description("Total input tokens consumed")
                .register(registry);

        this.outputTokensTotal = Counter.builder("issuebot.claude.tokens.output")
                .description("Total output tokens consumed")
                .register(registry);

        this.githubApiCalls = Counter.builder("issuebot.github.api.calls")
                .description("Total GitHub API calls made")
                .register(registry);

        this.claudeCodeDuration = Timer.builder("issuebot.claude.duration")
                .description("Claude Code CLI execution duration")
                .register(registry);

        this.ciWaitDuration = Timer.builder("issuebot.ci.wait.duration")
                .description("Time spent waiting for CI checks")
                .register(registry);

        this.activeSessionsGauge = registry.gauge("issuebot.claude.active_sessions",
                new AtomicInteger(0));
    }

    public void recordIssueCompleted() { issuesCompleted.increment(); }
    public void recordIssueFailed() { issuesFailed.increment(); }
    public void recordIssueDetected() { issuesDetected.increment(); }

    public void recordClaudeInvocation(long durationMs, long inputTokens, long outputTokens) {
        claudeInvocations.increment();
        claudeCodeDuration.record(Duration.ofMillis(durationMs));
        inputTokensTotal.increment(inputTokens);
        outputTokensTotal.increment(outputTokens);
    }

    public void recordCiWait(long durationMs) {
        ciWaitDuration.record(Duration.ofMillis(durationMs));
    }

    public void recordGitHubApiCall() { githubApiCalls.increment(); }

    public void incrementActiveSessions() {
        if (activeSessionsGauge != null) activeSessionsGauge.incrementAndGet();
    }

    public void decrementActiveSessions() {
        if (activeSessionsGauge != null) activeSessionsGauge.decrementAndGet();
    }
}

package com.dbbaskette.issuebot.service.workflow;

import com.dbbaskette.issuebot.config.IssueBotProperties;
import org.junit.jupiter.api.Test;
import java.time.*;
import static com.dbbaskette.issuebot.service.workflow.PrerequisiteStatusService.Component.*;
import static com.dbbaskette.issuebot.service.workflow.PrerequisiteStatusService.Result.*;
import static com.dbbaskette.issuebot.service.ui.RecoveryGuidance.PrerequisiteState.*;
import static org.assertj.core.api.Assertions.assertThat;

class PrerequisiteStatusServiceTest {
    private final IssueBotProperties properties = new IssueBotProperties();
    private final MutableClock clock = new MutableClock();
    private final PrerequisiteStatusService service = new PrerequisiteStatusService(properties, clock);

    @Test void freshnessIsFixedAndRestartIsUnknown() {
        var context = service.context("claude");
        assertThat(service.retryState()).isEqualTo(NOT_VERIFIED);
        service.record(context, CLI, UNMET);
        clock.advance(Duration.ofMinutes(4));
        assertThat(service.retryRejection()).isNotNull();
        clock.advance(Duration.ofMinutes(1));
        assertThat(service.retryState()).isEqualTo(NOT_VERIFIED);
        assertThat(new PrerequisiteStatusService(properties).retryState()).isEqualTo(NOT_VERIFIED);
    }

    @Test void harnessSuccessCannotClearGithubOrDirectoryFailures() {
        var context = service.context("claude");
        service.record(context, GITHUB, UNMET);
        service.record(context, WORK_DIRECTORY, UNMET);
        service.record(context, CLI, READY);
        service.record(context, SUBSCRIPTION, READY);
        assertThat(service.retryState()).isEqualTo(KNOWN_UNMET);
        service.record(context, GITHUB, READY);
        assertThat(service.retryState()).isEqualTo(KNOWN_UNMET);
        service.record(context, WORK_DIRECTORY, READY);
        assertThat(service.retryState()).isEqualTo(VERIFIED_READY);
        service.record(context, GITHUB, UNKNOWN);
        assertThat(service.retryState()).isEqualTo(NOT_VERIFIED);
    }

    @Test void actualHarnessAndConfigurationMustMatchAndOpaqueContextDoesNotExposeSecrets() {
        service.record(service.context("codex"), SUBSCRIPTION, UNMET);
        assertThat(service.state("codex")).isEqualTo(KNOWN_UNMET);
        assertThat(service.retryState()).isEqualTo(NOT_VERIFIED);
        service.record(service.context("claude"), CLI, UNMET);
        properties.setAgentProvider("codex");
        assertThat(service.retryState()).isEqualTo(NOT_VERIFIED);
        var beforeChange = service.context("codex");
        properties.getGithub().setToken("a-private-value");
        service.record(beforeChange, GITHUB, UNMET);
        assertThat(service.retryState()).isEqualTo(NOT_VERIFIED);
        assertThat(service.context("codex").toString()).isEqualTo("Prerequisite context");
        service.record(service.context("codex"), CLI, UNMET);
        properties.setWorkDirectory("/new-work-directory");
        assertThat(service.retryState()).isEqualTo(NOT_VERIFIED);
    }

    @Test void observationCountIsBounded() {
        var oldest = service.context("first");
        service.record(oldest, CLI, UNMET);
        for (int i = 0; i < 32; i++) service.record(service.context("harness-" + i), CLI, READY);
        assertThat(service.result(oldest, CLI)).isEqualTo(UNKNOWN);
    }

    private static class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-09-11T12:00:00Z");
        void advance(Duration duration) { now = now.plus(duration); }
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return now; }
    }
}

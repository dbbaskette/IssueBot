package com.dbbaskette.issuebot.service.harness;

import com.dbbaskette.issuebot.config.IssueBotProperties;
import com.dbbaskette.issuebot.repository.*;
import com.dbbaskette.issuebot.service.workflow.PrerequisiteStatusService;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static com.dbbaskette.issuebot.service.ui.RecoveryGuidance.PrerequisiteState.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Doubles only the operating-system process. Runner parsing, adapters, preflight and cache are real. */
public final class ConcreteReadinessProbeAssertions {
    private enum Outcome { TIMEOUT, IO, INTERRUPTED, MALFORMED, NONZERO, UNAUTHENTICATED, READY, MISSING }

    @FunctionalInterface public interface Starter { Process start(ProcessBuilder builder) throws IOException; }

    public static void verify(java.util.function.Function<Starter, CodingHarnessAdapter> adapters,
            String authenticated, String unauthenticated) throws Exception {
        for (boolean auth : List.of(false, true)) {
            for (Outcome outcome : Outcome.values()) {
                if (!auth && (outcome == Outcome.MALFORMED || outcome == Outcome.UNAUTHENTICATED)) continue;
                try {
                var adapter = adapters.apply(builder -> {
                    var command = builder.command();
                    boolean affected = auth ? !command.contains("--version") : command.contains("--version");
                    if (affected && outcome == Outcome.IO) {
                        throw new IOException("transport unavailable");
                    }
                    if (affected && outcome == Outcome.MISSING) {
                        throw new java.nio.file.NoSuchFileException("missing executable");
                    }
                    Process process = mock(Process.class);
                    when(process.isAlive()).thenReturn(affected && (outcome == Outcome.TIMEOUT || outcome == Outcome.INTERRUPTED));
                    try {
                        if (affected && outcome == Outcome.INTERRUPTED) when(process.waitFor(10, TimeUnit.SECONDS)).thenThrow(new InterruptedException());
                        else when(process.waitFor(10, TimeUnit.SECONDS)).thenReturn(!affected || outcome != Outcome.TIMEOUT);
                    } catch (InterruptedException impossibleDuringStubbing) { throw new AssertionError(impossibleDuringStubbing); }
                    when(process.exitValue()).thenReturn(affected && (outcome == Outcome.NONZERO || outcome == Outcome.UNAUTHENTICATED) ? 1 : 0);
                    String output = !affected ? (command.contains("--version") ? "version 1" : authenticated) : switch (outcome) {
                        case MALFORMED -> "unparseable provider response";
                        case UNAUTHENTICATED -> unauthenticated;
                        default -> authenticated;
                    };
                    when(process.getInputStream()).thenAnswer(ignored -> new ByteArrayInputStream(output.getBytes(StandardCharsets.UTF_8)));
                    return process;
                });
                    HarnessReadiness expected = outcome == Outcome.READY ? HarnessReadiness.READY
                            : outcome == Outcome.UNAUTHENTICATED || (!auth && outcome == Outcome.MISSING)
                            ? HarnessReadiness.UNMET : HarnessReadiness.UNKNOWN;
                    assertThat(auth ? adapter.probeSubscriptionAuthentication() : adapter.probeCliAvailability())
                            .as("%s %s %s", adapter.id(), auth ? "auth" : "CLI", outcome).isEqualTo(expected);
                    if (outcome == Outcome.INTERRUPTED) assertThat(Thread.interrupted()).isTrue();

                    assertThat(auth ? adapter.checkSubscriptionAuthentication() : adapter.checkCliAvailable())
                            .as("legacy wrapper is fail-closed").isEqualTo(expected.ready());
                    if (outcome == Outcome.INTERRUPTED) assertThat(Thread.interrupted()).isTrue();

                    var properties = new IssueBotProperties();
                    properties.setAgentProvider(adapter.id());
                    var cache = new PrerequisiteStatusService(properties);
                    var registry = new CodingHarnessRegistry(List.of(adapter));
                    var selections = new HarnessSelectionService(registry, properties,
                            mock(TrackedIssueRepository.class), mock(StageApprovalRepository.class), cache);
                    var model = adapter.models().getFirst();
                    var tuple = selections.resolve(adapter.id(), model.id(), model.defaultReasoningLevel());
                    if (expected == HarnessReadiness.READY) selections.validateReady(tuple);
                    else assertThatThrownBy(() -> selections.validateReady(tuple)).isInstanceOf(IllegalStateException.class);
                    if (outcome == Outcome.INTERRUPTED) assertThat(Thread.interrupted()).isTrue();
                    assertThat(cache.retryState()).as("cached %s %s %s", adapter.id(), auth, outcome)
                            .isEqualTo(expected == HarnessReadiness.UNMET ? KNOWN_UNMET : NOT_VERIFIED);
                    assertThat(cache.retryRejection() != null).isEqualTo(expected == HarnessReadiness.UNMET);
                    if (auth) {
                        var harness = new CodingHarnessService(registry, properties, selections, cache);
                        if (expected == HarnessReadiness.READY) harness.pinSubscriptionHarness(adapter.id());
                        else assertThatThrownBy(() -> harness.pinSubscriptionHarness(adapter.id())).isInstanceOf(IllegalStateException.class);
                        if (outcome == Outcome.INTERRUPTED) assertThat(Thread.interrupted()).isTrue();
                        assertThat(cache.retryRejection() != null).isEqualTo(expected == HarnessReadiness.UNMET);
                    }
                } finally { Thread.interrupted(); }
            }
        }
    }
}

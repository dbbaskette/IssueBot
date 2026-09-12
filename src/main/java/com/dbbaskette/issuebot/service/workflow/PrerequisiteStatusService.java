package com.dbbaskette.issuebot.service.workflow;

import com.dbbaskette.issuebot.config.IssueBotProperties;
import com.dbbaskette.issuebot.service.harness.HarnessReadiness;
import com.dbbaskette.issuebot.service.harness.HarnessIds;
import com.dbbaskette.issuebot.service.ui.RecoveryGuidance.PrerequisiteState;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** Bounded, process-local observations. Reads never probe, extend TTL, or retain raw probe output. */
@Service
public class PrerequisiteStatusService {
    public enum Component { CLI, SUBSCRIPTION, GITHUB, WORK_DIRECTORY }
    public enum Result { READY, UNMET, UNKNOWN }
    public static final Duration FRESHNESS = Duration.ofMinutes(5);
    public static final String RETRY_BLOCKED = "Retry is blocked by a recently confirmed unmet prerequisite. Resolve it in Setup and re-check.";
    private static final int MAX_OBSERVATIONS = 32;
    private final IssueBotProperties properties;
    private final Clock clock;
    private final Map<Key, Observation> observations = new LinkedHashMap<>();

    @Autowired
    public PrerequisiteStatusService(IssueBotProperties properties) { this(properties, Clock.systemUTC()); }
    PrerequisiteStatusService(IssueBotProperties properties, Clock clock) { this.properties = properties; this.clock = clock; }

    /** Opaque context must be captured before probing so a concurrent settings change cannot bless new settings. */
    public Context context(String harnessId) {
        String harness = HarnessIds.normalize(harnessId);
        String material = properties.getAgentProvider() + "\n" + properties.getWorkDirectory() + "\n"
                + (properties.getGithub() == null ? null : properties.getGithub().getToken());
        try {
            return new Context(harness, java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(material.getBytes(StandardCharsets.UTF_8))));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    public synchronized void record(Context context, Component component, Result result) {
        Key key = key(context, component);
        observations.remove(key);
        observations.put(key, new Observation(result, clock.instant()));
        while (observations.size() > MAX_OBSERVATIONS) observations.remove(observations.keySet().iterator().next());
    }

    /** Used only by explicit/actual preflight paths; an unavailable check supersedes older evidence with UNKNOWN. */
    public boolean observe(Context context, Component component,
            java.util.function.Supplier<HarnessReadiness> probe) {
        try {
            var readiness = probe.get();
            record(context, component, readiness == null ? Result.UNKNOWN : switch (readiness) {
                case READY -> Result.READY;
                case UNMET -> Result.UNMET;
                case UNKNOWN -> Result.UNKNOWN;
            });
            return readiness == HarnessReadiness.READY;
        } catch (RuntimeException unavailable) {
            record(context, component, Result.UNKNOWN);
            throw unavailable;
        }
    }

    public synchronized Result result(Context context, Component component) {
        Observation value = observations.get(key(context, component));
        Instant now = clock.instant();
        if (value == null || now.isBefore(value.at()) || !now.isBefore(value.at().plus(FRESHNESS))) return Result.UNKNOWN;
        return value.result();
    }

    public PrerequisiteState state(String harnessId) {
        Context context = context(harnessId);
        boolean unknown = false;
        for (Component component : Component.values()) {
            Result result = result(context, component);
            if (result == Result.UNMET) return PrerequisiteState.KNOWN_UNMET;
            unknown |= result == Result.UNKNOWN;
        }
        return unknown ? PrerequisiteState.NOT_VERIFIED : PrerequisiteState.VERIFIED_READY;
    }

    /** A retry starts a new run using the configured harness, not the last run's historical pin. */
    public PrerequisiteState retryState() { return state(properties.getAgentProvider()); }
    public String retryRejection() { return retryState() == PrerequisiteState.KNOWN_UNMET ? RETRY_BLOCKED : null; }

    private Key key(Context context, Component component) {
        // GitHub and filesystem observations are shared by harnesses, but remain configuration-scoped.
        return new Key(component == Component.CLI || component == Component.SUBSCRIPTION ? context.harness : "shared",
                context.fingerprint, component);
    }
    public static final class Context {
        private final String harness;
        private final String fingerprint;
        private Context(String harness, String fingerprint) { this.harness = harness; this.fingerprint = fingerprint; }
        @Override public String toString() { return "Prerequisite context"; }
    }
    private record Key(String harness, String fingerprint, Component component) {}
    private record Observation(Result result, Instant at) {}
}

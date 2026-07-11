package com.dbbaskette.issuebot.controller;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory record of recent GitHub webhook deliveries plus running counters,
 * shared by {@link WebhookController} (which records a {@link Delivery} at every
 * decision point) and {@link SetupController} (which renders them on the setup
 * page's Webhooks card). Process-local only — reset on restart, not a source of
 * truth for anything else. Never holds payload or signature bytes.
 */
@Component
public class WebhookDeliveryLog {

    /** Ring buffer capacity — enough recent history to debug without unbounded growth. */
    static final int CAPACITY = 50;

    /** Outcomes that count as a real action taken on an issue (for the actionsTaken counter). */
    private static final Set<String> ACTION_OUTCOMES = Set.of("started", "queued", "blocked", "recheck");

    /** One recorded webhook delivery. Fields are nullable when the data isn't trusted or known yet. */
    public record Delivery(Instant at, String event, String action, String repo, String outcome, String detail) {}

    private final ConcurrentLinkedDeque<Delivery> deliveries = new ConcurrentLinkedDeque<>();
    private final AtomicLong totalReceived = new AtomicLong();
    private final AtomicLong signatureFailures = new AtomicLong();
    private final AtomicLong actionsTaken = new AtomicLong();

    /**
     * Records one delivery and updates the counters. Trims the ring to
     * {@link #CAPACITY} entries, newest first.
     */
    public void record(String event, String action, String repo, String outcome, String detail) {
        deliveries.addFirst(new Delivery(Instant.now(), event, action, repo, outcome, detail));
        while (deliveries.size() > CAPACITY) {
            deliveries.pollLast();
        }

        totalReceived.incrementAndGet();
        if ("bad-signature".equals(outcome)) {
            signatureFailures.incrementAndGet();
        }
        if (ACTION_OUTCOMES.contains(outcome)) {
            actionsTaken.incrementAndGet();
        }
    }

    /** Defensive copy, newest first. */
    public List<Delivery> recentDeliveries() {
        return new ArrayList<>(deliveries);
    }

    public long totalReceived() {
        return totalReceived.get();
    }

    public long signatureFailures() {
        return signatureFailures.get();
    }

    public long actionsTaken() {
        return actionsTaken.get();
    }
}

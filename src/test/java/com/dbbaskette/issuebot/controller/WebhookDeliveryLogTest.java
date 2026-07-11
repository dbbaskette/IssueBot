package com.dbbaskette.issuebot.controller;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WebhookDeliveryLogTest {

    @Test
    void recordAppendsNewestFirst() {
        WebhookDeliveryLog log = new WebhookDeliveryLog();

        log.record("issues", "labeled", "acme/widgets", "started", "issue #1 started");
        log.record("issues", "closed", "acme/widgets", "recheck", "issue #2 closed");

        List<WebhookDeliveryLog.Delivery> deliveries = log.recentDeliveries();
        assertThat(deliveries).hasSize(2);
        assertThat(deliveries.get(0).outcome()).isEqualTo("recheck");
        assertThat(deliveries.get(1).outcome()).isEqualTo("started");
    }

    @Test
    void trimsAtCapacityKeepingOnlyNewest() {
        WebhookDeliveryLog log = new WebhookDeliveryLog();

        for (int i = 0; i < 60; i++) {
            log.record("issues", "labeled", "acme/widgets", "started", "issue #" + i + " started");
        }

        List<WebhookDeliveryLog.Delivery> deliveries = log.recentDeliveries();
        assertThat(deliveries).hasSize(50);
        // Newest first: the most recent record (#59) is at index 0, oldest kept is #10.
        assertThat(deliveries.get(0).detail()).isEqualTo("issue #59 started");
        assertThat(deliveries.get(49).detail()).isEqualTo("issue #10 started");
    }

    @Test
    void recentDeliveriesReturnsDefensiveCopy() {
        WebhookDeliveryLog log = new WebhookDeliveryLog();
        log.record("issues", "labeled", "acme/widgets", "started", "issue #1 started");

        List<WebhookDeliveryLog.Delivery> first = log.recentDeliveries();
        first.clear();

        assertThat(log.recentDeliveries()).hasSize(1);
    }

    @Test
    void totalReceivedIncrementsOnEveryRecord() {
        WebhookDeliveryLog log = new WebhookDeliveryLog();

        log.record("issues", "labeled", "acme/widgets", "started", "d1");
        log.record(null, null, "—", "bad-signature", null);
        log.record(null, null, "—", "oversized", null);

        assertThat(log.totalReceived()).isEqualTo(3);
    }

    @Test
    void signatureFailuresOnlyCountsBadSignatureOutcome() {
        WebhookDeliveryLog log = new WebhookDeliveryLog();

        log.record(null, null, "—", "bad-signature", null);
        log.record(null, null, "—", "bad-signature", null);
        log.record(null, null, "—", "oversized", null);
        log.record("issues", "labeled", "acme/widgets", "started", "d1");

        assertThat(log.signatureFailures()).isEqualTo(2);
    }

    @Test
    void actionsTakenCountsStartedQueuedBlockedAndRecheckOnly() {
        WebhookDeliveryLog log = new WebhookDeliveryLog();

        log.record("issues", "labeled", "acme/widgets", "started", "d1");
        log.record("issues", "labeled", "acme/widgets", "queued", "d2");
        log.record("issues", "labeled", "acme/widgets", "blocked", "d3");
        log.record("issues", "closed", "acme/widgets", "recheck", "d4");
        // These should NOT count toward actionsTaken:
        log.record("issues", "labeled", "acme/widgets", "already_tracked", "d5");
        log.record("issues", "assigned", "acme/widgets", "ignored", "d6");
        log.record(null, null, "—", "bad-signature", null);
        log.record(null, null, "—", "oversized", null);
        log.record("issues", "labeled", "acme/widgets", "error", "boom");

        assertThat(log.actionsTaken()).isEqualTo(4);
        assertThat(log.totalReceived()).isEqualTo(9);
    }
}

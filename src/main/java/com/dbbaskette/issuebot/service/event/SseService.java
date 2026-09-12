package com.dbbaskette.issuebot.service.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Server-Sent Events service for pushing real-time updates to the dashboard.
 */
@Service
public class SseService {

    private static final Logger log = LoggerFactory.getLogger(SseService.class);
    private static final long SSE_TIMEOUT = 0L; // no timeout — we handle cleanup via heartbeat

    private static final int MAX_ISSUES_WITH_OUTPUT = 32;
    private static final int MAX_LINES_PER_ISSUE = 150;
    private final List<Subscriber> emitters = new CopyOnWriteArrayList<>();
    private final Object outputLock = new Object();
    private final Map<Long, ArrayDeque<OutputLine>> recentOutput = new LinkedHashMap<>(16, 0.75f, true);
    private final String outputEpoch = UUID.randomUUID().toString();
    private long nextOutputSequence;

    private record Subscriber(SseEmitter emitter, Long issueId) {}
    public record OutputLine(String id, Instant at, String text) {}

    public SseEmitter subscribe() {
        return subscribe(null, null);
    }

    /** Replays only this issue's recent output; an EventSource reconnect sends its last event ID. */
    public SseEmitter subscribe(Long issueId, String lastEventId) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT);
        Subscriber subscriber = new Subscriber(emitter, issueId);

        emitter.onCompletion(() -> emitters.remove(subscriber));
        emitter.onTimeout(() -> emitters.remove(subscriber));
        emitter.onError(e -> emitters.remove(subscriber));

        synchronized (outputLock) {
            emitters.add(subscriber);
            if (issueId != null) {
                long after = cursor(lastEventId);
                for (OutputLine line : recentOutput.getOrDefault(issueId, new ArrayDeque<>())) {
                    if (sequence(line.id()) <= after) continue;
                    try { sendOutput(subscriber, issueId, line); }
                    catch (Exception error) { removeEmitter(subscriber); break; }
                }
            }
        }

        log.debug("SSE client connected, total: {}", emitters.size());
        return emitter;
    }

    /** A new run must not replay a previous run's terminal output. */
    public void beginIssueRun(Long issueId) {
        if (issueId == null) return;
        synchronized (outputLock) { recentOutput.remove(issueId); }
    }

    public List<OutputLine> recentOutput(Long issueId) {
        if (issueId == null) return List.of();
        synchronized (outputLock) {
            ArrayDeque<OutputLine> lines = recentOutput.get(issueId);
            return lines == null ? List.of() : List.copyOf(lines);
        }
    }

    /** Compact top-of-page preview; the full bounded replay stays in the terminal. */
    public String recentOutputText(Long issueId, int maxLines, int maxChars) {
        List<OutputLine> lines = recentOutput(issueId);
        if (lines.isEmpty()) return null;
        List<String> tail = new ArrayList<>();
        for (int index = Math.max(0, lines.size() - Math.max(1, maxLines)); index < lines.size(); index++) {
            tail.add(lines.get(index).text());
        }
        String value = String.join("\n", tail);
        return value.length() <= maxChars ? value : "…" + value.substring(value.length() - maxChars + 1);
    }

    /**
     * Send a heartbeat comment every 30s to keep connections alive and detect dead clients.
     */
    @Scheduled(fixedRate = 30_000)
    public void heartbeat() {
        if (emitters.isEmpty()) return;
        for (Subscriber subscriber : emitters) {
            try {
                subscriber.emitter().send(SseEmitter.event().comment("heartbeat"));
            } catch (Exception e) {
                removeEmitter(subscriber);
            }
        }
    }

    /**
     * Broadcast an event to all connected SSE clients.
     */
    public void broadcast(String eventName, String data) {
        for (Subscriber subscriber : emitters) {
            try {
                subscriber.emitter().send(SseEmitter.event()
                        .name(eventName)
                        .data(data));
            } catch (Exception e) {
                removeEmitter(subscriber);
            }
        }
    }

    private void removeEmitter(Subscriber subscriber) {
        emitters.remove(subscriber);
        try {
            subscriber.emitter().completeWithError(new IOException("Client disconnected"));
        } catch (Exception ignored) {
            // Already completed or errored — fine
        }
        log.debug("Removed disconnected SSE client, remaining: {}", emitters.size());
    }

    /**
     * Send an issue update event to trigger table refresh.
     */
    public void sendIssueUpdate() {
        broadcast("issue-update", "refresh");
    }

    /**
     * Broadcast a log line from Claude Code to all connected clients.
     * Sent as a "claude-log" event with JSON payload containing issueId and text.
     */
    public void broadcastClaudeLog(Long issueId, String text) {
        if (issueId == null) return;
        synchronized (outputLock) {
            if (!recentOutput.containsKey(issueId) && recentOutput.size() >= MAX_ISSUES_WITH_OUTPUT) {
                recentOutput.remove(recentOutput.keySet().iterator().next());
            }
            ArrayDeque<OutputLine> lines = recentOutput.computeIfAbsent(issueId, ignored -> new ArrayDeque<>());
            OutputLine line = new OutputLine(outputEpoch + ":" + ++nextOutputSequence, Instant.now(), text);
            lines.addLast(line);
            while (lines.size() > MAX_LINES_PER_ISSUE) lines.removeFirst();
            for (Subscriber subscriber : emitters) {
                if (subscriber.issueId() != null && !subscriber.issueId().equals(issueId)) continue;
                try { sendOutput(subscriber, issueId, line); }
                catch (Exception error) { removeEmitter(subscriber); }
            }
        }
    }

    private void sendOutput(Subscriber subscriber, Long issueId, OutputLine line) throws IOException {
        String data = "{\"issueId\":" + issueId + ",\"text\":" + escapeJson(line.text())
                + ",\"at\":" + escapeJson(line.at().toString()) + "}";
        subscriber.emitter().send(SseEmitter.event().id(line.id()).name("claude-log").data(data));
    }

    private long cursor(String lastEventId) {
        if (lastEventId == null || !lastEventId.startsWith(outputEpoch + ":")) return 0;
        return sequence(lastEventId);
    }

    private long sequence(String eventId) {
        try { return Math.max(0, Long.parseLong(eventId.substring(eventId.lastIndexOf(':') + 1))); }
        catch (RuntimeException invalid) { return 0; }
    }

    private String escapeJson(String text) {
        if (text == null) return "\"\"";
        return "\"" + text.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t") + "\"";
    }

    public int getClientCount() {
        return emitters.size();
    }
}

package com.dbbaskette.issuebot.service.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Server-Sent Events service for pushing real-time updates to the dashboard.
 */
@Service
public class SseService {

    private static final Logger log = LoggerFactory.getLogger(SseService.class);
    private static final long SSE_TIMEOUT = 0L; // no timeout — we handle cleanup via heartbeat

    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();

    public SseEmitter subscribe() {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT);
        emitters.add(emitter);

        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(e -> emitters.remove(emitter));

        log.debug("SSE client connected, total: {}", emitters.size());
        return emitter;
    }

    /**
     * Send a heartbeat comment every 30s to keep connections alive and detect dead clients.
     */
    @Scheduled(fixedRate = 30_000)
    public void heartbeat() {
        if (emitters.isEmpty()) return;
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().comment("heartbeat"));
            } catch (Exception e) {
                removeEmitter(emitter);
            }
        }
    }

    /**
     * Broadcast an event to all connected SSE clients.
     */
    public void broadcast(String eventName, String data) {
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event()
                        .name(eventName)
                        .data(data));
            } catch (Exception e) {
                removeEmitter(emitter);
            }
        }
    }

    private void removeEmitter(SseEmitter emitter) {
        emitters.remove(emitter);
        try {
            emitter.completeWithError(new IOException("Client disconnected"));
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
        String data = "{\"issueId\":" + issueId + ",\"text\":" + escapeJson(text) + "}";
        broadcast("claude-log", data);
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

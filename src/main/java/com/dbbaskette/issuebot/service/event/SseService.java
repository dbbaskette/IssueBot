package com.dbbaskette.issuebot.service.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    private static final long SSE_TIMEOUT = 300_000L; // 5 minutes

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
     * Broadcast an event to all connected SSE clients.
     */
    public void broadcast(String eventName, String data) {
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event()
                        .name(eventName)
                        .data(data));
            } catch (IOException e) {
                emitters.remove(emitter);
                log.debug("Removed disconnected SSE client");
            }
        }
    }

    /**
     * Send an issue update event to trigger table refresh.
     */
    public void sendIssueUpdate() {
        broadcast("issue-update", "refresh");
    }

    public int getClientCount() {
        return emitters.size();
    }
}

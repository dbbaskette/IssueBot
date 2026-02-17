package com.dbbaskette.issuebot.service.event;

import com.dbbaskette.issuebot.model.Event;
import com.dbbaskette.issuebot.model.TrackedIssue;
import com.dbbaskette.issuebot.model.WatchedRepo;
import com.dbbaskette.issuebot.repository.EventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class EventService {

    private static final Logger log = LoggerFactory.getLogger(EventService.class);

    private final EventRepository eventRepository;

    public EventService(EventRepository eventRepository) {
        this.eventRepository = eventRepository;
    }

    public Event log(String eventType, String message) {
        Event event = new Event(eventType, message);
        Event saved = eventRepository.save(event);
        log.info("[EVENT] {}: {}", eventType, message);
        return saved;
    }

    public Event log(String eventType, String message, WatchedRepo repo) {
        Event event = new Event(eventType, message, repo, null);
        Event saved = eventRepository.save(event);
        log.info("[EVENT] {} ({}): {}", eventType, repo.fullName(), message);
        return saved;
    }

    public Event log(String eventType, String message, WatchedRepo repo, TrackedIssue issue) {
        Event event = new Event(eventType, message, repo, issue);
        Event saved = eventRepository.save(event);
        log.info("[EVENT] {} ({} #{}): {}", eventType, repo.fullName(), issue.getIssueNumber(), message);
        return saved;
    }

    public List<Event> getRecentEvents(int limit) {
        return eventRepository.findAllByOrderByCreatedAtDesc(
                org.springframework.data.domain.PageRequest.of(0, limit));
    }
}

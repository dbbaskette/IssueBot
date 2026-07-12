package com.dbbaskette.issuebot.repository;

import com.dbbaskette.issuebot.model.Event;
import com.dbbaskette.issuebot.model.TrackedIssue;
import com.dbbaskette.issuebot.model.WatchedRepo;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface EventRepository extends JpaRepository<Event, Long> {

    List<Event> findAllByOrderByCreatedAtDesc(Pageable pageable);

    List<Event> findByIssueOrderByCreatedAtDesc(TrackedIssue issue, Pageable pageable);

    /**
     * ALL events for an issue, oldest first — unlike the capped/paged desc finder above,
     * this backs the loop timeline (#88), which needs every {@code PHASE_*} event across every
     * iteration (a single issue can easily log 10+ events per iteration) to derive accurate
     * per-stage segment boundaries.
     */
    List<Event> findByIssueOrderByCreatedAtAsc(TrackedIssue issue);

    void deleteByRepo(WatchedRepo repo);
}

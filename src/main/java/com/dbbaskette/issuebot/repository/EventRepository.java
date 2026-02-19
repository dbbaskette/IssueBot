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

    void deleteByRepo(WatchedRepo repo);
}

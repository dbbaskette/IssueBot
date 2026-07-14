package com.dbbaskette.issuebot.service.polling;

import com.dbbaskette.issuebot.model.IssueStatus;
import com.dbbaskette.issuebot.model.TrackedIssue;
import com.dbbaskette.issuebot.repository.TrackedIssueRepository;
import com.dbbaskette.issuebot.service.event.EventService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Recovers issues stranded {@code IN_PROGRESS} by a crash or restart.
 * <p>
 * An {@code IN_PROGRESS} issue's workflow runs on an async task; when the JVM stops, that task
 * dies but the DB row stays {@code IN_PROGRESS}. The poller only starts <em>untracked</em>
 * issues ({@code qualifiesForProcessing}), so nothing would ever pick a stranded run back up —
 * it would sit {@code IN_PROGRESS} forever, and (via the per-repo gate) also block every other
 * issue in that repo.
 * <p>
 * On startup we reset those rows to {@code PENDING}; {@link IssuePollingService#resumePendingIssues}
 * then re-dispatches them on the next poll, respecting the per-repo serialization gate (an issue
 * that died after opening a PR stays gated behind that PR, as it should). Human-wait states
 * ({@code AWAITING_*}) are deliberately left untouched — nothing was running for them.
 */
@Component
public class OrphanedRunRecovery {

    private static final Logger log = LoggerFactory.getLogger(OrphanedRunRecovery.class);

    private final TrackedIssueRepository issueRepository;
    private final EventService eventService;

    public OrphanedRunRecovery(TrackedIssueRepository issueRepository, EventService eventService) {
        this.issueRepository = issueRepository;
        this.eventService = eventService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void requeueOrphanedRuns() {
        List<TrackedIssue> orphaned = issueRepository.findByStatus(IssueStatus.IN_PROGRESS);
        if (orphaned.isEmpty()) {
            return;
        }
        for (TrackedIssue issue : orphaned) {
            log.info("Requeueing orphaned in-flight issue {} #{} — left IN_PROGRESS by a restart",
                    issue.getRepo().fullName(), issue.getIssueNumber());
            issue.setStatus(IssueStatus.PENDING);
            issue.setCurrentPhase(null);
            issueRepository.save(issue);
            eventService.log("ISSUE_RECOVERED",
                    "Requeued after a restart interrupted the run mid-flight",
                    issue.getRepo(), issue);
        }
        log.info("Requeued {} orphaned in-flight issue(s) after restart", orphaned.size());
    }
}

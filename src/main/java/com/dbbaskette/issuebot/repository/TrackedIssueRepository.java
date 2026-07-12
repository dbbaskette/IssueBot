package com.dbbaskette.issuebot.repository;

import com.dbbaskette.issuebot.model.IssueStatus;
import com.dbbaskette.issuebot.model.TrackedIssue;
import com.dbbaskette.issuebot.model.WatchedRepo;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;

public interface TrackedIssueRepository extends JpaRepository<TrackedIssue, Long> {

    Optional<TrackedIssue> findByRepoAndIssueNumber(WatchedRepo repo, int issueNumber);

    List<TrackedIssue> findByStatus(IssueStatus status);

    List<TrackedIssue> findByStatusIn(List<IssueStatus> statuses);

    long countByStatus(IssueStatus status);

    List<TrackedIssue> findByRepo(WatchedRepo repo);

    List<TrackedIssue> findByRepoAndStatus(WatchedRepo repo, IssueStatus status);

    List<TrackedIssue> findByRepoAndStatusIn(WatchedRepo repo, List<IssueStatus> statuses);

    long countByRepoAndStatusNot(WatchedRepo repo, IssueStatus status);

    /**
     * Total tracked issues for a repo regardless of status — used for the honest repo-removal
     * confirmation (#81), which states exactly how many issues (and their iterations, events,
     * and cost records) are cascade-deleted along with the repo. Distinct from
     * {@link #countByRepoAndStatusNot} (open-issue count shown in the repositories table), which
     * excludes completed issues that {@link com.dbbaskette.issuebot.controller.RepositoryController#delete}
     * still deletes.
     */
    long countByRepo(WatchedRepo repo);

    /**
     * Paged, filterable query backing the issue-queue upgrade (#87): status and repo
     * filters are the existing dropdown filters (null = "all"); {@code search} is the
     * queue's single text box, matching case-insensitively against the title OR
     * exactly against the issue number (whichever the operator meant — both are tried,
     * joined with OR, since a plain text field can't disambiguate intent up front).
     * Ordered newest-first (by id) for a stable, deterministic page boundary — there is
     * no column-sortable table here (YAGNI per the issue), just this one default order.
     */
    @Query("SELECT t FROM TrackedIssue t WHERE "
            + "(:status IS NULL OR t.status = :status) AND "
            + "(:repoId IS NULL OR t.repo.id = :repoId) AND "
            + "(:search IS NULL OR LOWER(t.issueTitle) LIKE LOWER(CONCAT('%', :search, '%')) OR CAST(t.issueNumber AS string) = :search) "
            + "ORDER BY t.id DESC")
    Page<TrackedIssue> search(@Param("status") IssueStatus status,
                              @Param("repoId") Long repoId,
                              @Param("search") String search,
                              Pageable pageable);
}

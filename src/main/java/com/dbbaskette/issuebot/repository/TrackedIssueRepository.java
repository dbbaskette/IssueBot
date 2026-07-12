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

    /**
     * Newest-first variant of {@link #findByStatus} for the Needs You inbox (#91), whose groups
     * are listed newest-first — {@code findByStatus} itself makes no ordering guarantee.
     */
    List<TrackedIssue> findByStatusOrderByIdDesc(IssueStatus status);

    /** Newest-first variant of {@link #findByStatusIn}, for the inbox's needs-human group. */
    List<TrackedIssue> findByStatusInOrderByIdDesc(List<IssueStatus> statuses);

    long countByStatus(IssueStatus status);

    /**
     * Sum of every status that blocks on the operator (#91 Needs You inbox): PR approvals,
     * plan approvals, split proposals, and needs-human (FAILED + COOLDOWN). Five cheap indexed
     * COUNTs — mirrors the {@code pendingApprovals} single-COUNT pattern each page controller
     * already runs, so this default method is the one place the sum is computed rather than
     * duplicating the five-way addition across every controller.
     */
    default long countNeedsYou() {
        return countByStatus(IssueStatus.AWAITING_APPROVAL)
                + countByStatus(IssueStatus.AWAITING_PLAN_APPROVAL)
                + countByStatus(IssueStatus.AWAITING_DECOMPOSITION)
                + countByStatus(IssueStatus.FAILED)
                + countByStatus(IssueStatus.COOLDOWN);
    }

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
     *
     * The raw term is escaped here so SQL LIKE wildcards typed by the operator match
     * literally (#87 review): "50%" finds titles containing "50%", not everything
     * starting with "50". Callers pass the raw term; only {@link #searchEscaped} sees
     * the escaped form. The escaped term is also used for the issue-number equality —
     * harmless, since a term containing % _ or \ can never equal a rendered integer.
     */
    default Page<TrackedIssue> search(IssueStatus status, Long repoId, String search, Pageable pageable) {
        return searchEscaped(status, repoId, escapeLikeWildcards(search), pageable);
    }

    /**
     * Raw JPQL behind {@link #search} — expects {@code search} already escaped via
     * {@link #escapeLikeWildcards} (the {@code ESCAPE '\'} clause makes {@code \%},
     * {@code \_}, and {@code \\} match literal characters). Not meant to be called
     * directly; use {@link #search}.
     */
    @Query("SELECT t FROM TrackedIssue t WHERE "
            + "(:status IS NULL OR t.status = :status) AND "
            + "(:repoId IS NULL OR t.repo.id = :repoId) AND "
            + "(:search IS NULL OR LOWER(t.issueTitle) LIKE LOWER(CONCAT('%', :search, '%')) ESCAPE '\\' OR CAST(t.issueNumber AS string) = :search) "
            + "ORDER BY t.id DESC")
    Page<TrackedIssue> searchEscaped(@Param("status") IssueStatus status,
                                     @Param("repoId") Long repoId,
                                     @Param("search") String search,
                                     Pageable pageable);

    /** Backslash first (it's the escape character itself), then the two LIKE wildcards. */
    static String escapeLikeWildcards(String s) {
        if (s == null) {
            return null;
        }
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}

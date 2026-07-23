package com.dbbaskette.issuebot.repository;

import com.dbbaskette.issuebot.model.DecompositionGroup;
import com.dbbaskette.issuebot.model.DecompositionGroupState;
import com.dbbaskette.issuebot.model.TrackedIssue;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DecompositionGroupRepository extends JpaRepository<DecompositionGroup, Long> {
    Set<DecompositionGroupState> UNFINISHED_STATES = Set.of(
            DecompositionGroupState.CREATING, DecompositionGroupState.WAITING,
            DecompositionGroupState.ACTIVE, DecompositionGroupState.NEEDS_ATTENTION,
            DecompositionGroupState.COMPLETING, DecompositionGroupState.ABANDONING);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select g from DecompositionGroup g where g.id = :id")
    Optional<DecompositionGroup> findByIdForUpdate(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select g from DecompositionGroup g where g.repo.id = :repoId and g.state in :states order by g.parentIssue.issueNumber asc")
    List<DecompositionGroup> findByRepoIdAndStateInForUpdate(
            @Param("repoId") Long repoId, @Param("states") Collection<DecompositionGroupState> states);

    @Query("select g from DecompositionGroup g where g.repo.id = :repoId and g.state in :states order by g.parentIssue.issueNumber asc")
    List<DecompositionGroup> findByRepoIdAndStateIn(
            @Param("repoId") Long repoId, @Param("states") Collection<DecompositionGroupState> states);

    Optional<DecompositionGroup> findByParentIssue(TrackedIssue parentIssue);
    List<DecompositionGroup> findByStateInOrderByIdAsc(Collection<DecompositionGroupState> states);

    default Optional<DecompositionGroup> findOldestUnfinishedByRepo(Long repoId) {
        return findByRepoIdAndStateIn(repoId, UNFINISHED_STATES).stream().findFirst();
    }

    default Optional<DecompositionGroup> findOwningByRepo(Long repoId) {
        return findOldestUnfinishedByRepo(repoId)
                .filter(group -> group.getState().ownsRepository());
    }
}

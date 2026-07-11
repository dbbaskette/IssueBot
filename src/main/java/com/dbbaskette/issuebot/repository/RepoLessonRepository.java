package com.dbbaskette.issuebot.repository;

import com.dbbaskette.issuebot.model.RepoLesson;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RepoLessonRepository extends JpaRepository<RepoLesson, Long> {

    List<RepoLesson> findByRepoIdOrderByCreatedAtAsc(Long repoId);

    long countByRepoId(Long repoId);

    /** The oldest unretired lesson for a repo — used for FIFO cap-eviction. */
    Optional<RepoLesson> findFirstByRepoIdOrderByCreatedAtAsc(Long repoId);
}

package com.dbbaskette.issuebot.service.workflow;

import com.dbbaskette.issuebot.model.RepoLesson;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RepoLessonQualityTest {
    @Test
    void keepsDurableRepositoryRulesAndDocumentationReferences() {
        assertThat(RepoLessonQuality.reusable("Follow docs/architecture.md for module boundaries.")).isTrue();
        assertThat(RepoLessonQuality.reusable("Run ./mvnw test before Java changes.")).isTrue();
        assertThat(RepoLessonQuality.reusable("Keep API contracts in the contracts/ module.")).isTrue();
    }

    @Test
    void rejectsObviousOneOffContext() {
        for (String text : List.of(
                "Finish issue #42 before starting the next task.",
                "Fix PR 91 by changing the handler.",
                "In this issue, replace the temporary adapter.",
                "Retry this run with a larger timeout.",
                "Review FooService.java:97 before merging.",
                "Check line 97 in FooService before merging.",
                "Use branch issuebot/42 for the next change.",
                "Read https://github.com/acme/widgets/issues/42 before coding.",
                "Return to commit 533b37c8a79f80d0 for the implementation.",
                "Read T04's plan and T03 contracts before coding.",
                "I’ll inspect the repository guidance and completed change.",
                "We will review the completed issue's notes.")) {
            assertThat(RepoLessonQuality.reusable(text)).as(text).isFalse();
        }
    }

    @Test
    void futurePromptOmitsOldOneOffRowsAndDuplicateRulesWithoutDeletingThem() {
        RepoLesson broad = new RepoLesson(1L, "Follow docs/architecture.md for module boundaries.", 1);
        RepoLesson duplicate = new RepoLesson(1L, " follow   docs/architecture.md for module boundaries. ", 2);
        RepoLesson specific = new RepoLesson(1L, "Fix issue #42 in FooService.java:97.", 42);

        assertThat(RepoLessonQuality.forPrompt(List.of(broad, duplicate, specific)))
                .containsExactly(broad.getLesson());
        assertThat(specific.getLesson()).contains("#42");
    }
}

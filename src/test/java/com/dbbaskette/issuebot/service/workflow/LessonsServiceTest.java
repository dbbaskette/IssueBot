package com.dbbaskette.issuebot.service.workflow;

import com.dbbaskette.issuebot.model.RepoLesson;
import com.dbbaskette.issuebot.model.TrackedIssue;
import com.dbbaskette.issuebot.model.WatchedRepo;
import com.dbbaskette.issuebot.repository.RepoLessonRepository;
import com.dbbaskette.issuebot.service.harness.HarnessExecutionResult;
import com.dbbaskette.issuebot.service.harness.CodingHarnessService;
import com.dbbaskette.issuebot.service.event.EventService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LessonsServiceTest {

    private CodingHarnessService harnessService;
    private RepoLessonRepository lessonRepository;
    private EventService eventService;
    private LessonsService lessonsService;

    @BeforeEach
    void setUp() {
        harnessService = mock(CodingHarnessService.class);
        lessonRepository = mock(RepoLessonRepository.class);
        eventService = mock(EventService.class);
        lessonsService = new LessonsService(harnessService, lessonRepository, eventService);
    }

    private TrackedIssue issueWithLessons(boolean enabled) {
        WatchedRepo repo = new WatchedRepo("owner", "repo");
        repo.setId(1L);
        repo.setLessonsEnabled(enabled);
        TrackedIssue issue = new TrackedIssue(repo, 42, "Fix the bug");
        issue.setId(10L);
        return issue;
    }

    private HarnessExecutionResult success(String output) {
        HarnessExecutionResult result = new HarnessExecutionResult();
        result.setSuccess(true);
        result.setOutput(output);
        return result;
    }

    @Test
    void disabledRepo_neverCallsClaudeCode() {
        TrackedIssue issue = issueWithLessons(false);

        lessonsService.capture(issue, "completed successfully", "no failures", Path.of("/tmp/repo"));

        verifyNoInteractions(harnessService);
        verifyNoInteractions(lessonRepository);
    }

    @Test
    void happyPath_threeLinesStored() {
        TrackedIssue issue = issueWithLessons(true);
        when(harnessService.executeUtility(anyString(), any(Path.class), isNull()))
                .thenReturn(success("Use constructor injection here\n"
                        + "Run tests with ./mvnw not mvn\n"
                        + "Never touch the legacy/ directory"));
        when(lessonRepository.countByRepoId(1L)).thenReturn(3L);

        lessonsService.capture(issue, "completed successfully", "no failures", Path.of("/tmp/repo"));

        ArgumentCaptor<RepoLesson> captor = ArgumentCaptor.forClass(RepoLesson.class);
        verify(lessonRepository, times(3)).save(captor.capture());
        List<RepoLesson> saved = captor.getAllValues();
        assertEquals("Use constructor injection here", saved.get(0).getLesson());
        assertEquals("Run tests with ./mvnw not mvn", saved.get(1).getLesson());
        assertEquals("Never touch the legacy/ directory", saved.get(2).getLesson());
        saved.forEach(l -> {
            assertEquals(1L, l.getRepoId());
            assertEquals(42, l.getSourceIssue());
        });
    }

    @Test
    void noneResponse_storesNothing() {
        TrackedIssue issue = issueWithLessons(true);
        when(harnessService.executeUtility(anyString(), any(Path.class), isNull()))
                .thenReturn(success("NONE"));

        lessonsService.capture(issue, "completed successfully", "no failures", Path.of("/tmp/repo"));

        verify(lessonRepository, never()).save(any());
    }

    @Test
    void moreThanThreeLines_capsAtThree() {
        TrackedIssue issue = issueWithLessons(true);
        when(harnessService.executeUtility(anyString(), any(Path.class), isNull()))
                .thenReturn(success("Lesson one\nLesson two\nLesson three\nLesson four\nLesson five"));
        when(lessonRepository.countByRepoId(1L)).thenReturn(3L);

        lessonsService.capture(issue, "completed successfully", "no failures", Path.of("/tmp/repo"));

        verify(lessonRepository, times(3)).save(any());
    }

    @Test
    void blankAndBulletMarkerLines_areCleaned() {
        TrackedIssue issue = issueWithLessons(true);
        when(harnessService.executeUtility(anyString(), any(Path.class), isNull()))
                .thenReturn(success("- Use constructor injection\n"
                        + "\n"
                        + "1. Run ./mvnw not mvn\n"
                        + "   \n"
                        + "* Never touch legacy/"));
        when(lessonRepository.countByRepoId(1L)).thenReturn(3L);

        lessonsService.capture(issue, "completed successfully", "no failures", Path.of("/tmp/repo"));

        ArgumentCaptor<RepoLesson> captor = ArgumentCaptor.forClass(RepoLesson.class);
        verify(lessonRepository, times(3)).save(captor.capture());
        List<String> lessons = captor.getAllValues().stream().map(RepoLesson::getLesson).toList();
        assertEquals(List.of("Use constructor injection", "Run ./mvnw not mvn", "Never touch legacy/"), lessons);
    }

    @Test
    void capEviction_deletesOldestBeyondThirty() {
        TrackedIssue issue = issueWithLessons(true);
        when(harnessService.executeUtility(anyString(), any(Path.class), isNull()))
                .thenReturn(success("One new lesson"));
        // After this capture's insert(s), the repo has 33 rows — 3 over the cap of 30.
        when(lessonRepository.countByRepoId(1L)).thenReturn(33L);
        RepoLesson oldest = new RepoLesson(1L, "stale lesson", 1);
        when(lessonRepository.findFirstByRepoIdOrderByCreatedAtAsc(1L)).thenReturn(Optional.of(oldest));

        lessonsService.capture(issue, "completed successfully", "no failures", Path.of("/tmp/repo"));

        verify(lessonRepository, times(3)).delete(oldest);
    }

    @Test
    void cliFailure_noExceptionEscapes_nothingStored() {
        TrackedIssue issue = issueWithLessons(true);
        when(harnessService.executeUtility(anyString(), any(Path.class), isNull()))
                .thenThrow(new RuntimeException("claude CLI crashed"));

        assertDoesNotThrow(() ->
                lessonsService.capture(issue, "completed successfully", "no failures", Path.of("/tmp/repo")));

        verify(lessonRepository, never()).save(any());
    }

    @Test
    void cliUnsuccessfulResult_noExceptionEscapes_nothingStored() {
        TrackedIssue issue = issueWithLessons(true);
        HarnessExecutionResult failure = new HarnessExecutionResult();
        failure.setSuccess(false);
        failure.setErrorMessage("utility model unavailable");
        when(harnessService.executeUtility(anyString(), any(Path.class), isNull())).thenReturn(failure);

        assertDoesNotThrow(() ->
                lessonsService.capture(issue, "completed successfully", "no failures", Path.of("/tmp/repo")));

        verify(lessonRepository, never()).save(any());
    }

    @Test
    void prompt_containsIssueNumberOutcomeAndContext() {
        String prompt = lessonsService.buildPrompt(42, "completed successfully", "no failures");
        assertTrue(prompt.contains("#42"));
        assertTrue(prompt.contains("completed successfully"));
        assertTrue(prompt.contains("no failures"));
        assertTrue(prompt.contains("NONE"));
    }
}

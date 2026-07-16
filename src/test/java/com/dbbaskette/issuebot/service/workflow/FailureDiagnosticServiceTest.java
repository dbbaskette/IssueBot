package com.dbbaskette.issuebot.service.workflow;

import com.dbbaskette.issuebot.model.*;
import com.dbbaskette.issuebot.repository.FailureDiagnosticRepository;
import com.dbbaskette.issuebot.repository.TrackedIssueRepository;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class FailureDiagnosticServiceTest {
    private final FailureDiagnosticRepository diagnostics = mock(FailureDiagnosticRepository.class);
    private final TrackedIssueRepository issues = mock(TrackedIssueRepository.class);
    private final FailureDiagnosticService service = new FailureDiagnosticService(diagnostics, issues);

    @Test void sanitizesAndTruncatesBeforePersistence() {
        TrackedIssue issue = new TrackedIssue(new WatchedRepo("acme", "widgets"), 42, "Test");
        issue.setId(1L);
        when(diagnostics.save(any())).thenAnswer(i -> i.getArgument(0));

        FailureDiagnostic saved = service.record(issue, FailureCategory.AGENT_EXIT, "Agent exited",
                "IMPLEMENTATION", "password=hunter2 /home/me/.codex/auth.json " + "x".repeat(20_000),
                "Retry with narrower guidance", FailureRetryability.RETRYABLE);

        assertThat(saved.getTechnicalDetails()).doesNotContain("hunter2", "auth.json");
        assertThat(saved.getTechnicalDetails()).hasSizeLessThanOrEqualTo(8000);
        assertThat(issue.getLastFailureReason()).isEqualTo("Agent exited");
        verify(issues).save(issue);
    }
}

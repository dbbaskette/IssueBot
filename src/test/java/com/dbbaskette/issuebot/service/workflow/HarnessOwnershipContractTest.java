package com.dbbaskette.issuebot.service.workflow;

import com.dbbaskette.issuebot.model.*;
import com.dbbaskette.issuebot.service.harness.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.assertj.core.api.Assertions.*;

class HarnessOwnershipContractTest {
    @TempDir Path directory;

    @Test void treeIdentityIncludesUntrackedChangesAndSurvivesCommit() throws Exception {
        try (Git git = Git.init().setDirectory(directory.toFile()).call()) {
            Files.writeString(directory.resolve("source.txt"), "first");
            String untracked = WorkspaceEvidenceIdentity.capture(directory);
            assertThat(untracked).startsWith("sha256:");
            git.add().addFilepattern(".").call();
            git.commit().setMessage("fixture").setAuthor("Test", "test@example.invalid").call();
            assertThat(WorkspaceEvidenceIdentity.capture(directory)).isEqualTo(untracked);
            Files.writeString(directory.resolve("source.txt"), "changed");
            assertThat(WorkspaceEvidenceIdentity.capture(directory)).isNotEqualTo(untracked);
            Files.writeString(directory.resolve("extra.txt"), "untracked source");
            String extra = WorkspaceEvidenceIdentity.capture(directory);
            Files.delete(directory.resolve("extra.txt"));
            assertThat(WorkspaceEvidenceIdentity.capture(directory)).isNotEqualTo(extra);
        }
    }

    @Test void unavailableWorkspaceIsNotAnEvidenceIdentity() {
        assertThat(WorkspaceEvidenceIdentity.capture(directory)).isEqualTo("UNAVAILABLE");
    }

    @Test void modelComparisonNormalizesProviderAliasesButDoesNotSubstituteModels() {
        assertThatThrownBy(() -> IndependentReviewPolicy.requireDistinct("codex-cli", "gpt-6-astra",
                "codex", "gpt-6-astra")).hasMessageContaining("different model");
        assertThatCode(() -> IndependentReviewPolicy.requireDistinct("codex", "gpt-6-astra",
                "codex", "gpt-5.6-terra")).doesNotThrowAnyException();
        assertThatThrownBy(() -> IndependentReviewPolicy.requireDistinct("codex", null,
                "codex", "gpt-6-astra")).hasMessageContaining("Choose implementation");
    }

    @Test void evidenceRemainsAnUntrustedClaimAndRoundTripsThroughLedger() {
        var result = new HarnessExecutionResult();
        result.setSuccess(true);
        result.setFinalResult(ImplementationOutcome.MARKER + """
                {"status":"COMPLETE","summary":"Done","checks":[{"command":"./test","result":"PASS"}],"limitations":"","evidence":{"testedTree":"abc with uncommitted edits","environment":"local fixtures","testedAt":"unknown"}}
                """);
        var mapper = new ObjectMapper();
        var outcome = ImplementationOutcome.parse(result, mapper);
        assertThat(outcome.evidence().environment()).isEqualTo("local fixtures");
        String ledger = ImplementationTurnLedger.append(null,
                ImplementationTurnLedger.Turn.from(1, outcome, result), mapper);
        assertThat(ImplementationTurnLedger.read(ledger, mapper).getFirst().outcome()).isEqualTo(outcome);
        var iteration = new Iteration(new TrackedIssue(new WatchedRepo("test", "repo"), 1, "test"), 1);
        iteration.setImplementationTurnsJson(ledger);
        var evidence = HarnessVerificationEvidence.capture(iteration, result, mapper);
        assertThat(evidence.status()).isEqualTo("REPORTED");
        assertThat(evidence.text()).contains("Claimed tested tree", "Claimed environment");
    }

    @Test void extensionRequiresExactRetainedIncompleteAttempt() {
        var issue = new TrackedIssue(new WatchedRepo("test", "repo"), 1, "test");
        issue.setStatus(IssueStatus.FAILED);
        issue.setCurrentPhase("IMPLEMENTATION");
        issue.setCurrentIteration(1);
        issue.setBranchName("issuebot/1");
        var iteration = new Iteration(issue, 1, issue.getWorkflowRun(), null);
        iteration.setImplementationHandoffLimit(8);
        iteration.setImplementationStopReason("HANDOFF_LIMIT");
        iteration.setImplementationOutcome("CONTINUE");
        iteration.setClaudeSessionId("retained");
        assertThat(ImplementationLimitRecovery.available(issue, iteration)).isTrue();
        issue.setStatus(IssueStatus.IN_PROGRESS);
        assertThat(ImplementationLimitRecovery.available(issue, iteration)).isFalse();
        issue.setStatus(IssueStatus.FAILED);
        iteration.setImplementationOutcome("COMPLETE");
        assertThat(ImplementationLimitRecovery.available(issue, iteration)).isFalse();
    }
}

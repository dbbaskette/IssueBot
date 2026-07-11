package com.dbbaskette.issuebot.service.workflow;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LocalVerificationServiceTest {

    private final LocalVerificationService service = new LocalVerificationService();
    private final Path repoPath = Path.of(System.getProperty("java.io.tmpdir"));

    @Test
    void run_allCommandsPass_returnsSuccessWithCapturedOutput() {
        List<String> lines = new ArrayList<>();
        LocalVerificationService.Result result = service.run(repoPath,
                List.of("true", "echo hi"), 1, lines::add);

        assertTrue(result.success());
        assertNull(result.failedCommand());
        assertTrue(result.output().contains("hi"));
        assertTrue(lines.stream().anyMatch(l -> l.contains("hi")));
    }

    @Test
    void run_failingCommand_namesTheFailingCommand() {
        LocalVerificationService.Result result = service.run(repoPath,
                List.of("false"), 1, null);

        assertFalse(result.success());
        assertEquals("false", result.failedCommand());
    }

    @Test
    void run_multiCommand_stopsAtFirstFailure() {
        List<String> lines = new ArrayList<>();
        LocalVerificationService.Result result = service.run(repoPath,
                List.of("echo first", "false", "echo third"), 1, lines::add);

        assertFalse(result.success());
        assertEquals("false", result.failedCommand());
        assertTrue(lines.stream().anyMatch(l -> l.contains("first")));
        assertTrue(lines.stream().noneMatch(l -> l.contains("third")));
    }

    @Test
    void run_noCommands_returnsSuccess() {
        LocalVerificationService.Result result = service.run(repoPath, List.of(), 1, null);

        assertTrue(result.success());
    }

    @Test
    void parseCommands_skipsBlankLinesAndComments() {
        String clob = "./mvnw -q verify\n\n# a comment\n  \nnpm run lint\n# another\n";
        List<String> commands = LocalVerificationService.parseCommands(clob);

        assertEquals(List.of("./mvnw -q verify", "npm run lint"), commands);
    }

    @Test
    void parseCommands_nullOrBlank_returnsEmptyList() {
        assertTrue(LocalVerificationService.parseCommands(null).isEmpty());
        assertTrue(LocalVerificationService.parseCommands("   \n  \n").isEmpty());
    }

    @Test
    void run_timeout_isTreatedAsFailure() {
        LocalVerificationService.Result result = service.run(repoPath,
                List.of("sleep 5"), 0, null); // 0-minute timeout — fails immediately... but waitFor(0, MINUTES) treats 0 as "don't wait", may finish=false

        assertFalse(result.success());
        assertEquals("sleep 5", result.failedCommand());
        assertTrue(result.output().toLowerCase().contains("timed out"));
    }
}

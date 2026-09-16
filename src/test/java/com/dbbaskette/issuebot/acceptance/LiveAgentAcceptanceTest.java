package com.dbbaskette.issuebot.acceptance;

import com.dbbaskette.issuebot.config.IssueBotProperties;
import com.dbbaskette.issuebot.model.*;
import com.dbbaskette.issuebot.repository.*;
import com.dbbaskette.issuebot.service.claude.*;
import com.dbbaskette.issuebot.service.codex.*;
import com.dbbaskette.issuebot.service.harness.*;
import com.dbbaskette.issuebot.service.workflow.WorkflowCancellationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** Opt-in subscription-backed tests. No application polling, HTTP server or GitHub client is loaded. */
@DataJpaTest(showSql = false)
@Import(HarnessInputService.class)
@TestPropertySource(properties = {"spring.config.import=", "issuebot.github.token=acceptance-fixture-only",
        "spring.datasource.url=jdbc:h2:mem:acceptance;DB_CLOSE_DELAY=-1"})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@EnabledIfSystemProperty(named = "issuebot.acceptance.live", matches = "true")
class LiveAgentAcceptanceTest {
    @Autowired HarnessInputService input;
    @Autowired TrackedIssueRepository issues;
    @Autowired WatchedRepoRepository repos;
    private final ObjectMapper json = new ObjectMapper();
    private final WorkflowCancellationService cancellation = new WorkflowCancellationService();
    private final List<String> assertions = new ArrayList<>();
    private final Map<String,Object> evidence = new LinkedHashMap<>();
    private Path root, workspace;
    private Long issueId;
    private CodingHarnessAdapter adapter;
    private ScheduledExecutorService responder;
    private final AtomicReference<Throwable> responderError = new AtomicReference<>();

    @Test void coding() throws Exception { scenario("coding"); }
    @Test void input() throws Exception { scenario("input"); }
    @Test void review() throws Exception { scenario("review"); }

    private void scenario(String kind) throws Exception {
        String status = "FAIL";
        try {
            setup();
            String model = property("model");
            String reviewMarker = UUID.randomUUID().toString();
            HarnessRole role = HarnessRole.IMPLEMENTATION;
            String prompt;
            if (kind.equals("review")) {
                String reviewer = property("reviewModel");
                if (reviewer.isBlank() || reviewer.equals(model)) throw new Blocked("A distinct --review-model is required");
                resolve(model); resolve(reviewer);
                assertions.add("distinct-review-model");
                model = reviewer; role = HarnessRole.FINAL_REVIEW;
                Files.writeString(workspace.resolve("sum.cjs"), "module.exports = values => values.reduce((a,b)=>a+b,0);\nmodule.exports.fixtureMarker='" + reviewMarker + "';\n");
                prompt = "Read sum.cjs and review whether it sums numeric arrays including empty arrays and negative values. "
                        + "Do not modify any files. Use only read-only inspection. Return exactly ACCEPTANCE_REVIEW: "
                        + "{\"passed\":true,\"fixtureMarker\":\"the literal marker found in the file\"} if correct, otherwise passed:false.";
            } else if (kind.equals("input")) {
                prompt = "Before doing anything else, use the native structured question tool (AskUserQuestion or request_user_input) "
                        + "to ask exactly: Choose fixture color. Offer blue and green. Do not answer it yourself or ask in plain text. "
                        + "After the operator responds, write only the chosen color to color.txt using the file editing tool. Do not run commands.";
            } else {
                prompt = "Implement sum.cjs so the exported function sums an array of numbers (empty array is zero). "
                        + "Use the file editing tool. Run node fixture.test.cjs, fix any failures, then stop. "
                        + "Do not alter fixture.test.cjs or AGENTS.md. No dependencies, network, git commands or other files are needed.";
            }
            String before = treeHash(workspace);
            String testBefore = Files.readString(workspace.resolve("fixture.test.cjs"));
            HarnessModel selected = resolve(model);
            String effort = selected.resolveReasoning(property("reasoning"));
            evidence.put("model", model); evidence.put("reasoning", effort);
            evidence.put("cliVersion", command(workspace, List.of(adapter.id(), "--version")));
            startResponder();
            var agentCheck = new java.util.concurrent.atomic.AtomicBoolean();
            var toolEvents = new ArrayList<String>();
            var result = adapter.executeSubscription(new HarnessExecutionRequest(role, prompt, workspace,
                    model, effort, null, issueId), event -> {
                try {
                    var value = json.readTree(event);
                    var item = value.path("params").path("item");
                    if (Set.of("commandExecution", "fileChange", "mcpToolCall", "dynamicToolCall").contains(item.path("type").asText()))
                        toolEvents.add(safe(item.toString()));
                    if (item.path("type").asText().equals("commandExecution")
                            && item.path("command").asText().contains("node fixture.test.cjs")) agentCheck.set(true);
                    for (var block : value.path("message").path("content"))
                        if (block.path("type").asText().equals("tool_use") && block.path("name").asText().equals("Bash")
                                && block.path("input").path("command").asText().equals("node fixture.test.cjs")) agentCheck.set(true);
                } catch (Exception ignored) { /* Plain-text output is not tool evidence. */ }
            });
            if (responderError.get() != null) throw new AssertionError("Fixture responder failed", responderError.get());
            evidence.put("durationMs", result.getDurationMs());
            evidence.put("toolEvents", toolEvents.stream().limit(20).toList());
            evidence.put("summary", safe(result.getFinalResultOrOutput()));
            evidence.put("sessionId", Objects.toString(result.getSessionId(), ""));
            if (!result.isSuccess()) throw new Blocked("Native harness did not finish successfully: " + safe(result.getErrorMessage()));
            assertThat(result.getSessionId()).as("fresh native session").isNotBlank();
            if (kind.equals("coding")) {
                assertions.add("fresh-session");
                assertThat(agentCheck.get()).as("agent invoked fixture checks").isTrue();
                assertions.add("agent-check-invoked");
                assertThat(Files.readString(workspace.resolve("fixture.test.cjs"))).isEqualTo(testBefore);
                // Assertions live outside the agent workspace and are never taken from agent output.
                Path verifier = Path.of("scripts/acceptance/verify-sum.cjs").toAbsolutePath();
                Path implementation = workspace.resolve("sum.cjs");
                command(root, List.of("node", "--permission", "--allow-fs-read=" + verifier,
                        "--allow-fs-read=" + implementation, verifier.toString(), implementation.toString()));
                assertions.add("trusted-fixture-tests");
            } else if (kind.equals("input")) {
                var questions = input.history(issueId).stream().filter(r -> {
                    try {return NativeInputProtocol.isQuestion(r.getMethod(), json.readTree(r.getPayload()));}
                    catch (Exception e) {return false;}
                }).toList();
                if (questions.isEmpty()) throw new Blocked("No native question observed; plain-text questions do not satisfy live input coverage");
                assertions.add("native-question-observed");
                assertThat(questions).allSatisfy(r -> {
                    assertThat(r.getState()).isEqualTo(HarnessInputRequest.State.DELIVERED);
                    assertThat(r.getIterationNum()).isEqualTo(1);
                    assertThat(r.getSessionId()).isEqualTo(result.getSessionId());
                });
                assertThat(Files.readString(workspace.resolve("color.txt")).trim()).isEqualTo("blue");
                assertions.add("answer-delivered");
                assertThat(issues.findById(issueId).orElseThrow().getCurrentIteration()).isEqualTo(1);
                assertions.add("same-attempt");
            } else {
                String output = Objects.toString(result.getFinalResultOrOutput(), "");
                var verdict = java.util.regex.Pattern.compile("ACCEPTANCE_REVIEW:\\s*(\\{[^}]*})").matcher(output);
                assertThat(verdict.find()).as("structured review verdict").isTrue();
                assertThat(json.readTree(verdict.group(1)).path("passed").asBoolean()).isTrue();
                assertThat(json.readTree(verdict.group(1)).path("fixtureMarker").asText()).as("reviewer inspected this fixture").isEqualTo(reviewMarker);
                assertions.add("review-verdict");
                assertThat(treeHash(workspace)).as("review must not change the tree").isEqualTo(before);
                assertions.add("tree-unchanged");
            }
            status = "PASS";
        } catch (Blocked e) {
            status = "BLOCKED"; evidence.put("reason", safe(e.getMessage())); throw e;
        } catch (Throwable e) {
            evidence.put("reason", safe(e.getMessage())); throw e;
        } finally {
            if (responder != null) {responder.shutdownNow(); responder.awaitTermination(5, TimeUnit.SECONDS);}
            if (issueId != null) cancellation.requestCancel(issueId);
            evidence.put("status", status); evidence.put("assertions", assertions);
            evidence.put("scenario", kind); evidence.put("finishedAt", java.time.Instant.now().toString());
            String destination = property("evidence");
            if (!destination.isBlank()) {
                Path file = Path.of(destination).toAbsolutePath();
                if (root != null && file.startsWith(root)) throw new IllegalArgumentException("Evidence must be outside the fixture");
                Files.createDirectories(file.getParent());
                json.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), evidence);
            }
            if (root != null && Files.isRegularFile(root.resolve(".acceptance-owned"))) {
                try (var paths = Files.walk(root)) {
                    for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
                }
            }
        }
    }

    private void setup() throws Exception {
        String harness = property("harness");
        if (!Set.of("codex", "claude").contains(harness)) throw new Blocked("Choose codex or claude explicitly");
        var properties = new IssueBotProperties();
        properties.getCodexCli().setTimeoutMinutes(2); properties.getCodexCli().setReviewTimeoutMinutes(2);
        properties.getClaudeCode().setTimeoutMinutes(2); properties.getClaudeCode().setReviewTimeoutMinutes(2);
        var parser = new StreamJsonParser(json);
        adapter = harness.equals("codex")
                ? new CodexHarnessAdapter(new CodexCliService(properties, new CodexJsonParser(json), cancellation, issues), new CodexModelCatalog(json))
                : new ClaudeHarnessAdapter(new ClaudeCodeService(properties, parser, cancellation));
        ReflectionTestUtils.setField(adapter, "interactive", new InteractiveHarnessRunner(input, json, properties, issues, cancellation, parser));
        evidence.put("harness", harness); evidence.put("permissions", "ASK");
        if (!adapter.probeCliAvailability().ready()) throw new Blocked("CLI unavailable");
        if (!adapter.probeSubscriptionAuthentication().ready()) throw new Blocked("Subscription authentication is not ready; API billing is not permitted");
        assertions.add("subscription-auth");
        root = Files.createTempDirectory("issuebot-acceptance-").toRealPath();
        Files.writeString(root.resolve(".acceptance-owned"), "IssueBot disposable acceptance fixture\n");
        workspace = Files.createDirectory(root.resolve("workspace"));
        Files.writeString(workspace.resolve("AGENTS.md"), "Disposable acceptance fixture. Work only here. No network, dependencies, subagents or external files. Do not change this file.\n");
        Files.writeString(workspace.resolve("sum.cjs"), "module.exports = values => 0;\n");
        Files.writeString(workspace.resolve("fixture.test.cjs"), "const a=require('node:assert/strict'),sum=require('./sum.cjs');a.equal(sum([1,2,3]),6);a.equal(sum([]),0);\n");
        command(workspace, List.of("git", "-c", "init.templateDir=", "init", "-q"));
        var repo = new WatchedRepo("acceptance-fixture", UUID.randomUUID().toString());
        repo.setExecutionPermissions(ExecutionPermissions.ASK);
        repo = repos.saveAndFlush(repo);
        var issue = new TrackedIssue(repo, 1, "Disposable acceptance fixture");
        issue.setStatus(IssueStatus.IN_PROGRESS); issue.setCurrentIteration(1);
        issueId = issues.saveAndFlush(issue).getId();
    }

    private HarnessModel resolve(String model) {
        return adapter.models().stream().filter(m -> m.id().equals(model)).findFirst()
                .orElseThrow(() -> new Blocked("Model is not in the installed harness catalog: " + model));
    }

    private void startResponder() {
        responder = Executors.newSingleThreadScheduledExecutor();
        long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(3);
        responder.scheduleWithFixedDelay(() -> {
            try {
                if (System.nanoTime() > deadline) {cancellation.requestCancel(issueId); return;}
                for (var request : input.history(issueId)) {
                    if (request.getState() != HarnessInputRequest.State.WAITING) continue;
                    var payload = json.readTree(request.getPayload());
                    Map<String,String> answers = new HashMap<>();
                    if (NativeInputProtocol.isQuestion(request.getMethod(), payload)) {
                        for (var q : NativeInputProtocol.questions(request.getMethod(), payload)) {
                            if (!q.text().equals("Choose fixture color")) throw new Blocked("Unexpected fixture question; not answering automatically");
                            answers.put(q.id(), "blue");
                        }
                    }
                    // Only native file tools for the two expected outputs are approved. Shell edits,
                    // network/permissions requests and unknown actions are denied, never escalated.
                    boolean allow = FixtureApproval.allows(workspace, request.getMethod(), payload);
                    input.answer(issueId, request.getId(), NativeInputProtocol.response(request.getMethod(), payload, allow ? "allow" : "deny", answers).toString());
                }
            } catch (Throwable e) {responderError.compareAndSet(null, e); cancellation.requestCancel(issueId);}
        }, 0, 100, TimeUnit.MILLISECONDS);
    }

    private String command(Path cwd, List<String> args) throws Exception {
        Path log = Files.createTempFile("acceptance-command-", ".log");
        Process process = null;
        try {
            var builder = new ProcessBuilder(args).directory(cwd.toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
            builder.environment().keySet().removeIf(k -> k.toUpperCase(Locale.ROOT).matches(".*(TOKEN|PASSWORD|SECRET|CREDENTIAL|PRIVATE_KEY|API_KEY|ASKPASS|SSH_AUTH_SOCK).*"));
            process = builder.start();
            if (!process.waitFor(15, TimeUnit.SECONDS)) throw new Blocked("Fixture command timed out: " + args.getFirst());
            assertThat(process.exitValue()).as("fixture command %s", args.getFirst()).isZero();
            return Files.readString(log).trim();
        } finally {WorkflowCancellationService.terminateProcessTree(process); Files.deleteIfExists(log);}
    }

    private String treeHash(Path directory) throws Exception {
        var hash = MessageDigest.getInstance("SHA-256");
        try (var paths = Files.walk(directory)) {
            for (Path p : paths.sorted().toList()) {
                hash.update(directory.relativize(p).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                if (Files.isSymbolicLink(p)) hash.update(Files.readSymbolicLink(p).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                else if (Files.isRegularFile(p)) hash.update(Files.readAllBytes(p));
            }
        }
        return HexFormat.of().formatHex(hash.digest());
    }
    private static String property(String name) {return System.getProperty("issuebot.acceptance." + name, "");}
    private static String safe(String text) {
        String value = Objects.toString(text, "No diagnostic supplied");
        value = value.replaceAll("(?:gh[pousr]_[\\w]+|github_pat_[\\w]+|sk-[\\w-]+)", "[REDACTED]");
        return value.substring(0, Math.min(value.length(), 1500));
    }
    private static class Blocked extends RuntimeException {Blocked(String message) {super(message);}}
}

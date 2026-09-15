package com.dbbaskette.issuebot.service.workflow;

import com.dbbaskette.issuebot.service.harness.HarnessExecutionResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/** A provider-neutral claim about plan progress; IssueBot's gates still establish correctness. */
public record ImplementationOutcome(Status status, String summary, List<Check> checks,
                                    String limitations, Evidence evidence) {
    public ImplementationOutcome(Status status, String summary, List<Check> checks, String limitations) {
        this(status, summary, checks, limitations, null);
    }
    /** Claims made by the harness, distinct from IssueBot's observation of the handoff tree. */
    public record Evidence(String testedTree, String environment, String testedAt) {}
    public enum Status { COMPLETE, CONTINUE, BLOCKED }
    public record Check(String command, String result) {}

    public static final String MARKER = "ISSUEBOT_IMPLEMENTATION_V1: ";
    private static final int MAX_CHECK_COMMAND_LENGTH = 2000;
    private static final int MAX_CHECK_RESULT_LENGTH = 500;

    public static ImplementationOutcome parse(HarnessExecutionResult result, ObjectMapper mapper) {
        if (result == null || !result.isSuccess()) {
            throw new IllegalArgumentException("Coding harness did not complete its CLI turn");
        }
        String answer = result.getFinalResultOrOutput();
        if (answer == null) throw new IllegalArgumentException("Coding harness returned no final answer");
        String json = null;
        for (String line : answer.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.startsWith(MARKER)) {
                if (json != null) throw new IllegalArgumentException("Coding harness returned multiple outcome markers");
                json = trimmed.substring(MARKER.length());
            }
        }
        if (json == null) throw new IllegalArgumentException("Coding harness omitted " + MARKER.trim());
        try {
            JsonNode root = mapper.readTree(json);
            if (!root.isObject() || (root.size() != 4 && !(root.size() == 5 && root.has("evidence")))
                    || !root.has("status") || !root.has("summary")
                    || !root.has("checks") || !root.has("limitations")) {
                throw new IllegalArgumentException("Outcome must contain status, summary, checks, and limitations");
            }
            Status status = Status.valueOf(root.path("status").asText());
            String summary = root.path("summary").asText("").strip();
            if (summary.isEmpty() || summary.length() > 2000) {
                throw new IllegalArgumentException("Outcome summary must be 1–2000 characters");
            }
            JsonNode checkNodes = root.path("checks");
            if (!checkNodes.isArray() || checkNodes.size() > 30) {
                throw new IllegalArgumentException("Outcome checks must be an array of at most 30 entries");
            }
            List<Check> checks = new ArrayList<>();
            for (int index = 0; index < checkNodes.size(); index++) {
                JsonNode check = checkNodes.get(index);
                int number = index + 1;
                if (!check.isObject() || check.size() != 2
                        || !check.has("command") || !check.has("result")
                        || !check.path("command").isTextual() || !check.path("result").isTextual()) {
                    throw new IllegalArgumentException("Check " + number
                            + " must contain only text command and result fields");
                }
                String command = check.path("command").asText("").strip();
                String checkResult = check.path("result").asText("").strip();
                if (command.isEmpty()) throw new IllegalArgumentException("Check " + number + " command is empty");
                if (checkResult.isEmpty()) throw new IllegalArgumentException("Check " + number + " result is empty");
                if (command.length() > MAX_CHECK_COMMAND_LENGTH) throw new IllegalArgumentException(
                        "Check " + number + " command is " + command.length()
                                + " characters (maximum " + MAX_CHECK_COMMAND_LENGTH + ")");
                if (checkResult.length() > MAX_CHECK_RESULT_LENGTH) throw new IllegalArgumentException(
                        "Check " + number + " result is " + checkResult.length()
                                + " characters (maximum " + MAX_CHECK_RESULT_LENGTH + ")");
                checks.add(new Check(command, checkResult));
            }
            JsonNode limitationNode = root.path("limitations");
            if (!limitationNode.isTextual() || limitationNode.asText().length() > 2000) {
                throw new IllegalArgumentException("Outcome limitations must be text up to 2000 characters");
            }
            Evidence evidence = null;
            if (root.has("evidence")) {
                JsonNode node = root.get("evidence");
                if (!node.isObject() || node.size() != 3) {
                    throw new IllegalArgumentException("Evidence must contain testedTree, environment, and testedAt");
                }
                for (String field : List.of("testedTree", "environment", "testedAt")) {
                    if (!node.path(field).isTextual() || node.path(field).asText().length() > 2000) {
                        throw new IllegalArgumentException("Evidence " + field + " must be text up to 2000 characters");
                    }
                }
                evidence = new Evidence(node.path("testedTree").asText().strip(),
                        node.path("environment").asText().strip(), node.path("testedAt").asText().strip());
            }
            return new ImplementationOutcome(status, summary, List.copyOf(checks),
                    limitationNode.asText().strip(), evidence);
        } catch (IllegalArgumentException invalid) {
            throw invalid;
        } catch (Exception invalid) {
            throw new IllegalArgumentException("Coding harness returned malformed outcome JSON", invalid);
        }
    }

    public static String promptContract() {
        return "\n## Required implementation outcome\n"
                + "Own the full issue implementation loop, including the approved plan when supplied: implement coherent slices, run focused checks, "
                + "repair failures, and continue until every acceptance criterion is met or an external blocker "
                + "requires operator guidance. Do not finish a turn with a progress-only narrative. "
                + "End your final message with exactly one single-line marker of this form:\n"
                + MARKER + "{\"status\":\"COMPLETE|CONTINUE|BLOCKED\",\"summary\":\"what is done and why independent checks should pass, or what is blocked\","
                + "\"checks\":[{\"command\":\"exact command\",\"result\":\"PASS or failure summary\"}],"
                + "\"limitations\":\"remaining limitations or empty string\","
                + "\"evidence\":{\"testedTree\":\"tested commit/tree and any uncommitted or untracked changes\","
                + "\"environment\":\"relevant runtime, dependencies and fixture limitations\","
                + "\"testedAt\":\"actual check timestamp, or unknown\"}}\n"
                + "Replace the status placeholder with exactly one of COMPLETE, CONTINUE, or BLOCKED; "
                + "the example's vertical bars are not a valid status. Put the JSON on one line. "
                + "Each check must have only text command and result fields; commands may be up to "
                + MAX_CHECK_COMMAND_LENGTH + " characters and results up to " + MAX_CHECK_RESULT_LENGTH
                + " characters. Commands must be exact and runnable; put a long command in a repository test script "
                + "and report the short invocation. Summarize lengthy results, not commands. "
                + "Use COMPLETE only when the assigned requirements are implemented, appropriate local checks pass (or no meaningful check exists and limitations explains why), "
                + "and you have an evidence-based reason to expect IssueBot's independent gates to pass. "
                + "State any untested risk or uncertainty in limitations; "
                + "IssueBot saves your commands and results for independent review; it does not rerun local tests. "
                + "Use CONTINUE if work remains and this session can continue. Use BLOCKED for missing "
                + "dependencies, permissions, or information that cannot be resolved within this sandbox after safe, relevant diagnosis and recovery. "
                + "Do not stop at the first environment error: inspect documented fixture setup, network/dependency availability, "
                + "and Docker/database reachability when relevant, and attempt permitted remedies. Report those attempts and the precise "
                + "operator action needed if still blocked; do not weaken tests, substitute pinned dependencies, or bypass permissions. "
                + "Do not claim COMPLETE because the CLI turn ended.\n";
    }
}

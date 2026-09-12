package com.dbbaskette.issuebot.service.workflow;

import com.dbbaskette.issuebot.service.harness.HarnessExecutionResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/** A provider-neutral claim about plan progress; IssueBot's gates still establish correctness. */
public record ImplementationOutcome(Status status, String summary, List<Check> checks,
                                    String limitations) {
    public enum Status { COMPLETE, CONTINUE, BLOCKED }
    public record Check(String command, String result) {}

    public static final String MARKER = "ISSUEBOT_IMPLEMENTATION_V1: ";

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
            if (!root.isObject() || root.size() != 4
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
            for (JsonNode check : checkNodes) {
                String command = check.path("command").asText("").strip();
                String checkResult = check.path("result").asText("").strip();
                if (!check.isObject() || check.size() != 2 || command.isEmpty()
                        || checkResult.isEmpty() || command.length() > 500 || checkResult.length() > 500) {
                    throw new IllegalArgumentException("Each check needs a bounded command and result");
                }
                checks.add(new Check(command, checkResult));
            }
            JsonNode limitationNode = root.path("limitations");
            if (!limitationNode.isTextual() || limitationNode.asText().length() > 2000) {
                throw new IllegalArgumentException("Outcome limitations must be text up to 2000 characters");
            }
            return new ImplementationOutcome(status, summary, List.copyOf(checks),
                    limitationNode.asText().strip());
        } catch (IllegalArgumentException invalid) {
            throw invalid;
        } catch (Exception invalid) {
            throw new IllegalArgumentException("Coding harness returned malformed outcome JSON", invalid);
        }
    }

    public static String promptContract() {
        return "\n## Required implementation outcome\n"
                + "Own the full approved-plan implementation loop: implement coherent slices, run focused checks, "
                + "repair failures, and continue until every acceptance criterion is met or an external blocker "
                + "requires operator guidance. Do not finish a turn with a progress-only narrative. "
                + "End your final message with exactly one single-line marker of this form:\n"
                + MARKER + "{\"status\":\"COMPLETE|CONTINUE|BLOCKED\",\"summary\":\"what is done or blocked\","
                + "\"checks\":[{\"command\":\"exact command\",\"result\":\"PASS or failure summary\"}],"
                + "\"limitations\":\"remaining limitations or empty string\"}\n"
                + "Replace the status placeholder with exactly one of COMPLETE, CONTINUE, or BLOCKED; "
                + "the example's vertical bars are not a valid status. Put the JSON on one line. "
                + "Use COMPLETE only when the plan is implemented and your appropriate focused checks pass; "
                + "IssueBot will still run trusted final verification and an independent review. "
                + "Use CONTINUE if work remains and this session can continue. Use BLOCKED for missing "
                + "dependencies, permissions, or information that cannot be resolved within this sandbox. "
                + "Do not claim COMPLETE because the CLI turn ended.\n";
    }
}

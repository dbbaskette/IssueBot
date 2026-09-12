package com.dbbaskette.issuebot.service.workflow;

import com.dbbaskette.issuebot.service.harness.HarnessExecutionResult;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/** Compact, durable evidence for the coding harness's inner turns. */
public final class ImplementationTurnLedger {
    private ImplementationTurnLedger() {}

    public record Turn(int ordinal, ImplementationOutcome outcome, String sessionId,
                       String finalAnswer, List<String> filesChanged, long inputTokens,
                       long outputTokens, BigDecimal costUsd, String model, long durationMs) {
        public static Turn from(int ordinal, ImplementationOutcome outcome, HarnessExecutionResult result) {
            String answer = result.getFinalResultOrOutput();
            return new Turn(ordinal, outcome, result.getSessionId(),
                    answer == null ? "" : answer.substring(0, Math.min(answer.length(), 8000)),
                    result.getFilesChanged() == null ? List.of() : List.copyOf(result.getFilesChanged()),
                    result.getInputTokens(), result.getOutputTokens(), result.getCostUsd(),
                    result.getModel(), result.getDurationMs());
        }
    }

    public static List<Turn> read(String json, ObjectMapper mapper) {
        if (json == null || json.isBlank()) return List.of();
        try {
            List<Turn> rows = mapper.readValue(json, new TypeReference<>() {});
            if (rows == null || rows.size() > 100) throw new IllegalArgumentException("Invalid implementation turn ledger");
            return List.copyOf(rows);
        } catch (Exception invalid) {
            throw new IllegalStateException("Cannot restore implementation turn ledger", invalid);
        }
    }

    public static String append(String json, Turn turn, ObjectMapper mapper) {
        List<Turn> rows = new ArrayList<>(read(json, mapper));
        if (turn.ordinal() != rows.size() + 1) throw new IllegalStateException("Implementation turn ordinal changed");
        rows.add(turn);
        try { return mapper.writeValueAsString(rows); }
        catch (Exception invalid) { throw new IllegalStateException("Cannot persist implementation turn", invalid); }
    }

    /** Reconstitute all tokens and cost after a restart; only the final turn's text is shown. */
    public static HarnessExecutionResult aggregate(List<Turn> rows, boolean success, String error) {
        HarnessExecutionResult result = new HarnessExecutionResult();
        result.setSuccess(success);
        result.setErrorMessage(error);
        if (rows.isEmpty()) return result;
        long input = 0, output = 0, duration = 0;
        BigDecimal cost = BigDecimal.ZERO;
        boolean costKnown = true;
        LinkedHashSet<String> files = new LinkedHashSet<>();
        for (Turn row : rows) {
            input += row.inputTokens();
            output += row.outputTokens();
            duration += row.durationMs();
            files.addAll(row.filesChanged() == null ? List.of() : row.filesChanged());
            if (row.costUsd() == null) costKnown = false;
            else cost = cost.add(row.costUsd());
        }
        Turn last = rows.get(rows.size() - 1);
        result.setInputTokens(input);
        result.setOutputTokens(output);
        result.setDurationMs(duration);
        result.setCostUsd(costKnown ? cost : null);
        result.setFilesChanged(List.copyOf(files));
        result.setModel(last.model());
        result.setSessionId(last.sessionId());
        result.setOutput(last.finalAnswer());
        result.setFinalResult(last.finalAnswer());
        return result;
    }
}

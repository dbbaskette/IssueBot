package com.dbbaskette.issuebot.service.claude;

import java.util.ArrayList;
import java.util.List;

public class ClaudeCodeResult {

    private boolean success;
    private String output;
    // The terminal stream-json `result` event's text ONLY — the model's final synthesized
    // answer, without the intermediate "let me look at X" narration that `output` accumulates
    // across every assistant turn. Use this when the CLI's answer is itself the deliverable
    // (e.g. a plan document), not for implementation runs (where files, not text, are the output).
    private String finalResult;
    private List<String> filesChanged = new ArrayList<>();
    private long inputTokens;
    private long outputTokens;
    private String model;
    private long durationMs;
    private String errorMessage;
    private boolean timedOut;
    private java.math.BigDecimal costUsd; // CLI-reported total_cost_usd; null if absent
    private String sessionId; // captured from stream-json system/init or result events (#67)

    public boolean isSuccess() { return success; }
    public void setSuccess(boolean success) { this.success = success; }

    public String getOutput() { return output; }
    public void setOutput(String output) { this.output = output; }

    public String getFinalResult() { return finalResult; }
    public void setFinalResult(String finalResult) { this.finalResult = finalResult; }

    /**
     * The final synthesized answer when present, else the full transcript. Prefer this whenever
     * the CLI's textual answer is itself the deliverable (e.g. a plan document) so intermediate
     * exploration narration doesn't leak in; the fallback ensures a plan is never silently lost
     * if a run somehow produced no terminal result event.
     */
    public String getFinalResultOrOutput() {
        return (finalResult != null && !finalResult.isBlank()) ? finalResult : output;
    }

    public List<String> getFilesChanged() { return filesChanged; }
    public void setFilesChanged(List<String> filesChanged) { this.filesChanged = filesChanged; }

    public long getInputTokens() { return inputTokens; }
    public void setInputTokens(long inputTokens) { this.inputTokens = inputTokens; }

    public long getOutputTokens() { return outputTokens; }
    public void setOutputTokens(long outputTokens) { this.outputTokens = outputTokens; }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public long getDurationMs() { return durationMs; }
    public void setDurationMs(long durationMs) { this.durationMs = durationMs; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public boolean isTimedOut() { return timedOut; }
    public void setTimedOut(boolean timedOut) { this.timedOut = timedOut; }

    public java.math.BigDecimal getCostUsd() { return costUsd; }
    public void setCostUsd(java.math.BigDecimal costUsd) { this.costUsd = costUsd; }

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    @Override
    public String toString() {
        return "ClaudeCodeResult{success=%s, files=%d, tokens=%d/%d, duration=%dms}"
                .formatted(success, filesChanged.size(), inputTokens, outputTokens, durationMs);
    }
}

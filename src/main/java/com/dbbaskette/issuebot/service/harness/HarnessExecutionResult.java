package com.dbbaskette.issuebot.service.harness;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/** Mutable execution output populated incrementally by harness stream parsers. */
public class HarnessExecutionResult {

    private boolean success;
    private String output;
    private String finalResult;
    private List<String> filesChanged = new ArrayList<>();
    private long inputTokens;
    private long outputTokens;
    private String model;
    private long durationMs;
    private String errorMessage;
    private boolean timedOut;
    private BigDecimal costUsd;
    private String sessionId;

    public boolean isSuccess() { return success; }
    public void setSuccess(boolean success) { this.success = success; }

    public String getOutput() { return output; }
    public void setOutput(String output) { this.output = output; }

    public String getFinalResult() { return finalResult; }
    public void setFinalResult(String finalResult) { this.finalResult = finalResult; }

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

    public BigDecimal getCostUsd() { return costUsd; }
    public void setCostUsd(BigDecimal costUsd) { this.costUsd = costUsd; }

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    @Override
    public String toString() {
        return "HarnessExecutionResult{success=%s, files=%d, tokens=%d/%d, duration=%dms}"
                .formatted(success, filesChanged.size(), inputTokens, outputTokens, durationMs);
    }
}

package com.dbbaskette.issuebot.service.history;

/** Structured transition identity only. Never pass prompts, guidance, logs, or provider payloads. */
public record DecisionDraft(
        Long issueId, Long repoId, String workflowRun, String sourceKey,
        Actor actor, Action action, Outcome outcome, Reason reason,
        Long planVersionId, Long iterationId, Long stageApprovalId,
        Long guidanceId, Integer prNumber) {
    public enum Actor { OPERATOR, AUTOMATION, LEGACY_UNKNOWN }
    public enum Action { APPROVE, REJECT, GUIDE, START, RETRY, PAUSE, RESUME,
        STOP, AUTO_STAGE, AUTO_RETRY, EXTERNAL_RESULT }
    public enum Outcome { ACCEPTED, SUCCEEDED, FAILED, UNKNOWN }
    public enum Reason { USER_REQUEST, POLICY_AUTOMATIC, GUIDANCE_ATTACHED,
        REVIEW_CHANGES_REQUIRED, LIMIT_REACHED, GLOBAL_CONTROL,
        EXTERNAL_CONFIRMED, EXTERNAL_UNCERTAIN }

    public DecisionDraft {
        if (issueId == null || issueId <= 0 || actor == null || action == null || outcome == null) {
            throw new IllegalArgumentException("A positive issue ID, actor, action, and outcome are required");
        }
        machineIdentity(sourceKey, false);
        machineIdentity(workflowRun, true);
        for (Long id : new Long[]{repoId, planVersionId, iterationId, stageApprovalId, guidanceId}) {
            if (id != null && id <= 0) throw new IllegalArgumentException("Artifact IDs must be positive");
        }
        if (prNumber != null && prNumber <= 0) throw new IllegalArgumentException("PR number must be positive");
    }

    private static void machineIdentity(String value, boolean optional) {
        if (value == null && optional) return;
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9._:/-]{0,199}")) {
            throw new IllegalArgumentException("Decision identities must be machine identifiers of 1–200 characters");
        }
    }
}

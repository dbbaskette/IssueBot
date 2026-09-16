package com.dbbaskette.issuebot.model;

/** Operator-selected policy; never inferred from model output. */
public enum ExecutionPermissions {
    ASK, AUTO_REVIEW, FULL_ACCESS;

    public String label() {
        return switch (this) {
            case ASK -> "Ask for approval";
            case AUTO_REVIEW -> "Approve for me";
            case FULL_ACCESS -> "Full access";
        };
    }
}

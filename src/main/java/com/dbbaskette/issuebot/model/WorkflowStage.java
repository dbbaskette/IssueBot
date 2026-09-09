package com.dbbaskette.issuebot.model;

public enum WorkflowStage {
    PLANNING, IMPLEMENTATION, VERIFICATION, REVIEW, MERGE;

    public static final String ALL = "PLANNING,IMPLEMENTATION,VERIFICATION,REVIEW,MERGE";

    public boolean modelDriven() {
        return this == PLANNING || this == IMPLEMENTATION || this == REVIEW;
    }
}

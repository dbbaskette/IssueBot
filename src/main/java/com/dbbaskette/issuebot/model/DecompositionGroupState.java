package com.dbbaskette.issuebot.model;

public enum DecompositionGroupState {
    CREATING, WAITING, ACTIVE, NEEDS_ATTENTION, COMPLETING, ABANDONING, COMPLETED, ABANDONED;

    public boolean ownsRepository() {
        return this == CREATING || this == ACTIVE || this == NEEDS_ATTENTION
                || this == COMPLETING || this == ABANDONING;
    }

    public boolean unfinished() {
        return this != COMPLETED && this != ABANDONED;
    }
}

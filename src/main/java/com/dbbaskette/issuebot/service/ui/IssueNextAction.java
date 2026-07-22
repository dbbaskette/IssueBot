package com.dbbaskette.issuebot.service.ui;

public record IssueNextAction(String summary, String ctaLabel, String href,
                              Tone tone, boolean actionRequired) {
    public enum Tone { ACTION, ACTIVE, WAITING, SUCCESS, NEUTRAL }

    public boolean hasAction() {
        return ctaLabel != null && href != null;
    }
}

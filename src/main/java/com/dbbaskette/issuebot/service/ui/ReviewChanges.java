package com.dbbaskette.issuebot.service.ui;

import java.util.List;
import java.util.Locale;

public record ReviewChanges(boolean comparable, String explanation,
                            List<Item> criteria, List<Item> findings) {

    public ReviewChanges {
        criteria = List.copyOf(criteria);
        findings = List.copyOf(findings);
    }

    public enum Change {
        NEW, RESOLVED, PERSISTENT, NEWLY_MET, NEWLY_UNMET,
        UNCHANGED, ADDED, REMOVED, UNCLEAR, NOT_COMPARABLE
    }

    public record Item(String key, Change change, String text,
                       String previousState, String currentState) {

        public String label() {
            return switch (change) {
                case NEW -> "New";
                case RESOLVED -> "Resolved";
                case PERSISTENT -> "Persistent";
                case NEWLY_MET -> "Newly met";
                case NEWLY_UNMET -> "Newly unmet";
                case UNCHANGED -> "Unchanged";
                case ADDED -> "Added";
                case REMOVED -> "Removed";
                case UNCLEAR -> "Unclear";
                case NOT_COMPARABLE -> "Not comparable";
            };
        }

        public String cssClass() {
            return "is-" + change.name().toLowerCase(Locale.ROOT).replace('_', '-');
        }
    }
}

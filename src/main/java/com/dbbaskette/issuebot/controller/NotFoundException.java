package com.dbbaskette.issuebot.controller;

/**
 * Thrown by URL-reachable entity lookups (e.g. a bookmarked/linked issue detail page) when the
 * requested resource no longer exists — a stale link is a routine, expected occurrence (the
 * parent repository may have been removed, cascading the issue with it), not a server error.
 * Carries a contextual message plus a "back to X" link/label so {@link GlobalExceptionHandler}
 * can render a friendly 404 instead of the generic error page (issue #81).
 */
public class NotFoundException extends RuntimeException {

    private final String backLink;
    private final String backLabel;

    public NotFoundException(String message, String backLink, String backLabel) {
        super(message);
        this.backLink = backLink;
        this.backLabel = backLabel;
    }

    public String getBackLink() {
        return backLink;
    }

    public String getBackLabel() {
        return backLabel;
    }
}

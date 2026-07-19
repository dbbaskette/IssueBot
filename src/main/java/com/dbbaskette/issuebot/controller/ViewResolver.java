package com.dbbaskette.issuebot.controller;

/** Resolves a Thymeleaf view name based on whether the request came from HTMX. */
public final class ViewResolver {

    private ViewResolver() {}

    /**
     * @param contentTemplate the page template name (also the value set as {@code contentTemplate})
     * @param htmxRequest      true when the {@code HX-Request} header is present
     * @return the bare content fragment for HTMX swaps, or the full {@code layout} otherwise
     */
    public static String view(String contentTemplate, boolean htmxRequest) {
        return htmxRequest ? contentTemplate + " :: content" : "layout";
    }

    /**
     * Resolves the post-action redirect for the six approve/reject endpoints shared between the
     * Approvals page and the Needs You inbox (#91): {@code returnTo} honors ONLY the literal
     * value {@code "inbox"}, sending the operator back to {@code /inbox} instead of the
     * endpoint's normal target. Any other value — blank, absent, garbage, even a full URL — falls
     * through to {@code fallback} unchanged. This is deliberately not a generic open redirect:
     * {@code returnTo} can only ever pick between two fixed, server-known destinations, never an
     * operator-supplied URL.
     *
     * @param returnTo the request's optional {@code returnTo} param
     * @param fallback the endpoint's normal redirect target (used for every value but "inbox")
     */
    public static String redirectTarget(String returnTo, String fallback) {
        return "inbox".equals(returnTo) ? "redirect:/inbox" : fallback;
    }

    /**
     * Resolves approval action redirects to known, server-owned destinations. A request may only
     * return to the Inbox or the issue on which the action was taken; every other value uses the
     * caller's fixed fallback.
     */
    public static String approvalRedirect(String returnTo, Long issueId, String fallback) {
        if ("inbox".equals(returnTo)) return "redirect:/inbox";
        if ("issue".equals(returnTo) && issueId != null) return "redirect:/issues/" + issueId;
        return fallback;
    }
}

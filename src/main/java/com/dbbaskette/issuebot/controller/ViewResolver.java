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
}

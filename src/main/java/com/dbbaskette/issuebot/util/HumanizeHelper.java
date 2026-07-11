package com.dbbaskette.issuebot.util;

/**
 * Thin instance wrapper around the static {@link Humanize} methods so Thymeleaf templates
 * can call them as {@code ${humanize.phase(issue.currentPhase)}} / {@code
 * ${humanize.eventType(event.eventType)}} — SpEL/OGNL in Thymeleaf resolves method calls on
 * model attributes normally, but registering a plain static-method utility as a model
 * attribute isn't idiomatic, so this bean is what actually gets published to the model
 * (see {@code com.dbbaskette.issuebot.controller.UiModelAdvice}).
 *
 * <p>Stateless and trivially constructible, so template-render tests that build their own
 * {@code WebContext} (bypassing the {@code @ControllerAdvice}) can just {@code new} one up.
 */
public class HumanizeHelper {

    public String phase(String rawPhase) {
        return Humanize.phase(rawPhase);
    }

    public String eventType(String rawEventType) {
        return Humanize.eventType(rawEventType);
    }
}

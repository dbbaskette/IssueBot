package com.dbbaskette.issuebot.controller;

import com.dbbaskette.issuebot.util.HumanizeHelper;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * Publishes shared, read-only helpers to every controller's model so Thymeleaf templates can
 * call them directly (e.g. {@code ${humanize.phase(issue.currentPhase)}}). Standard Thymeleaf
 * usage permits invoking methods on model attributes — this isn't a SpEL {@code T(...)} type
 * expression (which newer Thymeleaf restricts), just an ordinary property/method access on a
 * bean already in the model, so no expression-restriction workaround is needed.
 */
@ControllerAdvice
public class UiModelAdvice {

    @ModelAttribute("humanize")
    public HumanizeHelper humanize() {
        return new HumanizeHelper();
    }
}

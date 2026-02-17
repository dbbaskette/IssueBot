package com.dbbaskette.issuebot.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.NoSuchElementException;

@ControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(NoSuchElementException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public String handleNotFound(NoSuchElementException e, Model model) {
        log.warn("Resource not found: {}", e.getMessage());
        model.addAttribute("activePage", "");
        model.addAttribute("contentTemplate", "error");
        model.addAttribute("errorTitle", "Not Found");
        model.addAttribute("errorMessage", "The requested resource was not found.");
        model.addAttribute("agentRunning", true);
        model.addAttribute("pendingApprovals", 0L);
        return "layout";
    }

    @ExceptionHandler(NoResourceFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public String handleNoResource(NoResourceFoundException e, Model model) {
        model.addAttribute("activePage", "");
        model.addAttribute("contentTemplate", "error");
        model.addAttribute("errorTitle", "Not Found");
        model.addAttribute("errorMessage", "Page not found.");
        model.addAttribute("agentRunning", true);
        model.addAttribute("pendingApprovals", 0L);
        return "layout";
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public String handleGeneral(Exception e, Model model) {
        log.error("Unhandled exception", e);
        model.addAttribute("activePage", "");
        model.addAttribute("contentTemplate", "error");
        model.addAttribute("errorTitle", "Error");
        model.addAttribute("errorMessage", "An unexpected error occurred: " + e.getMessage());
        model.addAttribute("agentRunning", true);
        model.addAttribute("pendingApprovals", 0L);
        return "layout";
    }
}

package com.dbbaskette.issuebot.controller;

import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.io.IOException;
import java.util.NoSuchElementException;

@ControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * SSE connections time out naturally — handle silently.
     */
    @ExceptionHandler(AsyncRequestTimeoutException.class)
    @ResponseBody
    public ResponseEntity<Void> handleAsyncTimeout() {
        return ResponseEntity.noContent().build();
    }

    /**
     * Broken SSE connections — client disconnected, nothing to render.
     */
    @ExceptionHandler(AsyncRequestNotUsableException.class)
    public void handleAsyncNotUsable() {
        // Client gone — nothing we can write; just return silently
    }

    /**
     * Broken pipe from dead SSE clients — suppress the Thymeleaf rendering attempt.
     */
    @ExceptionHandler(IOException.class)
    @ResponseBody
    public ResponseEntity<Void> handleIOException(IOException e) {
        log.debug("IO error (likely SSE client disconnect): {}", e.getMessage());
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler(NoSuchElementException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public String handleNotFound(NoSuchElementException e, Model model) {
        log.warn("Resource not found: {}", e.getMessage());
        return errorPage(model, "Not Found", "The requested resource was not found.");
    }

    @ExceptionHandler(NoResourceFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public String handleNoResource(NoResourceFoundException e, Model model) {
        return errorPage(model, "Not Found", "Page not found.");
    }

    @ExceptionHandler(Exception.class)
    public String handleGeneral(Exception e, Model model, HttpServletResponse response) {
        if (response.isCommitted()) {
            log.debug("Exception on committed response (SSE): {}", e.getMessage());
            return null;
        }
        log.error("Unhandled exception", e);
        response.setStatus(HttpStatus.INTERNAL_SERVER_ERROR.value());
        return errorPage(model, "Error", "An unexpected error occurred: " + e.getMessage());
    }

    private String errorPage(Model model, String title, String message) {
        model.addAttribute("activePage", "");
        model.addAttribute("contentTemplate", "error");
        model.addAttribute("errorTitle", title);
        model.addAttribute("errorMessage", message);
        model.addAttribute("agentRunning", true);
        model.addAttribute("pendingApprovals", 0L);
        return "layout";
    }
}

package com.dbbaskette.issuebot.controller;

import org.junit.jupiter.api.Test;
import org.springframework.stereotype.Controller;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * Verifies {@link GlobalExceptionHandler}'s handling of {@link NotFoundException} (#81): a
 * friendly 404 carrying the exception's message and back-link/label, plus the pre-existing
 * generic-NoSuchElementException path still defaulting those two attributes sanely.
 */
class GlobalExceptionHandlerTest {

    @Controller
    static class ThrowingController {
        @GetMapping("/boom-not-found")
        String boomNotFound() {
            throw new NotFoundException(
                    "Issue not found — it may have been removed with its repository.",
                    "/issues", "Back to the queue");
        }

        @GetMapping("/boom-generic")
        String boomGeneric() {
            throw new java.util.NoSuchElementException("no such element");
        }
    }

    private MockMvc mockMvc() {
        return MockMvcBuilders.standaloneSetup(new ThrowingController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void notFoundException_returns404WithContextualMessageAndBackLink() throws Exception {
        mockMvc().perform(get("/boom-not-found"))
                .andExpect(status().isNotFound())
                .andExpect(view().name("layout"))
                .andExpect(model().attribute("errorMessage",
                        "Issue not found — it may have been removed with its repository."))
                .andExpect(model().attribute("backLink", "/issues"))
                .andExpect(model().attribute("backLabel", "Back to the queue"));
    }

    @Test
    void notFoundException_hxRequestReturnsFragmentView() throws Exception {
        mockMvc().perform(get("/boom-not-found").header("HX-Request", "true"))
                .andExpect(status().isNotFound())
                .andExpect(view().name("error :: content"));
    }

    @Test
    void genericNotFound_defaultsBackLinkAndLabel() throws Exception {
        mockMvc().perform(get("/boom-generic"))
                .andExpect(status().isNotFound())
                .andExpect(model().attribute("errorMessage", "The requested resource was not found."))
                .andExpect(model().attribute("backLink", "/"))
                .andExpect(model().attribute("backLabel", "Back to Dashboard"));
    }
}

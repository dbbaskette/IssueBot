package com.dbbaskette.issuebot.controller;

import com.dbbaskette.issuebot.service.workflow.ProcessingControlService;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ProcessingControlControllerTest {
    @Test void pauseReportsSuccess() {
        var service = mock(ProcessingControlService.class);
        var redirects = mock(RedirectAttributes.class);
        assertThat(new ProcessingControlController(service).pause("/issues/7", redirects))
                .isEqualTo("redirect:/issues/7");
        verify(service).stopNow();
        verify(redirects).addFlashAttribute("success", "Processing paused — active work is stopping");
    }

    @Test void pausePersistenceFailureReportsError() {
        var service = mock(ProcessingControlService.class);
        var redirects = mock(RedirectAttributes.class);
        doThrow(new RuntimeException("disk full")).when(service).stopNow();
        new ProcessingControlController(service).pause("//evil.example", redirects);
        verify(redirects).addFlashAttribute("error", "Processing could not be paused; active work was not stopped");
    }

    @Test void resumeRestartsProcessing() {
        var service = mock(ProcessingControlService.class);
        var redirects = mock(RedirectAttributes.class);

        assertThat(new ProcessingControlController(service).resume("/", redirects))
                .isEqualTo("redirect:/");

        verify(service).restart();
        verify(redirects).addFlashAttribute("success", "Processing resumed");
    }
}

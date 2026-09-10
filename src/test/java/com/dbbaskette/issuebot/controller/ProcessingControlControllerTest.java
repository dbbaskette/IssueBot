package com.dbbaskette.issuebot.controller;

import com.dbbaskette.issuebot.service.workflow.ProcessingControlService;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ProcessingControlControllerTest {
    private final ProcessingControlService service = mock(ProcessingControlService.class);
    private final RedirectAttributes redirects = mock(RedirectAttributes.class);
    private final ProcessingControlController controller = new ProcessingControlController(service);

    @Test void pauseAfterCurrentIsExplicitAndPreservesLocalQuery() {
        assertThat(controller.pauseAfterCurrent("/issues?status=FAILED", redirects))
                .isEqualTo("redirect:/issues?status=FAILED");
        verify(service).pauseAfterCurrent();
        verify(service, never()).stopNow();
        verify(redirects).addFlashAttribute("success",
                "Queue paused. Running work can finish; no other issue will start automatically.");
    }

    @Test void pauseAfterCurrentFailureDoesNotClaimSuccess() {
        doThrow(new RuntimeException("disk full")).when(service).pauseAfterCurrent();
        controller.pauseAfterCurrent("/", redirects);
        verify(redirects).addFlashAttribute("error",
                "Processing could not be set to pause after current work.");
    }

    @Test void stopNowIsExplicitAndRejectsProtocolRelativeRedirect() {
        assertThat(controller.stopNow("//evil.example", redirects)).isEqualTo("redirect:/");
        verify(service).stopNow();
        verify(service, never()).pauseAfterCurrent();
        verify(redirects).addFlashAttribute("success",
                "Stopping active work. The queue will stay stopped.");
    }

    @Test void stopNowFailureDoesNotClaimCancellation() {
        doThrow(new RuntimeException("disk full")).when(service).stopNow();
        controller.stopNow("https://evil.example", redirects);
        verify(redirects).addFlashAttribute("error",
                "Processing could not be stopped; active work was not cancelled.");
    }

    @Test void restartCanBeSubmittedRepeatedly() {
        assertThat(controller.restart("/issues/7", redirects)).isEqualTo("redirect:/issues/7");
        assertThat(controller.restart("/issues/7", redirects)).isEqualTo("redirect:/issues/7");
        verify(service, times(2)).restart();
        verify(redirects, times(2)).addFlashAttribute("success",
                "Queue resumed. Ready issues can start automatically.");
    }

    @Test void restartFailureIsModeSpecific() {
        doThrow(new RuntimeException("disk full")).when(service).restart();
        controller.restart(null, redirects);
        verify(redirects).addFlashAttribute("error", "Processing could not be restarted.");
    }
}

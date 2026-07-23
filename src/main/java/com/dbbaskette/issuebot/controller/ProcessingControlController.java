package com.dbbaskette.issuebot.controller;

import com.dbbaskette.issuebot.service.workflow.ProcessingControlService;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/processing")
public class ProcessingControlController {
    private final ProcessingControlService control;

    public ProcessingControlController(ProcessingControlService control) { this.control = control; }

    @PostMapping("/pause-after-current")
    public String pauseAfterCurrent(@RequestParam(defaultValue = "/") String returnTo,
                                    RedirectAttributes redirects) {
        try {
            control.pauseAfterCurrent();
            redirects.addFlashAttribute("success",
                    "Processing will pause after current work finishes; queued issues will remain queued.");
        } catch (RuntimeException e) {
            redirects.addFlashAttribute("error",
                    "Processing could not be set to pause after current work.");
        }
        return redirect(returnTo);
    }

    @PostMapping("/stop-now")
    public String stopNow(@RequestParam(defaultValue = "/") String returnTo,
                          RedirectAttributes redirects) {
        try {
            control.stopNow();
            redirects.addFlashAttribute("success",
                    "Processing stopped; active work is being cancelled and queued issues will remain queued.");
        } catch (RuntimeException e) {
            redirects.addFlashAttribute("error",
                    "Processing could not be stopped; active work was not cancelled.");
        }
        return redirect(returnTo);
    }

    @PostMapping("/restart")
    public String restart(@RequestParam(defaultValue = "/") String returnTo,
                          RedirectAttributes redirects) {
        try {
            control.restart();
            redirects.addFlashAttribute("success",
                    "Processing restarted; queued issues can run again.");
        } catch (RuntimeException e) {
            redirects.addFlashAttribute("error", "Processing could not be restarted.");
        }
        return redirect(returnTo);
    }

    private static String redirect(String path) {
        return path != null && path.startsWith("/") && !path.startsWith("//") ? "redirect:" + path : "redirect:/";
    }
}

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

    @PostMapping("/pause")
    public String pause(@RequestParam(defaultValue = "/") String returnTo, RedirectAttributes redirects) {
        try {
            control.pause();
            redirects.addFlashAttribute("success", "Processing paused — active work is stopping");
        } catch (RuntimeException e) {
            redirects.addFlashAttribute("error", "Processing could not be paused; active work was not stopped");
        }
        return redirect(returnTo);
    }

    @PostMapping("/resume")
    public String resume(@RequestParam(defaultValue = "/") String returnTo, RedirectAttributes redirects) {
        control.resume();
        redirects.addFlashAttribute("success", "Processing resumed");
        return redirect(returnTo);
    }

    private static String redirect(String path) {
        return path != null && path.startsWith("/") && !path.startsWith("//") ? "redirect:" + path : "redirect:/";
    }
}

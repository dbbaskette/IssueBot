package com.dbbaskette.issuebot.controller;

import com.dbbaskette.issuebot.service.ui.QueueDependencyService;
import com.dbbaskette.issuebot.service.workflow.QueueRecoveryService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/issues/recovery")
public class QueueRecoveryController {
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.dbbaskette.issuebot.service.event.EventService events;
    private final QueueRecoveryService recovery;
    private final QueueDependencyService dependencies;
    public QueueRecoveryController(QueueRecoveryService recovery, QueueDependencyService dependencies) {
        this.recovery = recovery; this.dependencies = dependencies;
    }
    @GetMapping("/map")
    public String map(@RequestParam(required = false) Long repoId, Model model) {
        model.addAttribute("dependencyGraphs", dependencies.graphs(repoId));
        model.addAttribute("selectedRepoId", repoId);
        return "fragments/dependency-map :: map";
    }
    @PostMapping("/manual")
    public String manual(RedirectAttributes redirect) {
        try {
            int count = recovery.enterManualRecovery();
            if (events != null) events.log("QUEUE_MANUAL_RECOVERY", "Paused automatic starts and suspended " + count + " decomposition reservations; progress preserved");
            redirect.addFlashAttribute("success", "Automatic processing paused. Suspended " + count
                    + " decomposition reservations without losing progress. Select an eligible issue below.");
        } catch (IllegalStateException ex) { redirect.addFlashAttribute("error", ex.getMessage()); }
        return "redirect:/issues#dependency-map";
    }
    @PostMapping("/groups/{id}/resume")
    public String resume(@PathVariable Long id, RedirectAttributes redirect) {
        try {
            recovery.resumeGroup(id);
            if (events != null) events.log("GROUP_RESERVATION_RESUMED", "Restored decomposition reservation for group " + id);
            redirect.addFlashAttribute("success", "Group reservation restored. No issue was started and global processing mode is unchanged.");
        } catch (IllegalStateException ex) { redirect.addFlashAttribute("error", ex.getMessage()); }
        return "redirect:/issues#dependency-map";
    }
}

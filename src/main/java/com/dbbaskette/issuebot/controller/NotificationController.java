package com.dbbaskette.issuebot.controller;

import com.dbbaskette.issuebot.model.Notification;
import com.dbbaskette.issuebot.repository.WatchedRepoRepository;
import com.dbbaskette.issuebot.service.notification.NotificationTriageService;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@Controller
@RequestMapping("/notifications")
public class NotificationController {
    private final NotificationTriageService triage;
    private final WatchedRepoRepository repos;
    public NotificationController(NotificationTriageService triage, WatchedRepoRepository repos) {
        this.triage = triage;
        this.repos = repos;
    }
    @GetMapping("/panel")
    public String panel(Model model) {
        model.addAttribute("notificationSnapshot", triage.snapshot("", null, "ALL", "ALL", false, PageRequest.of(0, 10)));
        return "notifications :: panel";
    }
    @GetMapping
    public String history(Model model, @RequestParam(defaultValue = "") String query,
            @RequestParam(required = false) Long repoId, @RequestParam(defaultValue = "ALL") String category,
            @RequestParam(defaultValue = "ALL") String readFilter, @RequestParam(defaultValue = "false") boolean actionsOnly,
            @RequestParam(defaultValue = "0") int page, @RequestHeader(value = "HX-Request", required = false) String hx) {
        validate(() -> {
            if (query.length() > 200 || page < 0 || page > 1_000_000 || repoId != null && repoId <= 0)
                throw new IllegalArgumentException("Invalid history filters");
            NotificationTriageService.CategoryFilter.valueOf(category);
            NotificationTriageService.ReadFilter.valueOf(readFilter);
        });
        model.addAttribute("notificationSnapshot", triage.snapshot(query, repoId, category, readFilter, actionsOnly, PageRequest.of(page, 25)));
        model.addAttribute("query", query);
        model.addAttribute("repoId", repoId);
        model.addAttribute("category", category);
        model.addAttribute("readFilter", readFilter);
        model.addAttribute("actionsOnly", actionsOnly);
        model.addAttribute("repos", repos.findAll());
        model.addAttribute("mutedCategories", triage.mutedCategories());
        model.addAttribute("contentTemplate", "notification-history");
        model.addAttribute("activePage", "notifications");
        model.addAttribute("pageTitle", "Notification history");
        return ViewResolver.view("notification-history", hx != null);
    }
    @GetMapping("/group")
    public String group(Model model, @RequestParam String groupKey, @RequestParam(defaultValue = "0") int page) {
        validate(() -> {
            NotificationTriageService.validateGroupKey(groupKey);
            if (page < 0 || page > 1_000_000) throw new IllegalArgumentException("Invalid history page");
        });
        var events = triage.history(groupKey, page);
        model.addAttribute("groupKey", groupKey);
        model.addAttribute("historyEvents", events);
        model.addAttribute("historyThroughId", events.stream().mapToLong(Notification::getId).max().orElse(0));
        return "notification-history :: entries";
    }
    @PostMapping("/read")
    public String markRead(Model model, @RequestParam long throughId,
                           @RequestParam(defaultValue = "panel") String returnTo) {
        validate(() -> NotificationTriageService.validateWatermark(throughId));
        triage.markAllRead(throughId);
        return "history".equals(returnTo) ? "redirect:/notifications" : panel(model);
    }
    @PostMapping("/group/read")
    public String markGroupRead(Model model, @RequestParam String groupKey, @RequestParam long throughId,
                                @RequestParam(defaultValue = "panel") String returnTo) {
        validate(() -> { NotificationTriageService.validateGroupKey(groupKey); NotificationTriageService.validateWatermark(throughId); });
        triage.markGroupRead(groupKey, throughId);
        return "history".equals(returnTo) ? "redirect:/notifications" : panel(model);
    }
    @PostMapping("/mute")
    public String mute(@RequestParam String category, @RequestParam boolean muted) {
        validate(() -> triage.setMuted(Notification.Category.valueOf(category), muted));
        return "redirect:/notifications";
    }
    private static void validate(Runnable validation) {
        try { validation.run(); }
        catch (IllegalArgumentException invalid) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid notification request"); }
    }

    @ExceptionHandler({ResponseStatusException.class,
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,
            org.springframework.web.bind.MissingServletRequestParameterException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ResponseBody
    public String invalidRequest() { return "Invalid notification request"; }
}

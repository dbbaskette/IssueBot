package com.dbbaskette.issuebot.controller;

import com.dbbaskette.issuebot.repository.TrackedIssueRepository;
import com.dbbaskette.issuebot.service.history.DecisionHistoryService;
import com.dbbaskette.issuebot.service.history.DecisionHistoryView;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

/** Read-only, inside the same authenticated /issues scope as IssueController.detail. */
@Controller
@RequestMapping("/issues/{id}/decisions")
public class DecisionHistoryController {
    private final TrackedIssueRepository issues;
    private final DecisionHistoryService history;
    private final DecisionHistoryView view;

    public DecisionHistoryController(TrackedIssueRepository issues, DecisionHistoryService history, DecisionHistoryView view) {
        this.issues = issues; this.history = history; this.view = view;
    }

    @GetMapping
    public String page(@PathVariable String id, @RequestParam(defaultValue = "0") String page, Model model) {
        long issueId;
        int pageNumber;
        try {
            issueId = Long.parseLong(id);
            pageNumber = Integer.parseInt(page);
            if (issueId <= 0 || pageNumber < 0 || (long) pageNumber * 25 > Integer.MAX_VALUE) throw new NumberFormatException();
        } catch (NumberFormatException ex) { throw new InvalidHistoryRequest(); }
        issues.findById(issueId).orElseThrow(() -> new NotFoundException(
                "Issue not found — it may have been removed with its repository.", "/issues", "Back to the queue"));
        var result = history.page(issueId, PageRequest.of(pageNumber, 25));
        model.addAttribute("decisionIssueId", issueId);
        model.addAttribute("decisionPage", result);
        model.addAttribute("decisionEntries", view.entries(result.getContent()));
        model.addAttribute("decisionTrackingStartedAt", history.trackingStartedAt());
        return "fragments/decision-history :: history";
    }

    @ExceptionHandler(InvalidHistoryRequest.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ResponseBody
    public String invalidRequest() { return "Invalid issue ID or decision history page."; }

    private static class InvalidHistoryRequest extends RuntimeException {}
}

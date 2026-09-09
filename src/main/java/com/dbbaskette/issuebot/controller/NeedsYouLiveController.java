package com.dbbaskette.issuebot.controller;

import com.dbbaskette.issuebot.service.ui.NeedsYouService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class NeedsYouLiveController {
    private final NeedsYouService needsYou;
    private final InboxController inbox;

    public NeedsYouLiveController(NeedsYouService needsYou, InboxController inbox) {
        this.needsYou = needsYou;
        this.inbox = inbox;
    }

    @GetMapping("/inbox/live")
    public String live(Model model, @RequestParam(defaultValue = "false") boolean includeInbox,
                       HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        var snapshot = needsYou.snapshot();
        model.addAttribute("needsYouSnapshot", snapshot);
        model.addAttribute("needsYouCount", snapshot.totalCount());
        model.addAttribute("includeInbox", includeInbox);
        if (includeInbox) inbox.populate(model, snapshot);
        return "fragments/needs-you :: live";
    }
}

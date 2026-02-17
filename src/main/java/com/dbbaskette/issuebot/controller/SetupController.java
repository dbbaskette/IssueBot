package com.dbbaskette.issuebot.controller;

import com.dbbaskette.issuebot.config.IssueBotProperties;
import com.dbbaskette.issuebot.model.IssueStatus;
import com.dbbaskette.issuebot.repository.TrackedIssueRepository;
import com.dbbaskette.issuebot.service.claude.ClaudeCodeService;
import com.dbbaskette.issuebot.service.polling.IssuePollingService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.io.File;

@Controller
public class SetupController {

    private final ClaudeCodeService claudeCodeService;
    private final IssueBotProperties properties;
    private final IssuePollingService pollingService;
    private final TrackedIssueRepository issueRepository;

    public SetupController(ClaudeCodeService claudeCodeService,
                            IssueBotProperties properties,
                            IssuePollingService pollingService,
                            TrackedIssueRepository issueRepository) {
        this.claudeCodeService = claudeCodeService;
        this.properties = properties;
        this.pollingService = pollingService;
        this.issueRepository = issueRepository;
    }

    @GetMapping("/setup")
    public String setup(Model model) {
        model.addAttribute("activePage", "setup");
        model.addAttribute("contentTemplate", "setup");
        model.addAttribute("agentRunning", pollingService.isEnabled());
        model.addAttribute("pendingApprovals", issueRepository.countByStatus(IssueStatus.AWAITING_APPROVAL));

        // Prerequisites checks
        boolean cliAvailable = claudeCodeService.isCliAvailable();
        model.addAttribute("cliAvailable", cliAvailable);

        boolean cliAuthenticated = false;
        if (cliAvailable) {
            cliAuthenticated = claudeCodeService.checkAuthentication();
        }
        model.addAttribute("cliAuthenticated", cliAuthenticated);

        String token = properties.getGithub().getToken();
        boolean githubTokenSet = token != null && !token.isBlank() && !"not-set".equals(token);
        model.addAttribute("githubTokenSet", githubTokenSet);

        // Work directory check
        File workDir = new File(properties.getWorkDirectory());
        boolean workDirOk;
        String workDirMessage;
        if (workDir.exists()) {
            long freeSpaceMb = workDir.getFreeSpace() / (1024 * 1024);
            workDirOk = freeSpaceMb > 500;
            workDirMessage = workDir.getAbsolutePath() + " (" + freeSpaceMb + " MB free)";
        } else {
            boolean created = workDir.mkdirs();
            workDirOk = created;
            workDirMessage = created
                    ? workDir.getAbsolutePath() + " (created)"
                    : "Could not create " + workDir.getAbsolutePath();
        }
        model.addAttribute("workDirOk", workDirOk);
        model.addAttribute("workDirMessage", workDirMessage);

        model.addAttribute("allPassed", cliAvailable && cliAuthenticated && githubTokenSet && workDirOk);

        return "layout";
    }
}

package com.dbbaskette.issuebot.service.harness;

import com.dbbaskette.issuebot.config.IssueBotProperties;
import com.dbbaskette.issuebot.repository.*;
import com.dbbaskette.issuebot.service.claude.ClaudeCodeService;
import com.dbbaskette.issuebot.service.codex.*;
import java.util.List;
import static org.mockito.Mockito.*;

/** Real catalogs/selection with only CLI execution and persistence doubled. */
public class HarnessSelectionFixture {
    public final IssueBotProperties properties = new IssueBotProperties();
    public final ClaudeCodeService claude = mock(ClaudeCodeService.class);
    public final CodexCliService codex = mock(CodexCliService.class);
    public final TrackedIssueRepository issues = mock(TrackedIssueRepository.class);
    public final StageApprovalRepository stages = mock(StageApprovalRepository.class);
    public final CodingHarnessRegistry registry;
    public final HarnessSelectionService selections;

    public HarnessSelectionFixture() {
        var catalog = mock(CodexModelCatalog.class);
        when(catalog.models()).thenReturn(CodexModelCatalog.fallbackModels());
        registry = new CodingHarnessRegistry(List.of(new ClaudeHarnessAdapter(claude), new CodexHarnessAdapter(codex, catalog)));
        selections = new HarnessSelectionService(registry, properties, issues, stages);
        when(claude.probeCliAvailability()).thenReturn(HarnessReadiness.READY);
        when(claude.probeSubscriptionAuthentication()).thenReturn(HarnessReadiness.READY);
        when(codex.probeCliAvailability()).thenReturn(HarnessReadiness.READY);
        when(codex.probeSubscriptionAuthentication()).thenReturn(HarnessReadiness.READY);
    }
}

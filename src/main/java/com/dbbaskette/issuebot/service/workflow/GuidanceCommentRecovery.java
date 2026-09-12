package com.dbbaskette.issuebot.service.workflow;

import com.dbbaskette.issuebot.model.OperatorTransition;
import com.dbbaskette.issuebot.repository.OperatorTransitionRepository;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** An accepted guidance comment has no safe replay operation after restart. */
@Component
public class GuidanceCommentRecovery {
    private final OperatorTransitionRepository intents;
    private final IssueOperatorTransactionService transactions;
    public GuidanceCommentRecovery(OperatorTransitionRepository intents, IssueOperatorTransactionService transactions) {
        this.intents = intents; this.transactions = transactions;
    }
    @EventListener(ApplicationReadyEvent.class)
    public void recover() {
        for (var intent : intents.findByKindAndState("GUIDANCE_COMMENT", OperatorTransition.State.IN_FLIGHT))
            transactions.recoverGuidanceComment(intent.getIssueId(), intent.getId());
    }
}

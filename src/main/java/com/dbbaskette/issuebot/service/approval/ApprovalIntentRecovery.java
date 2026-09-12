package com.dbbaskette.issuebot.service.approval;

import com.dbbaskette.issuebot.model.OperatorTransition;
import com.dbbaskette.issuebot.repository.OperatorTransitionRepository;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Restart cannot prove whether GitHub applied a merge. Recovery records uncertainty only. */
@Component
public class ApprovalIntentRecovery {
    private final OperatorTransitionRepository intents;
    private final ApprovalDecisionTransactionManager transactions;
    public ApprovalIntentRecovery(OperatorTransitionRepository intents, ApprovalDecisionTransactionManager transactions) {
        this.intents = intents; this.transactions = transactions;
    }
    @EventListener(ApplicationReadyEvent.class)
    public void recover() {
        for (var intent : intents.findByKindAndState("PR_MERGE", OperatorTransition.State.IN_FLIGHT))
            transactions.recoverInterrupted(intent.getId());
    }
}

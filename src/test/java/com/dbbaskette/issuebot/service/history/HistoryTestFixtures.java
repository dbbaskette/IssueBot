package com.dbbaskette.issuebot.service.history;

import org.springframework.test.util.ReflectionTestUtils;
import static org.mockito.Mockito.mock;

/** Explicit collaborator wiring for non-Spring tests; production always requires the real bean. */
public final class HistoryTestFixtures {
    private HistoryTestFixtures() {}
    public static <T> T withHistory(T service) {
        ReflectionTestUtils.setField(service, "decisions", mock(DecisionProducer.class));
        return service;
    }
    public static void iterationManager(com.dbbaskette.issuebot.service.workflow.IterationManager service) {
        withHistory(service);
        var repos = (com.dbbaskette.issuebot.repository.WatchedRepoRepository) ReflectionTestUtils.getField(service, "repoRepository");
        var issues = (com.dbbaskette.issuebot.repository.TrackedIssueRepository) ReflectionTestUtils.getField(service, "issueRepository");
        var iterations = (com.dbbaskette.issuebot.repository.IterationRepository) ReflectionTestUtils.getField(service, "iterationRepository");
        org.mockito.Mockito.when(repos.findByIdForUpdate(org.mockito.ArgumentMatchers.any()))
                .thenReturn(java.util.Optional.of(new com.dbbaskette.issuebot.model.WatchedRepo("fixture", "lock")));
        org.mockito.Mockito.when(issues.findByIdForDispatch(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(call -> issues.findById(call.getArgument(0)));
        org.mockito.Mockito.when(iterations.saveAndFlush(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(call -> iterations.save(call.getArgument(0)));
    }
    public static com.dbbaskette.issuebot.service.approval.ApprovalDecisionService approval(
            com.dbbaskette.issuebot.service.approval.ApprovalDecisionService service,
            com.dbbaskette.issuebot.repository.TrackedIssueRepository issues,
            com.dbbaskette.issuebot.repository.WatchedRepoRepository repos,
            com.dbbaskette.issuebot.service.event.EventService events) {
        var transitions = mock(com.dbbaskette.issuebot.repository.OperatorTransitionRepository.class);
        var stored = new java.util.HashMap<Long, com.dbbaskette.issuebot.model.OperatorTransition>();
        org.mockito.Mockito.when(transitions.saveAndFlush(org.mockito.ArgumentMatchers.any())).thenAnswer(call -> {
            com.dbbaskette.issuebot.model.OperatorTransition intent = call.getArgument(0);
            if (intent.getId() == null) ReflectionTestUtils.setField(intent, "id", (long) stored.size() + 1);
            stored.put(intent.getId(), intent); return intent;
        });
        org.mockito.Mockito.when(transitions.findById(org.mockito.ArgumentMatchers.anyLong()))
                .thenAnswer(call -> java.util.Optional.ofNullable(stored.get(call.getArgument(0))));
        var producer = mock(DecisionProducer.class);
        ReflectionTestUtils.setField(service, "decisions", producer);
        ReflectionTestUtils.setField(service, "transactions", new com.dbbaskette.issuebot.service.approval.ApprovalDecisionTransactionManager(
                issues, repos, transitions, producer, events));
        return service;
    }
}

package com.dbbaskette.issuebot.service.workflow;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class WorkflowCancellationServiceTest {

    private final WorkflowCancellationService service = new WorkflowCancellationService();

    @Test
    void cancelIsPerIssueAndClearable() {
        assertThat(service.isCancelled(1L)).isFalse();
        service.requestCancel(1L);
        assertThat(service.isCancelled(1L)).isTrue();
        assertThat(service.isCancelled(2L)).isFalse();
        service.clear(1L);
        assertThat(service.isCancelled(1L)).isFalse();
    }

    @Test
    void registeredProcessIsDestroyedOnCancel() throws Exception {
        Process p = new ProcessBuilder("sleep", "30").start();
        service.registerProcess(1L, p);
        service.requestCancel(1L);
        p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS);
        assertThat(p.isAlive()).isFalse();
    }

    @Test
    void lateRegistrationAfterCancelKillsImmediately() throws Exception {
        service.requestCancel(1L);
        Process p = new ProcessBuilder("sleep", "30").start();
        service.registerProcess(1L, p);
        p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS);
        assertThat(p.isAlive()).isFalse();
    }

    @Test
    void cancellationReasonIsRetainedUntilClear() {
        service.requestCancel(1L, CancellationReason.GLOBAL_PAUSE);
        assertThat(service.reason(1L)).contains(CancellationReason.GLOBAL_PAUSE);

        service.clear(1L);

        assertThat(service.reason(1L)).isEmpty();
    }
}

package com.dbbaskette.issuebot.service.workflow;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

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
    void cancelDestroysDescendantProcessesBeforeReturning() throws Exception {
        Process parent = mock(Process.class);
        ProcessHandle child = mock(ProcessHandle.class);
        when(parent.descendants()).thenReturn(java.util.stream.Stream.of(child));
        when(parent.isAlive()).thenReturn(true);
        when(child.isAlive()).thenReturn(true);
        service.registerProcess(1L, parent);

        service.requestCancel(1L);

        var order = inOrder(child, parent);
        order.verify(child).destroyForcibly();
        order.verify(parent).destroyForcibly();
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
        service.requestCancel(1L, CancellationReason.OPERATOR_STOP);
        assertThat(service.reason(1L)).contains(CancellationReason.OPERATOR_STOP);

        service.clear(1L);

        assertThat(service.reason(1L)).isEmpty();
    }
}

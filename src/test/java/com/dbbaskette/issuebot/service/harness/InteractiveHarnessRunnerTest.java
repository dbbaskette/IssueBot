package com.dbbaskette.issuebot.service.harness;

import com.dbbaskette.issuebot.config.IssueBotProperties;
import com.dbbaskette.issuebot.model.*;
import com.dbbaskette.issuebot.repository.TrackedIssueRepository;
import com.dbbaskette.issuebot.service.claude.StreamJsonParser;
import com.dbbaskette.issuebot.service.workflow.WorkflowCancellationService;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.nio.file.Path;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class InteractiveHarnessRunnerTest {
    private final ObjectMapper json=new ObjectMapper();
    private HarnessExecutionRequest request() {return new HarnessExecutionRequest(HarnessRole.IMPLEMENTATION,"Implement the test",Path.of("/tmp"),"test-model","low",null,1L);}

    @Test void policiesMapToNativeReviewNotBypass() {
        assertThat(InteractiveHarnessRunner.command(false,ExecutionPermissions.AUTO_REVIEW,request(),false,false))
                .contains("auto","--permission-prompt-tool","stdio").doesNotContain("bypassPermissions","--dangerously-skip-permissions");
        assertThat(InteractiveHarnessRunner.command(false,ExecutionPermissions.FULL_ACCESS,request(),false,false)).contains("bypassPermissions");
        assertThat(InteractiveHarnessRunner.command(true,ExecutionPermissions.AUTO_REVIEW,request(),false,false))
                .contains("forced_login_method=\"chatgpt\"","model_provider=\"openai\"","app-server","--stdio")
                .doesNotContain("--dangerously-bypass-approvals-and-sandbox");
    }

    @Test void codexApprovalReturnsToSameSessionAndCompletes() throws Exception {runFixture(true,false);}
    @Test void claudeQuestionReturnsToSameSessionAndCompletes() throws Exception {runFixture(false,false);}
    @Test void lostNativeRequestDoesNotBecomeAFailedAttempt() throws Exception {runFixture(true,true);}

    private void runFixture(boolean codex,boolean lost) throws Exception {
        var input=mock(HarnessInputService.class);
        var issues=mock(TrackedIssueRepository.class);
        var issue=new TrackedIssue(new WatchedRepo("test","repo"),1,"test");
        when(issues.findByIdWithApprovedPlanningVersion(1L)).thenReturn(Optional.of(issue));
        when(input.policy(1L)).thenReturn(ExecutionPermissions.AUTO_REVIEW);
        var pending=mock(HarnessInputRequest.class);when(pending.getId()).thenReturn(42L);
        when(input.open(eq(1L),anyString(),anyString(),anyString(),eq("same-session"),anyString(),anyString())).thenReturn(pending);
        if(lost) when(input.await(eq(42L),any())).thenThrow(new HarnessInputInterruptedException("lost"));
        else when(input.await(eq(42L),any())).thenAnswer(invocation->{Thread.sleep(30);return codex ? "{\"decision\":\"accept\"}" : "{\"behavior\":\"allow\",\"updatedInput\":{\"answers\":{\"Where?\":\"Local\"}}}";});
        var process=new FixtureProcess(codex);
        var runner=new InteractiveHarnessRunner(input,json,new IssueBotProperties(),issues,new WorkflowCancellationService(),new StreamJsonParser(json)) {
            @Override protected Process start(ProcessBuilder builder) {return process;}
        };
        if(lost) {
            assertThatThrownBy(()->runner.execute(HarnessIds.CODEX,request(),null)).isInstanceOf(HarnessInputInterruptedException.class);
            verify(input,never()).delivered(any());
        } else {
            long started=System.nanoTime();
            var result=runner.execute(codex?HarnessIds.CODEX:HarnessIds.CLAUDE,request(),null);
            long elapsed=java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started);
            assertThat(result.isSuccess()).withFailMessage("%s",result.getErrorMessage()).isTrue();
            assertThat(result.getDurationMs()).isLessThanOrEqualTo(elapsed-20);
            assertThat(result.getSessionId()).isEqualTo("same-session");
            assertThat(result.getFinalResultOrOutput()).contains("Done");
            verify(input).delivered(42L);
            if(codex) {
                assertThat(process.received.toString()).contains("auto_review","on-request","workspace-write");
                assertThat(result.getCostUsd()).isEqualByComparingTo(java.math.BigDecimal.ZERO);
                assertThat(result.getInputTokens()).isEqualTo(150);
                assertThat(result.getOutputTokens()).isEqualTo(20);
            }
        }
        verify(input).disconnect(anyString());
        assertThat(process.isAlive()).isFalse();
    }

    private class FixtureProcess extends Process {
        private final PipedInputStream stdout=new PipedInputStream();
        private final PipedOutputStream server;
        private boolean alive=true;
        private final StringBuilder received=new StringBuilder();
        private final boolean codex;
        FixtureProcess(boolean codex) throws IOException {this.codex=codex;server=new PipedOutputStream(stdout);}
        private void emit(String message) throws IOException {server.write((message+"\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));server.flush();}
        @Override public OutputStream getOutputStream() {return new ByteArrayOutputStream(){
            @Override public void flush() throws IOException {
                String value=toString(java.nio.charset.StandardCharsets.UTF_8);reset();
                if(value.isBlank()) return;received.append(value);
                JsonNode message=json.readTree(value);
                if(codex) {
                    String method=message.path("method").asText();
                    if(method.equals("initialize")) emit("{\"id\":1,\"result\":{}}");
                    else if(method.equals("thread/start")) emit("{\"id\":2,\"result\":{\"thread\":{\"id\":\"same-session\"}}}");
                    else if(method.equals("turn/start")) {
                        emit("{\"id\":3,\"result\":{\"turn\":{\"id\":\"turn\"}}}");
                        emit("{\"method\":\"thread/tokenUsage/updated\",\"params\":{\"turnId\":\"turn\",\"tokenUsage\":{\"total\":{\"inputTokens\":1100,\"outputTokens\":110},\"last\":{\"inputTokens\":100,\"outputTokens\":10}}}}");
                        emit("{\"id\":\"approval\",\"method\":\"item/commandExecution/requestApproval\",\"params\":{\"command\":\"docker ps\"}}");
                    }
                    else if(message.path("id").asText().equals("approval")) {
                        emit("{\"method\":\"thread/tokenUsage/updated\",\"params\":{\"turnId\":\"turn\",\"tokenUsage\":{\"total\":{\"inputTokens\":1150,\"outputTokens\":120},\"last\":{\"inputTokens\":50,\"outputTokens\":10}}}}");
                        emit("{\"method\":\"item/completed\",\"params\":{\"item\":{\"type\":\"agentMessage\",\"text\":\"Done\"}}}");
                        emit("{\"method\":\"turn/completed\",\"params\":{\"turn\":{\"status\":\"completed\"}}}");
                    }
                } else {
                    String type=message.path("type").asText();
                    if(type.equals("control_request")) emit("{\"type\":\"control_response\",\"response\":{\"subtype\":\"success\",\"request_id\":\"initialize\"}}");
                    else if(type.equals("user")) {
                        emit("{\"type\":\"system\",\"subtype\":\"init\",\"session_id\":\"same-session\"}");
                        emit("{\"type\":\"control_request\",\"request_id\":\"question\",\"request\":{\"subtype\":\"can_use_tool\",\"tool_name\":\"AskUserQuestion\",\"input\":{\"questions\":[{\"question\":\"Where?\"}]}}}");
                    } else if(type.equals("control_response")) emit("{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":false,\"session_id\":\"same-session\",\"result\":\"Done\"}");
                }
            }
        };}
        @Override public InputStream getInputStream(){return stdout;}
        @Override public InputStream getErrorStream(){return InputStream.nullInputStream();}
        @Override public int waitFor(){return 0;}
        @Override public int exitValue(){return 0;}
        @Override public boolean isAlive(){return alive;}
        @Override public void destroy(){alive=false;try{server.close();}catch(IOException ignored){}}
        @Override public Process destroyForcibly(){destroy();return this;}
    }
}

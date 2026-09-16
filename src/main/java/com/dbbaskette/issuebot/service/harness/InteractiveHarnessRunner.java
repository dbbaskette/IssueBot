package com.dbbaskette.issuebot.service.harness;

import com.dbbaskette.issuebot.config.IssueBotProperties;
import com.dbbaskette.issuebot.model.ExecutionPermissions;
import com.dbbaskette.issuebot.repository.TrackedIssueRepository;
import com.dbbaskette.issuebot.service.claude.ClaudeCodeService;
import com.dbbaskette.issuebot.service.claude.StreamJsonParser;
import com.dbbaskette.issuebot.service.workflow.WorkflowCancellationService;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Native bidirectional transports. Only implementation roles enter this runner. */
@Service
public class InteractiveHarnessRunner {
    private final HarnessInputService input;
    private final ObjectMapper json;
    private final IssueBotProperties properties;
    private final TrackedIssueRepository issues;
    private final WorkflowCancellationService cancellation;
    private final StreamJsonParser claudeParser;

    public InteractiveHarnessRunner(HarnessInputService input, ObjectMapper json, IssueBotProperties properties,
                                    TrackedIssueRepository issues, WorkflowCancellationService cancellation,
                                    StreamJsonParser claudeParser) {
        this.input=input; this.json=json; this.properties=properties; this.issues=issues;
        this.cancellation=cancellation; this.claudeParser=claudeParser;
    }

    public HarnessExecutionResult execute(String harness, HarnessExecutionRequest request, Consumer<String> callback) {
        boolean codex = HarnessIds.CODEX.equals(harness);
        ExecutionPermissions policy = input.policy(request.issueId());
        var issue = issues.findByIdWithApprovedPlanningVersion(request.issueId()).orElseThrow();
        boolean network = properties.getCodexCli().getNetworkAllowedRepositories().contains(issue.getRepo().fullName());
        List<String> command = command(codex, policy, request, issue.isSubagentsAllowed(), network);
        String transport = UUID.randomUUID().toString();
        long started = System.nanoTime(), waited = 0;
        long budget = TimeUnit.MINUTES.toNanos(codex ? properties.getCodexCli().getTimeoutMinutes()
                : properties.getClaudeCode().getTimeoutMinutes());
        Process process = null;
        List<Thread> readers=new ArrayList<>();
        HarnessExecutionResult result = new HarnessExecutionResult();
        result.setModel(request.model());
        if(codex) result.setCostUsd(java.math.BigDecimal.ZERO);
        String currentTurn=null;
        long previousInputTotal=-1, previousOutputTotal=-1;
        StringBuilder raw = new StringBuilder(), text = new StringBuilder();
        try {
            ProcessBuilder builder = new ProcessBuilder(command).directory(request.workingDirectory().toFile());
            ClaudeCodeService.sanitizeBillingEnvironment(builder.environment());
            builder.environment().remove("CLAUDECODE");
            builder.environment().keySet().removeIf(name -> {
                String key=name.toUpperCase(Locale.ROOT);
                return key.contains("TOKEN") || key.contains("PASSWORD") || key.contains("SECRET")
                        || key.contains("CREDENTIAL") || key.contains("PRIVATE_KEY") || key.contains("API_KEY")
                        || Set.of("SSH_AUTH_SOCK", "SSH_ASKPASS", "GIT_ASKPASS").contains(key);
            });
            builder.environment().put("GIT_TERMINAL_PROMPT", "0");
            process=start(builder);
            cancellation.registerProcess(request.issueId(), process);
            input.connect(transport);
            BlockingQueue<String> lines = new ArrayBlockingQueue<>(1024);
            Set<String> resolvedRequests=ConcurrentHashMap.newKeySet();
            Thread reader = reader(process.getInputStream(), lines, line -> {
                try {
                    JsonNode event=json.readTree(line);
                    if(event==null) return;
                    if(event.path("method").asText().equals("serverRequest/resolved"))
                        resolvedRequests.add(event.path("params").path("requestId").toString());
                    else if(event.path("type").asText().equals("control_cancel_request"))
                        resolvedRequests.add(event.path("request_id").asText());
                } catch(IOException ignored) { /* The main loop reports malformed protocol. */ }
            });
            BlockingQueue<String> errors = new ArrayBlockingQueue<>(128);
            Thread stderr = reader(process.getErrorStream(), errors, null);
            readers.add(reader);readers.add(stderr);
            try (Writer writer = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8)) {
                if (codex) send(writer, Map.of("id", 1, "method", "initialize", "params", Map.of(
                        "clientInfo", Map.of("name", "issuebot", "version", "1"),
                        "capabilities", Map.of("experimentalApi", true))));
                else send(writer, Map.of("type", "control_request", "request_id", "initialize",
                        "request", Map.of("subtype", "initialize")));
                boolean done = false;
                while (!done) {
                    if (System.nanoTime()-started-waited > budget) {
                        result.setTimedOut(true);
                        throw new IOException("Coding time limit reached (time waiting for your input was excluded)");
                    }
                    String line = lines.poll(200, TimeUnit.MILLISECONDS);
                    if (line == null) {
                        if (!process.isAlive() && !reader.isAlive())
                            throw new IOException("Assistant disconnected before completing its turn. " + String.join("\n", errors));
                        continue;
                    }
                    if (raw.length() + line.length() > 8_000_000) throw new IOException("Assistant output exceeded the safety limit");
                    raw.append(line).append('\n');
                    JsonNode message = json.readTree(line);
                    if (message == null) continue;
                    if (codex) {
                        if (message.has("error")) throw new IOException("Codex rejected native request: " + message.path("error").path("message").asText());
                        if (message.path("id").asInt(-1)==1 && message.has("result")) {
                            send(writer, Map.of("method", "initialized"));
                            ObjectNode params=json.createObjectNode();
                            params.put("model",request.model()).put("modelProvider","openai")
                                    .put("cwd",request.workingDirectory().toAbsolutePath().toString())
                                    .put("approvalPolicy",policy==ExecutionPermissions.FULL_ACCESS ? "never":"on-request")
                                    .put("approvalsReviewer",policy==ExecutionPermissions.AUTO_REVIEW ? "auto_review":"user")
                                    .put("sandbox",policy==ExecutionPermissions.FULL_ACCESS ? "danger-full-access":"workspace-write")
                                    .put("experimentalRawEvents",false);
                            String method="thread/start";
                            if(request.resumeSessionId()!=null) {method="thread/resume";params.put("threadId",request.resumeSessionId());}
                            send(writer,Map.of("id",2,"method",method,"params",params));
                        } else if(message.path("id").asInt(-1)==2 && message.has("result")) {
                            String session=message.path("result").path("thread").path("id").asText();
                            if(session.isBlank()) throw new IOException("Codex did not provide a session identifier");
                            result.setSessionId(session);
                            send(writer,Map.of("id",3,"method","turn/start","params",Map.of("threadId",session,
                                    "effort",request.reasoningLevel(),"input",List.of(Map.of("type","text","text",request.prompt(),"text_elements",List.of())))));
                        } else if(message.path("id").asInt(-1)==3 && message.has("result")) {
                            currentTurn=message.path("result").path("turn").path("id").asText();
                        } else if(message.has("method") && message.has("id")) {
                            long before=System.nanoTime();
                            answer(writer,process,request,harness,transport,result.getSessionId(),message,
                                    message.path("method").asText(),message.path("params"),true,resolvedRequests);
                            waited+=System.nanoTime()-before;
                        } else {
                            String method=message.path("method").asText();
                            JsonNode params=message.path("params");
                            if(method.equals("turn/started")) currentTurn=params.path("turn").path("id").asText();
                            if(callback!=null && method.startsWith("item/") && !method.equals("item/agentMessage/delta")) callback.accept(line);
                            if(method.equals("item/completed") && params.path("item").path("type").asText().equals("agentMessage")) {
                                String content=params.path("item").path("text").asText();
                                text.append(content).append('\n');
                                if(callback!=null) callback.accept(content);
                            } else if(method.equals("thread/tokenUsage/updated") && Objects.equals(currentTurn,params.path("turnId").asText())) {
                                JsonNode usage=params.path("tokenUsage");
                                long inputTotal=usage.path("total").path("inputTokens").asLong();
                                long outputTotal=usage.path("total").path("outputTokens").asLong();
                                result.setInputTokens(result.getInputTokens()+(previousInputTotal<0 || inputTotal<previousInputTotal
                                        ? usage.path("last").path("inputTokens").asLong() : inputTotal-previousInputTotal));
                                result.setOutputTokens(result.getOutputTokens()+(previousOutputTotal<0 || outputTotal<previousOutputTotal
                                        ? usage.path("last").path("outputTokens").asLong() : outputTotal-previousOutputTotal));
                                previousInputTotal=inputTotal;previousOutputTotal=outputTotal;
                            } else if(method.equals("turn/completed")) {
                                done=true;
                                result.setSuccess(params.path("turn").path("status").asText().equals("completed"));
                                if(!result.isSuccess()) result.setErrorMessage(params.path("turn").path("error").path("message").asText("Assistant turn did not complete"));
                            }
                        }
                    } else {
                        String type=message.path("type").asText();
                        if(type.equals("control_response") && message.path("response").path("request_id").asText().equals("initialize")) {
                            if(!message.path("response").path("subtype").asText().equals("success")) throw new IOException("Claude native initialization failed");
                            send(writer,Map.of("type","user","message",Map.of("role","user","content",request.prompt())));
                        } else if(type.equals("control_request")) {
                            JsonNode payload=message.path("request");
                            long before=System.nanoTime();
                            answer(writer,process,request,harness,transport,result.getSessionId(),message,
                                    payload.path("subtype").asText(),payload,false,resolvedRequests);
                            waited+=System.nanoTime()-before;
                        } else {
                            if(message.has("session_id")) result.setSessionId(message.path("session_id").asText());
                            if(type.equals("result")) { result=claudeParser.parse(raw.toString()); done=true; }
                            else if(type.equals("assistant") && callback!=null) callback.accept(line);
                        }
                    }
                }
            }
        } catch(HarnessInputInterruptedException interrupted) {
            throw interrupted;
        } catch(InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new HarnessInputInterruptedException("Assistant run interrupted; any pending input is preserved for recovery");
        } catch(Exception failure) {
            result.setSuccess(false); result.setErrorMessage(failure.getMessage());
        } finally {
            input.disconnect(transport);
            WorkflowCancellationService.terminateProcessTree(process);
            readers.forEach(Thread::interrupt);
            cancellation.unregisterProcess(request.issueId());
        }
        if(codex) {result.setOutput(text.toString());result.setFinalResult(text.toString());}
        result.setDurationMs(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started-waited));
        return result;
    }

    private void answer(Writer writer, Process process, HarnessExecutionRequest request, String harness,
                        String transport, String session, JsonNode message, String method, JsonNode payload,
                        boolean codex, Set<String> resolvedRequests) throws Exception {
        if(!NativeInputProtocol.supported(method,payload)) {
            if(codex) send(writer,Map.of("id",message.get("id"),"error",Map.of("code",-32601,"message","Unsupported native input request")));
            else send(writer,Map.of("type","control_response","response",Map.of("subtype","error",
                    "request_id",message.path("request_id").asText(),"error","Unsupported native input request")));
            return;
        }
        String nativeId=codex ? message.get("id").toString() : message.path("request_id").asText();
        if(resolvedRequests.remove(nativeId)) return;
        var pending=input.open(request.issueId(),harness,transport,nativeId,session,method,payload.toString());
        String response=input.await(pending.getId(),()->{
            if(resolvedRequests.remove(nativeId)) input.withdraw(pending.getId());
            return process.isAlive()&&!cancellation.isCancelled(request.issueId());
        });
        if(response==null) return;
        try {
            if(codex) send(writer,Map.of("id",message.get("id"),"result",json.readTree(response)));
            else send(writer,Map.of("type","control_response","response",Map.of("subtype","success",
                    "request_id",nativeId,"response",json.readTree(response))));
            input.delivered(pending.getId());
        } catch(Exception deliveryFailure) {
            throw new HarnessInputInterruptedException("The answer could not be confirmed delivered. Recovery is required; it will not be replayed automatically.");
        }
    }

    static List<String> command(boolean codex, ExecutionPermissions policy, HarnessExecutionRequest request,
                                boolean subagents, boolean network) {
        if(codex) return List.of("codex","app-server","--stdio","-c","forced_login_method=\"chatgpt\"",
                "-c","model_provider=\"openai\"","-c","sandbox_workspace_write.network_access="+network,
                subagents ? "--enable":"--disable","multi_agent");
        List<String> command=new ArrayList<>(List.of("claude","-p","--input-format","stream-json",
                "--output-format","stream-json","--verbose","--permission-prompt-tool","stdio",
                "--permission-mode",switch(policy){case ASK->"default";case AUTO_REVIEW->"auto";case FULL_ACCESS->"bypassPermissions";},
                "--model",request.model(),"--effort",request.reasoningLevel(),"--setting-sources","",
                "--settings","{\"apiKeyHelper\":\"\",\"forceLoginMethod\":\"claudeai\"}"));
        if(request.resumeSessionId()!=null) command.addAll(List.of("--resume",request.resumeSessionId()));
        return command;
    }

    private void send(Writer writer,Object value) throws IOException {writer.write(json.writeValueAsString(value));writer.write('\n');writer.flush();}
    private static Thread reader(InputStream stream,BlockingQueue<String> target,Consumer<String> observer) {
        Thread thread=Thread.ofVirtual().start(()->{
            try(var reader=new BufferedReader(new InputStreamReader(stream,StandardCharsets.UTF_8))) {
                String line;
                while((line=reader.readLine())!=null) {
                    if(line.length()>1_000_000) break;
                    if(observer!=null) {observer.accept(line);target.put(line);}
                    else {if(!target.offer(line)){target.poll();target.offer(line);}}
                }
            } catch(IOException ignored) { /* The owner observes process exit. */ }
            catch(InterruptedException interrupted) {Thread.currentThread().interrupt();}
        });
        return thread;
    }
    protected Process start(ProcessBuilder builder) throws IOException { return builder.start(); }
}

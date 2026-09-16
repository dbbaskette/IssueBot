package com.dbbaskette.issuebot.controller;

import com.dbbaskette.issuebot.model.*;
import com.dbbaskette.issuebot.repository.WatchedRepoRepository;
import com.dbbaskette.issuebot.service.harness.*;
import com.fasterxml.jackson.databind.*;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Controller
public class HarnessInputController {
    private final HarnessInputService inputs;
    private final WatchedRepoRepository repos;
    private final ObjectMapper json;
    public HarnessInputController(HarnessInputService inputs, WatchedRepoRepository repos, ObjectMapper json) {
        this.inputs=inputs;this.repos=repos;this.json=json;
    }
    public record RequestView(Long id, String harness, String session, String state, boolean actionable,
                              boolean question, String summary, String allowLabel, String details, List<NativeInputProtocol.Question> questions) {}

    @GetMapping("/issues/{issueId}/assistant-input")
    public String panel(@PathVariable Long issueId, Model model) {
        List<RequestView> views=new ArrayList<>();
        for(var request:inputs.history(issueId)) {
            try {
                JsonNode payload=json.readTree(request.getPayload());
                boolean connected=inputs.isConnected(request);
                String state=request.getState()==HarnessInputRequest.State.DELIVERED ? "Answer sent"
                        : request.getState()==HarnessInputRequest.State.WITHDRAWN ? "Request withdrawn by assistant"
                        : !connected ? "Connection lost — recovery needed"
                        : request.getState()==HarnessInputRequest.State.ANSWERED ? "Sending your answer" : "Waiting for your input";
                boolean question=NativeInputProtocol.isQuestion(request.getMethod(),payload);
                views.add(new RequestView(request.getId(),request.getHarness(),request.getSessionId(),state,
                        connected && request.getState()==HarnessInputRequest.State.WAITING,question,
                        payload.path("command").asText(payload.path("input").path("command").asText(
                                payload.path("reason").asText(payload.path("tool_name").asText("Assistant requests your decision")))),
                        request.getMethod().equals("item/permissions/requestApproval") ? "Allow for this turn" : "Allow once",
                        json.writerWithDefaultPrettyPrinter().writeValueAsString(payload),
                        question ? NativeInputProtocol.questions(request.getMethod(),payload):List.of()));
            } catch(Exception malformed) {
                views.add(new RequestView(request.getId(),request.getHarness(),request.getSessionId(),
                        "Request could not be displayed safely",false,false,"","","",List.of()));
            }
        }
        model.addAttribute("assistantRequests",views);
        model.addAttribute("assistantIssueId",issueId);
        return "fragments/assistant-input :: panel";
    }

    @PostMapping("/issues/{issueId}/assistant-input/{requestId}")
    public String answer(@PathVariable Long issueId,@PathVariable Long requestId,
                         @RequestParam Map<String,String> form,RedirectAttributes redirect) {
        try {
            var request=inputs.history(issueId).stream().filter(r->r.getId().equals(requestId)).findFirst().orElseThrow();
            JsonNode payload=json.readTree(request.getPayload());
            Map<String,String> answers=new HashMap<>();
            var questions=NativeInputProtocol.questions(request.getMethod(),payload);
            for(int i=0;i<questions.size();i++) answers.put(questions.get(i).id(),form.get("answer"+i));
            JsonNode response=NativeInputProtocol.response(request.getMethod(),payload,form.getOrDefault("decision",""),answers);
            inputs.answer(issueId,requestId,response.toString());
            redirect.addFlashAttribute("success","Your response is being sent to the same assistant run.");
        } catch(Exception failure) {
            redirect.addFlashAttribute("error",failure.getMessage()==null ? "This request is no longer available" : failure.getMessage());
        }
        return "redirect:/issues/"+issueId+"#harness-input";
    }

    @PostMapping("/repositories/{repoId}/execution-permissions")
    @Transactional
    public String permissions(@PathVariable Long repoId,@RequestParam ExecutionPermissions permissions,
                              @RequestParam(defaultValue="false") boolean confirmFullAccess,RedirectAttributes redirect) {
        if(permissions==ExecutionPermissions.FULL_ACCESS && !confirmFullAccess) {
            redirect.addFlashAttribute("error","Confirm full access before enabling it. The assistant can act with the service account's host permissions.");
        } else {
            var repo=repos.findById(repoId).orElseThrow();
            repo.setExecutionPermissions(permissions);repos.save(repo);
            redirect.addFlashAttribute("success","Assistant permissions saved for new workflow runs. Current runs keep their original policy.");
        }
        return "redirect:/repositories";
    }
}

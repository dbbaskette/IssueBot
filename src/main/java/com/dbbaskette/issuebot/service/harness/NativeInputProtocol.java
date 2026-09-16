package com.dbbaskette.issuebot.service.harness;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;

/** Small, fail-closed translation boundary: a browser can answer questions, not invent permissions. */
public final class NativeInputProtocol {
    private NativeInputProtocol() {}
    public record Question(String id, String text, List<String> options) {}

    public static boolean isQuestion(String method, JsonNode payload) {
        return method.equals("item/tool/requestUserInput")
                || (method.equals("can_use_tool") && payload.path("tool_name").asText().equals("AskUserQuestion"));
    }

    public static boolean supported(String method, JsonNode payload) {
        return isQuestion(method, payload) || Set.of("item/commandExecution/requestApproval",
                "item/fileChange/requestApproval", "item/permissions/requestApproval", "can_use_tool").contains(method);
    }

    public static List<Question> questions(String method, JsonNode payload) {
        JsonNode items = method.equals("can_use_tool") ? payload.path("input").path("questions") : payload.path("questions");
        List<Question> result = new ArrayList<>();
        for (JsonNode question : items) {
            String text = question.path("question").asText();
            String id = method.equals("can_use_tool") ? text : question.path("id").asText();
            if (id.isBlank()) throw new IllegalArgumentException("Assistant question has no identifier");
            List<String> options = new ArrayList<>();
            question.path("options").forEach(option -> options.add(option.path("label").asText()));
            result.add(new Question(id, text, List.copyOf(options)));
        }
        return List.copyOf(result);
    }

    public static JsonNode response(String method, JsonNode payload, String decision, Map<String,String> answers) {
        if (!supported(method, payload)) throw new IllegalArgumentException("Unsupported assistant request");
        var factory = JsonNodeFactory.instance;
        ObjectNode response = factory.objectNode();
        if (isQuestion(method, payload)) {
            ObjectNode mapped = factory.objectNode();
            var questions = questions(method, payload);
            if (questions.isEmpty()) throw new IllegalArgumentException("Assistant supplied no questions");
            for (Question question : questions) {
                String answer = answers.get(question.id());
                if (answer == null || answer.isBlank() || answer.length() > 4096)
                    throw new IllegalArgumentException("Answer every question (up to 4,096 characters each)");
                if (method.equals("can_use_tool")) mapped.put(question.id(), answer);
                else mapped.putObject(question.id()).putArray("answers").add(answer);
            }
            if (method.equals("can_use_tool")) {
                ObjectNode input = payload.path("input").deepCopy();
                input.set("answers", mapped);
                response.put("behavior", "allow").set("updatedInput", input);
            } else response.set("answers", mapped);
            return response;
        }
        if (!Set.of("allow", "deny").contains(decision)) throw new IllegalArgumentException("Choose allow once or deny");
        boolean allow = decision.equals("allow");
        if (method.equals("can_use_tool")) {
            response.put("behavior", allow ? "allow" : "deny");
            if (allow) response.set("updatedInput", payload.path("input"));
            else response.put("message", "The operator denied this action. Continue with a safe alternative.");
        } else if (method.equals("item/permissions/requestApproval")) {
            response.set("permissions", allow ? payload.path("permissions") : factory.objectNode());
            response.put("scope", "turn");
        } else {
            String nativeDecision = allow ? "accept" : "decline";
            JsonNode choices = payload.path("availableDecisions");
            if (choices.isArray() && !choices.isEmpty()) {
                boolean found = false;
                for (JsonNode choice : choices) if (choice.asText().equals(nativeDecision)) found = true;
                if (!found) throw new IllegalArgumentException("This assistant version does not offer that decision");
            }
            response.put("decision", nativeDecision);
        }
        return response;
    }
}

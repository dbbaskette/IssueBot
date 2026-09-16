package com.dbbaskette.issuebot.service.harness;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class NativeInputProtocolTest {
    private final ObjectMapper json=new ObjectMapper();
    @Test void codexPermissionsCannotBeExpandedOrPersistedByTheBrowser() throws Exception {
        var payload=json.readTree("{\"permissions\":{\"network\":{\"enabled\":true}}}");
        var allowed=NativeInputProtocol.response("item/permissions/requestApproval",payload,"allow",Map.of());
        assertThat(allowed.path("permissions")).isEqualTo(payload.path("permissions"));
        assertThat(allowed.path("scope").asText()).isEqualTo("turn");
        var denied=NativeInputProtocol.response("item/permissions/requestApproval",payload,"deny",Map.of());
        assertThat(denied.path("permissions").isEmpty()).isTrue();
        assertThatThrownBy(()->NativeInputProtocol.response("item/permissions/requestApproval",payload,"allow-all",Map.of())).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void codexQuestionsRequireEveryAnswer() throws Exception {
        var payload=json.readTree("{\"questions\":[{\"id\":\"destination\",\"question\":\"Where?\",\"options\":[{\"label\":\"Local\"}]}]}");
        assertThatThrownBy(()->NativeInputProtocol.response("item/tool/requestUserInput",payload,"",Map.of())).isInstanceOf(IllegalArgumentException.class);
        var response=NativeInputProtocol.response("item/tool/requestUserInput",payload,"",Map.of("destination","Local"));
        assertThat(response.at("/answers/destination/answers/0").asText()).isEqualTo("Local");
    }
    @Test void claudePreservesToolInputAndNeverAcceptsSuggestedPersistentRules() throws Exception {
        var payload=json.readTree("{\"tool_name\":\"Bash\",\"input\":{\"command\":\"docker ps\"},\"permission_suggestions\":[{\"dangerous\":true}]}");
        var response=NativeInputProtocol.response("can_use_tool",payload,"allow",Map.of());
        assertThat(response.path("behavior").asText()).isEqualTo("allow");
        assertThat(response.path("updatedInput")).isEqualTo(payload.path("input"));
        assertThat(response.has("updatedPermissions")).isFalse();
        assertThat(NativeInputProtocol.response("can_use_tool",payload,"deny",Map.of()).path("behavior").asText()).isEqualTo("deny");
    }
    @Test void claudeQuestionsUseOriginalQuestionKeys() throws Exception {
        var payload=json.readTree("{\"tool_name\":\"AskUserQuestion\",\"input\":{\"questions\":[{\"question\":\"Which database?\"}]}}");
        var response=NativeInputProtocol.response("can_use_tool",payload,"",Map.of("Which database?","Postgres"));
        assertThat(response.path("updatedInput").path("answers").path("Which database?").asText()).isEqualTo("Postgres");
    }
    @Test void unavailableNativeDecisionsFailClosed() throws Exception {
        var payload=json.readTree("{\"availableDecisions\":[\"cancel\"]}");
        assertThatThrownBy(()->NativeInputProtocol.response("item/commandExecution/requestApproval",payload,"allow",Map.of())).isInstanceOf(IllegalArgumentException.class);
        assertThat(NativeInputProtocol.supported("unknown",payload)).isFalse();
    }
}

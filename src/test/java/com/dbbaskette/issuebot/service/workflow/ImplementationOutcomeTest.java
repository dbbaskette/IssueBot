package com.dbbaskette.issuebot.service.workflow;

import com.dbbaskette.issuebot.service.harness.HarnessExecutionResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ImplementationOutcomeTest {
    private final ObjectMapper mapper = new ObjectMapper();

    private HarnessExecutionResult result(String finalText) {
        HarnessExecutionResult value = new HarnessExecutionResult();
        value.setSuccess(true);
        value.setFinalResult(finalText);
        return value;
    }

    @Test
    void parsesBoundedCompleteOutcomeFromLastMessage() {
        var parsed = ImplementationOutcome.parse(result("Implemented all steps.\n"
                + "ISSUEBOT_IMPLEMENTATION_V1: {\"status\":\"COMPLETE\",\"summary\":\"Done\","
                + "\"checks\":[{\"command\":\"./mvnw test\",\"result\":\"PASS\"}],"
                + "\"limitations\":\"\"}"), mapper);
        assertThat(parsed.status()).isEqualTo(ImplementationOutcome.Status.COMPLETE);
        assertThat(parsed.checks()).containsExactly(new ImplementationOutcome.Check("./mvnw test", "PASS"));
    }

    @Test
    void rejectsCliCompletionWithoutSemanticOutcome() {
        assertThatThrownBy(() -> ImplementationOutcome.parse(result("I made partial progress"), mapper))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("omitted");
    }

    @Test
    void parsesBlockedOutcomeWithoutTreatingItAsSuccess() {
        var parsed = ImplementationOutcome.parse(result("ISSUEBOT_IMPLEMENTATION_V1: "
                + "{\"status\":\"BLOCKED\",\"summary\":\"Maven Central unavailable\","
                + "\"checks\":[],\"limitations\":\"No compile proof\"}"), mapper);
        assertThat(parsed.status()).isEqualTo(ImplementationOutcome.Status.BLOCKED);
        assertThat(parsed.summary()).contains("Maven Central");
    }

    @Test
    void rejectsMultipleOrMalformedMarkers() {
        String valid = "ISSUEBOT_IMPLEMENTATION_V1: {\"status\":\"CONTINUE\","
                + "\"summary\":\"More work\",\"checks\":[],\"limitations\":\"\"}";
        assertThatThrownBy(() -> ImplementationOutcome.parse(result(valid + "\n" + valid), mapper))
                .hasMessageContaining("multiple");
        assertThatThrownBy(() -> ImplementationOutcome.parse(result("ISSUEBOT_IMPLEMENTATION_V1: {}"), mapper))
                .hasMessageContaining("must contain");
    }
}

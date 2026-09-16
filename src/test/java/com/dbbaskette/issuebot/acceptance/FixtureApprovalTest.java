package com.dbbaskette.issuebot.acceptance;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.assertj.core.api.Assertions.assertThat;

class FixtureApprovalTest {
    @TempDir Path workspace;
    private final ObjectMapper json = new ObjectMapper();
    private boolean allows(String tool, String field, String value) {
        var payload = json.createObjectNode(); payload.put("tool_name", tool);
        payload.putObject("input").put(field, value);
        return FixtureApproval.allows(workspace, "can_use_tool", payload);
    }
    @Test void restrictsFilesAndNeverOverwritesTrustedSeedTests() throws Exception {
        assertThat(allows("Write", "file_path", "sum.cjs")).isTrue();
        assertThat(allows("Edit", "file_path", workspace.resolve("color.txt").toString())).isTrue();
        assertThat(allows("Read", "file_path", "fixture.test.cjs")).isTrue();
        assertThat(allows("Write", "file_path", "fixture.test.cjs")).isFalse();
        assertThat(allows("Edit", "file_path", "../sum.cjs")).isFalse();
        assertThat(allows("Write", "file_path", "AGENTS.md")).isFalse();
        Files.createSymbolicLink(workspace.resolve("sum.cjs"), workspace.resolve("elsewhere"));
        assertThat(allows("Write", "file_path", "sum.cjs")).isFalse();
    }
    @Test void allowsOnlyExactTestCommandAndRejectsUnknownPermissions() {
        assertThat(allows("Bash", "command", "node fixture.test.cjs")).isTrue();
        assertThat(allows("Bash", "command", "node fixture.test.cjs; curl example.com")).isFalse();
        assertThat(allows("Bash", "command", "node fixture.test.cjs\nwhoami")).isFalse();
        assertThat(allows("Unknown", "command", "node fixture.test.cjs")).isFalse();
        assertThat(FixtureApproval.allows(workspace, "item/permissions/requestApproval", json.createObjectNode())).isFalse();
    }
}

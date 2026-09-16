package com.dbbaskette.issuebot.acceptance;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.*;
import java.util.Set;

/** Test-only, fail-closed approval policy. Never used for real operator requests. */
final class FixtureApproval {
    private FixtureApproval() {}
    static boolean allows(Path workspace, String method, JsonNode payload) {
        if (!method.equals("can_use_tool")) return false;
        String tool = payload.path("tool_name").asText();
        String file = payload.path("input").path("file_path").asText();
        if (Set.of("Write", "Edit", "Read").contains(tool) && !file.isBlank()) {
            Path target = workspace.resolve(file).normalize();
            return Set.of(workspace.resolve("sum.cjs"), workspace.resolve("color.txt"), workspace.resolve("fixture.test.cjs")).contains(target)
                    && !Files.isSymbolicLink(target) && (tool.equals("Read") || !target.endsWith("fixture.test.cjs"));
        }
        return tool.equals("Bash") && payload.path("input").path("command").asText().equals("node fixture.test.cjs");
    }
}

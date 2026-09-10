package com.dbbaskette.issuebot.service.codex;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Discovers the models visible to the operator's installed, authenticated Codex CLI. */
@Service
public class CodexModelCatalog {

    private static final Logger log = LoggerFactory.getLogger(CodexModelCatalog.class);
    private static final Duration CACHE_TTL = Duration.ofMinutes(5);
    public static final List<String> REASONING_LEVELS = List.of(
            "low", "medium", "high", "xhigh", "max", "ultra");
    private static final ModelInfo ASTRA = new ModelInfo(
            "gpt-6-astra", "GPT-6-Astra", "Most capable Codex model for complex, demanding work.",
            "medium", List.of("low", "medium", "high", "xhigh", "max", "ultra"));
    private static final List<ModelInfo> FALLBACK = List.of(
            ASTRA,
            new ModelInfo("gpt-5.6-sol", "GPT-5.6-Sol", "Most capable model for complex, demanding work.",
                    "low", List.of("low", "medium", "high", "xhigh", "max", "ultra")),
            new ModelInfo("gpt-5.6-terra", "GPT-5.6-Terra", "Balanced agentic coding model for everyday work.",
                    "medium", List.of("low", "medium", "high", "xhigh", "max", "ultra")),
            new ModelInfo("gpt-5.6-luna", "GPT-5.6-Luna", "Fast and affordable agentic coding model.",
                    "medium", List.of("low", "medium", "high", "xhigh", "max")),
            new ModelInfo("gpt-5.5", "GPT-5.5", "Proven previous-generation coding model.",
                    "medium", List.of("low", "medium", "high", "xhigh")),
            new ModelInfo("gpt-5.3-codex-spark", "GPT-5.3-Codex-Spark", "Ultra-fast coding model.",
                    "high", List.of("low", "medium", "high", "xhigh")));

    private final ObjectMapper objectMapper;
    private volatile List<ModelInfo> cached;
    private volatile Instant cachedAt;

    public CodexModelCatalog(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public List<ModelInfo> models() {
        List<ModelInfo> current = cached;
        if (current != null && cachedAt != null
                && cachedAt.plus(CACHE_TTL).isAfter(Instant.now())) return current;

        try {
            List<ModelInfo> discovered = discover(List.of("codex", "debug", "models"));
            // Current Codex builds require a TTY for this debug command. macOS `script`
            // supplies one for a background launchd service while keeping discovery read-only.
            if (discovered.isEmpty() && java.nio.file.Files.isExecutable(java.nio.file.Path.of("/usr/bin/script"))) {
                discovered = discover(List.of("/usr/bin/script", "-q", "/dev/null",
                        "codex", "debug", "models"));
            }
            if (!discovered.isEmpty()) {
                if (discovered.stream().noneMatch(model -> ASTRA.id().equals(model.id()))) {
                    List<ModelInfo> withAstra = new ArrayList<>();
                    withAstra.add(ASTRA);
                    withAstra.addAll(discovered);
                    discovered = List.copyOf(withAstra);
                }
                cached = discovered;
                cachedAt = Instant.now();
                return discovered;
            }
        } catch (Exception e) {
            log.debug("Codex model discovery failed: {}", e.getMessage());
        }
        cached = FALLBACK;
        cachedAt = Instant.now();
        return FALLBACK;
    }

    private List<ModelInfo> discover(List<String> command) throws Exception {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        process.getOutputStream().close();
        var output = new java.util.concurrent.FutureTask<String>(() ->
                new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        Thread.ofVirtual().start(output);
        try {
            if (!process.waitFor(10, TimeUnit.SECONDS) || process.exitValue() != 0) return List.of();
            return parseCatalog(objectMapper, output.get(1, TimeUnit.SECONDS));
        } finally {
            if (process.isAlive()) process.destroyForcibly();
            process.getInputStream().close();
            output.cancel(true);
        }
    }

    static List<ModelInfo> parseCatalog(ObjectMapper objectMapper, String json) throws Exception {
        int start = json.indexOf("{\"models\"");
        int end = json.lastIndexOf('}');
        if (start < 0 || end < start) return List.of();
        JsonNode root = objectMapper.readTree(json.substring(start, end + 1));
        List<ModelInfo> models = new ArrayList<>();
        for (JsonNode model : root.path("models")) {
            if (!"list".equals(model.path("visibility").asText())) continue;
            String id = model.path("slug").asText("");
            if (id.isBlank()) continue;
            List<String> levels = new ArrayList<>();
            for (JsonNode level : model.path("supported_reasoning_levels")) {
                String effort = level.path("effort").asText("");
                if (!effort.isBlank()) levels.add(effort);
            }
            String defaultLevel = model.path("default_reasoning_level").asText("");
            if (levels.isEmpty()) levels.addAll(REASONING_LEVELS);
            if (defaultLevel.isBlank()) defaultLevel = levels.getFirst();
            models.add(new ModelInfo(id, model.path("display_name").asText(id),
                    model.path("description").asText(""), defaultLevel, List.copyOf(levels)));
        }
        return List.copyOf(models);
    }

    public boolean contains(String modelId) {
        return models().stream().anyMatch(model -> model.id().equals(modelId));
    }

    public static List<ModelInfo> fallbackModels() { return FALLBACK; }

    public record ModelInfo(String id, String displayName, String description,
                            String defaultReasoningLevel, List<String> supportedReasoningLevels) {
        public ModelInfo(String id, String displayName, String description) {
            this(id, displayName, description, "medium", REASONING_LEVELS);
        }

        public String reasoningLevelsCsv() {
            return String.join(",", supportedReasoningLevels);
        }
    }
}

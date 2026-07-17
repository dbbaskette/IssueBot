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
    private static final List<ModelInfo> FALLBACK = List.of(
            new ModelInfo("gpt-5.6-sol", "GPT-5.6-Sol", "Latest frontier agentic coding model."),
            new ModelInfo("gpt-5.6-terra", "GPT-5.6-Terra", "Balanced agentic coding model for everyday work."),
            new ModelInfo("gpt-5.6-luna", "GPT-5.6-Luna", "Fast agentic coding model."),
            new ModelInfo("gpt-5.5", "GPT-5.5", "Frontier model for complex coding and research."),
            new ModelInfo("gpt-5.4", "GPT-5.4", "Strong model for everyday coding."),
            new ModelInfo("gpt-5.4-mini", "GPT-5.4-Mini", "Small model for simpler coding tasks."));

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
            Process process = new ProcessBuilder("codex", "debug", "models").start();
            String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            boolean finished = process.waitFor(10, TimeUnit.SECONDS);
            if (!finished) process.destroyForcibly();
            if (finished && process.exitValue() == 0) {
                List<ModelInfo> discovered = parseCatalog(objectMapper, stdout);
                if (!discovered.isEmpty()) {
                    cached = discovered;
                    cachedAt = Instant.now();
                    return discovered;
                }
            }
            log.debug("Codex model discovery unavailable: {}", stderr.trim());
        } catch (Exception e) {
            log.debug("Codex model discovery failed: {}", e.getMessage());
        }
        return FALLBACK;
    }

    static List<ModelInfo> parseCatalog(ObjectMapper objectMapper, String json) throws Exception {
        JsonNode root = objectMapper.readTree(json);
        List<ModelInfo> models = new ArrayList<>();
        for (JsonNode model : root.path("models")) {
            if (!"list".equals(model.path("visibility").asText())) continue;
            String id = model.path("slug").asText("");
            if (id.isBlank()) continue;
            models.add(new ModelInfo(id, model.path("display_name").asText(id),
                    model.path("description").asText("")));
        }
        return List.copyOf(models);
    }

    public boolean contains(String modelId) {
        return models().stream().anyMatch(model -> model.id().equals(modelId));
    }

    public static List<ModelInfo> fallbackModels() { return FALLBACK; }

    public record ModelInfo(String id, String displayName, String description) { }
}

package com.dbbaskette.issuebot.service.tool;

import com.dbbaskette.issuebot.config.IssueBotProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class ConfigTool {

    private static final Logger log = LoggerFactory.getLogger(ConfigTool.class);

    private final IssueBotProperties properties;
    private final ObjectMapper objectMapper;

    public ConfigTool(IssueBotProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public String getRepoConfig(
            String owner,
            String name) {
        try {
            for (IssueBotProperties.RepositoryConfig repo : properties.getRepositories()) {
                if (repo.getOwner().equals(owner) && repo.getName().equals(name)) {
                    Map<String, Object> config = new LinkedHashMap<>();
                    config.put("owner", repo.getOwner());
                    config.put("name", repo.getName());
                    config.put("branch", repo.getBranch());
                    config.put("mode", repo.getMode());
                    config.put("maxIterations", repo.getMaxIterations());
                    config.put("ciTimeoutMinutes", repo.getCiTimeoutMinutes());
                    config.put("allowedPaths", repo.getAllowedPaths());
                    return objectMapper.writeValueAsString(config);
                }
            }
            return "{\"error\": \"Repository not found in configuration: " + owner + "/" + name + "\"}";
        } catch (Exception e) {
            log.error("Failed to get repo config for {}/{}", owner, name, e);
            return "{\"error\": \"" + e.getMessage().replace("\"", "'") + "\"}";
        }
    }

    public String getGlobalConfig() {
        try {
            Map<String, Object> config = new LinkedHashMap<>();
            config.put("pollIntervalSeconds", properties.getPollIntervalSeconds());
            config.put("maxConcurrentIssues", properties.getMaxConcurrentIssues());
            config.put("workDirectory", properties.getWorkDirectory());
            config.put("claudeCode", Map.of(
                    "maxTurnsPerInvocation", properties.getClaudeCode().getMaxTurnsPerInvocation(),
                    "model", properties.getClaudeCode().getModel(),
                    "timeoutMinutes", properties.getClaudeCode().getTimeoutMinutes()
            ));
            config.put("notifications", Map.of(
                    "desktop", properties.getNotifications().isDesktop(),
                    "dashboard", properties.getNotifications().isDashboard()
            ));
            config.put("repositoryCount", properties.getRepositories().size());
            return objectMapper.writeValueAsString(config);
        } catch (Exception e) {
            log.error("Failed to get global config", e);
            return "{\"error\": \"" + e.getMessage().replace("\"", "'") + "\"}";
        }
    }
}

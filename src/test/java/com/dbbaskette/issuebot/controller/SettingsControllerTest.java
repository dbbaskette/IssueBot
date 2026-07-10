package com.dbbaskette.issuebot.controller;

import com.dbbaskette.issuebot.config.IssueBotProperties;
import com.dbbaskette.issuebot.repository.TrackedIssueRepository;
import com.dbbaskette.issuebot.service.polling.IssuePollingService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class SettingsControllerTest {

    @TempDir
    Path tempDir;

    private SettingsController controller(IssueBotProperties properties, Path configFile) {
        SettingsController controller = new SettingsController(properties,
                mock(IssuePollingService.class), mock(TrackedIssueRepository.class));
        controller.setConfigPathForTests(configFile);
        return controller;
    }

    @Test
    void modelSaveUpdatesBeanAndYaml() throws Exception {
        Path configFile = tempDir.resolve("config.yml");
        String yaml = "issuebot:\n"
                + "  poll-interval-seconds: 45\n"
                + "  claude-code:\n"
                + "    implementation-model: claude-opus-4-6\n";
        Files.writeString(configFile, yaml);

        IssueBotProperties properties = new IssueBotProperties();
        SettingsController controller = controller(properties, configFile);

        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();
        String view = controller.saveModels("claude-opus-4-8", "claude-sonnet-5", "claude-haiku-4-5",
                redirectAttributes);

        assertThat(view).isEqualTo("redirect:/settings");

        // Bean updated live.
        assertThat(properties.getClaudeCode().getImplementationModel()).isEqualTo("claude-opus-4-8");
        assertThat(properties.getClaudeCode().getReviewModel()).isEqualTo("claude-sonnet-5");
        assertThat(properties.getClaudeCode().getUtilityModel()).isEqualTo("claude-haiku-4-5");

        // File written with the three new keys, and unrelated keys retained.
        String written = Files.readString(configFile);
        assertThat(written).contains("implementation-model: claude-opus-4-8");
        assertThat(written).contains("review-model: claude-sonnet-5");
        assertThat(written).contains("utility-model: claude-haiku-4-5");
        assertThat(written).contains("poll-interval-seconds: 45");

        assertThat(redirectAttributes.getFlashAttributes().get("success"))
                .isNotNull()
                .asString().contains("no restart needed");
    }

    @Test
    void modelSaveRejectsBlank() throws Exception {
        Path configFile = tempDir.resolve("config.yml");
        String yaml = "issuebot:\n  poll-interval-seconds: 45\n";
        Files.writeString(configFile, yaml);
        byte[] originalBytes = Files.readAllBytes(configFile);

        IssueBotProperties properties = new IssueBotProperties();
        properties.getClaudeCode().setImplementationModel("sentinel-impl");
        properties.getClaudeCode().setReviewModel("sentinel-review");
        properties.getClaudeCode().setUtilityModel("sentinel-utility");
        SettingsController controller = controller(properties, configFile);

        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();
        String view = controller.saveModels("", "x", "y", redirectAttributes);

        assertThat(view).isEqualTo("redirect:/settings");

        // File untouched.
        assertThat(Files.readAllBytes(configFile)).isEqualTo(originalBytes);

        // Bean untouched.
        assertThat(properties.getClaudeCode().getImplementationModel()).isEqualTo("sentinel-impl");
        assertThat(properties.getClaudeCode().getReviewModel()).isEqualTo("sentinel-review");
        assertThat(properties.getClaudeCode().getUtilityModel()).isEqualTo("sentinel-utility");

        assertThat(redirectAttributes.getFlashAttributes().get("error")).isNotNull();
    }

    @Test
    void modelSaveRejectsCustomSentinel() throws Exception {
        Path configFile = tempDir.resolve("config.yml");
        String yaml = "issuebot:\n  poll-interval-seconds: 45\n";
        Files.writeString(configFile, yaml);
        byte[] originalBytes = Files.readAllBytes(configFile);

        IssueBotProperties properties = new IssueBotProperties();
        properties.getClaudeCode().setImplementationModel("sentinel-impl");
        properties.getClaudeCode().setReviewModel("sentinel-review");
        properties.getClaudeCode().setUtilityModel("sentinel-utility");
        SettingsController controller = controller(properties, configFile);

        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();
        String view = controller.saveModels("__custom__", "claude-sonnet-5", "claude-haiku-4-5",
                redirectAttributes);

        assertThat(view).isEqualTo("redirect:/settings");

        // File untouched.
        assertThat(Files.readAllBytes(configFile)).isEqualTo(originalBytes);

        // Bean untouched.
        assertThat(properties.getClaudeCode().getImplementationModel()).isEqualTo("sentinel-impl");
        assertThat(properties.getClaudeCode().getReviewModel()).isEqualTo("sentinel-review");
        assertThat(properties.getClaudeCode().getUtilityModel()).isEqualTo("sentinel-utility");

        assertThat(redirectAttributes.getFlashAttributes().get("error"))
                .isNotNull()
                .asString().contains("custom model ID");
    }

    @Test
    void modelSaveCreatesMissingConfigFileAndTrimsValues() throws Exception {
        // Config path in a directory that does not exist yet — exercises the
        // createDirectories branch plus the file-absent load path.
        Path configFile = tempDir.resolve("nested").resolve("config.yml");
        assertThat(Files.exists(configFile)).isFalse();

        IssueBotProperties properties = new IssueBotProperties();
        SettingsController controller = controller(properties, configFile);

        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();
        String view = controller.saveModels("  claude-opus-4-8  ", " claude-sonnet-5", "claude-haiku-4-5 ",
                redirectAttributes);

        assertThat(view).isEqualTo("redirect:/settings");

        // File created with the three keys, values trimmed.
        assertThat(Files.exists(configFile)).isTrue();
        String written = Files.readString(configFile);
        assertThat(written).contains("implementation-model: claude-opus-4-8");
        assertThat(written).contains("review-model: claude-sonnet-5");
        assertThat(written).contains("utility-model: claude-haiku-4-5");

        // Bean updated with trimmed values.
        assertThat(properties.getClaudeCode().getImplementationModel()).isEqualTo("claude-opus-4-8");
        assertThat(properties.getClaudeCode().getReviewModel()).isEqualTo("claude-sonnet-5");
        assertThat(properties.getClaudeCode().getUtilityModel()).isEqualTo("claude-haiku-4-5");

        assertThat(redirectAttributes.getFlashAttributes().get("success")).isNotNull();
    }

    @Test
    void modelSaveRejectsUnparseableYamlWithoutWriting() throws Exception {
        Path configFile = tempDir.resolve("config.yml");
        String badYaml = "issuebot: [unclosed";
        Files.writeString(configFile, badYaml);
        byte[] originalBytes = Files.readAllBytes(configFile);

        IssueBotProperties properties = new IssueBotProperties();
        properties.getClaudeCode().setImplementationModel("sentinel-impl");
        properties.getClaudeCode().setReviewModel("sentinel-review");
        properties.getClaudeCode().setUtilityModel("sentinel-utility");
        SettingsController controller = controller(properties, configFile);

        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();
        String view = controller.saveModels("claude-opus-4-8", "claude-sonnet-5", "claude-haiku-4-5",
                redirectAttributes);

        assertThat(view).isEqualTo("redirect:/settings");

        // File bytes identical to before — never touched.
        assertThat(Files.readAllBytes(configFile)).isEqualTo(originalBytes);

        // Bean untouched.
        assertThat(properties.getClaudeCode().getImplementationModel()).isEqualTo("sentinel-impl");
        assertThat(properties.getClaudeCode().getReviewModel()).isEqualTo("sentinel-review");
        assertThat(properties.getClaudeCode().getUtilityModel()).isEqualTo("sentinel-utility");

        assertThat(redirectAttributes.getFlashAttributes().get("error")).isNotNull();
    }
}

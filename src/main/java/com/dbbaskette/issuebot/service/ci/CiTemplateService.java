package com.dbbaskette.issuebot.service.ci;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Generates GitHub Actions CI workflow files for target repositories.
 * Creates a basic compile+test workflow if none exists.
 */
@Service
public class CiTemplateService {

    private static final Logger log = LoggerFactory.getLogger(CiTemplateService.class);
    private static final String WORKFLOW_DIR = ".github/workflows";
    private static final String WORKFLOW_FILE = "issuebot-ci.yml";

    /**
     * Ensure a CI workflow exists in the target repo.
     * Returns true if a workflow was created, false if one already exists.
     */
    public boolean ensureCiWorkflow(Path repoPath, String buildTool) {
        Path workflowDir = repoPath.resolve(WORKFLOW_DIR);
        Path workflowFile = workflowDir.resolve(WORKFLOW_FILE);

        if (Files.exists(workflowFile)) {
            log.debug("CI workflow already exists at {}", workflowFile);
            return false;
        }

        // Check if any workflow files exist
        if (Files.exists(workflowDir) && hasExistingWorkflows(workflowDir)) {
            log.debug("Existing CI workflows found in {} — skipping template", workflowDir);
            return false;
        }

        String template = generateTemplate(buildTool);
        try {
            Files.createDirectories(workflowDir);
            Files.writeString(workflowFile, template);
            log.info("Created CI workflow at {}", workflowFile);
            return true;
        } catch (IOException e) {
            log.warn("Failed to create CI workflow at {}: {}", workflowFile, e.getMessage());
            return false;
        }
    }

    /**
     * Detect the build tool used by a repository.
     */
    public String detectBuildTool(Path repoPath) {
        if (Files.exists(repoPath.resolve("pom.xml"))) {
            return "maven";
        }
        if (Files.exists(repoPath.resolve("build.gradle"))
                || Files.exists(repoPath.resolve("build.gradle.kts"))) {
            return "gradle";
        }
        if (Files.exists(repoPath.resolve("package.json"))) {
            return "node";
        }
        if (Files.exists(repoPath.resolve("Cargo.toml"))) {
            return "cargo";
        }
        if (Files.exists(repoPath.resolve("go.mod"))) {
            return "go";
        }
        if (Files.exists(repoPath.resolve("requirements.txt"))
                || Files.exists(repoPath.resolve("pyproject.toml"))) {
            return "python";
        }
        return "unknown";
    }

    private String generateTemplate(String buildTool) {
        return switch (buildTool) {
            case "maven" -> mavenTemplate();
            case "gradle" -> gradleTemplate();
            case "node" -> nodeTemplate();
            case "go" -> goTemplate();
            default -> mavenTemplate(); // default to Maven
        };
    }

    private String mavenTemplate() {
        return """
                name: IssueBot CI
                on:
                  push:
                    branches-ignore: [main, master]
                  pull_request:
                    types: [opened, synchronize]

                jobs:
                  build:
                    runs-on: ubuntu-latest
                    steps:
                      - uses: actions/checkout@v4
                      - name: Set up JDK
                        uses: actions/setup-java@v4
                        with:
                          java-version: '21'
                          distribution: 'temurin'
                          cache: maven
                      - name: Build & Test
                        run: mvn -B compile test --no-transfer-progress
                """;
    }

    private String gradleTemplate() {
        return """
                name: IssueBot CI
                on:
                  push:
                    branches-ignore: [main, master]
                  pull_request:
                    types: [opened, synchronize]

                jobs:
                  build:
                    runs-on: ubuntu-latest
                    steps:
                      - uses: actions/checkout@v4
                      - name: Set up JDK
                        uses: actions/setup-java@v4
                        with:
                          java-version: '21'
                          distribution: 'temurin'
                          cache: gradle
                      - name: Build & Test
                        run: ./gradlew build test
                """;
    }

    private String nodeTemplate() {
        return """
                name: IssueBot CI
                on:
                  push:
                    branches-ignore: [main, master]
                  pull_request:
                    types: [opened, synchronize]

                jobs:
                  build:
                    runs-on: ubuntu-latest
                    steps:
                      - uses: actions/checkout@v4
                      - name: Set up Node
                        uses: actions/setup-node@v4
                        with:
                          node-version: '20'
                          cache: 'npm'
                      - name: Install & Test
                        run: |
                          npm ci
                          npm test
                """;
    }

    private String goTemplate() {
        return """
                name: IssueBot CI
                on:
                  push:
                    branches-ignore: [main, master]
                  pull_request:
                    types: [opened, synchronize]

                jobs:
                  build:
                    runs-on: ubuntu-latest
                    steps:
                      - uses: actions/checkout@v4
                      - name: Set up Go
                        uses: actions/setup-go@v5
                        with:
                          go-version: '1.22'
                      - name: Build & Test
                        run: |
                          go build ./...
                          go test ./...
                """;
    }

    private boolean hasExistingWorkflows(Path workflowDir) {
        try (var stream = Files.list(workflowDir)) {
            return stream.anyMatch(p -> p.toString().endsWith(".yml") || p.toString().endsWith(".yaml"));
        } catch (IOException e) {
            return false;
        }
    }
}

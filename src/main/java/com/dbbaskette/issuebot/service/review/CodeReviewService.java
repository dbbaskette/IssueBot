package com.dbbaskette.issuebot.service.review;

import com.dbbaskette.issuebot.service.claude.ClaudeCodeResult;
import com.dbbaskette.issuebot.service.claude.ClaudeCodeService;
import com.dbbaskette.issuebot.service.git.GitOperationsService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.jgit.api.Git;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Executes independent code review using Sonnet 4.6 via Claude CLI.
 * Builds a review prompt, invokes Sonnet, and parses the structured JSON response.
 */
@Service
public class CodeReviewService {

    private static final Logger log = LoggerFactory.getLogger(CodeReviewService.class);

    private final ClaudeCodeService claudeCodeService;
    private final ReviewPromptBuilder reviewPromptBuilder;
    private final GitOperationsService gitOperationsService;
    private final ObjectMapper objectMapper;

    public CodeReviewService(ClaudeCodeService claudeCodeService,
                               ReviewPromptBuilder reviewPromptBuilder,
                               GitOperationsService gitOperationsService,
                               ObjectMapper objectMapper) {
        this.claudeCodeService = claudeCodeService;
        this.reviewPromptBuilder = reviewPromptBuilder;
        this.gitOperationsService = gitOperationsService;
        this.objectMapper = objectMapper;
    }

    /**
     * Execute an independent code review using Sonnet 4.6.
     */
    public CodeReviewResult reviewCode(Path repoPath, String issueTitle, String issueBody,
                                         String baseBranch, boolean securityReview,
                                         Consumer<String> lineCallback) {
        log.info("Starting independent code review in {} against branch {}", repoPath, baseBranch);

        // 1. Get changed files and diff
        List<String> changedFiles;
        String diff;
        try (Git git = Git.open(repoPath.toFile())) {
            changedFiles = getChangedFiles(git, baseBranch);
            diff = gitOperationsService.diff(git, baseBranch);
        } catch (Exception e) {
            log.error("Failed to get diff for review", e);
            return CodeReviewResult.failed("Failed to get diff: " + e.getMessage(), 0, 0, null);
        }

        if (changedFiles.isEmpty()) {
            log.warn("No changed files found for review");
            return CodeReviewResult.failed("No changed files to review", 0, 0, null);
        }

        log.info("Reviewing {} changed files: {}", changedFiles.size(), changedFiles);

        // 2. Build the review prompt
        String prompt = reviewPromptBuilder.buildReviewPrompt(
                issueTitle, issueBody, changedFiles, diff, securityReview);

        // 3. Invoke Sonnet via CLI
        ClaudeCodeResult result = claudeCodeService.executeReview(prompt, repoPath, lineCallback);

        if (!result.isSuccess()) {
            log.error("Sonnet review invocation failed: {}", result.getErrorMessage());
            return CodeReviewResult.failed("Review invocation failed: " + result.getErrorMessage(),
                    result.getInputTokens(), result.getOutputTokens(), result.getModel());
        }

        // 4. Parse JSON response
        return parseReviewResponse(result);
    }

    /**
     * Get list of files changed vs base branch.
     */
    private List<String> getChangedFiles(Git git, String baseBranch) throws Exception {
        var repo = git.getRepository();
        var baseId = repo.resolve("origin/" + baseBranch + "^{tree}");
        if (baseId == null) {
            log.warn("Could not resolve origin/{} for changed files", baseBranch);
            return List.of();
        }

        // Stage everything to capture untracked files
        git.add().addFilepattern(".").call();
        git.add().addFilepattern(".").setUpdate(true).call();

        var reader = repo.newObjectReader();
        var baseTree = new org.eclipse.jgit.treewalk.CanonicalTreeParser();
        baseTree.reset(reader, baseId);

        var diffs = git.diff().setOldTree(baseTree).setCached(true).call();
        List<String> files = new ArrayList<>();
        for (var entry : diffs) {
            String path = entry.getNewPath();
            if ("/dev/null".equals(path)) {
                path = entry.getOldPath();
            }
            files.add(path);
        }
        reader.close();
        return files;
    }

    /**
     * Parse the Sonnet review response JSON from Claude Code output.
     */
    private CodeReviewResult parseReviewResponse(ClaudeCodeResult result) {
        String output = result.getOutput();
        if (output == null || output.isBlank()) {
            return CodeReviewResult.failed("Empty review output",
                    result.getInputTokens(), result.getOutputTokens(), result.getModel());
        }

        try {
            String json = extractJson(output);
            JsonNode root = objectMapper.readTree(json);

            boolean passed = root.path("passed").asBoolean(false);
            String summary = root.path("summary").asText("No summary");
            double specCompliance = root.path("specComplianceScore").asDouble(0.0);
            double correctness = root.path("correctnessScore").asDouble(0.0);
            double codeQuality = root.path("codeQualityScore").asDouble(0.0);
            double testCoverage = root.path("testCoverageScore").asDouble(0.0);
            double architectureFit = root.path("architectureFitScore").asDouble(0.0);
            double regressions = root.path("regressionsScore").asDouble(0.0);
            double security = root.path("securityScore").asDouble(1.0);
            String advice = root.path("advice").asText("");

            List<CodeReviewResult.ReviewFinding> findings = new ArrayList<>();
            JsonNode findingsNode = root.path("findings");
            if (findingsNode.isArray()) {
                for (JsonNode f : findingsNode) {
                    findings.add(new CodeReviewResult.ReviewFinding(
                            f.path("severity").asText("medium"),
                            f.path("category").asText(""),
                            f.path("file").asText(""),
                            f.has("line") && !f.path("line").isNull() ? f.path("line").asInt() : null,
                            f.path("finding").asText(""),
                            f.path("suggestion").asText("")
                    ));
                }
            }

            log.info("Review parsed: passed={}, scores=[spec={}, correct={}, quality={}, tests={}, arch={}, regress={}, sec={}], findings={}",
                    passed, specCompliance, correctness, codeQuality, testCoverage,
                    architectureFit, regressions, security, findings.size());

            return new CodeReviewResult(
                    passed, summary,
                    specCompliance, correctness, codeQuality, testCoverage,
                    architectureFit, regressions, security,
                    findings, advice, json,
                    result.getInputTokens(), result.getOutputTokens(), result.getModel()
            );
        } catch (Exception e) {
            log.warn("Failed to parse review JSON: {}", e.getMessage());
            return CodeReviewResult.failed(
                    "Failed to parse review: " + e.getMessage() + " — raw: "
                            + output.substring(0, Math.min(200, output.length())),
                    result.getInputTokens(), result.getOutputTokens(), result.getModel()
            );
        }
    }

    /**
     * Extract JSON object from output that may contain surrounding text.
     */
    private String extractJson(String output) {
        String trimmed = output.trim();

        // Try direct parse first
        if (trimmed.startsWith("{")) {
            int lastBrace = trimmed.lastIndexOf('}');
            if (lastBrace > 0) {
                return trimmed.substring(0, lastBrace + 1);
            }
        }

        // Try to find JSON between code fences
        int fenceStart = trimmed.indexOf("```json");
        if (fenceStart >= 0) {
            int jsonStart = trimmed.indexOf('\n', fenceStart) + 1;
            int fenceEnd = trimmed.indexOf("```", jsonStart);
            if (fenceEnd > jsonStart) {
                return trimmed.substring(jsonStart, fenceEnd).trim();
            }
        }

        // Find first { and last }
        int braceStart = trimmed.indexOf('{');
        int braceEnd = trimmed.lastIndexOf('}');
        if (braceStart >= 0 && braceEnd > braceStart) {
            return trimmed.substring(braceStart, braceEnd + 1);
        }

        return trimmed;
    }
}

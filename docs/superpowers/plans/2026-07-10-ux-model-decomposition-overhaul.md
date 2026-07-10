# IssueBot Overhaul Implementation Plan — Model Selection, Issue-Noise Control, Dashboard UX

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement the spec at `docs/superpowers/specs/2026-07-10-ux-model-decomposition-overhaul-design.md`: per-role model selection (implementation / review / utility) at global, repo, and issue levels; replace the unbounded follow-up/decomposition issue flood with a rolling backlog and an approval-gated split flow; and land the six in-scope dashboard UX fixes (cancel, honest approve/merge, failure reasons, settings coherence, accurate cost, GitHub deep links).

**Architecture:** Spring Boot 3.4 monolith. New small components (`ModelCatalog`, `ModelResolver`, `BacklogService`, `FollowUpService`, `WorkflowCancellationService`) keep `IssueWorkflowService` from growing; existing services gain explicit-model entry points. One Flyway migration (V11) carries all schema changes. UI stays Thymeleaf + HTMX.

**Tech Stack:** Java 21, Spring Boot 3.4.2, Spring Data JPA + H2 + Flyway, WebClient (GitHub), JUnit 5 + Mockito, Thymeleaf + HTMX.

**Build/test commands:** `./mvnw test` (all), `./mvnw test -Dtest=ClassName` (one class), `./mvnw compile` (fast check). Run app: `./run.sh` (port 8090).

**Phases (each is independently shippable as a PR):**
- Phase 1 (Tasks 1–8): schema + model selection + accurate cost
- Phase 2 (Tasks 9–14): follow-up backlog + decomposition guards/propose flow + failure reasons
- Phase 3 (Tasks 15–18): cancel, honest approve/merge, deep links, settings coherence

---

## Phase 1 — Schema & Model Selection

### Task 1: Flyway V11 migration + entity fields + new enums

**Files:**
- Create: `src/main/resources/db/migration/V11__model_selection_and_issue_noise.sql`
- Create: `src/main/java/com/dbbaskette/issuebot/model/FollowUpMode.java`
- Create: `src/main/java/com/dbbaskette/issuebot/model/DecompositionMode.java`
- Modify: `src/main/java/com/dbbaskette/issuebot/model/WatchedRepo.java`
- Modify: `src/main/java/com/dbbaskette/issuebot/model/TrackedIssue.java`
- Modify: `src/main/java/com/dbbaskette/issuebot/model/Iteration.java`
- Modify: `src/main/java/com/dbbaskette/issuebot/model/IssueStatus.java`

- [ ] **Step 1: Write the migration**

```sql
-- V11__model_selection_and_issue_noise.sql
ALTER TABLE watched_repo ADD COLUMN implementation_model VARCHAR(100);
ALTER TABLE watched_repo ADD COLUMN review_model VARCHAR(100);
ALTER TABLE watched_repo ADD COLUMN follow_up_mode VARCHAR(20) DEFAULT 'ROLLING_BACKLOG' NOT NULL;
ALTER TABLE watched_repo ADD COLUMN decomposition_mode VARCHAR(20) DEFAULT 'PROPOSE' NOT NULL;
ALTER TABLE watched_repo ADD COLUMN pre_screen_enabled BOOLEAN DEFAULT TRUE NOT NULL;
UPDATE watched_repo SET follow_up_mode = 'OFF' WHERE follow_up_enabled = FALSE;

ALTER TABLE tracked_issue ADD COLUMN impl_model_override VARCHAR(100);
ALTER TABLE tracked_issue ADD COLUMN review_model_override VARCHAR(100);
ALTER TABLE tracked_issue ADD COLUMN resolved_impl_model VARCHAR(100);
ALTER TABLE tracked_issue ADD COLUMN resolved_review_model VARCHAR(100);
ALTER TABLE tracked_issue ADD COLUMN last_failure_reason VARCHAR(2000);
ALTER TABLE tracked_issue ADD COLUMN decomposition_proposal CLOB;

ALTER TABLE iteration ADD COLUMN impl_model VARCHAR(100);
```

(`follow_up_enabled` is retained for one release for rollback; a later V12 drops it.)

- [ ] **Step 2: Create the enums**

```java
// FollowUpMode.java
package com.dbbaskette.issuebot.model;

/** How non-blocking review findings are captured after a passing review. */
public enum FollowUpMode {
    OFF,              // findings live only in the PR review comment
    COMMENT_ONLY,     // also summarized as a comment on the original issue
    ROLLING_BACKLOG,  // appended (deduped) to the single per-repo backlog issue
    PER_ISSUE         // legacy: one new follow-up issue per completed issue
}
```

```java
// DecompositionMode.java
package com.dbbaskette.issuebot.model;

/** Whether IssueBot may split a too-large issue into sub-issues. */
public enum DecompositionMode {
    OFF,      // never decompose; escalate to needs-human instead
    PROPOSE,  // post the breakdown as a proposal; human approves from the dashboard
    AUTO      // legacy: create sub-issues immediately
}
```

- [ ] **Step 3: Add entity fields**

`WatchedRepo.java` — add next to the existing `followUpEnabled` field (keep that field mapped for now):

```java
@Column(name = "implementation_model")
private String implementationModel;

@Column(name = "review_model")
private String reviewModel;

@Enumerated(EnumType.STRING)
@Column(name = "follow_up_mode", nullable = false)
private FollowUpMode followUpMode = FollowUpMode.ROLLING_BACKLOG;

@Enumerated(EnumType.STRING)
@Column(name = "decomposition_mode", nullable = false)
private DecompositionMode decompositionMode = DecompositionMode.PROPOSE;

@Column(name = "pre_screen_enabled", nullable = false)
private boolean preScreenEnabled = true;
```

plus standard getters/setters matching the class's existing style.

`TrackedIssue.java` — add:

```java
@Column(name = "impl_model_override")
private String implModelOverride;

@Column(name = "review_model_override")
private String reviewModelOverride;

@Column(name = "resolved_impl_model")
private String resolvedImplModel;

@Column(name = "resolved_review_model")
private String resolvedReviewModel;

@Column(name = "last_failure_reason", length = 2000)
private String lastFailureReason;

@Lob
@Column(name = "decomposition_proposal")
private String decompositionProposal;
```

plus getters/setters.

`Iteration.java` — add:

```java
@Column(name = "impl_model")
private String implModel;
```

plus getter/setter.

`IssueStatus.java` — add constant `AWAITING_DECOMPOSITION` to the enum (after `DECOMPOSED`).

- [ ] **Step 4: Verify the app boots and migrates**

Run: `./mvnw test -Dtest=IssueBotApplicationTests`
Expected: PASS (context loads, Flyway applies V11 to the in-memory DB).

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/db/migration/V11__model_selection_and_issue_noise.sql src/main/java/com/dbbaskette/issuebot/model/
git commit -m "feat: V11 schema — model overrides, follow-up/decomposition modes, failure reason"
```

---

### Task 2: ModelCatalog

**Files:**
- Create: `src/main/java/com/dbbaskette/issuebot/service/claude/ModelCatalog.java`
- Test: `src/test/java/com/dbbaskette/issuebot/service/claude/ModelCatalogTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.dbbaskette.issuebot.service.claude;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import static org.assertj.core.api.Assertions.assertThat;

class ModelCatalogTest {

    @Test
    void findsKnownModel() {
        assertThat(ModelCatalog.find("claude-opus-4-8")).isPresent();
        assertThat(ModelCatalog.find("claude-opus-4-8").get().displayName()).isEqualTo("Claude Opus 4.8");
    }

    @Test
    void unknownModelIsEmpty() {
        assertThat(ModelCatalog.find("my-custom-model")).isEmpty();
        assertThat(ModelCatalog.estimateCost("my-custom-model", 1000, 1000)).isEmpty();
    }

    @Test
    void estimatesCostFromPerMTokPricing() {
        // Opus 4.8: $5/MTok in, $25/MTok out → 1M in + 1M out = $30
        assertThat(ModelCatalog.estimateCost("claude-opus-4-8", 1_000_000, 1_000_000))
                .contains(new BigDecimal("30.000000"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=ModelCatalogTest`
Expected: COMPILE FAILURE (`ModelCatalog` does not exist).

- [ ] **Step 3: Implement**

```java
package com.dbbaskette.issuebot.service.claude;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;

/**
 * Curated model list: single source of truth for UI dropdowns and
 * fallback pricing. Pricing is a fallback only — the CLI-reported
 * total_cost_usd is authoritative when present.
 */
public final class ModelCatalog {

    public record ModelInfo(String id, String displayName,
                            double inputPerMTok, double outputPerMTok) {}

    public static final List<ModelInfo> MODELS = List.of(
            new ModelInfo("claude-opus-4-8", "Claude Opus 4.8", 5.0, 25.0),
            new ModelInfo("claude-opus-4-6", "Claude Opus 4.6", 5.0, 25.0),
            new ModelInfo("claude-sonnet-5", "Claude Sonnet 5", 3.0, 15.0),
            new ModelInfo("claude-sonnet-4-6", "Claude Sonnet 4.6", 3.0, 15.0),
            new ModelInfo("claude-haiku-4-5", "Claude Haiku 4.5", 1.0, 5.0));

    private ModelCatalog() {}

    public static Optional<ModelInfo> find(String modelId) {
        if (modelId == null) return Optional.empty();
        return MODELS.stream().filter(m -> m.id().equals(modelId)).findFirst();
    }

    public static Optional<BigDecimal> estimateCost(String modelId, long inputTokens, long outputTokens) {
        return find(modelId).map(m -> BigDecimal.valueOf(inputTokens)
                .multiply(BigDecimal.valueOf(m.inputPerMTok()))
                .divide(BigDecimal.valueOf(1_000_000), 6, RoundingMode.HALF_UP)
                .add(BigDecimal.valueOf(outputTokens)
                        .multiply(BigDecimal.valueOf(m.outputPerMTok()))
                        .divide(BigDecimal.valueOf(1_000_000), 6, RoundingMode.HALF_UP)));
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw test -Dtest=ModelCatalogTest`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/dbbaskette/issuebot/service/claude/ModelCatalog.java src/test/java/com/dbbaskette/issuebot/service/claude/ModelCatalogTest.java
git commit -m "feat: ModelCatalog — curated model list with fallback pricing"
```

---

### Task 3: ModelResolver (issue > repo > global precedence)

**Files:**
- Create: `src/main/java/com/dbbaskette/issuebot/service/claude/ModelResolver.java`
- Test: `src/test/java/com/dbbaskette/issuebot/service/claude/ModelResolverTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.dbbaskette.issuebot.service.claude;

import com.dbbaskette.issuebot.config.IssueBotProperties;
import com.dbbaskette.issuebot.model.TrackedIssue;
import com.dbbaskette.issuebot.model.WatchedRepo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ModelResolverTest {

    private IssueBotProperties properties;
    private ModelResolver resolver;
    private WatchedRepo repo;
    private TrackedIssue issue;

    @BeforeEach
    void setUp() {
        properties = new IssueBotProperties();
        properties.getClaudeCode().setImplementationModel("global-impl");
        properties.getClaudeCode().setReviewModel("global-review");
        properties.getClaudeCode().setUtilityModel("global-utility");
        resolver = new ModelResolver(properties);
        repo = new WatchedRepo("owner", "name");
        issue = new TrackedIssue(repo, 1, "title");
    }

    @Test
    void fallsBackToGlobalDefaults() {
        assertThat(resolver.implementationModel(issue)).isEqualTo("global-impl");
        assertThat(resolver.reviewModel(issue)).isEqualTo("global-review");
        assertThat(resolver.utilityModel()).isEqualTo("global-utility");
    }

    @Test
    void repoOverrideBeatsGlobal() {
        repo.setImplementationModel("repo-impl");
        repo.setReviewModel("repo-review");
        assertThat(resolver.implementationModel(issue)).isEqualTo("repo-impl");
        assertThat(resolver.reviewModel(issue)).isEqualTo("repo-review");
    }

    @Test
    void issueOverrideBeatsRepoAndGlobal() {
        repo.setImplementationModel("repo-impl");
        issue.setImplModelOverride("issue-impl");
        issue.setReviewModelOverride("issue-review");
        assertThat(resolver.implementationModel(issue)).isEqualTo("issue-impl");
        assertThat(resolver.reviewModel(issue)).isEqualTo("issue-review");
    }

    @Test
    void blankOverridesAreIgnored() {
        repo.setImplementationModel("  ");
        issue.setImplModelOverride("");
        assertThat(resolver.implementationModel(issue)).isEqualTo("global-impl");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=ModelResolverTest`
Expected: COMPILE FAILURE (`ModelResolver` / `getUtilityModel` do not exist).

- [ ] **Step 3: Implement — add `utilityModel` to config, then the resolver**

In `IssueBotProperties.ClaudeCodeConfig`, next to `reviewModel`:

```java
private String utilityModel = "claude-haiku-4-5";

public String getUtilityModel() { return utilityModel; }
public void setUtilityModel(String utilityModel) { this.utilityModel = utilityModel; }
```

Also update the stale defaults in the same class:

```java
private String implementationModel = "claude-opus-4-8";
private String reviewModel = "claude-sonnet-5";
```

New `ModelResolver.java`:

```java
package com.dbbaskette.issuebot.service.claude;

import com.dbbaskette.issuebot.config.IssueBotProperties;
import com.dbbaskette.issuebot.model.TrackedIssue;
import org.springframework.stereotype.Component;

/** Resolves the model for each role: issue override > repo override > global default. */
@Component
public class ModelResolver {

    private final IssueBotProperties properties;

    public ModelResolver(IssueBotProperties properties) {
        this.properties = properties;
    }

    public String implementationModel(TrackedIssue issue) {
        String fromIssue = blankToNull(issue.getImplModelOverride());
        if (fromIssue != null) return fromIssue;
        String fromRepo = blankToNull(issue.getRepo().getImplementationModel());
        if (fromRepo != null) return fromRepo;
        return properties.getClaudeCode().getImplementationModel();
    }

    public String reviewModel(TrackedIssue issue) {
        String fromIssue = blankToNull(issue.getReviewModelOverride());
        if (fromIssue != null) return fromIssue;
        String fromRepo = blankToNull(issue.getRepo().getReviewModel());
        if (fromRepo != null) return fromRepo;
        return properties.getClaudeCode().getReviewModel();
    }

    public String utilityModel() {
        return properties.getClaudeCode().getUtilityModel();
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw test -Dtest=ModelResolverTest`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/dbbaskette/issuebot/service/claude/ModelResolver.java src/main/java/com/dbbaskette/issuebot/config/IssueBotProperties.java src/test/java/com/dbbaskette/issuebot/service/claude/ModelResolverTest.java
git commit -m "feat: ModelResolver with issue>repo>global precedence; utility model config; current defaults"
```

---

### Task 4: CLI cost capture + explicit-model service methods

**Files:**
- Modify: `src/main/java/com/dbbaskette/issuebot/service/claude/StreamJsonParser.java`
- Modify: `src/main/java/com/dbbaskette/issuebot/service/claude/ClaudeCodeResult.java`
- Modify: `src/main/java/com/dbbaskette/issuebot/service/claude/ClaudeCodeService.java`
- Test: `src/test/java/com/dbbaskette/issuebot/service/claude/StreamJsonParserTest.java` (extend)

- [ ] **Step 1: Write the failing test** (add to `StreamJsonParserTest`)

```java
@Test
void capturesTotalCostUsdFromResultEvent() {
    String output = """
            {"type":"result","result":"done","model":"claude-opus-4-8","total_cost_usd":0.4321,"usage":{"input_tokens":100,"output_tokens":50}}
            """;
    ClaudeCodeResult result = parser.parse(output);
    assertThat(result.getCostUsd()).isEqualByComparingTo(new java.math.BigDecimal("0.4321"));
}

@Test
void costUsdIsNullWhenAbsent() {
    ClaudeCodeResult result = parser.parse("{\"type\":\"result\",\"result\":\"done\"}");
    assertThat(result.getCostUsd()).isNull();
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=StreamJsonParserTest`
Expected: COMPILE FAILURE (`getCostUsd` does not exist).

- [ ] **Step 3: Implement**

`ClaudeCodeResult` — add field + accessors:

```java
private java.math.BigDecimal costUsd; // CLI-reported total_cost_usd; null if absent

public java.math.BigDecimal getCostUsd() { return costUsd; }
public void setCostUsd(java.math.BigDecimal costUsd) { this.costUsd = costUsd; }
```

`StreamJsonParser` — inside the `case "result" ->` block, after `result.setModel(...)`:

```java
JsonNode cost = node.path("total_cost_usd");
if (cost.isNumber()) {
    result.setCostUsd(java.math.BigDecimal.valueOf(cost.asDouble()));
}
```

`ClaudeCodeService` — replace the two config-reading role methods with explicit-model versions and add the utility entry point (callers are updated in Tasks 5–6 and 10):

```java
/** Execute implementation with the resolved model. */
public ClaudeCodeResult executeImplementation(String prompt, Path workingDirectory,
                                              String model, Consumer<String> lineCallback) {
    IssueBotProperties.ClaudeCodeConfig config = properties.getClaudeCode();
    return executeTask(prompt, workingDirectory, model,
            config.getMaxTurnsPerInvocation(), config.getTimeoutMinutes(),
            null, lineCallback);
}

/** Execute independent review with the resolved model. */
public ClaudeCodeResult executeReview(String prompt, Path workingDirectory,
                                      String model, Consumer<String> lineCallback) {
    IssueBotProperties.ClaudeCodeConfig config = properties.getClaudeCode();
    return executeTask(prompt, workingDirectory, model,
            config.getReviewMaxTurns(), config.getReviewTimeoutMinutes(),
            null, lineCallback);
}

/** Pre-screen / decomposition analysis on the cheap utility model (review budgets). */
public ClaudeCodeResult executeUtility(String prompt, Path workingDirectory,
                                       Consumer<String> lineCallback) {
    IssueBotProperties.ClaudeCodeConfig config = properties.getClaudeCode();
    return executeTask(prompt, workingDirectory, config.getUtilityModel(),
            config.getReviewMaxTurns(), config.getReviewTimeoutMinutes(),
            null, lineCallback);
}
```

Then fix the compile errors this creates at the call sites — **mechanical only in this task** (behavior wiring comes later):
- `IssueDecompositionService.preScreen` and `analyzeAndDecompose`: change `claudeCode.executeReview(prompt, repoPath, null)` → `claudeCode.executeUtility(prompt, repoPath, null)`.
- `CodeReviewService.reviewCode`: add a `String model` parameter (after `baseBranch`) and pass it through to `executeReview(prompt, repoPath, model, lineCallback)`.
- `IssueWorkflowService.phaseImplementation` / `phaseIndependentReview`: temporarily pass `properties.getClaudeCode().getImplementationModel()` / `...getReviewModel()` — Task 5 replaces these with `ModelResolver`. (Add the `IssueBotProperties` constructor dependency to `IssueWorkflowService` now; Task 5 also uses it.)

- [ ] **Step 4: Run the full test suite**

Run: `./mvnw test`
Expected: PASS — `StreamJsonParserTest` new tests green; `IssueWorkflowServiceTest`, `CodeReviewService`-related and `IssueDecompositionServiceTest` mocks updated as needed for the new signatures (update the test stubs' method signatures in the same commit).

- [ ] **Step 5: Commit**

```bash
git add -A src/main src/test
git commit -m "feat: capture CLI total_cost_usd; explicit-model execute methods + utility model entry point"
```

---

### Task 5: Wire ModelResolver into the workflow + accurate cost tracking

**Files:**
- Modify: `src/main/java/com/dbbaskette/issuebot/service/workflow/IssueWorkflowService.java`
- Modify: `src/main/java/com/dbbaskette/issuebot/service/review/CodeReviewResult.java` (add `costUsd` pass-through)
- Modify: `src/main/java/com/dbbaskette/issuebot/service/review/CodeReviewService.java`
- Test: `src/test/java/com/dbbaskette/issuebot/service/workflow/IssueWorkflowServiceTest.java` (extend)

- [ ] **Step 1: Write the failing test** (add to `IssueWorkflowServiceTest`, following its existing mock style)

```java
@Test
void trackCostPrefersCliReportedCost() {
    ClaudeCodeResult result = new ClaudeCodeResult();
    result.setInputTokens(1_000_000);
    result.setOutputTokens(1_000_000);
    result.setModel("claude-opus-4-8");
    result.setCostUsd(new BigDecimal("0.50")); // CLI says 50 cents (cache discounts etc.)

    BigDecimal cost = workflowService.resolveCost(result.getCostUsd(), result.getModel(),
            result.getInputTokens(), result.getOutputTokens(), "IMPLEMENTATION");
    assertThat(cost).isEqualByComparingTo("0.50");
}

@Test
void trackCostFallsBackToCatalogPricing() {
    BigDecimal cost = workflowService.resolveCost(null, "claude-opus-4-8",
            1_000_000, 1_000_000, "IMPLEMENTATION");
    assertThat(cost).isEqualByComparingTo("30.000000"); // $5 + $25 per MTok
}

@Test
void trackCostFallsBackToLegacyEstimateForUnknownModel() {
    BigDecimal cost = workflowService.resolveCost(null, "my-custom-model",
            1_000_000, 1_000_000, "REVIEW");
    assertThat(cost).isEqualByComparingTo("18.000000"); // legacy review rate $3 + $15
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=IssueWorkflowServiceTest`
Expected: COMPILE FAILURE (`resolveCost` does not exist).

- [ ] **Step 3: Implement**

In `IssueWorkflowService`:

1. Add constructor dependency `ModelResolver modelResolver` (field + constructor arg; update test construction).

2. At the top of `processIssue(...)`, after status is set to `IN_PROGRESS`, resolve and persist the models once per run:

```java
trackedIssue.setResolvedImplModel(modelResolver.implementationModel(trackedIssue));
trackedIssue.setResolvedReviewModel(modelResolver.reviewModel(trackedIssue));
issueRepository.save(trackedIssue);
```

3. `phaseImplementation`: replace the temporary global lookup with the persisted value and record it on the iteration:

```java
ClaudeCodeResult result = claudeCode.executeImplementation(prompt, repoPath,
        trackedIssue.getResolvedImplModel(), line -> streamClaudeLog(issueId, line));
```

and in the iteration loop after `iterationRepository.save(iteration)` for a new iteration:

```java
iteration.setImplModel(trackedIssue.getResolvedImplModel());
```

4. `phaseIndependentReview`: pass `trackedIssue.getResolvedReviewModel()` into `codeReviewService.reviewCode(...)`.

5. Replace `estimateCost` with the tiered `resolveCost` (package-private for the test):

```java
BigDecimal resolveCost(BigDecimal cliCost, String model,
                       long inputTokens, long outputTokens, String phase) {
    if (cliCost != null) return cliCost;
    return ModelCatalog.estimateCost(model, inputTokens, outputTokens)
            .orElseGet(() -> legacyEstimate(inputTokens, outputTokens, phase));
}

/** Last-resort estimate when the model is unknown to the catalog. */
private BigDecimal legacyEstimate(long inputTokens, long outputTokens, String phase) {
    double inputRate = "REVIEW".equals(phase) ? 3.0 : 5.0;   // Opus-tier current pricing
    double outputRate = "REVIEW".equals(phase) ? 15.0 : 25.0;
    BigDecimal inputCost = BigDecimal.valueOf(inputTokens)
            .multiply(BigDecimal.valueOf(inputRate))
            .divide(BigDecimal.valueOf(1_000_000), 6, RoundingMode.HALF_UP);
    BigDecimal outputCost = BigDecimal.valueOf(outputTokens)
            .multiply(BigDecimal.valueOf(outputRate))
            .divide(BigDecimal.valueOf(1_000_000), 6, RoundingMode.HALF_UP);
    return inputCost.add(outputCost);
}
```

Wait — the legacy REVIEW fallback in the test above expects `$3/$15` (18.000000): keep review rates 3.0/15.0 and implementation rates 5.0/25.0 as shown. Delete the old `$15/$75` constants.

6. Update both `trackCost` overloads to route through `resolveCost`. The `ClaudeCodeResult` overload passes `result.getCostUsd()`; the review overload needs the cost from the review — so:

`CodeReviewResult`: add `BigDecimal costUsd` as a record component (after `modelUsed`), defaulting to `null` in the `failed(...)` factory; `CodeReviewService.parseReviewResponse` passes `result.getCostUsd()` through.

- [ ] **Step 4: Run the full suite**

Run: `./mvnw test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add -A src/main src/test
git commit -m "feat: resolve models per issue via ModelResolver; CLI-cost-first cost tracking"
```

---

### Task 6: Settings page — Models card with live apply + config.yml write-back

**Files:**
- Modify: `src/main/java/com/dbbaskette/issuebot/controller/SettingsController.java`
- Modify: `src/main/resources/templates/settings.html`
- Test: `src/test/java/com/dbbaskette/issuebot/controller/SettingsControllerTest.java` (create)

- [ ] **Step 1: Write the failing test**

```java
package com.dbbaskette.issuebot.controller;

import com.dbbaskette.issuebot.config.IssueBotProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.assertThat;

class SettingsControllerTest {

    @TempDir
    Path tempDir;

    @Test
    void modelSaveUpdatesBeanAndYaml() throws Exception {
        Path config = tempDir.resolve("config.yml");
        Files.writeString(config, """
                issuebot:
                  poll-interval-seconds: 60
                  claude-code:
                    implementation-model: claude-opus-4-6
                """);
        IssueBotProperties props = new IssueBotProperties();
        SettingsController controller = newController(props, config); // test helper wiring mocks

        controller.saveModels("claude-opus-4-8", "claude-sonnet-5", "claude-haiku-4-5",
                new org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap());

        assertThat(props.getClaudeCode().getImplementationModel()).isEqualTo("claude-opus-4-8");
        String yaml = Files.readString(config);
        assertThat(yaml).contains("implementation-model: claude-opus-4-8");
        assertThat(yaml).contains("review-model: claude-sonnet-5");
        assertThat(yaml).contains("utility-model: claude-haiku-4-5");
    }

    @Test
    void modelSaveRejectsUnparseableYamlWithoutWriting() throws Exception {
        Path config = tempDir.resolve("config.yml");
        String broken = "issuebot: [unclosed";
        Files.writeString(config, broken);
        IssueBotProperties props = new IssueBotProperties();
        SettingsController controller = newController(props, config);

        controller.saveModels("claude-opus-4-8", "claude-sonnet-5", "claude-haiku-4-5",
                new org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap());

        assertThat(Files.readString(config)).isEqualTo(broken); // untouched
        assertThat(props.getClaudeCode().getImplementationModel())
                .isNotEqualTo("claude-opus-4-8"); // bean not updated either
    }
}
```

(`newController` constructs `SettingsController` with mocks for its other dependencies and the config path overridden to `config` — add a package-private constructor or a settable `configPath` for testability, matching whichever is less invasive to the existing class.)

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=SettingsControllerTest`
Expected: COMPILE FAILURE (`saveModels` does not exist).

- [ ] **Step 3: Implement**

`SettingsController` — make the config path injectable (default unchanged) and add:

```java
@PostMapping("/settings/models")
public String saveModels(@RequestParam String implementationModel,
                         @RequestParam String reviewModel,
                         @RequestParam String utilityModel,
                         RedirectAttributes redirectAttributes) {
    if (implementationModel.isBlank() || reviewModel.isBlank() || utilityModel.isBlank()) {
        redirectAttributes.addFlashAttribute("error", "Model IDs cannot be blank");
        return "redirect:/settings";
    }
    try {
        writeModelsToConfigYaml(implementationModel, reviewModel, utilityModel);
    } catch (Exception e) {
        redirectAttributes.addFlashAttribute("error",
                "config.yml could not be updated (" + e.getMessage() + ") — fix it in the editor below");
        return "redirect:/settings";
    }
    // Apply live only after the file write succeeded (keeps file and bean in sync)
    IssueBotProperties.ClaudeCodeConfig cc = properties.getClaudeCode();
    cc.setImplementationModel(implementationModel);
    cc.setReviewModel(reviewModel);
    cc.setUtilityModel(utilityModel);
    redirectAttributes.addFlashAttribute("message",
            "Models updated — applies to the next issue picked up (no restart needed)");
    return "redirect:/settings";
}

@SuppressWarnings("unchecked")
private void writeModelsToConfigYaml(String impl, String review, String utility) throws IOException {
    Path path = getConfigPath();
    org.yaml.snakeyaml.Yaml yaml = new org.yaml.snakeyaml.Yaml();
    Map<String, Object> root = Files.exists(path)
            ? yaml.load(Files.readString(path))
            : null;
    if (root == null) root = new LinkedHashMap<>();
    Map<String, Object> issuebot = (Map<String, Object>) root
            .computeIfAbsent("issuebot", k -> new LinkedHashMap<String, Object>());
    Map<String, Object> claudeCode = (Map<String, Object>) issuebot
            .computeIfAbsent("claude-code", k -> new LinkedHashMap<String, Object>());
    claudeCode.put("implementation-model", impl);
    claudeCode.put("review-model", review);
    claudeCode.put("utility-model", utility);

    org.yaml.snakeyaml.DumperOptions options = new org.yaml.snakeyaml.DumperOptions();
    options.setDefaultFlowStyle(org.yaml.snakeyaml.DumperOptions.FlowStyle.BLOCK);
    Files.writeString(path, new org.yaml.snakeyaml.Yaml(options).dump(root));
}
```

Note: SnakeYAML round-trip drops comments from `config.yml`. Add this note to the Settings UI next to the save button: *"Saving rewrites config.yml (comments are not preserved)."*

`settings.html` — add a "Models" card above Quick Settings, using the page's existing card/form markup conventions:

```html
<section class="card">
  <h2>Models</h2>
  <p class="muted">Applies immediately to newly started issues. Per-repo and per-issue overrides win over these defaults.</p>
  <form method="post" th:action="@{/settings/models}">
    <label for="implementationModel">Implementation model (writes code)</label>
    <select id="implementationModel" name="implementationModel"
            th:attr="data-current=${implementationModel}">
      <option th:each="m : ${modelCatalog}" th:value="${m.id}"
              th:text="${m.displayName + ' — $' + m.inputPerMTok + '/$' + m.outputPerMTok + ' per MTok'}"
              th:selected="${m.id == implementationModel}"></option>
      <option value="__custom__">Custom…</option>
    </select>
    <input type="text" name="implementationModelCustom" class="custom-model-input" hidden
           placeholder="claude-model-id" aria-label="Custom implementation model ID"/>

    <label for="reviewModel">Review model (reviews PRs)</label>
    <select id="reviewModel" name="reviewModel"><!-- same option structure --></select>
    <input type="text" name="reviewModelCustom" class="custom-model-input" hidden
           placeholder="claude-model-id" aria-label="Custom review model ID"/>

    <label for="utilityModel">Utility model (pre-screen &amp; decomposition)</label>
    <select id="utilityModel" name="utilityModel"><!-- same option structure --></select>

    <button type="submit" class="btn btn-primary">Save Models</button>
    <p class="muted">Saving rewrites config.yml (comments are not preserved).</p>
  </form>
</section>
```

Add a small handler in `app.js` (following its existing addEventListener patterns): when a model `<select>` changes to `__custom__`, unhide the sibling `.custom-model-input` and copy its value into the select's name on submit (simplest: on submit, if select value is `__custom__`, set select value to the text input's value). The GET handler for `/settings` adds `model.addAttribute("modelCatalog", ModelCatalog.MODELS)` plus the three current model values.

- [ ] **Step 4: Run tests + boot check**

Run: `./mvnw test -Dtest=SettingsControllerTest` → PASS.
Run: `./run.sh`, open `http://localhost:8090/settings`, change the review model, save, confirm the toast and that `~/.issuebot/config.yml` contains the new value. Stop the app.

- [ ] **Step 5: Commit**

```bash
git add -A src/main src/test
git commit -m "feat: Settings Models card — live apply + structured config.yml write-back"
```

---

### Task 7: Per-repo model overrides + per-issue override on Retry/Start

**Files:**
- Modify: `src/main/java/com/dbbaskette/issuebot/controller/RepositoryController.java`
- Modify: `src/main/resources/templates/repositories.html`
- Modify: `src/main/java/com/dbbaskette/issuebot/controller/IssueController.java`
- Modify: `src/main/resources/templates/issue-detail.html` (retry modal)
- Modify: `src/main/resources/templates/issues.html` (start action)
- Test: `src/test/java/com/dbbaskette/issuebot/controller/IssueControllerTest.java` (extend)

- [ ] **Step 1: Write the failing test** (add to `IssueControllerTest`, matching its existing MockMvc/mock style)

```java
@Test
void retryStoresModelOverrides() throws Exception {
    // arrange a FAILED tracked issue per the class's existing retry test setup
    mockMvc.perform(post("/issues/" + issue.getId() + "/retry")
                    .param("instructions", "")
                    .param("implModelOverride", "claude-sonnet-5")
                    .param("reviewModelOverride", ""))
            .andExpect(status().is3xxRedirection());

    TrackedIssue saved = captureSavedIssue(); // existing verify/capture helper pattern
    assertThat(saved.getImplModelOverride()).isEqualTo("claude-sonnet-5");
    assertThat(saved.getReviewModelOverride()).isNull(); // blank normalizes to null
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=IssueControllerTest`
Expected: FAIL (unknown request params are ignored; override not stored → assertion fails).

- [ ] **Step 3: Implement**

`IssueController.retry(...)` — add parameters and store before kicking off:

```java
@RequestParam(required = false) String implModelOverride,
@RequestParam(required = false) String reviewModelOverride,
```

```java
issue.setImplModelOverride(normalize(implModelOverride));
issue.setReviewModelOverride(normalize(reviewModelOverride));
```

with `private static String normalize(String s) { return (s == null || s.isBlank()) ? null : s.trim(); }`. Apply the same two params to the `/issues/{id}/start` endpoint.

`issue-detail.html` retry modal — inside the existing form, after the instructions textarea:

```html
<label for="retryImplModel">Implementation model for this retry (optional)</label>
<select id="retryImplModel" name="implModelOverride">
  <option value="" selected>Use repo/global setting</option>
  <option th:each="m : ${modelCatalog}" th:value="${m.id}" th:text="${m.displayName}"></option>
</select>
<label for="retryReviewModel">Review model for this retry (optional)</label>
<select id="retryReviewModel" name="reviewModelOverride">
  <option value="" selected>Use repo/global setting</option>
  <option th:each="m : ${modelCatalog}" th:value="${m.id}" th:text="${m.displayName}"></option>
</select>
```

(`IssueController.detail` adds `modelCatalog` to the model.)

`repositories.html` add/edit form — after the Max Review Iterations field:

```html
<label for="implementationModel">Implementation model (blank = inherit global)</label>
<select id="implementationModel" name="implementationModel">
  <option value="">Inherit global</option>
  <option th:each="m : ${modelCatalog}" th:value="${m.id}" th:text="${m.displayName}"
          th:selected="${m.id == repoForm?.implementationModel}"></option>
</select>
<label for="reviewModel">Review model (blank = inherit global)</label>
<select id="reviewModel" name="reviewModel">
  <option value="">Inherit global</option>
  <option th:each="m : ${modelCatalog}" th:value="${m.id}" th:text="${m.displayName}"
          th:selected="${m.id == repoForm?.reviewModel}"></option>
</select>
```

`RepositoryController.addOrUpdate` binds the two params onto `WatchedRepo` (normalizing blank → null), and the list GET adds `modelCatalog`.

- [ ] **Step 4: Run tests**

Run: `./mvnw test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add -A src/main src/test
git commit -m "feat: per-repo model overrides and per-issue override on retry/start"
```

---

### Task 8: Truthful model labels in the UI + docs/defaults cleanup

**Files:**
- Modify: `src/main/resources/templates/issue-detail.html` (phase pipeline: lines ~244, ~271)
- Modify: `src/main/resources/templates/costs.html` (show model per cost row if absent)
- Modify: `src/main/java/com/dbbaskette/issuebot/config/ConfigInitializer.java` (sample config)
- Modify: `src/main/resources/application.yml`, `README.md`

- [ ] **Step 1: Replace hardcoded model names in the pipeline**

In `issue-detail.html`, replace the literal "Opus writing code" with:

```html
<span th:text="${issue.resolvedImplModel != null ? issue.resolvedImplModel + ' writing code' : 'Implementation model writing code'}"></span>
```

and the literal "Sonnet 4.6 review" with the same pattern using `issue.resolvedReviewModel`. Also render the resolved models in the issue-detail header meta row ("Models: X / Y").

- [ ] **Step 2: Sample config + docs**

`ConfigInitializer.ensureSampleConfig` — replace the stale `claude-code` block in the heredoc:

```yaml
  claude-code:
    implementation-model: claude-opus-4-8
    review-model: claude-sonnet-5
    utility-model: claude-haiku-4-5
    max-turns-per-invocation: 30
    timeout-minutes: 10
```

`application.yml`: align any model defaults with the new `IssueBotProperties` values. `README.md`: update the configuration example (implementation/review/utility models) and the "Dual-Model Architecture" wording to say models are selectable in the UI at global/repo/issue level.

- [ ] **Step 3: Grep for leftovers**

Run: `grep -rn "Opus\|Sonnet" src/main/resources/templates src/main/java --include='*.html' --include='*.java' | grep -v "resolvedImplModel\|resolvedReviewModel\|modelUsed\|ModelCatalog"`
Expected: only comments/log strings that don't render user-facing model claims. Fix any misses.

- [ ] **Step 4: Build + eyeball**

Run: `./mvnw test` → PASS. Run `./run.sh`, open a past issue's detail page → pipeline shows stored model names (or the neutral fallback for pre-migration issues).

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "fix: truthful model labels in pipeline UI; current-model defaults in sample config and docs"
```

---

## Phase 2 — Issue-Noise Overhaul

### Task 9: GitHubApiClient additions (update body, reopen, search by label)

**Files:**
- Modify: `src/main/java/com/dbbaskette/issuebot/service/github/GitHubApiClient.java`

- [ ] **Step 1: Add methods** (same WebClient style as `closeIssue`/`createIssue`)

```java
public void updateIssueBody(String owner, String repo, int issueNumber, String body) {
    log.debug("Updating body of {}/{} #{}", owner, repo, issueNumber);
    webClient.patch()
            .uri("/repos/{owner}/{repo}/issues/{number}", owner, repo, issueNumber)
            .bodyValue(Map.of("body", body))
            .retrieve()
            .toBodilessEntity()
            .retryWhen(retryOnServerError())
            .block(Duration.ofSeconds(15));
}

public void reopenIssue(String owner, String repo, int issueNumber) {
    log.debug("Reopening issue {}/{} #{}", owner, repo, issueNumber);
    webClient.patch()
            .uri("/repos/{owner}/{repo}/issues/{number}", owner, repo, issueNumber)
            .bodyValue(Map.of("state", "open"))
            .retrieve()
            .toBodilessEntity()
            .retryWhen(retryOnServerError())
            .block(Duration.ofSeconds(15));
}
```

`listIssues(owner, repo, label, state)` already exists — for backlog lookup call it with `state="all"` (verify the existing method passes `state` through; it does, as a query param).

- [ ] **Step 2: Compile**

Run: `./mvnw compile`
Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/dbbaskette/issuebot/service/github/GitHubApiClient.java
git commit -m "feat: GitHubApiClient updateIssueBody + reopenIssue for rolling backlog"
```

---

### Task 10: BacklogService (rolling backlog with dedup)

**Files:**
- Create: `src/main/java/com/dbbaskette/issuebot/service/workflow/BacklogService.java`
- Test: `src/test/java/com/dbbaskette/issuebot/service/workflow/BacklogServiceTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.dbbaskette.issuebot.service.workflow;

import com.dbbaskette.issuebot.service.review.CodeReviewResult.ReviewFinding;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class BacklogServiceTest {

    private final BacklogService service = new BacklogService(null, null); // pure methods under test

    @Test
    void dedupKeyIsStableAndIgnoresLineNumbers() {
        ReviewFinding a = new ReviewFinding("medium", "code_quality", "src/Foo.java", 42, "Magic number 7", "extract constant");
        ReviewFinding b = new ReviewFinding("medium", "code_quality", "src/Foo.java", 99, "Magic number 7", "different suggestion");
        assertThat(BacklogService.dedupKey(a)).isEqualTo(BacklogService.dedupKey(b));
    }

    @Test
    void differentFindingsGetDifferentKeys() {
        ReviewFinding a = new ReviewFinding("medium", "code_quality", "src/Foo.java", 42, "Magic number 7", null);
        ReviewFinding b = new ReviewFinding("medium", "security", "src/Foo.java", 42, "Magic number 7", null);
        assertThat(BacklogService.dedupKey(a)).isNotEqualTo(BacklogService.dedupKey(b));
    }

    @Test
    void mergeAppendsOnlyNewFindingsAndUpdatesKeyStore() {
        String existingBody = """
                Findings from automated reviews.

                - [ ] **[MEDIUM — code_quality]** `src/Foo.java:42` — Magic number 7 (from #10 / PR #11)

                <!-- issuebot-keys: %s -->
                """.formatted(BacklogService.dedupKey(
                        new ReviewFinding("medium", "code_quality", "src/Foo.java", 42, "Magic number 7", null)));

        List<ReviewFinding> incoming = List.of(
                new ReviewFinding("medium", "code_quality", "src/Foo.java", 42, "Magic number 7", null), // dup
                new ReviewFinding("medium", "test_coverage", "src/Bar.java", 5, "No test for null path", null));

        BacklogService.MergeResult result = BacklogService.merge(existingBody, incoming, 12, 13);
        assertThat(result.added()).isEqualTo(1);
        assertThat(result.body()).contains("No test for null path");
        assertThat(result.body()).containsOnlyOnce("Magic number 7");
        assertThat(result.body()).contains("issuebot-keys:");
    }

    @Test
    void mergePrunesOldestCheckedItemsBeyondCap() {
        StringBuilder body = new StringBuilder("Findings.\n\n");
        for (int i = 0; i < 55; i++) {
            body.append("- [x] **[MEDIUM — code_quality]** `f").append(i).append(".java:1` — done item ").append(i)
                .append(" (from #1 / PR #2)\n");
        }
        body.append("\n<!-- issuebot-keys: -->\n");
        BacklogService.MergeResult result = BacklogService.merge(body.toString(),
                List.of(new ReviewFinding("medium", "code_quality", "new.java", 1, "fresh", null)), 3, 4);
        long items = result.body().lines().filter(l -> l.startsWith("- [")).count();
        assertThat(items).isLessThanOrEqualTo(50);
        assertThat(result.body()).contains("fresh");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=BacklogServiceTest`
Expected: COMPILE FAILURE.

- [ ] **Step 3: Implement**

```java
package com.dbbaskette.issuebot.service.workflow;

import com.dbbaskette.issuebot.model.WatchedRepo;
import com.dbbaskette.issuebot.service.github.GitHubApiClient;
import com.dbbaskette.issuebot.service.event.EventService;
import com.dbbaskette.issuebot.service.review.CodeReviewResult.ReviewFinding;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/**
 * Maintains one rolling "IssueBot Backlog" issue per repo. Findings are
 * checklist items, deduplicated by a stable key stored in an HTML comment.
 */
@Service
public class BacklogService {

    private static final Logger log = LoggerFactory.getLogger(BacklogService.class);
    static final String BACKLOG_LABEL = "issuebot-backlog";
    static final String BACKLOG_TITLE = "IssueBot Backlog";
    static final int MAX_ITEMS = 50;
    private static final String KEYS_PREFIX = "<!-- issuebot-keys:";

    private final GitHubApiClient gitHubApi;
    private final EventService eventService;

    public BacklogService(GitHubApiClient gitHubApi, EventService eventService) {
        this.gitHubApi = gitHubApi;
        this.eventService = eventService;
    }

    /** Append findings to the repo's backlog issue, creating/reopening it as needed. */
    public void addFindings(WatchedRepo repo, List<ReviewFinding> findings,
                            int sourceIssueNumber, int prNumber) {
        if (findings.isEmpty()) return;
        try {
            JsonNode backlog = findBacklogIssue(repo);
            if (backlog == null) {
                MergeResult fresh = merge(initialBody(), findings, sourceIssueNumber, prNumber);
                JsonNode created = gitHubApi.createIssue(repo.getOwner(), repo.getName(),
                        BACKLOG_TITLE, fresh.body(), List.of(BACKLOG_LABEL));
                eventService.log("BACKLOG_UPDATED", "Created backlog issue #"
                        + created.path("number").asInt() + " with " + fresh.added() + " findings", repo);
                return;
            }
            int number = backlog.path("number").asInt();
            if ("closed".equals(backlog.path("state").asText())) {
                gitHubApi.reopenIssue(repo.getOwner(), repo.getName(), number);
            }
            MergeResult result = merge(backlog.path("body").asText(""), findings,
                    sourceIssueNumber, prNumber);
            if (result.added() > 0) {
                gitHubApi.updateIssueBody(repo.getOwner(), repo.getName(), number, result.body());
            }
            eventService.log("BACKLOG_UPDATED", "Backlog #" + number + ": +"
                    + result.added() + " findings (" + (findings.size() - result.added()) + " duplicates skipped)", repo);
        } catch (Exception e) {
            log.warn("Failed to update backlog for {}: {}", repo.fullName(), e.getMessage());
        }
    }

    private JsonNode findBacklogIssue(WatchedRepo repo) {
        List<JsonNode> issues = gitHubApi.listIssues(repo.getOwner(), repo.getName(),
                BACKLOG_LABEL, "all");
        return (issues == null || issues.isEmpty()) ? null : issues.get(0);
    }

    private static String initialBody() {
        return "Non-blocking findings from IssueBot code reviews. Check items off as they are addressed, "
                + "or promote them to `agent-ready` issues from the IssueBot dashboard.\n\n"
                + KEYS_PREFIX + " -->\n";
    }

    /** Stable dedup key: file + category + normalized finding text (line numbers drift, so excluded). */
    static String dedupKey(ReviewFinding f) {
        String normalized = (f.file() + "|" + f.category() + "|"
                + f.finding().toLowerCase().replaceAll("\\s+", " ").trim());
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(normalized.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 8; i++) hex.append(String.format("%02x", hash[i]));
            return hex.toString();
        } catch (Exception e) {
            return Integer.toHexString(normalized.hashCode());
        }
    }

    record MergeResult(String body, int added) {}

    static MergeResult merge(String existingBody, List<ReviewFinding> findings,
                             int sourceIssueNumber, int prNumber) {
        Set<String> keys = parseKeys(existingBody);
        List<String> items = new ArrayList<>(existingBody.lines()
                .filter(l -> l.startsWith("- [")).toList());
        String header = existingBody.lines()
                .takeWhile(l -> !l.startsWith("- [") && !l.startsWith(KEYS_PREFIX))
                .reduce("", (a, b) -> a.isEmpty() ? b : a + "\n" + b);

        int added = 0;
        for (ReviewFinding f : findings) {
            String key = dedupKey(f);
            if (!keys.add(key)) continue;
            items.add("- [ ] **[" + f.severity().toUpperCase() + " — " + f.category() + "]** `"
                    + f.file() + (f.line() != null ? ":" + f.line() : "") + "` — " + f.finding()
                    + " (from #" + sourceIssueNumber + " / PR #" + prNumber + ")");
            added++;
        }

        // Prune: drop oldest checked items first, then oldest unchecked, down to MAX_ITEMS
        while (items.size() > MAX_ITEMS) {
            int checkedIdx = -1;
            for (int i = 0; i < items.size(); i++) {
                if (items.get(i).startsWith("- [x]")) { checkedIdx = i; break; }
            }
            items.remove(checkedIdx >= 0 ? checkedIdx : 0);
        }

        StringBuilder body = new StringBuilder(header).append("\n\n");
        items.forEach(i -> body.append(i).append("\n"));
        body.append("\n").append(KEYS_PREFIX).append(" ")
            .append(String.join(",", keys)).append(" -->\n");
        return new MergeResult(body.toString(), added);
    }

    private static Set<String> parseKeys(String body) {
        Set<String> keys = new LinkedHashSet<>();
        body.lines().filter(l -> l.startsWith(KEYS_PREFIX)).findFirst().ifPresent(line -> {
            String inner = line.substring(KEYS_PREFIX.length()).replace("-->", "").trim();
            for (String k : inner.split(",")) if (!k.isBlank()) keys.add(k.trim());
        });
        return keys;
    }
}
```

(Check `EventService.log(type, message, repo)` — a repo-only overload exists for `POLL_ERROR`; if not, use the `(type, message, repo, null)` form.)

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw test -Dtest=BacklogServiceTest`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/dbbaskette/issuebot/service/workflow/BacklogService.java src/test/java/com/dbbaskette/issuebot/service/workflow/BacklogServiceTest.java
git commit -m "feat: BacklogService — rolling per-repo backlog issue with dedup and pruning"
```

---

### Task 11: FollowUpService with modes; workflow switchover

**Files:**
- Create: `src/main/java/com/dbbaskette/issuebot/service/workflow/FollowUpService.java`
- Modify: `src/main/java/com/dbbaskette/issuebot/service/workflow/IssueWorkflowService.java` (remove `createFollowUpIssue`, call the service)
- Test: `src/test/java/com/dbbaskette/issuebot/service/workflow/FollowUpServiceTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.dbbaskette.issuebot.service.workflow;

import com.dbbaskette.issuebot.model.*;
import com.dbbaskette.issuebot.service.github.GitHubApiClient;
import com.dbbaskette.issuebot.service.event.EventService;
import com.dbbaskette.issuebot.service.review.CodeReviewResult;
import com.dbbaskette.issuebot.service.review.CodeReviewResult.ReviewFinding;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FollowUpServiceTest {

    private GitHubApiClient gitHubApi;
    private BacklogService backlogService;
    private FollowUpService service;
    private WatchedRepo repo;
    private TrackedIssue issue;
    private CodeReviewResult review;

    @BeforeEach
    void setUp() {
        gitHubApi = mock(GitHubApiClient.class);
        backlogService = mock(BacklogService.class);
        service = new FollowUpService(gitHubApi, backlogService, mock(EventService.class));
        repo = new WatchedRepo("o", "r");
        issue = new TrackedIssue(repo, 7, "t");
        review = reviewWithFindings(
                new ReviewFinding("medium", "code_quality", "F.java", 1, "finding-m", null),
                new ReviewFinding("low", "code_quality", "F.java", 2, "finding-l", null));
    }

    @Test
    void offModeCreatesNothing() {
        repo.setFollowUpMode(FollowUpMode.OFF);
        service.handleNonBlockingFindings(issue, issueJson("Normal title", List.of()), review, 9);
        verifyNoInteractions(gitHubApi, backlogService);
    }

    @Test
    void rollingBacklogSendsOnlyMediumFindings() {
        repo.setFollowUpMode(FollowUpMode.ROLLING_BACKLOG);
        service.handleNonBlockingFindings(issue, issueJson("Normal title", List.of()), review, 9);
        verify(backlogService).addFindings(eq(repo),
                argThat(list -> list.size() == 1 && list.get(0).severity().equals("medium")),
                eq(7), eq(9));
        verify(gitHubApi, never()).createIssue(any(), any(), any(), any(), any());
    }

    @Test
    void perIssueModeKeepsLegacyBehavior() {
        repo.setFollowUpMode(FollowUpMode.PER_ISSUE);
        when(gitHubApi.createIssue(any(), any(), any(), any(), any()))
                .thenReturn(new ObjectMapper().createObjectNode().put("number", 42));
        service.handleNonBlockingFindings(issue, issueJson("Normal title", List.of()), review, 9);
        verify(gitHubApi).createIssue(eq("o"), eq("r"), startsWith("Follow-Up:"), any(), any());
    }

    @Test
    void backlogAndFollowUpIssuesNeverFeedThemselves() {
        repo.setFollowUpMode(FollowUpMode.ROLLING_BACKLOG);
        service.handleNonBlockingFindings(issue,
                issueJson("Anything", List.of("issuebot-backlog")), review, 9);
        verifyNoInteractions(backlogService);
    }

    // helpers: issueJson(title, labels) builds the GitHub issue JsonNode;
    // reviewWithFindings(...) builds a passing CodeReviewResult with the given findings.
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=FollowUpServiceTest`
Expected: COMPILE FAILURE.

- [ ] **Step 3: Implement**

`FollowUpService` — move `createFollowUpIssue`, `isFollowUpIssue`, `FOLLOW_UP_LABEL`, `FOLLOW_UP_TITLE_PREFIX` out of `IssueWorkflowService` into this class, then dispatch on mode:

```java
package com.dbbaskette.issuebot.service.workflow;

import com.dbbaskette.issuebot.model.FollowUpMode;
import com.dbbaskette.issuebot.model.TrackedIssue;
import com.dbbaskette.issuebot.model.WatchedRepo;
import com.dbbaskette.issuebot.service.event.EventService;
import com.dbbaskette.issuebot.service.github.GitHubApiClient;
import com.dbbaskette.issuebot.service.review.CodeReviewResult;
import com.dbbaskette.issuebot.service.review.CodeReviewResult.ReviewFinding;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import java.util.List;

/** Routes non-blocking review findings per the repo's FollowUpMode. */
@Service
public class FollowUpService {

    private static final Logger log = LoggerFactory.getLogger(FollowUpService.class);
    static final String FOLLOW_UP_LABEL = "issuebot-followup";
    static final String FOLLOW_UP_TITLE_PREFIX = "Follow-Up:";
    private static final List<String> SELF_FEEDING_LABELS =
            List.of(FOLLOW_UP_LABEL, BacklogService.BACKLOG_LABEL);

    private final GitHubApiClient gitHubApi;
    private final BacklogService backlogService;
    private final EventService eventService;

    public FollowUpService(GitHubApiClient gitHubApi, BacklogService backlogService,
                           EventService eventService) {
        this.gitHubApi = gitHubApi;
        this.backlogService = backlogService;
        this.eventService = eventService;
    }

    public void handleNonBlockingFindings(TrackedIssue trackedIssue, JsonNode issueDetails,
                                          CodeReviewResult review, int prNumber) {
        WatchedRepo repo = trackedIssue.getRepo();
        FollowUpMode mode = repo.getFollowUpMode();
        if (mode == FollowUpMode.OFF) return;
        if (isSelfFeeding(issueDetails)) {
            log.info("Skipping follow-up for {} #{} — issue is itself bot-generated follow-up/backlog",
                    repo.fullName(), trackedIssue.getIssueNumber());
            return;
        }

        List<ReviewFinding> medium = review.findings().stream()
                .filter(f -> "medium".equalsIgnoreCase(f.severity())).toList();
        List<ReviewFinding> nonBlocking = review.findings().stream()
                .filter(f -> "medium".equalsIgnoreCase(f.severity())
                          || "low".equalsIgnoreCase(f.severity())).toList();

        switch (mode) {
            case ROLLING_BACKLOG -> backlogService.addFindings(repo, medium,
                    trackedIssue.getIssueNumber(), prNumber);
            case COMMENT_ONLY -> postSummaryComment(trackedIssue, nonBlocking, prNumber);
            case PER_ISSUE -> createLegacyFollowUpIssue(trackedIssue, nonBlocking, prNumber);
            case OFF -> { /* handled above */ }
        }
    }

    boolean isSelfFeeding(JsonNode issueDetails) {
        if (issueDetails == null || issueDetails.isMissingNode()) return false;
        if (issueDetails.path("title").asText("").startsWith(FOLLOW_UP_TITLE_PREFIX)) return true;
        JsonNode labels = issueDetails.path("labels");
        if (labels.isArray()) {
            for (JsonNode label : labels) {
                if (SELF_FEEDING_LABELS.contains(label.path("name").asText().toLowerCase())) return true;
            }
        }
        return false;
    }

    // postSummaryComment: one comment on the original issue listing nonBlocking findings
    // (reuse the finding-rendering loop from the old createFollowUpIssue body).
    // createLegacyFollowUpIssue: the old createFollowUpIssue body moved verbatim
    // (title prefix, body format, FOLLOW_UP_LABEL, linking comment, event log).
}
```

`IssueWorkflowService` — delete `createFollowUpIssue`/`isFollowUpIssue`/the two constants, add a `FollowUpService` constructor dependency, and replace the call site:

```java
if (reviewResult != null && reviewResult.passed()) {
    try {
        followUpService.handleNonBlockingFindings(trackedIssue, issueDetails, reviewResult, prNumber);
    } catch (Exception e) {
        log.warn("Follow-up handling failed for {} #{}: {}",
                repo.fullName(), trackedIssue.getIssueNumber(), e.getMessage());
    }
}
```

Note: **decomposed sub-issues are intentionally no longer excluded** — their medium findings flow to the (deduplicating, capped) backlog rather than spawning per-sub-issue orphans.

- [ ] **Step 4: Run the full suite**

Run: `./mvnw test`
Expected: PASS — including existing `IssueWorkflowServiceTest` follow-up tests, which move/adapt to `FollowUpServiceTest`.

- [ ] **Step 5: Commit**

```bash
git add -A src/main src/test
git commit -m "feat: FollowUpService with OFF/COMMENT_ONLY/ROLLING_BACKLOG/PER_ISSUE modes"
```

---

### Task 12: Decomposition guards (one level, cap, pre-screen toggle)

**Files:**
- Modify: `src/main/java/com/dbbaskette/issuebot/service/workflow/IssueDecompositionService.java`
- Modify: `src/main/java/com/dbbaskette/issuebot/service/workflow/IssueWorkflowService.java` (pre-screen gate)
- Test: `src/test/java/com/dbbaskette/issuebot/service/workflow/IssueDecompositionServiceTest.java` (extend)

- [ ] **Step 1: Write the failing tests**

```java
@Test
void neverDecomposesAnAlreadyDecomposedIssue() {
    JsonNode issue = issueJsonWithLabels("issuebot-decomposed");
    boolean result = decompositionService.decompose(trackedIssue, issue, repoPath, "timed out");
    assertThat(result).isFalse();
    verify(gitHubApi, never()).createIssue(any(), any(), any(), any(), any());
}

@Test
void preScreenSkipsDecomposedIssues() {
    JsonNode issue = issueJsonWithLabels("issuebot-decomposed");
    var result = decompositionService.preScreen(issue, repoPath);
    assertThat(result.tooLarge()).isFalse();
    verify(claudeCode, never()).executeUtility(any(), any(), any());
}

@Test
void refusesDecompositionBeyondOpenSubIssueCap() {
    when(gitHubApi.listIssues("o", "r", "issuebot-decomposed", "open"))
            .thenReturn(tenOpenIssues());
    boolean result = decompositionService.decompose(trackedIssue, plainIssueJson(), repoPath, "timed out");
    assertThat(result).isFalse();
    verify(gitHubApi, never()).createIssue(any(), any(), any(), any(), any());
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./mvnw test -Dtest=IssueDecompositionServiceTest`
Expected: FAIL (no guards yet).

- [ ] **Step 3: Implement**

In `IssueDecompositionService` add:

```java
static final String DECOMPOSED_LABEL = "issuebot-decomposed";
static final int MAX_OPEN_SUB_ISSUES = 10;

static boolean hasLabel(JsonNode issueDetails, String labelName) {
    JsonNode labels = issueDetails.path("labels");
    if (!labels.isArray()) return false;
    for (JsonNode label : labels) {
        if (labelName.equalsIgnoreCase(label.path("name").asText())) return true;
    }
    return false;
}
```

Guard `preScreen` (first lines):

```java
if (hasLabel(issueDetails, DECOMPOSED_LABEL)) {
    return new PreScreenResult(false, null); // sub-issues are never re-screened
}
```

Guard `decompose` (first lines, before the analysis call):

```java
if (hasLabel(issueDetails, DECOMPOSED_LABEL)) {
    log.info("Refusing to decompose {} #{} — already a decomposed sub-issue",
            repo.fullName(), issueNumber);
    eventService.log("DECOMPOSITION_REFUSED", "Sub-issues are never decomposed further",
            repo, trackedIssue);
    return false;
}
List<JsonNode> openSubs = gitHubApi.listIssues(repo.getOwner(), repo.getName(),
        DECOMPOSED_LABEL, "open");
if (openSubs != null && openSubs.size() >= MAX_OPEN_SUB_ISSUES) {
    log.warn("Refusing to decompose {} #{} — {} open sub-issues (cap {})",
            repo.fullName(), issueNumber, openSubs.size(), MAX_OPEN_SUB_ISSUES);
    eventService.log("DECOMPOSITION_REFUSED", "Open sub-issue cap reached ("
            + openSubs.size() + "/" + MAX_OPEN_SUB_ISSUES + ")", repo, trackedIssue);
    return false;
}
```

In `IssueWorkflowService.processIssue`, gate the pre-screen block:

```java
if (repo.isPreScreenEnabled() && repo.getDecompositionMode() != DecompositionMode.OFF) {
    // ... existing pre-screen try/catch ...
}
```

and gate every `decompositionService.isDecomposable(...) && decompose(...)` call with `repo.getDecompositionMode() != DecompositionMode.OFF`.

- [ ] **Step 4: Run the full suite**

Run: `./mvnw test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add -A src/main src/test
git commit -m "feat: decomposition guards — one level only, open sub-issue cap, pre-screen toggle"
```

---

### Task 13: PROPOSE mode — proposal storage, approval endpoints, parent-as-tracker

**Files:**
- Modify: `src/main/java/com/dbbaskette/issuebot/service/workflow/IssueDecompositionService.java`
- Modify: `src/main/java/com/dbbaskette/issuebot/service/workflow/IssueWorkflowService.java`
- Modify: `src/main/java/com/dbbaskette/issuebot/controller/IssueController.java`
- Modify: `src/main/resources/templates/issue-detail.html`
- Modify: `src/main/java/com/dbbaskette/issuebot/service/polling/IssuePollingService.java` (close parent when subs done)
- Test: `src/test/java/com/dbbaskette/issuebot/service/workflow/IssueDecompositionServiceTest.java` + `src/test/java/com/dbbaskette/issuebot/controller/IssueControllerTest.java` (extend)

- [ ] **Step 1: Write the failing tests**

```java
// IssueDecompositionServiceTest
@Test
void proposeStoresProposalAndDoesNotCreateIssues() {
    trackedIssue.getRepo().setDecompositionMode(DecompositionMode.PROPOSE);
    stubUtilityDecompositionResponse(); // existing helper pattern: Claude returns 3 sub-issues JSON
    boolean handled = decompositionService.decompose(trackedIssue, plainIssueJson(), repoPath, "too large");
    assertThat(handled).isTrue();
    assertThat(trackedIssue.getStatus()).isEqualTo(IssueStatus.AWAITING_DECOMPOSITION);
    assertThat(trackedIssue.getDecompositionProposal()).contains("\"title\"");
    verify(gitHubApi, never()).createIssue(any(), any(), any(), any(), any());
    verify(gitHubApi, never()).closeIssue(any(), any(), anyInt());
    verify(gitHubApi).addComment(any(), any(), anyInt(), contains("Proposed Split"));
}

@Test
void approveCreatesSubIssuesAndConvertsParentToTracker() {
    trackedIssue.setStatus(IssueStatus.AWAITING_DECOMPOSITION);
    trackedIssue.setDecompositionProposal(sampleProposalJson(3));
    when(gitHubApi.createIssue(any(), any(), any(), any(), any()))
            .thenReturn(nodeWithNumber(101), nodeWithNumber(102), nodeWithNumber(103));
    decompositionService.approveProposal(trackedIssue);
    verify(gitHubApi, times(3)).createIssue(any(), any(), any(), any(),
            eq(List.of("agent-ready", "issuebot-decomposed")));
    verify(gitHubApi).addLabels(any(), any(), eq(trackedIssue.getIssueNumber()),
            eq(List.of("issuebot-parent")));
    verify(gitHubApi).removeLabel(any(), any(), eq(trackedIssue.getIssueNumber()), eq("agent-ready"));
    verify(gitHubApi, never()).closeIssue(any(), any(), anyInt()); // parent stays open
    assertThat(trackedIssue.getStatus()).isEqualTo(IssueStatus.DECOMPOSED);
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./mvnw test -Dtest=IssueDecompositionServiceTest`
Expected: COMPILE FAILURE (`approveProposal` missing; PROPOSE branch missing).

- [ ] **Step 3: Implement**

`IssueDecompositionService`:

1. In `decompose(...)`, after `analyzeAndDecompose` succeeds and `subIssues.size() >= MIN_SUB_ISSUES`, branch on mode:

```java
if (repo.getDecompositionMode() == DecompositionMode.PROPOSE) {
    trackedIssue.setDecompositionProposal(objectMapper.writeValueAsString(subIssues));
    trackedIssue.setStatus(IssueStatus.AWAITING_DECOMPOSITION);
    trackedIssue.setCurrentPhase(null);
    issueRepository.save(trackedIssue);
    gitHubApi.addComment(repo.getOwner(), repo.getName(), issueNumber,
            buildProposalComment(subIssues, skipReason));
    eventService.log("DECOMPOSITION_PROPOSED",
            "Proposed split into " + subIssues.size() + " sub-issues — awaiting approval",
            repo, trackedIssue);
    notificationService.info("Decomposition Proposed",
            repo.fullName() + " #" + issueNumber + " — approve or reject in the dashboard");
    return true;
}
// AUTO falls through to the existing create-sub-issues path (with the changes in step 3.2)
```

`buildProposalComment` renders "## IssueBot: Proposed Split" + a numbered list of titles/descriptions + "Approve or reject from the IssueBot dashboard."
`SubIssue` must round-trip through Jackson: make the record public and annotate with `@JsonProperty` names matching its fields, or serialize a `List<Map<String,String>>` — pick the record + Jackson annotations.

2. Extract sub-issue creation into `createSubIssues(trackedIssue, List<SubIssue>)` (used by both AUTO and `approveProposal`) and change the parent handling for **both** modes: instead of `closeIssue`, do

```java
gitHubApi.addComment(...decomposition comment with checklist of created sub-issue numbers...);
gitHubApi.addLabels(repo.getOwner(), repo.getName(), issueNumber, List.of("issuebot-parent"));
gitHubApi.removeLabel(repo.getOwner(), repo.getName(), issueNumber, "agent-ready");
```

3. New public methods:

```java
public void approveProposal(TrackedIssue trackedIssue) { /* parse proposal JSON → createSubIssues → label parent → status DECOMPOSED → clear proposal */ }
public void rejectProposal(TrackedIssue trackedIssue) { /* clear proposal, delegate to iterationManager.handleRetrySkipped(trackedIssue, "Decomposition proposal rejected by operator") */ }
```

(`IterationManager` becomes a constructor dependency of `IssueDecompositionService`; Spring handles the wiring — verify no dependency cycle: `IterationManager` does not depend on `IssueDecompositionService`, so this is safe.)

`IssueController` — two endpoints mirroring the retry endpoint's structure and flash-message style:

```java
@PostMapping("/issues/{id}/decomposition/approve")  // guards: status == AWAITING_DECOMPOSITION
@PostMapping("/issues/{id}/decomposition/reject")
```

`issue-detail.html` — when `issue.status == AWAITING_DECOMPOSITION`, render a "Proposed Split" card listing the proposal (controller parses the JSON into a `List<Map>` model attribute) with **Approve split** and **Reject & escalate** buttons in the page's existing confirm-modal pattern.

`IssuePollingService` — in `pollForIssues()` after `recheckBlockedIssues(repo)`, add `closeCompletedParents(repo)`:

```java
/** Close issuebot-parent tracking issues whose sub-issues are all closed. */
private void closeCompletedParents(WatchedRepo repo) {
    try {
        List<JsonNode> parents = gitHubApiClient.listIssues(repo.getOwner(), repo.getName(),
                "issuebot-parent", "open");
        if (parents == null || parents.isEmpty()) return;
        List<JsonNode> openSubs = gitHubApiClient.listIssues(repo.getOwner(), repo.getName(),
                "issuebot-decomposed", "open");
        if (openSubs == null || !openSubs.isEmpty()) return; // any open sub → keep parents open
        for (JsonNode parent : parents) {
            int number = parent.path("number").asInt();
            gitHubApiClient.addComment(repo.getOwner(), repo.getName(), number,
                    "All sub-issues are closed — closing this tracking issue.");
            gitHubApiClient.closeIssue(repo.getOwner(), repo.getName(), number);
            eventService.log("PARENT_ISSUE_CLOSED", "Closed tracking issue #" + number, repo);
        }
    } catch (Exception e) {
        log.warn("Failed to check parent tracking issues for {}: {}", repo.fullName(), e.getMessage());
    }
}
```

(Simplification: parents close only when the repo has zero open sub-issues. With the per-repo serialization model — one issue in flight at a time — this is accurate in practice and avoids per-parent sub-issue bookkeeping. Revisit if multiple parents per repo become common.)

- [ ] **Step 4: Run the full suite**

Run: `./mvnw test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add -A src/main src/test
git commit -m "feat: PROPOSE decomposition mode with dashboard approval; parent stays open as tracker"
```

---

### Task 14: Failure reasons + status visibility (tiles, filters, cooldown countdown)

**Files:**
- Modify: `src/main/java/com/dbbaskette/issuebot/service/workflow/IterationManager.java` (persist reason in `escalateFailure`)
- Modify: `src/main/java/com/dbbaskette/issuebot/service/workflow/IssueWorkflowService.java` (persist reason on every FAILED transition)
- Modify: `src/main/java/com/dbbaskette/issuebot/controller/DashboardController.java` + `templates/dashboard.html` (DECOMPOSED + AWAITING_DECOMPOSITION tiles)
- Modify: `templates/issue-detail.html` (failure banner, cooldown countdown), `templates/issues.html` (reason tooltip)
- Modify: `src/main/resources/templates/repositories.html` + `RepositoryController` (follow-up mode + decomposition mode + pre-screen selects replacing the followUpEnabled checkbox)
- Test: `src/test/java/com/dbbaskette/issuebot/service/workflow/IterationManagerTest.java` (extend)

- [ ] **Step 1: Failing test**

```java
@Test
void escalationPersistsFailureReason() {
    iterationManager.handleRetrySkipped(trackedIssue, "Implementation timed out on iteration 2");
    assertThat(trackedIssue.getLastFailureReason())
            .isEqualTo("Implementation timed out on iteration 2");
    assertThat(trackedIssue.getStatus()).isIn(IssueStatus.FAILED, IssueStatus.COOLDOWN);
}
```

Run: `./mvnw test -Dtest=IterationManagerTest` → FAIL.

- [ ] **Step 2: Implement backend**

`IterationManager.escalateFailure` — first line of the method body:

```java
trackedIssue.setLastFailureReason(notificationDetail);
```

`IssueWorkflowService` — everywhere `setStatus(IssueStatus.FAILED)` is called (unhandled error, setup failure, PR-creation failure, completion failure), also `setLastFailureReason(<the message already being logged>)`. On workflow start (`processIssue` top), clear it: `trackedIssue.setLastFailureReason(null)`.

- [ ] **Step 3: UI**

`issue-detail.html` — under the header, when `issue.lastFailureReason != null` and status is FAILED/COOLDOWN:

```html
<div class="banner banner-error" role="alert" th:if="${issue.lastFailureReason != null and (issue.status.name() == 'FAILED' or issue.status.name() == 'COOLDOWN')}">
  <strong>Last failure:</strong> <span th:text="${issue.lastFailureReason}"></span>
  <span th:if="${issue.status.name() == 'COOLDOWN' and issue.cooldownUntil != null}"
        th:text="'— cooldown until ' + ${#temporals.format(issue.cooldownUntil, 'HH:mm')}"></span>
</div>
```

Show the Retry button for COOLDOWN as "Retry now" (retry already clears `cooldownUntil`). `issues.html` — add `th:title="${issue.lastFailureReason}"` on FAILED/COOLDOWN status badges.

`dashboard.html` + `DashboardController.populateMetrics` — add two tiles following the existing tile markup exactly (including the `href`-less pattern for now; global a11y fix is catalogued separately): "Decomposed" (`DECOMPOSED` count) and "Awaiting Split Approval" (`AWAITING_DECOMPOSITION` count), each deep-linking to the queue filtered by that status. Add both statuses to the queue's status filter dropdown in `issues.html`.

`repositories.html` — replace the `followUpEnabled` checkbox with:

```html
<label for="followUpMode">Review findings handling</label>
<select id="followUpMode" name="followUpMode">
  <option value="ROLLING_BACKLOG">Rolling backlog issue (recommended)</option>
  <option value="COMMENT_ONLY">Comment on issue only</option>
  <option value="PER_ISSUE">One follow-up issue per PR (legacy)</option>
  <option value="OFF">Off — PR review comment only</option>
</select>

<label for="decompositionMode">Large-issue splitting</label>
<select id="decompositionMode" name="decompositionMode">
  <option value="PROPOSE">Propose — I approve splits (recommended)</option>
  <option value="AUTO">Automatic (legacy)</option>
  <option value="OFF">Off — escalate to needs-human</option>
</select>

<label><input type="checkbox" name="preScreenEnabled" checked> Pre-screen issue size before implementing</label>
```

with `th:selected`/`th:checked` bindings to the repo being edited, and `RepositoryController.addOrUpdate` binding all three.

- [ ] **Step 4: Run the full suite + boot check**

Run: `./mvnw test` → PASS. `./run.sh`: dashboard shows the two new tiles; repositories form shows the new selects.

- [ ] **Step 5: Commit**

```bash
git add -A src/main src/test
git commit -m "feat: persisted failure reasons, decomposition status visibility, repo noise-control settings UI"
```

---

## Phase 3 — Dashboard UX Fixes

### Task 15: Cancel a running issue

**Files:**
- Create: `src/main/java/com/dbbaskette/issuebot/service/workflow/WorkflowCancellationService.java`
- Modify: `src/main/java/com/dbbaskette/issuebot/service/claude/ClaudeCodeService.java` (process registry)
- Modify: `src/main/java/com/dbbaskette/issuebot/service/workflow/IssueWorkflowService.java` (checkpoints)
- Modify: `src/main/java/com/dbbaskette/issuebot/controller/IssueController.java` (+ endpoint)
- Modify: `src/main/resources/templates/issue-detail.html` (Stop button)
- Test: `src/test/java/com/dbbaskette/issuebot/service/workflow/WorkflowCancellationServiceTest.java`

- [ ] **Step 1: Failing test**

```java
package com.dbbaskette.issuebot.service.workflow;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class WorkflowCancellationServiceTest {

    private final WorkflowCancellationService service = new WorkflowCancellationService();

    @Test
    void cancelIsPerIssueAndClearable() {
        assertThat(service.isCancelled(1L)).isFalse();
        service.requestCancel(1L);
        assertThat(service.isCancelled(1L)).isTrue();
        assertThat(service.isCancelled(2L)).isFalse();
        service.clear(1L);
        assertThat(service.isCancelled(1L)).isFalse();
    }

    @Test
    void registeredProcessIsDestroyedOnCancel() throws Exception {
        Process p = new ProcessBuilder("sleep", "30").start();
        service.registerProcess(1L, p);
        service.requestCancel(1L);
        p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS);
        assertThat(p.isAlive()).isFalse();
    }
}
```

Run: `./mvnw test -Dtest=WorkflowCancellationServiceTest` → COMPILE FAILURE.

- [ ] **Step 2: Implement the service**

```java
package com.dbbaskette.issuebot.service.workflow;

import org.springframework.stereotype.Component;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Tracks operator cancellation requests and live Claude CLI processes per issue. */
@Component
public class WorkflowCancellationService {

    private final Set<Long> cancelRequested = ConcurrentHashMap.newKeySet();
    private final Map<Long, Process> liveProcesses = new ConcurrentHashMap<>();

    public void requestCancel(Long issueId) {
        cancelRequested.add(issueId);
        Process p = liveProcesses.remove(issueId);
        if (p != null && p.isAlive()) p.destroyForcibly();
    }

    public boolean isCancelled(Long issueId) { return cancelRequested.contains(issueId); }

    public void clear(Long issueId) {
        cancelRequested.remove(issueId);
        liveProcesses.remove(issueId);
    }

    public void registerProcess(Long issueId, Process process) {
        liveProcesses.put(issueId, process);
        if (cancelRequested.contains(issueId) && process.isAlive()) {
            process.destroyForcibly(); // cancel raced with process start
        }
    }

    public void unregisterProcess(Long issueId) { liveProcesses.remove(issueId); }
}
```

- [ ] **Step 3: Wire it through**

`ClaudeCodeService` — add `WorkflowCancellationService` dependency and an `Long issueId` parameter (nullable) on the full `executeTask(...)` signature plus the three role methods; after `pb.start()`:

```java
if (issueId != null) cancellationService.registerProcess(issueId, process);
```

and in a `finally` around the wait/parse block: `if (issueId != null) cancellationService.unregisterProcess(issueId);`. The workflow passes `trackedIssue.getId()`; decomposition/utility calls pass `null` (pre-screen is cheap; keep scope tight).

`IssueWorkflowService` — checkpoint helper + calls:

```java
private boolean cancelled(TrackedIssue trackedIssue) {
    if (!cancellationService.isCancelled(trackedIssue.getId())) return false;
    trackedIssue.setStatus(IssueStatus.FAILED);
    trackedIssue.setCurrentPhase(null);
    trackedIssue.setLastFailureReason("Cancelled by operator");
    issueRepository.save(trackedIssue);
    eventService.log("WORKFLOW_CANCELLED", "Cancelled by operator",
            trackedIssue.getRepo(), trackedIssue);
    cancellationService.clear(trackedIssue.getId());
    return true;
}
```

Call `if (cancelled(trackedIssue)) return;` at: top of the iteration loop, after `phaseImplementation`, after CI verification, and before `phaseIndependentReview`. Clear stale flags at workflow start: `cancellationService.clear(trackedIssue.getId())`.

`IssueController`:

```java
@PostMapping("/issues/{id}/cancel")
public String cancel(@PathVariable Long id, RedirectAttributes redirectAttributes) {
    TrackedIssue issue = issueRepository.findById(id).orElse(null);
    if (issue == null || issue.getStatus() != IssueStatus.IN_PROGRESS) {
        redirectAttributes.addFlashAttribute("error", "Only running issues can be stopped");
        return "redirect:/issues/" + id;
    }
    cancellationService.requestCancel(id);
    redirectAttributes.addFlashAttribute("message",
            "Stop requested — the workflow halts at the next checkpoint");
    return "redirect:/issues/" + id;
}
```

`issue-detail.html` — next to the existing action buttons, visible only when `IN_PROGRESS`, using the page's confirm-modal pattern:

```html
<button type="button" class="btn btn-danger" data-modal-open="stop-modal"
        th:if="${issue.status.name() == 'IN_PROGRESS'}">Stop</button>
<!-- stop-modal: copy of the retry modal structure; body text:
     "Stops the Claude process and marks this issue FAILED (reason: cancelled).
      In-flight GitHub calls finish first. You can retry afterwards." -->
```

- [ ] **Step 4: Run the full suite + manual check**

Run: `./mvnw test` → PASS. Manual: start an issue against a test repo, press Stop, confirm the terminal halts, status flips to FAILED with the "Cancelled by operator" banner, and Retry works after.

- [ ] **Step 5: Commit**

```bash
git add -A src/main src/test
git commit -m "feat: cancel running issue — process kill + workflow checkpoints + Stop button"
```

---

### Task 16: Honest approve/merge

**Files:**
- Modify: `src/main/java/com/dbbaskette/issuebot/controller/ApprovalController.java`
- Modify: `src/main/resources/templates/approvals.html`
- Test: `src/test/java/com/dbbaskette/issuebot/controller/ApprovalControllerTest.java` (extend)

- [ ] **Step 1: Failing test**

```java
@Test
void approveMergesThePrWhenRequested() throws Exception {
    // arrange an AWAITING_APPROVAL issue with prNumber 55 per the class's existing setup
    mockMvc.perform(post("/approvals/" + issue.getId() + "/approve").param("merge", "true"))
            .andExpect(status().is3xxRedirection());
    verify(gitHubApi).mergePullRequest(eq("o"), eq("r"), eq(55), any(), eq("squash"));
}

@Test
void approveWithoutMergeOnlyMarksCompleted() throws Exception {
    mockMvc.perform(post("/approvals/" + issue.getId() + "/approve").param("merge", "false"))
            .andExpect(status().is3xxRedirection());
    verify(gitHubApi, never()).mergePullRequest(any(), any(), anyInt(), any(), any());
}
```

Run: `./mvnw test -Dtest=ApprovalControllerTest` → FAIL.

- [ ] **Step 2: Implement**

`ApprovalController.approve` — add `@RequestParam(defaultValue = "false") boolean merge` and, before setting COMPLETED:

```java
if (merge) {
    if (issue.getPrNumber() == null || issue.getPrNumber() <= 0) {
        redirectAttributes.addFlashAttribute("error", "No PR recorded for this issue — merge manually on GitHub");
        return "redirect:/approvals";
    }
    try {
        String prTitle = "IssueBot: " + issue.getIssueTitle()
                + " (#" + issue.getIssueNumber() + ") (#" + issue.getPrNumber() + ")";
        gitHubApi.mergePullRequest(repo.getOwner(), repo.getName(),
                issue.getPrNumber(), prTitle, "squash");
        eventService.log("PR_MERGED_ON_APPROVAL", "Merged PR #" + issue.getPrNumber(), repo, issue);
    } catch (Exception e) {
        redirectAttributes.addFlashAttribute("error",
                "Merge failed: " + e.getMessage() + " — PR is still open on GitHub");
        return "redirect:/approvals";
    }
}
```

(`GitHubApiClient` becomes a dependency of `ApprovalController` if not already.)

`approvals.html` — the approve modal gets an explicit choice and honest copy:

```html
<label><input type="checkbox" name="merge" value="true" checked>
  Squash-merge PR <span th:text="'#' + ${issue.prNumber}"></span> on GitHub</label>
<p>Approving marks the issue completed. With the box checked, the PR is squash-merged;
   unchecked, the PR stays open for you to merge manually.</p>
```

Button label becomes "Approve" (the checkbox states the merge). Also render CI check status inline on the card: `ApprovalController` list view calls `gitHubApi.getCheckRuns(owner, name, branchName)` per pending card and passes a `ciStatus` string ("passed" / "failed" / "pending" / "unknown") shown as a badge next to the PR link. Wrap in try/catch → "unknown" on API errors.

- [ ] **Step 3: Run + verify**

Run: `./mvnw test` → PASS.

- [ ] **Step 4: Commit**

```bash
git add -A src/main src/test
git commit -m "feat: approve action can actually squash-merge the PR; honest copy + inline CI status"
```

---

### Task 17: GitHub deep links on issue detail

**Files:**
- Modify: `src/main/resources/templates/issue-detail.html`
- Modify: `src/main/java/com/dbbaskette/issuebot/controller/IssueController.java` (model attributes if needed)

- [ ] **Step 1: Implement**

Header meta row gains three links (all `target="_blank" rel="noopener"`), built from `issue.repo.owner`/`name`, `issue.issueNumber`, `issue.prNumber`, `issue.branchName`:

```html
<a th:href="'https://github.com/' + ${issue.repo.owner} + '/' + ${issue.repo.name} + '/issues/' + ${issue.issueNumber}"
   target="_blank" rel="noopener">Issue #<span th:text="${issue.issueNumber}"></span> ↗</a>
<a th:if="${issue.prNumber != null and issue.prNumber > 0}"
   th:href="'https://github.com/' + ${issue.repo.owner} + '/' + ${issue.repo.name} + '/pull/' + ${issue.prNumber}"
   target="_blank" rel="noopener">PR #<span th:text="${issue.prNumber}"></span> ↗</a>
<a th:if="${issue.branchName != null}"
   th:href="'https://github.com/' + ${issue.repo.owner} + '/' + ${issue.repo.name} + '/tree/' + ${issue.branchName}"
   target="_blank" rel="noopener" th:text="${issue.branchName} + ' ↗'"></a>
```

Blocker chips (`issue-detail.html:65-71`) and the queue's "Waiting on #N" become links to `https://github.com/{owner}/{name}/issues/{n}` using the same pattern (split the stored comma-separated `blockedByIssues` in the template via `${#strings.arraySplit(issue.blockedByIssues, ',')}`).

- [ ] **Step 2: Build + eyeball**

Run: `./mvnw test` → PASS. `./run.sh` → detail page shows working links for an issue with a PR.

- [ ] **Step 3: Commit**

```bash
git add -A src/main
git commit -m "feat: GitHub deep links — issue, PR, branch, blocker chips"
```

---

### Task 18: Settings coherence — persist quick settings, real YAML validation, no config clobber

**Files:**
- Modify: `src/main/java/com/dbbaskette/issuebot/controller/SettingsController.java`
- Modify: `src/main/java/com/dbbaskette/issuebot/config/ConfigInitializer.java`
- Modify: `src/main/resources/templates/settings.html`
- Test: `src/test/java/com/dbbaskette/issuebot/controller/SettingsControllerTest.java` (extend)

- [ ] **Step 1: Failing tests**

```java
@Test
void quickSettingsPersistToYaml() throws Exception {
    Path config = tempDir.resolve("config.yml");
    Files.writeString(config, "issuebot:\n  poll-interval-seconds: 60\n");
    IssueBotProperties props = new IssueBotProperties();
    SettingsController controller = newController(props, config);

    controller.quickSettings(120, 5, true, false,
            new org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap());

    assertThat(props.getPollIntervalSeconds()).isEqualTo(120);
    assertThat(Files.readString(config)).contains("poll-interval-seconds: 120");
    assertThat(Files.readString(config)).contains("max-concurrent-issues: 5");
}

@Test
void rawConfigSaveRejectsUnparseableYaml() throws Exception {
    Path config = tempDir.resolve("config.yml");
    Files.writeString(config, "issuebot: {}\n");
    SettingsController controller = newController(new IssueBotProperties(), config);

    controller.saveConfig("issuebot: [broken",
            new org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap());

    assertThat(Files.readString(config)).isEqualTo("issuebot: {}\n"); // untouched
}
```

Run: `./mvnw test -Dtest=SettingsControllerTest` → FAIL.

- [ ] **Step 2: Implement**

- Generalize Task 6's YAML helper to `writeConfigValues(Map<String,Object> issuebotLevelUpdates)` (SnakeYAML load → merge keys at the `issuebot` level → dump); `quickSettings` calls it with `poll-interval-seconds`, `max-concurrent-issues`, and a `notifications` submap after updating the live bean. Remove the "revert on restart" warning from `settings.html`; each card states "Applies immediately and is saved to config.yml".
- `saveConfig` (raw editor): replace the `contains("issuebot:")` check with a real parse — `new Yaml().load(content)` inside try/catch; on exception, flash the parse error and don't write. Keep a structural check that the parsed root contains an `issuebot` map.
- `ConfigInitializer.syncRepositories`: apply config values **only for newly created repos**:

```java
private void syncRepositories() {
    for (IssueBotProperties.RepositoryConfig repoCfg : properties.getRepositories()) {
        if (repoRepository.findByOwnerAndName(repoCfg.getOwner(), repoCfg.getName()).isPresent()) {
            continue; // existing repos are managed from the dashboard — do not clobber UI edits
        }
        WatchedRepo repo = new WatchedRepo(repoCfg.getOwner(), repoCfg.getName());
        log.info("Adding new watched repo from config: {}", repoCfg.fullName());
        repo.setBranch(repoCfg.getBranch());
        repo.setMode(parseMode(repoCfg.getMode()));
        repo.setMaxIterations(repoCfg.getMaxIterations());
        repo.setCiTimeoutMinutes(repoCfg.getCiTimeoutMinutes());
        if (!repoCfg.getAllowedPaths().isEmpty()) {
            try {
                repo.setAllowedPaths(objectMapper.writeValueAsString(repoCfg.getAllowedPaths()));
            } catch (Exception e) {
                log.warn("Failed to serialize allowed paths for {}", repoCfg.fullName());
            }
        }
        repoRepository.save(repo);
    }
}
```

- [ ] **Step 3: Run the full suite**

Run: `./mvnw test` → PASS.

- [ ] **Step 4: Commit**

```bash
git add -A src/main src/test
git commit -m "feat: settings persist to config.yml with real YAML validation; config sync no longer clobbers UI edits"
```

---

## Final Verification

- [ ] `./mvnw test` — full suite green.
- [ ] `./run.sh` end-to-end smoke against a scratch repo: label an issue `agent-ready` → confirm the resolved models appear in the pipeline UI and cost rows → let review pass with medium findings → confirm exactly one "IssueBot Backlog" issue is created/updated (not a new follow-up issue) → run a deliberately oversized issue → confirm a split *proposal* appears (no sub-issues yet) → approve from the dashboard → confirm sub-issues created, parent open with `issuebot-parent` → Stop a running issue mid-implementation → confirm FAILED + "Cancelled by operator" → Retry with a Sonnet override → confirm the override model in the pipeline header.
- [ ] Update `README.md` feature list: model selection (global/repo/issue), rolling backlog, propose-mode decomposition, cancel button (part of Task 8 + a final pass here).

## Self-Review Notes

- **Spec coverage:** §3 (models/catalog/resolver/plumbing/UI truth/cost) → Tasks 2–8; §4.1 (backlog) → Tasks 9–11; §4.2 (guards/propose/parent) → Tasks 12–13; §4.3 (failure reason/cooldown) → Task 14; §5 items 1–6 → Tasks 15–18 (+5 in Task 5/8, +6 in Task 17). §5 item 4 → Tasks 6+18. Backlog "Promote to issue" dashboard action is **deferred** — the backlog issue itself ships first; promotion is checklist-item → manual issue for now (noted in backlog body copy). §6 items 7–17 are explicitly out of scope.
- **Types:** `FollowUpMode`/`DecompositionMode`/`AWAITING_DECOMPOSITION` defined in Task 1 and used from Task 11 onward; `ModelCatalog.estimateCost` returns `Optional<BigDecimal>` (Task 2) consumed in Task 5; `executeUtility` introduced in Task 4, used by Tasks 12–13 tests; `WorkflowCancellationService` API consistent between Task 15 steps.
- **Known judgment calls:** parent-close simplification (zero open subs per repo), SnakeYAML comment loss on config writes, cancel marks FAILED rather than a new status — all documented in the spec §10.

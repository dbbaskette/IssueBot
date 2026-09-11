# Coding Harness Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace IssueBot's Claude/Codex branching with a provider-neutral harness contract and make all current model selections validate and persist a harness/model/reasoning tuple.

**Architecture:** A `CodingHarnessAdapter` owns one CLI's command construction, capability catalog, authentication, parsing, continuation, and cancellation. `CodingHarnessRegistry` resolves adapters by stable string ID, while `CodingHarnessService` preserves the current workflow entry points and delegates through an explicitly pinned harness. Existing utility, planning, implementation, and review flows remain behaviorally unchanged in this release.

**Tech Stack:** Java 21, Spring Boot 3.4, Thymeleaf, vanilla JavaScript, JPA/H2, Flyway, JUnit 5, Mockito, Node test runner.

**Spec:** `docs/superpowers/specs/2026-09-11-cross-harness-superpowers-orchestration-design.md`

## Global Constraints

- IssueBot remains authoritative for workflow transitions, repository mutation, cancellation, PRs, and merge behavior.
- Release 1 supports the existing Claude and Codex CLIs only; OpenCode and Cursor adapters are later releases.
- Every model selection is paired with a harness-supported reasoning value, including Claude's `--effort` option.
- Stable persisted harness IDs are `claude` and `codex`; legacy `CLAUDE_CODE` and `CODEX` values remain readable.
- Existing active issues and stage approvals must resume without provider substitution or replay.
- No harness may silently substitute a model, effort, authentication mode, or billing path.
- Current utility, planning, implementation, and review workflow behavior remains unchanged.
- Keep the output filename `target/issuebot.jar` stable.
- This feature release increments Maven version from `0.5.1` to `0.6.0` and adds one changelog section.

---

### Task 1: Define the provider-neutral harness contract

**Files:**
- Create: `src/main/java/com/dbbaskette/issuebot/service/harness/HarnessIds.java`
- Create: `src/main/java/com/dbbaskette/issuebot/service/harness/HarnessRole.java`
- Create: `src/main/java/com/dbbaskette/issuebot/service/harness/HarnessModel.java`
- Create: `src/main/java/com/dbbaskette/issuebot/service/harness/HarnessCapabilities.java`
- Create: `src/main/java/com/dbbaskette/issuebot/service/harness/HarnessExecutionRequest.java`
- Create: `src/main/java/com/dbbaskette/issuebot/service/harness/HarnessExecutionResult.java`
- Create: `src/main/java/com/dbbaskette/issuebot/service/harness/CodingHarnessAdapter.java`
- Create: `src/main/java/com/dbbaskette/issuebot/service/harness/CodingHarnessRegistry.java`
- Test: `src/test/java/com/dbbaskette/issuebot/service/harness/CodingHarnessRegistryTest.java`
- Test: `src/test/java/com/dbbaskette/issuebot/service/harness/HarnessModelTest.java`

**Interfaces:**
- Produces: `HarnessIds.normalize(String)`, `HarnessRole`, immutable model/capability/request records, the mutable parser result, `CodingHarnessAdapter`, and registry lookup/catalog methods used by every later task.
- Consumes: only JDK types and Spring component injection.

- [ ] **Step 1: Write failing registry and model-capability tests**

```java
@Test
void resolvesStableAndLegacyHarnessIds() {
    var claude = adapter("claude");
    var codex = adapter("codex");
    var registry = new CodingHarnessRegistry(List.of(claude, codex));

    assertThat(registry.require("claude")).isSameAs(claude);
    assertThat(registry.require("CLAUDE_CODE")).isSameAs(claude);
    assertThat(registry.require("CODEX")).isSameAs(codex);
    assertThatThrownBy(() -> registry.require("cursor"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("cursor");
}

@Test
void rejectsReasoningThatModelDoesNotSupport() {
    var model = new HarnessModel("test", "Test", "", "high", List.of("low", "high"));
    assertThatCode(() -> model.validateReasoning("high")).doesNotThrowAnyException();
    assertThatThrownBy(() -> model.validateReasoning("medium"))
            .isInstanceOf(IllegalArgumentException.class);
}
```

- [ ] **Step 2: Run the focused tests and verify RED**

Run: `./mvnw -Dtest=CodingHarnessRegistryTest,HarnessModelTest test`

Expected: compilation fails because the harness package and types do not exist.

- [ ] **Step 3: Implement the stable IDs and shared records**

```java
public final class HarnessIds {
    public static final String CLAUDE = "claude";
    public static final String CODEX = "codex";

    public static String normalize(String value) {
        if (value == null || value.isBlank()) return CLAUDE;
        return switch (value.trim().toLowerCase(Locale.ROOT).replace('-', '_')) {
            case "claude", "claude_code" -> CLAUDE;
            case "codex", "codex_cli" -> CODEX;
            default -> value.trim().toLowerCase(Locale.ROOT);
        };
    }
}

public enum HarnessRole {
    ANALYSIS_CLASSIFICATION, DESIGN_PLANNING, IMPLEMENTATION,
    DEBUGGING_CORRECTIONS, TASK_REVIEW, FINAL_REVIEW
}

public record HarnessModel(
        String id, String displayName, String description,
        String defaultReasoningLevel, List<String> supportedReasoningLevels) {
    public HarnessModel {
        supportedReasoningLevels = List.copyOf(supportedReasoningLevels);
    }
    public String resolveReasoning(String requested) {
        String value = requested == null || requested.isBlank() ? defaultReasoningLevel : requested.trim();
        validateReasoning(value);
        return value;
    }
    public void validateReasoning(String value) {
        if (!supportedReasoningLevels.contains(value))
            throw new IllegalArgumentException("Reasoning " + value + " is not supported by " + id);
    }
    public String reasoningLevelsCsv() { return String.join(",", supportedReasoningLevels); }
}
```

- [ ] **Step 4: Implement the adapter and registry interfaces**

```java
public interface CodingHarnessAdapter {
    String id();
    String displayName();
    List<HarnessModel> models();
    HarnessCapabilities capabilities();
    boolean checkCliAvailable();
    boolean checkSubscriptionAuthentication();
    HarnessExecutionResult execute(HarnessExecutionRequest request, Consumer<String> lineCallback);
}

@Service
public class CodingHarnessRegistry {
    private final Map<String, CodingHarnessAdapter> adapters;
    public CodingHarnessRegistry(List<CodingHarnessAdapter> adapters) {
        this.adapters = adapters.stream().collect(Collectors.toUnmodifiableMap(
                adapter -> HarnessIds.normalize(adapter.id()), Function.identity()));
    }
    public CodingHarnessAdapter require(String id) {
        String normalized = HarnessIds.normalize(id);
        CodingHarnessAdapter adapter = adapters.get(normalized);
        if (adapter == null) throw new IllegalArgumentException("Unsupported coding harness: " + id);
        return adapter;
    }
    public List<CodingHarnessAdapter> adapters() { return List.copyOf(adapters.values()); }
}
```

- [ ] **Step 5: Run focused tests and verify GREEN**

Run: `./mvnw -Dtest=CodingHarnessRegistryTest,HarnessModelTest test`

Expected: both classes pass with zero failures.

- [ ] **Step 6: Commit the contract**

```bash
git add src/main/java/com/dbbaskette/issuebot/service/harness src/test/java/com/dbbaskette/issuebot/service/harness
git commit -m "feat: add coding harness contract"
```

### Task 2: Adapt Claude and Codex execution behind the contract

**Files:**
- Modify: `src/main/java/com/dbbaskette/issuebot/service/claude/ClaudeCodeService.java`
- Modify: `src/main/java/com/dbbaskette/issuebot/service/claude/StreamJsonParser.java`
- Modify: `src/main/java/com/dbbaskette/issuebot/service/codex/CodexCliService.java`
- Modify: `src/main/java/com/dbbaskette/issuebot/service/codex/CodexJsonParser.java`
- Modify: `src/main/java/com/dbbaskette/issuebot/service/claude/ModelCatalog.java`
- Modify: `src/main/java/com/dbbaskette/issuebot/service/codex/CodexModelCatalog.java`
- Delete: `src/main/java/com/dbbaskette/issuebot/service/claude/ClaudeCodeResult.java`
- Create: `src/main/java/com/dbbaskette/issuebot/service/harness/ClaudeHarnessAdapter.java`
- Create: `src/main/java/com/dbbaskette/issuebot/service/harness/CodexHarnessAdapter.java`
- Test: `src/test/java/com/dbbaskette/issuebot/service/harness/ClaudeHarnessAdapterTest.java`
- Test: `src/test/java/com/dbbaskette/issuebot/service/harness/CodexHarnessAdapterTest.java`
- Modify: `src/test/java/com/dbbaskette/issuebot/service/claude/ClaudeCodeServiceTest.java`
- Modify: `src/test/java/com/dbbaskette/issuebot/service/claude/StreamJsonParserTest.java`
- Modify: `src/test/java/com/dbbaskette/issuebot/service/codex/CodexCliServiceTest.java`
- Modify: `src/test/java/com/dbbaskette/issuebot/service/codex/CodexJsonParserTest.java`

**Interfaces:**
- Consumes: Task 1's request/result/model/capability/adapter contract.
- Produces: two Spring adapter beans with stable IDs and provider-specific command translation; Claude commands accept an explicit reasoning value through `--effort`.

- [ ] **Step 1: Write failing adapter routing and command tests**

```java
@Test
void claudeImplementationPassesModelEffortAndResume() {
    HarnessExecutionRequest request = new HarnessExecutionRequest(
            HarnessRole.IMPLEMENTATION, "implement", Path.of("."),
            "claude-opus-4-8", "xhigh", "session-1", 7L);
    adapter.execute(request, null);
    verify(runner).executeImplementation("implement", Path.of("."),
            "claude-opus-4-8", "xhigh", "session-1", 7L, null);
}

@Test
void codexReviewNeverResumesImplementationSession() {
    HarnessExecutionRequest request = new HarnessExecutionRequest(
            HarnessRole.FINAL_REVIEW, "review", Path.of("."),
            "gpt-6-astra", "high", "session-1", 7L);
    adapter.execute(request, null);
    verify(runner).executeReview("review", Path.of("."), "gpt-6-astra", "high", 7L, null);
}
```

- [ ] **Step 2: Run adapter tests and verify RED**

Run: `./mvnw -Dtest=ClaudeHarnessAdapterTest,CodexHarnessAdapterTest test`

Expected: compilation fails because the adapters and explicit Claude effort parameter do not exist.

- [ ] **Step 3: Move the result type to the neutral package and update both parsers**

Move the exact fields and methods from `ClaudeCodeResult` into `HarnessExecutionResult`, change parser return types and constructors, then update parser tests to import the neutral type. Preserve JSON parsing, session IDs, usage, costs, changed files, final-result behavior, error text, and `toString()` semantics.

```java
public HarnessExecutionResult parse(String rawOutput) {
    HarnessExecutionResult result = new HarnessExecutionResult();
    // existing parser behavior remains unchanged
    return result;
}
```

- [ ] **Step 4: Make Claude accept and pass explicit effort**

Add `String reasoningLevel` to implementation, review, utility, planning, task, and command-building entry points. Insert `--effort` and the resolved value in both implementation and read-only planning commands. Remove the Codex dependency, provider pin, and provider branching from `ClaudeCodeService`; it becomes a Claude-only runner.

```java
private static void addEffort(List<String> command, String reasoningLevel) {
    if (reasoningLevel == null || reasoningLevel.isBlank()) return;
    command.add("--effort");
    command.add(reasoningLevel);
}
```

- [ ] **Step 5: Make Codex role entry points accept explicit reasoning**

Thread `request.reasoningLevel()` into the existing `--config model_reasoning_effort=...` command construction for utility, planning, implementation, and review. Review ignores `resumeSessionId`; implementation alone may resume.

- [ ] **Step 6: Implement both adapters and capability catalogs**

Claude exposes `low`, `medium`, `high`, `xhigh`, and `max` only on catalog models whose adapter metadata declares effort support; models without effort expose one `default` reasoning value that omits `--effort`. Codex maps every `CodexModelCatalog.ModelInfo` directly to `HarnessModel`.

```java
@Component
public final class CodexHarnessAdapter implements CodingHarnessAdapter {
    public String id() { return HarnessIds.CODEX; }
    public String displayName() { return "Codex CLI"; }
    public HarnessExecutionResult execute(HarnessExecutionRequest request, Consumer<String> callback) {
        return switch (request.role()) {
            case ANALYSIS_CLASSIFICATION -> runner.executeUtility(request.prompt(), request.workspace(),
                    request.model(), request.reasoningLevel(), callback);
            case DESIGN_PLANNING -> runner.executePlanning(request.prompt(), request.workspace(),
                    request.model(), request.reasoningLevel(), request.issueId(), callback);
            case IMPLEMENTATION, DEBUGGING_CORRECTIONS -> runner.executeImplementation(
                    request.prompt(), request.workspace(), request.model(), request.reasoningLevel(),
                    request.resumeSessionId(), request.issueId(), callback);
            case TASK_REVIEW, FINAL_REVIEW -> runner.executeReview(request.prompt(), request.workspace(),
                    request.model(), request.reasoningLevel(), request.issueId(), callback);
        };
    }
}
```

- [ ] **Step 7: Run runner, parser, and adapter tests**

Run: `./mvnw -Dtest=ClaudeCodeServiceTest,StreamJsonParserTest,CodexCliServiceTest,CodexJsonParserTest,ClaudeHarnessAdapterTest,CodexHarnessAdapterTest test`

Expected: all selected tests pass; command assertions include Claude `--effort` and Codex reasoning config.

- [ ] **Step 8: Commit adapter extraction**

```bash
git add src/main/java/com/dbbaskette/issuebot/service/claude src/main/java/com/dbbaskette/issuebot/service/codex src/main/java/com/dbbaskette/issuebot/service/harness src/test/java/com/dbbaskette/issuebot/service/claude src/test/java/com/dbbaskette/issuebot/service/codex src/test/java/com/dbbaskette/issuebot/service/harness
git commit -m "refactor: adapt Claude and Codex runners"
```

### Task 3: Introduce the neutral execution facade and migrate workflow consumers

**Files:**
- Create: `src/main/java/com/dbbaskette/issuebot/service/harness/CodingHarnessService.java`
- Modify: `src/main/java/com/dbbaskette/issuebot/service/review/CodeReviewService.java`
- Modify: `src/main/java/com/dbbaskette/issuebot/service/workflow/IssueDecompositionService.java`
- Modify: `src/main/java/com/dbbaskette/issuebot/service/workflow/IssueWorkflowService.java`
- Modify: `src/main/java/com/dbbaskette/issuebot/service/workflow/IterationManager.java`
- Modify: `src/main/java/com/dbbaskette/issuebot/service/workflow/LessonsService.java`
- Modify: `src/main/java/com/dbbaskette/issuebot/service/workflow/PlanFirstService.java`
- Modify: `src/main/java/com/dbbaskette/issuebot/service/workflow/StageWorkflowCoordinator.java`
- Modify: `src/main/java/com/dbbaskette/issuebot/service/workflow/WorkflowCheckpointTransactionManager.java`
- Modify: `src/main/java/com/dbbaskette/issuebot/observability/IssueBotHealthIndicator.java`
- Modify: `src/main/java/com/dbbaskette/issuebot/validation/StartupValidator.java`
- Test: `src/test/java/com/dbbaskette/issuebot/service/harness/CodingHarnessServiceTest.java`
- Modify: `src/test/java/com/dbbaskette/issuebot/controller/DashboardTileRenderTest.java`
- Modify: `src/test/java/com/dbbaskette/issuebot/controller/IssueDetailLayoutRenderTest.java`
- Modify: `src/test/java/com/dbbaskette/issuebot/controller/SetupControllerTest.java`
- Modify: `src/test/java/com/dbbaskette/issuebot/service/review/CodeReviewServiceParseTest.java`
- Modify: `src/test/java/com/dbbaskette/issuebot/service/workflow/IntegrationWorkflowTest.java`
- Modify: `src/test/java/com/dbbaskette/issuebot/service/workflow/IssueDecompositionServiceTest.java`
- Modify: `src/test/java/com/dbbaskette/issuebot/service/workflow/IssueDispatchTransactionManagerTest.java`
- Modify: `src/test/java/com/dbbaskette/issuebot/service/workflow/IssueWorkflowServiceTest.java`
- Modify: `src/test/java/com/dbbaskette/issuebot/service/workflow/IterationManagerTest.java`
- Modify: `src/test/java/com/dbbaskette/issuebot/service/workflow/LessonsServiceTest.java`
- Modify: `src/test/java/com/dbbaskette/issuebot/service/workflow/PlanConformanceWorkflowTest.java`
- Modify: `src/test/java/com/dbbaskette/issuebot/service/workflow/PlanFirstServiceTest.java`
- Modify: `src/test/java/com/dbbaskette/issuebot/service/workflow/PlanFirstTransactionManagerTest.java`
- Modify: `src/test/java/com/dbbaskette/issuebot/service/workflow/PlanInvalidationEventPersistenceTest.java`
- Modify: `src/test/java/com/dbbaskette/issuebot/service/workflow/StageWorkflowCoordinatorTest.java`
- Modify: `src/test/java/com/dbbaskette/issuebot/service/workflow/WorkflowCheckpointTransactionManagerTest.java`

**Interfaces:**
- Consumes: `CodingHarnessRegistry`, `HarnessExecutionRequest`, and stable harness IDs.
- Produces: workflow-compatible `executeUtility`, `executePlanning`, `executeImplementation`, `executeReview`, `pinHarness`, `pinSubscriptionHarness`, and `clearPinnedHarness` methods returning `HarnessExecutionResult`.

- [ ] **Step 1: Write the failing facade delegation tests**

```java
@Test
void pinnedHarnessReceivesExplicitRoleModelAndReasoning() {
    service.pinHarness("codex");
    service.executePlanning("plan", Path.of("repo"), "gpt-6-astra", "ultra", 9L, null);

    verify(codex).execute(argThat(request -> request.role() == HarnessRole.DESIGN_PLANNING
            && request.model().equals("gpt-6-astra")
            && request.reasoningLevel().equals("ultra")), isNull());
}
```

- [ ] **Step 2: Run the facade test and verify RED**

Run: `./mvnw -Dtest=CodingHarnessServiceTest test`

Expected: compilation fails because `CodingHarnessService` does not exist.

- [ ] **Step 3: Implement the neutral facade**

```java
@Service
public final class CodingHarnessService {
    private final CodingHarnessRegistry registry;
    private final IssueBotProperties properties;
    private final ThreadLocal<String> pinnedHarness = new ThreadLocal<>();

    public String harnessId() {
        String pinned = pinnedHarness.get();
        return HarnessIds.normalize(pinned == null ? properties.getAgentProvider() : pinned);
    }

    public void pinHarness(String id) { pinnedHarness.set(registry.require(id).id()); }
    public void clearPinnedHarness() { pinnedHarness.remove(); }
}
```

All execution entry points resolve the adapter once, resolve/validate the model's reasoning, construct `HarnessExecutionRequest`, and delegate. Subscription pinning performs fresh adapter authentication before storing the pin.

- [ ] **Step 4: Migrate production consumers from Claude-named types**

Replace `ClaudeCodeService` injection with `CodingHarnessService`, `ClaudeCodeResult` with `HarnessExecutionResult`, provider enum comparisons with normalized string IDs, and pin/clear calls with neutral names. Preserve planning read-only behavior, independent review session isolation, retry continuation, cancellation registration, event streaming, cost tracking, and error messages.

- [ ] **Step 5: Migrate affected unit tests and run the focused workflow slice**

Run: `./mvnw -Dtest=CodingHarnessServiceTest,CodeReviewServiceParseTest,IssueDecompositionServiceTest,PlanFirstServiceTest,StageWorkflowCoordinatorTest,WorkflowCheckpointTransactionManagerTest,IterationManagerTest,LessonsServiceTest test`

Expected: all selected tests pass with neutral mocks and result imports.

- [ ] **Step 6: Commit the neutral workflow facade**

```bash
git add src/main/java src/test/java
git commit -m "refactor: route workflows through coding harness service"
```

### Task 4: Persist stable harness IDs without breaking legacy runs

**Files:**
- Create: `src/main/resources/db/migration/V39__provider_neutral_harness_ids.sql`
- Modify: `src/main/java/com/dbbaskette/issuebot/model/TrackedIssue.java`
- Modify: `src/main/java/com/dbbaskette/issuebot/model/StageApproval.java`
- Modify: `src/main/java/com/dbbaskette/issuebot/service/workflow/PlanFirstTransactionManager.java`
- Modify: `src/main/java/com/dbbaskette/issuebot/service/workflow/StageApprovalService.java`
- Modify: `src/main/java/com/dbbaskette/issuebot/service/workflow/StageWorkflowCoordinator.java`
- Test: `src/test/java/com/dbbaskette/issuebot/repository/HarnessIdMigrationTest.java`
- Modify: `src/test/java/com/dbbaskette/issuebot/model/TrackedIssueTest.java`
- Modify: `src/test/java/com/dbbaskette/issuebot/service/workflow/StageApprovalServiceTest.java`
- Modify: `src/test/java/com/dbbaskette/issuebot/service/workflow/PlanFirstTransactionManagerTest.java`

**Interfaces:**
- Produces: `TrackedIssue.resolvedHarnessId` and `StageApproval.harnessId` as the authoritative new fields.
- Consumes: `HarnessIds.normalize` for legacy compatibility.

- [ ] **Step 1: Write a failing Flyway migration test**

Create a pre-V39 H2 schema row with `resolved_agent_provider='CLAUDE_CODE'` and a stage approval with `provider='CODEX'`, migrate, then assert:

```java
assertThat(query("select resolved_harness_id from tracked_issues where id=1"))
        .isEqualTo("claude");
assertThat(query("select harness_id from stage_approvals where id=1"))
        .isEqualTo("codex");
```

- [ ] **Step 2: Run migration tests and verify RED**

Run: `./mvnw -Dtest=HarnessIdMigrationTest test`

Expected: the migration or new columns are missing.

- [ ] **Step 3: Add additive migration V39**

```sql
ALTER TABLE tracked_issues ADD COLUMN resolved_harness_id VARCHAR(64);
UPDATE tracked_issues
SET resolved_harness_id = CASE resolved_agent_provider
    WHEN 'CLAUDE_CODE' THEN 'claude'
    WHEN 'CODEX' THEN 'codex'
    ELSE LOWER(resolved_agent_provider)
END
WHERE resolved_agent_provider IS NOT NULL;

ALTER TABLE stage_approvals ADD COLUMN harness_id VARCHAR(64);
UPDATE stage_approvals
SET harness_id = CASE provider
    WHEN 'CLAUDE_CODE' THEN 'claude'
    WHEN 'CODEX' THEN 'codex'
    ELSE LOWER(provider)
END
WHERE provider IS NOT NULL;
```

Do not drop the legacy columns in this release; they are rollback and active-run compatibility data.

- [ ] **Step 4: Make new string fields authoritative in entities and transactions**

```java
@Column(name = "resolved_harness_id", length = 64)
private String resolvedHarnessId;

public String getResolvedHarnessId() { return resolvedHarnessId; }
public void setResolvedHarnessId(String value) { resolvedHarnessId = HarnessIds.normalize(value); }
```

Apply the same pattern to `StageApproval.harnessId`. Retain deprecated enum accessors only where a legacy test or migration path still requires them; new workflow code reads and writes string IDs. Reset paths clear the new field with the legacy session/provider fields.

- [ ] **Step 5: Run migration, entity, approval, and planning transaction tests**

Run: `./mvnw -Dtest=HarnessIdMigrationTest,TrackedIssueTest,StageApprovalServiceTest,PlanFirstTransactionManagerTest,StageWorkflowCoordinatorTest test`

Expected: migrated values are stable IDs and active-run identity checks remain exact.

- [ ] **Step 6: Commit provider-neutral persistence**

```bash
git add src/main/resources/db/migration/V39__provider_neutral_harness_ids.sql src/main/java/com/dbbaskette/issuebot/model src/main/java/com/dbbaskette/issuebot/service/workflow src/test/java/com/dbbaskette/issuebot
git commit -m "feat: persist provider-neutral harness IDs"
```

### Task 5: Make model and reasoning resolution capability-driven

**Files:**
- Modify: `src/main/java/com/dbbaskette/issuebot/config/IssueBotProperties.java`
- Create: `src/main/java/com/dbbaskette/issuebot/service/harness/HarnessSelection.java`
- Create: `src/main/java/com/dbbaskette/issuebot/service/harness/HarnessSelectionService.java`
- Delete: `src/main/java/com/dbbaskette/issuebot/service/codex/ReasoningSelectionService.java`
- Modify: `src/main/java/com/dbbaskette/issuebot/service/claude/ModelResolver.java`
- Modify: `src/main/java/com/dbbaskette/issuebot/service/workflow/StageModelSelectionService.java`
- Modify: `src/main/java/com/dbbaskette/issuebot/service/workflow/StageApprovalService.java`
- Modify: `src/main/java/com/dbbaskette/issuebot/service/workflow/StageWorkflowCoordinator.java`
- Test: `src/test/java/com/dbbaskette/issuebot/service/harness/HarnessSelectionServiceTest.java`
- Modify: `src/test/java/com/dbbaskette/issuebot/service/claude/ModelResolverTest.java`
- Modify: `src/test/java/com/dbbaskette/issuebot/service/workflow/StageModelSelectionServiceTest.java`
- Modify: `src/test/java/com/dbbaskette/issuebot/service/workflow/StageApprovalServiceTest.java`

**Interfaces:**
- Produces: one `HarnessSelection(harnessId, modelId, reasoningLevel)` and validation path used by defaults, repository overrides, issue overrides, and stage approvals.
- Consumes: adapter model catalogs and availability/authentication checks.

- [ ] **Step 1: Write failing selection tests for Claude and Codex**

```java
@Test
void resolvesAndValidatesCompleteTupleForEitherHarness() {
    HarnessSelection claude = service.resolve("claude", "claude-opus-4-8", "xhigh");
    HarnessSelection codex = service.resolve("codex", "gpt-6-astra", "ultra");
    assertThat(claude.reasoningLevel()).isEqualTo("xhigh");
    assertThat(codex.reasoningLevel()).isEqualTo("ultra");
}

@Test
void neverSilentlyChangesAnExplicitUnsupportedReasoningValue() {
    assertThatThrownBy(() -> service.resolve("claude", "claude-haiku-4-5", "max"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("max").hasMessageContaining("claude-haiku-4-5");
}
```

- [ ] **Step 2: Run selection tests and verify RED**

Run: `./mvnw -Dtest=HarnessSelectionServiceTest,StageModelSelectionServiceTest test`

Expected: neutral selection types are missing and Claude reasoning is not validated.

- [ ] **Step 3: Convert global provider configuration to a stable string ID**

Keep the YAML key `issuebot.agent-provider` for compatibility in Release 1, but store its value as a normalized string. Add Claude implementation/review/utility reasoning properties with adapter-valid defaults. Retain the nested Claude and Codex model sections so existing config files continue to bind.

```java
@NotNull
private String agentProvider = HarnessIds.CLAUDE;

public String getAgentProvider() { return HarnessIds.normalize(agentProvider); }
public void setAgentProvider(String value) { agentProvider = HarnessIds.normalize(value); }
```

- [ ] **Step 4: Implement neutral tuple resolution and stage validation**

`HarnessSelectionService.resolve` requires the adapter, requires an exact catalog model, resolves blank reasoning to that model's declared default, rejects unsupported explicit reasoning, and returns the full tuple. `validateReady` checks CLI installation and fresh subscription authentication. `StageModelSelectionService.Selection` becomes or wraps `HarnessSelection`; non-model-driven stages reject any part of the tuple.

- [ ] **Step 5: Update legacy model resolution and all stage decisions**

`ModelResolver` reads the selected adapter ID rather than enum values and keeps current precedence: issue override, compatible repository override, then provider-specific global default. Stage approvals persist harness/model/reasoning together and reject stale or incomplete selections before mutation.

- [ ] **Step 6: Run selection and stage tests**

Run: `./mvnw -Dtest=HarnessSelectionServiceTest,ModelResolverTest,StageModelSelectionServiceTest,StageApprovalServiceTest,StageWorkflowCoordinatorTest test`

Expected: both Claude and Codex reasoning are validated from adapter metadata and every approved decision carries the exact tuple.

- [ ] **Step 7: Commit capability-driven selection**

```bash
git add src/main/java/com/dbbaskette/issuebot/config src/main/java/com/dbbaskette/issuebot/service/harness src/main/java/com/dbbaskette/issuebot/service/claude src/main/java/com/dbbaskette/issuebot/service/codex src/main/java/com/dbbaskette/issuebot/service/workflow src/test/java/com/dbbaskette/issuebot
git commit -m "feat: resolve harness model and reasoning together"
```

### Task 6: Render all model and reasoning controls from harness capabilities

**Files:**
- Create: `src/main/java/com/dbbaskette/issuebot/controller/HarnessCatalogAdvice.java`
- Delete: `src/main/java/com/dbbaskette/issuebot/controller/ReasoningModelAdvice.java`
- Modify: `src/main/java/com/dbbaskette/issuebot/controller/UiModelAdvice.java`
- Modify: `src/main/java/com/dbbaskette/issuebot/controller/SettingsController.java`
- Modify: `src/main/java/com/dbbaskette/issuebot/controller/SetupController.java`
- Modify: `src/main/java/com/dbbaskette/issuebot/controller/RepositoryController.java`
- Modify: `src/main/java/com/dbbaskette/issuebot/controller/IssueController.java`
- Modify: `src/main/java/com/dbbaskette/issuebot/controller/StageApprovalController.java`
- Modify: `src/main/resources/templates/settings.html`
- Modify: `src/main/resources/templates/setup.html`
- Modify: `src/main/resources/templates/repositories.html`
- Modify: `src/main/resources/templates/issue-detail.html`
- Modify: `src/main/resources/templates/fragments/reasoning.html`
- Modify: `src/main/resources/templates/fragments/stage-approval.html`
- Modify: `src/main/resources/static/js/app.js`
- Modify: `src/main/resources/static/js/repository-workflow.js`
- Test: `src/test/java/com/dbbaskette/issuebot/controller/HarnessCatalogAdviceTest.java`
- Modify: `src/test/java/com/dbbaskette/issuebot/controller/SettingsControllerTest.java`
- Modify: `src/test/java/com/dbbaskette/issuebot/controller/SettingsPageRenderTest.java`
- Modify: `src/test/java/com/dbbaskette/issuebot/controller/SetupControllerTest.java`
- Modify: `src/test/java/com/dbbaskette/issuebot/controller/RepositoryControllerTest.java`
- Modify: `src/test/java/com/dbbaskette/issuebot/controller/IssueControllerTest.java`
- Modify: `src/test/java/com/dbbaskette/issuebot/controller/StageApprovalRenderTest.java`
- Modify: `src/test/js/reasoning-pickers.test.cjs`

**Interfaces:**
- Consumes: registry adapters and `HarnessModel` metadata.
- Produces: `harnessCatalog` and `harnessCatalogJson` model attributes plus generic browser behavior for harness/model/reasoning controls.

- [ ] **Step 1: Write failing controller and render tests**

```java
@Test
void settingsRendersReasoningForClaudeAndCodexFromOneCatalog() {
    Document page = renderSettings();
    assertThat(page.select("[data-harness-id=claude]")).isNotEmpty();
    assertThat(page.select("option[data-model-id=claude-opus-4-8][data-reasoning-levels*=xhigh]")).hasSize(1);
    assertThat(page.select("option[data-model-id=gpt-6-astra][data-reasoning-levels*=ultra]")).hasSize(1);
    assertThat(page.select("[data-reasoning-picker]")).hasSizeGreaterThanOrEqualTo(3);
}
```

Add a Node test that switches from Codex/Astra/ultra to Claude/Haiku and proves the picker resets to the adapter-declared default instead of retaining an unsupported value.

- [ ] **Step 2: Run render and JavaScript tests and verify RED**

Run: `./mvnw -Dtest=HarnessCatalogAdviceTest,SettingsControllerTest,SettingsPageRenderTest,SetupControllerTest,RepositoryControllerTest,IssueControllerTest,StageApprovalRenderTest test`

Run: `node --test src/test/js/reasoning-pickers.test.cjs`

Expected: Claude reasoning and generic harness metadata are absent.

- [ ] **Step 3: Publish a generic catalog to page controllers**

```java
@ModelAttribute("harnessCatalog")
public List<HarnessView> harnessCatalog() {
    return registry.adapters().stream().map(HarnessView::from).toList();
}

@ModelAttribute("harnessCatalogJson")
public String harnessCatalogJson() throws JsonProcessingException {
    return objectMapper.writeValueAsString(harnessCatalog());
}
```

Catalog views contain ID, display name, availability, authentication, capabilities, and model metadata. Page rendering does not execute expensive fresh authentication checks; setup and save actions do.

- [ ] **Step 4: Make Settings save and persist a full tuple for every existing role**

Replace enum binding with normalized `harnessId`. Require implementation, review, and utility reasoning for both Claude and Codex, validate all three tuples before writing YAML or mutating live properties, and persist reasoning under the selected provider section. Failed validation leaves both the file and live bean unchanged.

- [ ] **Step 5: Replace hardcoded template branches and JavaScript**

Use `data-harness-id`, `data-model-id`, `data-reasoning-levels`, and `data-default-reasoning`. Remove `data-codex-active`, `data-codex-reasoning-group`, enum names, Codex-only help text, and hardcoded six-level pickers.

```javascript
function syncHarnessSelection(group) {
  var harness = group.querySelector('[data-harness-select]').value;
  var model = group.querySelector('[data-model-select]').value;
  var info = harnessCatalog.find(function (entry) { return entry.id === harness; })
    .models.find(function (entry) { return entry.id === model; });
  renderReasoningOptions(group.querySelector('[data-reasoning-select]'),
                         info.supportedReasoningLevels, info.defaultReasoningLevel);
}
```

Settings, repository edit, issue start/retry, recovery, and stage approval surfaces must submit and redisplay the complete tuple. Keep one model and reasoning pair visually adjacent. Setup uses “Coding harness” language and reports the selected adapter's readiness.

- [ ] **Step 6: Run controller, render, and JavaScript tests**

Run: `./mvnw -Dtest=HarnessCatalogAdviceTest,SettingsControllerTest,SettingsPageRenderTest,SetupControllerTest,RepositoryControllerTest,IssueControllerTest,StageApprovalRenderTest,IssueDetailGoalCardRenderTest test`

Run: `node --test src/test/js/*.cjs src/test/js/*.js`

Expected: all selected Java and all JavaScript tests pass; no model selector lacks a paired reasoning control.

- [ ] **Step 7: Commit the capability-driven UI**

```bash
git add src/main/java/com/dbbaskette/issuebot/controller src/main/resources/templates src/main/resources/static/js src/test/java/com/dbbaskette/issuebot/controller src/test/js
git commit -m "feat: render coding harness capabilities"
```

### Task 7: Release 0.6.0 and verify the foundation

**Files:**
- Modify: `pom.xml`
- Modify: `CHANGELOG.md`
- Modify: `README.md`
- Modify: `src/main/resources/application.yml`
- Test: `src/test/java/com/dbbaskette/issuebot/IssueBotApplicationTests.java`

**Interfaces:**
- Consumes: all earlier tasks.
- Produces: one documented, buildable 0.6.0 release with unchanged jar name and explicit migration/configuration behavior.

- [ ] **Step 1: Add an application-context integration assertion for both adapters**

```java
@Autowired CodingHarnessRegistry registry;

@Test
void registersCurrentCodingHarnesses() {
    assertThat(registry.require("claude")).isNotNull();
    assertThat(registry.require("codex")).isNotNull();
}
```

- [ ] **Step 2: Run the context integration test**

Run: `./mvnw -Dtest=IssueBotApplicationTests test`

Expected: the context starts once, and both stable harness IDs resolve to exactly one adapter bean. This is release integration coverage rather than a new behavior slice, so it does not require an artificial RED state after Tasks 1–6.

- [ ] **Step 3: Update version, changelog, configuration example, and README**

Set `<version>0.6.0</version>` in `pom.xml`. Add a `## 0.6.0` changelog entry covering the harness registry, Claude and Codex effort pairing, stable migrated harness IDs, and capability-driven UI. Update README terminology from “provider” to “coding harness” where user-facing, document `agent-provider: claude|codex` compatibility, and explain that every saved model now carries reasoning.

- [ ] **Step 4: Run focused harness and migration verification**

Run: `./mvnw -Dtest='*Harness*,*Model*,*StageApproval*,*Migration*' test`

Expected: zero failures and zero errors.

- [ ] **Step 5: Run the complete Java and JavaScript suites**

Run: `./mvnw verify`

Expected: `BUILD SUCCESS` with zero failed tests.

Run: `node --test src/test/js/*.cjs src/test/js/*.js`

Expected: all JavaScript tests pass.

- [ ] **Step 6: Verify the packaged artifact and version metadata**

Run: `test -f target/issuebot.jar && unzip -p target/issuebot.jar META-INF/build-info.properties | rg 'build.version=0.6.0'`

Expected: `target/issuebot.jar` exists and output contains `build.version=0.6.0`.

- [ ] **Step 7: Commit the release metadata**

```bash
git add pom.xml CHANGELOG.md README.md src/main/resources/application.yml src/test/java/com/dbbaskette/issuebot/IssueBotApplicationTests.java
git commit -m "release: prepare IssueBot 0.6.0"
```

- [ ] **Step 8: Run a final branch verification before PR work**

Run: `./mvnw verify && node --test src/test/js/*.cjs src/test/js/*.js && git status --short`

Expected: Java build success, all JavaScript tests pass, and the worktree has no uncommitted files.

# Versioned Plan First Approval Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace IssueBot's overlapping planning modes with a default-on, versioned Superpowers-style Plan First workflow whose approved spec and plan govern implementation and review.

**Architecture:** Add immutable `PlanningVersion` records and make `TrackedIssue.approvedPlanningVersion` the authoritative contract reference. Consolidate planning, approval, revision, and approved-context lookup in `PlanFirstService`; feed one `ApprovedPlanContext` into implementation and independent review; then render the selected version through a focused three-tab issue-page card. Existing safe dispatch, pause, cancellation, repository serialization, iteration records, and insert-only operator guidance remain the execution primitives.

**Tech Stack:** Java 21, Spring Boot 3.4, Spring Data JPA, Flyway/H2, Thymeleaf, HTMX, vanilla JavaScript/CSS, JUnit 5, Mockito, AssertJ.

## Global Constraints

- Plan First is enabled for new and existing repositories unless a repository or issue explicitly opts out.
- A Plan First issue cannot modify code until one valid Design Spec and Implementation Plan version is approved.
- The planner must use the issue's resolved implementation provider and implementation model.
- Planner output must contain exact top-level `# Design Spec` and `# Implementation Plan` headings, in that order, with nonblank content and no text outside those sections.
- Planning failures never fall through to implementation.
- Every planning version is immutable; only the latest pending version is actionable.
- One approval action approves both artifacts.
- The first non-conforming review triggers exactly one corrective implementation iteration; the second stops for human guidance.
- Guidance after the second miss changes implementation context only and never changes the approved planning version.
- Global pause, cancellation, concurrency limits, repository serialization, and restart recovery remain effective.
- Planning content over the supported size is rejected, never truncated.

---

## File Structure

**New production files**

- `src/main/java/com/dbbaskette/issuebot/model/PlanningVersion.java` — immutable persisted planning artifact version.
- `src/main/java/com/dbbaskette/issuebot/model/PlanningVersionState.java` — `PENDING`, `APPROVED`, `SUPERSEDED`, and `LEGACY` lifecycle values.
- `src/main/java/com/dbbaskette/issuebot/repository/PlanningVersionRepository.java` — ordered version lookup and current-version queries.
- `src/main/java/com/dbbaskette/issuebot/service/workflow/PlanArtifactParser.java` — exact two-section validation and splitting.
- `src/main/java/com/dbbaskette/issuebot/service/workflow/ApprovedPlanContext.java` — immutable prompt contract shared by implementation and review.
- `src/main/resources/db/migration/V27__versioned_plan_first.sql` — schema, Plan First defaults, and legacy artifact migration.
- `src/main/resources/db/migration/V28__remove_autonomous_superpowers.sql` — removes the old toggle after all Java consumers are removed.

**Primary modified files**

- `TrackedIssue`, `WatchedRepo`, and their repositories — approved-version and conformance-cycle state.
- `PlanFirstService` — the single planning/versioning/approval service.
- `IssueWorkflowService`, `CodeReviewService`, `ReviewPromptBuilder`, and `IterationManager` — approved-context propagation and bounded conformance correction.
- `IssueController`, `InboxController`, `RepositoryController` — version selection and safe actions.
- `issue-detail.html`, `inbox.html`, `repositories.html`, `style.css`, and `app.js` — focused tabs, history, guidance, and default-on copy.
- Existing focused unit, integration, migration, controller, and template-render tests.

**Removed production files**

- `SuperpowersMethodologyService.java` and its test — its methodology becomes the required Plan First planning prompt rather than an autonomous second mode.

---

### Task 1: Persist immutable planning versions and enable Plan First by default

**Files:**
- Create: `src/main/java/com/dbbaskette/issuebot/model/PlanningVersion.java`
- Create: `src/main/java/com/dbbaskette/issuebot/model/PlanningVersionState.java`
- Create: `src/main/java/com/dbbaskette/issuebot/repository/PlanningVersionRepository.java`
- Create: `src/main/resources/db/migration/V27__versioned_plan_first.sql`
- Modify: `src/main/java/com/dbbaskette/issuebot/model/TrackedIssue.java`
- Test: `src/test/java/com/dbbaskette/issuebot/repository/PlanningVersionRepositoryTest.java`
- Test: `src/test/java/com/dbbaskette/issuebot/repository/VersionedPlanFirstMigrationTest.java`

**Interfaces:**
- Produces: `PlanningVersionRepository.findByIssueIdOrderByVersionNumberDesc(Long)` and `findFirstByIssueIdOrderByVersionNumberDesc(Long)`.
- Produces: scoped single-version lookup and batched pending-version lookup for the issue page and Inbox.
- Produces: `TrackedIssue.getApprovedPlanningVersion()`, `getPlanConformanceAttempt()`, and `isPlanCorrectionPending()`.
- Preserves: legacy `implementationPlan`, `planApproved`, and `planFeedback` columns during this release for safe migration/revision handoff; new workflow code must not treat them as authoritative.

- [ ] **Step 1: Write repository and migration tests that describe the new persistence contract**

```java
@Test
void versionsAreReturnedNewestFirstAndVersionNumberIsUniquePerIssue() {
    TrackedIssue issue = issues.save(issue(repo));
    versions.save(PlanningVersion.pending(issue, 1, "spec one", "plan one", "CODEX", "gpt-5.6-sol", null));
    versions.save(PlanningVersion.pending(issue, 2, "spec two", "plan two", "CODEX", "gpt-5.6-sol", "add rollback"));

    assertThat(versions.findByIssueIdOrderByVersionNumberDesc(issue.getId()))
            .extracting(PlanningVersion::getVersionNumber)
            .containsExactly(2, 1);
    assertThatThrownBy(() -> versions.saveAndFlush(
            PlanningVersion.pending(issue, 2, "duplicate", "duplicate", "CODEX", "gpt-5.6-sol", null)))
            .isInstanceOf(DataIntegrityViolationException.class);
}

@Test
void migrationEnablesPlanFirstAndConvertsStoredPlanToLegacyVersion() {
    assertThat(repoRepository.findById(existingRepoId).orElseThrow().isPlanFirst()).isTrue();
    PlanningVersion legacy = versionRepository.findFirstByIssueIdOrderByVersionNumberDesc(existingIssueId)
            .orElseThrow();
    assertThat(legacy.getState()).isEqualTo(PlanningVersionState.LEGACY);
    assertThat(legacy.getImplementationPlan()).isEqualTo("legacy plan text");
    assertThat(legacy.getDesignSpec()).isNull();
}
```

- [ ] **Step 2: Run the focused tests and confirm the schema/classes are missing**

Run: `./mvnw -q -Dtest=PlanningVersionRepositoryTest,VersionedPlanFirstMigrationTest test`

Expected: FAIL because `PlanningVersion`, its repository, and migration V27 do not exist.

- [ ] **Step 3: Add the migration with deterministic defaults and legacy conversion**

```sql
CREATE TABLE planning_versions (
    id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    issue_id BIGINT NOT NULL,
    version_number INT NOT NULL,
    design_spec CLOB,
    implementation_plan CLOB NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    provider VARCHAR(40),
    model VARCHAR(120),
    revision_feedback CLOB,
    state VARCHAR(20) NOT NULL,
    approved_at TIMESTAMP,
    CONSTRAINT fk_planning_version_issue FOREIGN KEY (issue_id) REFERENCES tracked_issues(id),
    CONSTRAINT uq_planning_version_number UNIQUE (issue_id, version_number)
);

ALTER TABLE tracked_issues ADD COLUMN approved_planning_version_id BIGINT;
ALTER TABLE tracked_issues ADD COLUMN plan_conformance_attempt INT DEFAULT 0 NOT NULL;
ALTER TABLE tracked_issues ADD COLUMN plan_correction_pending BOOLEAN DEFAULT FALSE NOT NULL;

INSERT INTO planning_versions
    (issue_id, version_number, design_spec, implementation_plan, created_at,
     provider, model, revision_feedback, state, approved_at)
SELECT id, 1, NULL, implementation_plan, updated_at,
       resolved_agent_provider, resolved_impl_model, plan_feedback, 'LEGACY',
       CASE WHEN plan_approved THEN updated_at ELSE NULL END
FROM tracked_issues
WHERE implementation_plan IS NOT NULL;

UPDATE tracked_issues t
SET approved_planning_version_id = (
    SELECT p.id FROM planning_versions p
    WHERE p.issue_id = t.id AND p.version_number = 1
)
WHERE t.plan_approved = TRUE AND t.implementation_plan IS NOT NULL;

ALTER TABLE tracked_issues ADD CONSTRAINT fk_issue_approved_plan
    FOREIGN KEY (approved_planning_version_id) REFERENCES planning_versions(id);

UPDATE watched_repos SET plan_first = TRUE;
ALTER TABLE watched_repos ALTER COLUMN plan_first SET DEFAULT TRUE;
```

- [ ] **Step 4: Add the model and repository interfaces**

```java
public enum PlanningVersionState { PENDING, APPROVED, SUPERSEDED, LEGACY }

@Entity
@Table(name = "planning_versions",
       uniqueConstraints = @UniqueConstraint(columnNames = {"issue_id", "version_number"}))
public class PlanningVersion {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "issue_id", nullable = false)
    private TrackedIssue issue;
    @Column(name = "version_number", nullable = false) private int versionNumber;
    @Lob @Column(name = "design_spec") private String designSpec;
    @Lob @Column(name = "implementation_plan", nullable = false) private String implementationPlan;
    @Column(name = "created_at", nullable = false, updatable = false) private LocalDateTime createdAt = LocalDateTime.now();
    @Column(length = 40) private String provider;
    @Column(length = 120) private String model;
    @Lob @Column(name = "revision_feedback") private String revisionFeedback;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private PlanningVersionState state;
    @Column(name = "approved_at") private LocalDateTime approvedAt;

    public static PlanningVersion pending(TrackedIssue issue, int number, String spec, String plan,
                                          String provider, String model, String feedback) {
        PlanningVersion value = new PlanningVersion();
        value.issue = issue;
        value.versionNumber = number;
        value.designSpec = spec;
        value.implementationPlan = plan;
        value.provider = provider;
        value.model = model;
        value.revisionFeedback = feedback;
        value.state = PlanningVersionState.PENDING;
        return value;
    }

    public void approve(LocalDateTime now) {
        if (state != PlanningVersionState.PENDING) throw new IllegalStateException("Version is not pending");
        state = PlanningVersionState.APPROVED;
        approvedAt = now;
    }

    public void supersede() {
        if (state == PlanningVersionState.PENDING) state = PlanningVersionState.SUPERSEDED;
    }

    public Long getId() { return id; }
    public TrackedIssue getIssue() { return issue; }
    public int getVersionNumber() { return versionNumber; }
    public String getDesignSpec() { return designSpec; }
    public String getImplementationPlan() { return implementationPlan; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public String getProvider() { return provider; }
    public String getModel() { return model; }
    public String getRevisionFeedback() { return revisionFeedback; }
    public PlanningVersionState getState() { return state; }
    public LocalDateTime getApprovedAt() { return approvedAt; }
}

public interface PlanningVersionRepository extends JpaRepository<PlanningVersion, Long> {
    List<PlanningVersion> findByIssueIdOrderByVersionNumberDesc(Long issueId);
    Optional<PlanningVersion> findFirstByIssueIdOrderByVersionNumberDesc(Long issueId);
    Optional<PlanningVersion> findByIssueIdAndVersionNumber(Long issueId, int versionNumber);
    List<PlanningVersion> findByIssueIdInAndState(Collection<Long> issueIds, PlanningVersionState state);
}
```

Add the `approvedPlanningVersion`, `planConformanceAttempt`, and `planCorrectionPending` mappings to `TrackedIssue`. Keep the Java repository default and obsolete Superpowers field until Task 8 so existing controllers compile between commits; V27 still updates persisted repositories and establishes the database default immediately.

- [ ] **Step 5: Run persistence tests and the complete migration suite**

Run: `./mvnw -q -Dtest=PlanningVersionRepositoryTest,VersionedPlanFirstMigrationTest test`

Expected: PASS; duplicate `(issue_id, version_number)` is rejected and existing repos/plans migrate as specified.

Run: `./mvnw -q test`

Expected: PASS. The persistence commit introduces storage without yet changing Java-side repository form defaults or removing old consumers.

- [ ] **Step 6: Commit the persistence boundary**

```bash
git add src/main/java/com/dbbaskette/issuebot/model/PlanningVersion.java \
  src/main/java/com/dbbaskette/issuebot/model/PlanningVersionState.java \
  src/main/java/com/dbbaskette/issuebot/model/TrackedIssue.java \
  src/main/java/com/dbbaskette/issuebot/repository/PlanningVersionRepository.java \
  src/main/resources/db/migration/V27__versioned_plan_first.sql \
  src/test/java/com/dbbaskette/issuebot/repository/PlanningVersionRepositoryTest.java \
  src/test/java/com/dbbaskette/issuebot/repository/VersionedPlanFirstMigrationTest.java
git commit -m "feat: persist versioned Plan First artifacts"
```

### Task 2: Parse and validate the two required planning artifacts

**Files:**
- Create: `src/main/java/com/dbbaskette/issuebot/service/workflow/PlanArtifactParser.java`
- Test: `src/test/java/com/dbbaskette/issuebot/service/workflow/PlanArtifactParserTest.java`

**Interfaces:**
- Produces: `PlanArtifactParser.parse(String)` returning `PlanningArtifact(String designSpec, String implementationPlan)`.
- Throws: `InvalidPlanningArtifactException` with operator-safe, specific validation messages.

- [ ] **Step 1: Write exact-format, missing-section, narration, order, blank, and size tests**

```java
@Test
void parsesExactOrderedSections() {
    PlanningArtifact artifact = parser.parse("""
            # Design Spec
            A focused design.
            # Implementation Plan
            1. Write the failing test.
            """);
    assertThat(artifact.designSpec()).isEqualTo("A focused design.");
    assertThat(artifact.implementationPlan()).startsWith("1. Write");
}

@ParameterizedTest
@ValueSource(strings = {
        "preface\n# Design Spec\nspec\n# Implementation Plan\nplan",
        "# Implementation Plan\nplan\n# Design Spec\nspec",
        "# Design Spec\n\n# Implementation Plan\nplan",
        "# Design Spec\nspec"
})
void rejectsAnythingOutsideTheExactContract(String output) {
    assertThatThrownBy(() -> parser.parse(output))
            .isInstanceOf(InvalidPlanningArtifactException.class);
}

@Test
void rejectsOversizeInsteadOfTruncating() {
    String output = "# Design Spec\n" + "s".repeat(20_001)
            + "\n# Implementation Plan\nplan";
    assertThatThrownBy(() -> parser.parse(output))
            .hasMessageContaining("20,000");
}
```

- [ ] **Step 2: Run the parser test and verify it fails because the parser is absent**

Run: `./mvnw -q -Dtest=PlanArtifactParserTest test`

Expected: FAIL at compilation for missing parser types.

- [ ] **Step 3: Implement one deterministic parser with a 20,000-character limit per section**

```java
@Component
public final class PlanArtifactParser {
    static final String SPEC_HEADING = "# Design Spec";
    static final String PLAN_HEADING = "# Implementation Plan";
    static final int MAX_SECTION_CHARS = 20_000;

    public PlanningArtifact parse(String output) {
        if (output == null || output.isBlank()) throw invalid("Planner returned no final output");
        String normalized = output.strip();
        if (!normalized.startsWith(SPEC_HEADING + "\n"))
            throw invalid("Planner output must begin with # Design Spec");
        int planHeading = normalized.indexOf("\n" + PLAN_HEADING + "\n");
        if (planHeading < 0) throw invalid("Planner output is missing # Implementation Plan");
        String spec = normalized.substring(SPEC_HEADING.length(), planHeading).strip();
        String plan = normalized.substring(planHeading + PLAN_HEADING.length() + 2).strip();
        if (spec.isBlank()) throw invalid("Design Spec is blank");
        if (plan.isBlank()) throw invalid("Implementation Plan is blank");
        if (spec.contains("\n" + PLAN_HEADING) || plan.contains("\n" + SPEC_HEADING))
            throw invalid("Planner output contains duplicate or reordered top-level sections");
        if (spec.length() > MAX_SECTION_CHARS || plan.length() > MAX_SECTION_CHARS)
            throw invalid("Each planning section must be 20,000 characters or fewer");
        return new PlanningArtifact(spec, plan);
    }

    private InvalidPlanningArtifactException invalid(String message) {
        return new InvalidPlanningArtifactException(message);
    }

    public record PlanningArtifact(String designSpec, String implementationPlan) {}
    public static final class InvalidPlanningArtifactException extends IllegalArgumentException {
        public InvalidPlanningArtifactException(String message) { super(message); }
    }
}
```

- [ ] **Step 4: Run and commit the parser**

Run: `./mvnw -q -Dtest=PlanArtifactParserTest test`

Expected: PASS.

```bash
git add src/main/java/com/dbbaskette/issuebot/service/workflow/PlanArtifactParser.java \
  src/test/java/com/dbbaskette/issuebot/service/workflow/PlanArtifactParserTest.java
git commit -m "feat: validate structured planning artifacts"
```

### Task 3: Consolidate generation, versioning, approval, and revision in PlanFirstService

**Files:**
- Create: `src/main/java/com/dbbaskette/issuebot/service/workflow/ApprovedPlanContext.java`
- Modify: `src/main/java/com/dbbaskette/issuebot/service/workflow/PlanFirstService.java`
- Modify: `src/test/java/com/dbbaskette/issuebot/service/workflow/PlanFirstServiceTest.java`

**Interfaces:**
- Produces: `PlanningOutcome generateVersion(TrackedIssue, JsonNode, Path)` with values `AWAITING_APPROVAL` and `FAILED`.
- Produces: `void approvePlan(Long issueId, Long expectedVersionId)`.
- Produces: `void requestRevision(Long issueId, Long expectedVersionId, String feedback)`.
- Produces: `Optional<ApprovedPlanContext> approvedContext(TrackedIssue)`.
- `ApprovedPlanContext` fields: `long id`, `int versionNumber`, `String designSpec`, `String implementationPlan`.

- [ ] **Step 1: Replace fall-through tests with strict generation and version lifecycle tests**

```java
@Test
void generationUsesResolvedImplementationModelAndStoresBothArtifacts() {
    issue.setResolvedImplModel("gpt-5.6-sol");
    issue.setResolvedAgentProvider(AgentProvider.CODEX);
    when(agent.executePlanning(anyString(), eq(repoPath), eq("gpt-5.6-sol"), eq(issue.getId()), isNull()))
            .thenReturn(success("# Design Spec\nspec\n# Implementation Plan\nplan"));

    assertThat(service.generateVersion(issue, details, repoPath))
            .isEqualTo(PlanningOutcome.AWAITING_APPROVAL);
    verify(versions).save(argThat(v -> v.getVersionNumber() == 1
            && v.getDesignSpec().equals("spec") && v.getImplementationPlan().equals("plan")));
    assertThat(issue.getStatus()).isEqualTo(IssueStatus.AWAITING_PLAN_APPROVAL);
}

@Test
void invalidPlannerOutputStopsInsteadOfImplementing() {
    when(agent.executePlanning(anyString(), any(), anyString(), anyLong(), isNull()))
            .thenReturn(success("implementation plan only"));
    assertThat(service.generateVersion(issue, details, repoPath)).isEqualTo(PlanningOutcome.FAILED);
    assertThat(issue.getStatus()).isEqualTo(IssueStatus.FAILED);
    assertThat(issue.getLastFailureReason()).contains("# Design Spec");
}

@Test
void revisionSupersedesCurrentPendingVersionAndQueuesGenerationWithFeedback() {
    when(issues.findById(issue.getId())).thenReturn(Optional.of(issue));
    when(versions.findById(7L)).thenReturn(Optional.of(pendingVersion(issue, 2, 7L)));
    when(versions.findFirstByIssueIdOrderByVersionNumberDesc(issue.getId()))
            .thenReturn(Optional.of(pendingVersion(issue, 2, 7L)));

    service.requestRevision(issue.getId(), 7L, "Include rollback behavior");

    assertThat(issue.getStatus()).isEqualTo(IssueStatus.PENDING);
    assertThat(issue.getPlanFeedback()).isEqualTo("Include rollback behavior");
}

@Test
void staleApprovalCannotApproveAnOlderVersion() {
    when(issues.findById(issue.getId())).thenReturn(Optional.of(issue));
    when(versions.findFirstByIssueIdOrderByVersionNumberDesc(issue.getId()))
            .thenReturn(Optional.of(pendingVersion(issue, 3, 9L)));
    assertThatThrownBy(() -> service.approvePlan(issue.getId(), 7L))
            .hasMessageContaining("current pending version is 3");
}
```

- [ ] **Step 2: Run the focused service tests and verify old behavior conflicts**

Run: `./mvnw -q -Dtest=PlanFirstServiceTest test`

Expected: FAIL because the old service uses the utility model, stores one mutable plan, limits rejection count, and falls through on planning failure.

- [ ] **Step 3: Replace the old prompt and mutable plan lifecycle**

Use one prompt constant that includes the approved methodology and exact output contract:

```java
static final String PLANNING_METHODOLOGY = """
        You are producing a DESIGN SPEC and IMPLEMENTATION PLAN before any code is written.
        Make no code changes and create no files. Inspect the issue and relevant code, state
        assumptions, compare credible approaches, choose the simplest sufficient design, and
        create small test-first implementation tasks with exact files and verification commands.

        Your final response must contain exactly these two top-level sections, in this order,
        with no text before, between, or after them except their content:
        # Design Spec
        # Implementation Plan
        """;

public enum PlanningOutcome { AWAITING_APPROVAL, FAILED }

public record ApprovedPlanContext(long id, int versionNumber,
                                  String designSpec, String implementationPlan) {}
```

In `generateVersion`, call `executePlanning`, require `result.isSuccess()`, parse only `getFinalResultOrOutput()`, compute `nextVersion = latest.map(v -> v.getVersionNumber() + 1).orElse(1)`, save the pending version with `trackedIssue.getPlanFeedback()`, clear `planFeedback`, and enter `AWAITING_PLAN_APPROVAL`. On invocation or validation failure set `FAILED`, clear `currentPhase`, save a bounded specific reason, emit `PLAN_FAILED`, notify, and return `FAILED`.

- [ ] **Step 4: Implement synchronized, fresh-read approval and revision guards**

```java
public synchronized void approvePlan(Long issueId, Long expectedVersionId) {
    TrackedIssue issue = requireIssue(issueId);
    PlanningVersion current = requireCurrentPending(issue);
    if (!current.getId().equals(expectedVersionId))
        throw new IllegalStateException("Stale approval: current pending version is " + current.getVersionNumber());
    current.approve(LocalDateTime.now());
    issue.setApprovedPlanningVersion(current);
    issue.setPlanConformanceAttempt(0);
    issue.setPlanCorrectionPending(false);
    issue.setStatus(IssueStatus.PENDING);
    versions.save(current);
    issues.save(issue);
    publishApprovalAudit(issue, current);
}

public synchronized void requestRevision(Long issueId, Long expectedVersionId, String feedback) {
    if (feedback == null || feedback.isBlank())
        throw new IllegalArgumentException("Revision guidance is required");
    TrackedIssue issue = requireIssue(issueId);
    PlanningVersion current = requireCurrentPending(issue);
    if (!current.getId().equals(expectedVersionId))
        throw new IllegalStateException("Stale revision: current pending version is " + current.getVersionNumber());
    current.supersede();
    versions.save(current);
    issue.setPlanFeedback(feedback.strip());
    issue.setStatus(IssueStatus.PENDING);
    issues.save(issue);
    publishRevisionAudit(issue, current, feedback.strip());
}
```

`approvedContext` must return empty for null/legacy/incomplete approved versions, preventing a legacy plan from masquerading as the new contract.

- [ ] **Step 5: Run service tests and remove the obsolete rejection-cap assertions**

Run: `./mvnw -q -Dtest=PlanFirstServiceTest test`

Expected: PASS for generation, strict failure, monotonically numbered revision, immutable approval, stale-action rejection, audit events, and GitHub-comment failure tolerance.

- [ ] **Step 6: Commit the authoritative service**

```bash
git add src/main/java/com/dbbaskette/issuebot/service/workflow/ApprovedPlanContext.java \
  src/main/java/com/dbbaskette/issuebot/service/workflow/PlanFirstService.java \
  src/test/java/com/dbbaskette/issuebot/service/workflow/PlanFirstServiceTest.java
git commit -m "feat: add versioned Plan First lifecycle"
```

### Task 4: Make the approved version mandatory for implementation and remove autonomous Superpowers mode

**Files:**
- Modify: `src/main/java/com/dbbaskette/issuebot/service/workflow/IssueWorkflowService.java`
- Modify: `src/test/java/com/dbbaskette/issuebot/service/workflow/IssueWorkflowServiceTest.java`
- Delete: `src/main/java/com/dbbaskette/issuebot/service/workflow/SuperpowersMethodologyService.java`
- Delete: `src/test/java/com/dbbaskette/issuebot/service/workflow/SuperpowersMethodologyServiceTest.java`

**Interfaces:**
- Consumes: `PlanFirstService.generateVersion(...)` and `approvedContext(...)`.
- Changes: `buildImplementationPrompt(...)` accepts `ApprovedPlanContext approvedPlan` rather than a mutable plan string.

- [ ] **Step 1: Write workflow tests that prohibit planning bypass and assert exact prompt propagation**

```java
@Test
void planFirstGenerationFailureStopsBeforeImplementation() {
    issue.getRepo().setPlanFirst(true);
    when(planFirst.generateVersion(any(), any(), any())).thenReturn(PlanningOutcome.FAILED);
    workflow.processIssue(issue, null);
    verify(agent, never()).executeImplementation(anyString(), any(), anyString(), any(), anyLong(), any());
}

@Test
void approvedContextIsIncludedInImplementationPrompt() {
    ApprovedPlanContext context = new ApprovedPlanContext(14L, 3, "approved spec", "approved plan");
    String prompt = workflow.buildImplementationPrompt(details, null, null, null, null, context);
    assertThat(prompt).contains("## Approved Planning Contract — Version 3")
            .contains("### Design Spec\napproved spec")
            .contains("### Implementation Plan\napproved plan");
}

@Test
void planFirstWithoutApprovedContextFailsInvariantBeforeImplementation() {
    issue.getRepo().setPlanFirst(true);
    issue.setStatus(IssueStatus.IN_PROGRESS);
    when(planFirst.approvedContext(issue)).thenReturn(Optional.empty());
    workflow.processIssue(issue, null);
    assertThat(issue.getStatus()).isEqualTo(IssueStatus.FAILED);
    assertThat(issue.getLastFailureReason()).contains("approved planning version");
}

@Test
void alreadyStartedLegacyApprovedIssueMayFinishWithoutPretendingItHasANewSpec() {
    issue.setCurrentIteration(1);
    issue.setPlanApproved(true);
    issue.setImplementationPlan("legacy implementation plan");
    issue.setApprovedPlanningVersion(legacyVersion);
    workflow.processIssue(issue, null);
    verify(agent).executeImplementation(argThat(prompt -> prompt.contains("Legacy approved plan")
            && !prompt.contains("Approved Design Spec")), any(), anyString(), any(), anyLong(), any());
}
```

- [ ] **Step 2: Run the workflow test and confirm the old fall-through/autonomous branches fail the contract**

Run: `./mvnw -q -Dtest=IssueWorkflowServiceTest test`

Expected: FAIL because `proposePlan(false)` falls through and approved context is not yet required.

- [ ] **Step 3: Replace both planning branches with one strict gate**

```java
ApprovedPlanContext approvedPlan = null;
if (trackedIssue.effectivePlanFirst()) {
    Optional<ApprovedPlanContext> existing = planFirstService.approvedContext(trackedIssue);
    if (existing.isEmpty()) {
        if (planFirstService.generateVersion(trackedIssue, issueDetails, repoPath)
                == PlanFirstService.PlanningOutcome.AWAITING_APPROVAL) return;
        return;
    }
    approvedPlan = existing.get();
}
```

Delete the `SuperpowersMethodologyService` constructor dependency and autonomous generation block. Add the approved context to both normal and cold-resume implementation prompts. The prompt must state that mechanical plan steps may adapt to the codebase but scope and acceptance criteria may not change.

Add one migration-only compatibility branch: an issue with `planApproved=true`, an approved `LEGACY` version, and `currentIteration > 0` may resume using its labeled legacy implementation plan and original GitHub issue. It must not be labeled as a new approved Design Spec, and a not-yet-started legacy issue must regenerate under the new gate.

- [ ] **Step 4: Remove the autonomous service and run workflow tests**

Run: `./mvnw -q -Dtest=IssueWorkflowServiceTest test`

Expected: PASS, including no implementation invocation after planning failure and byte-for-byte approved artifact inclusion.

- [ ] **Step 5: Commit the unified workflow gate**

```bash
git add src/main/java/com/dbbaskette/issuebot/service/workflow/IssueWorkflowService.java \
  src/test/java/com/dbbaskette/issuebot/service/workflow/IssueWorkflowServiceTest.java
git rm src/main/java/com/dbbaskette/issuebot/service/workflow/SuperpowersMethodologyService.java \
  src/test/java/com/dbbaskette/issuebot/service/workflow/SuperpowersMethodologyServiceTest.java
git commit -m "feat: require approved Plan First contract"
```

### Task 5: Review against the approved contract and enforce two conformance attempts

**Files:**
- Modify: `src/main/java/com/dbbaskette/issuebot/service/review/ReviewPromptBuilder.java`
- Modify: `src/main/java/com/dbbaskette/issuebot/service/review/CodeReviewService.java`
- Modify: `src/main/java/com/dbbaskette/issuebot/service/review/CodeReviewResult.java`
- Modify: `src/main/java/com/dbbaskette/issuebot/service/workflow/IssueWorkflowService.java`
- Modify: `src/main/java/com/dbbaskette/issuebot/service/workflow/IterationManager.java`
- Test: `src/test/java/com/dbbaskette/issuebot/service/review/ReviewPromptBuilderTest.java`
- Test: `src/test/java/com/dbbaskette/issuebot/service/review/CodeReviewServiceParseTest.java`
- Test: `src/test/java/com/dbbaskette/issuebot/service/workflow/PlanConformanceWorkflowTest.java`

**Interfaces:**
- `ReviewPromptBuilder.buildReviewPrompt(...)` gains nullable `ApprovedPlanContext approvedPlan`.
- `CodeReviewService.reviewCode(...)` gains the same context.
- `CodeReviewResult.hasBlockingSpecFinding()` enforces server-side blocking semantics.
- `IterationManager.canIterate(...)` returns true when `planCorrectionPending` is true, even if ordinary implementation iterations are exhausted.

- [ ] **Step 1: Write prompt-policy and two-attempt workflow tests**

```java
@Test
void reviewPromptIncludesApprovedVersionAndBlockingRules() {
    String prompt = builder.buildReviewPrompt(title, body, files, diff, criteria, false, .70, null,
            new ApprovedPlanContext(4L, 2, "spec contract", "plan contract"));
    assertThat(prompt).contains("## Approved Design Spec — Version 2")
            .contains("spec contract")
            .contains("## Approved Implementation Plan")
            .contains("plan contract")
            .contains("high-severity unmet acceptance criterion")
            .contains("Set passed to false");
}

@Test
void highSpecFindingBlocksEvenWhenModelSaysPassed() {
    CodeReviewResult parsed = service.parseReviewResponse(successJson(
            true, .95, finding("high", "spec_compliance")));
    assertThat(parsed.passed()).isFalse();
    assertThat(parsed.hasBlockingSpecFinding()).isTrue();
}

@Test
void firstPlanConformanceMissSchedulesExactlyOneCorrection() {
    runReviewVerdict(issue, failedConformance());
    assertThat(issue.getPlanConformanceAttempt()).isEqualTo(1);
    assertThat(issue.isPlanCorrectionPending()).isTrue();
    verify(agent, times(2)).executeImplementation(anyString(), any(), anyString(), any(), anyLong(), any());
}

@Test
void secondPlanConformanceMissStopsForGuidance() {
    runTwoFailedConformanceVerdicts(issue);
    assertThat(issue.getPlanConformanceAttempt()).isEqualTo(2);
    assertThat(issue.getStatus()).isIn(IssueStatus.FAILED, IssueStatus.COOLDOWN);
    assertThat(issue.getLastFailureReason()).contains("approved Plan v2").contains("two review attempts");
}
```

- [ ] **Step 2: Run the focused review/conformance tests and verify missing context/policy failures**

Run: `./mvnw -q -Dtest=ReviewPromptBuilderTest,CodeReviewServiceParseTest,PlanConformanceWorkflowTest test`

Expected: FAIL because reviews only receive the GitHub issue and use the existing configurable iteration gate.

- [ ] **Step 3: Extend the review prompt and enforce blocking findings after parsing**

```java
public boolean hasBlockingSpecFinding() {
    boolean highSpec = findings != null && findings.stream().anyMatch(f ->
            "high".equalsIgnoreCase(f.severity())
                    && "spec_compliance".equalsIgnoreCase(f.category()));
    boolean unmet = criteria != null && criteria.stream().anyMatch(c -> "unmet".equals(c.verdict()));
    return highSpec || unmet;
}
```

After JSON parsing, rebuild the record with `passed = parsed.passed() && !parsed.hasBlockingSpecFinding()`. Insert the approved Design Spec and Implementation Plan ahead of the diff and instruct the reviewer to tie each blocking finding to an acceptance criterion or required plan deliverable.

- [ ] **Step 4: Persist conformance state around the existing iteration loop**

On each completed Plan First review verdict, increment `planConformanceAttempt`. If the verdict fails at attempt 1, set `planCorrectionPending=true`, persist the full review feedback, and continue. At the start of the corrective implementation iteration, consume the pending flag only after the iteration slot is claimed. `IterationManager.canIterate` must allow that pending correction independently of the repository's ordinary max-iteration count.

At failed attempt 2, call the existing failure escalation path with a Plan First-specific summary and the two iteration review records. Do not schedule a third correction. A passed verdict clears `planCorrectionPending` and completes normally.

- [ ] **Step 5: Run review, conformance, and workflow regression tests**

Run: `./mvnw -q -Dtest=ReviewPromptBuilderTest,CodeReviewServiceParseTest,PlanConformanceWorkflowTest,IssueWorkflowServiceTest,IterationManagerTest test`

Expected: PASS; review invocation failures still follow their separate bounded retry path and do not consume a conformance attempt.

- [ ] **Step 6: Commit review conformance enforcement**

```bash
git add src/main/java/com/dbbaskette/issuebot/service/review/ReviewPromptBuilder.java \
  src/main/java/com/dbbaskette/issuebot/service/review/CodeReviewService.java \
  src/main/java/com/dbbaskette/issuebot/service/review/CodeReviewResult.java \
  src/main/java/com/dbbaskette/issuebot/service/workflow/IssueWorkflowService.java \
  src/main/java/com/dbbaskette/issuebot/service/workflow/IterationManager.java \
  src/test/java/com/dbbaskette/issuebot/service/review/ReviewPromptBuilderTest.java \
  src/test/java/com/dbbaskette/issuebot/service/review/CodeReviewServiceParseTest.java \
  src/test/java/com/dbbaskette/issuebot/service/workflow/PlanConformanceWorkflowTest.java
git commit -m "feat: review against approved planning contract"
```

### Task 6: Add safe approval, revision, and guidance-only retry endpoints

**Files:**
- Modify: `src/main/java/com/dbbaskette/issuebot/controller/IssueController.java`
- Modify: `src/test/java/com/dbbaskette/issuebot/controller/IssueControllerTest.java`
- Modify: `src/main/java/com/dbbaskette/issuebot/service/workflow/IssueDispatchService.java`
- Modify: `src/test/java/com/dbbaskette/issuebot/service/workflow/IssueDispatchServiceTest.java`

**Interfaces:**
- POST `/issues/{id}/plan/approve` requires `versionId`.
- POST `/issues/{id}/plan/revise` requires `versionId` and `feedback`.
- POST `/issues/{id}/plan/retry-implementation` requires nonblank `guidance` and only accepts the second-miss needs-human state.

- [ ] **Step 1: Write controller tests for version IDs, required feedback, stale actions, pause, and guidance-only retry**

```java
@Test
void approvePassesExpectedVersionAndRedirectsToPlanCard() {
    String view = controller.approvePlan(8L, 13L, redirects);
    verify(planFirst).approvePlan(8L, 13L);
    assertThat(view).isEqualTo("redirect:/issues/8#plan-review");
}

@Test
void reviseRequiresGuidance() {
    String view = controller.revisePlan(8L, 13L, "  ", redirects);
    verifyNoInteractions(planFirst);
    verify(redirects).addFlashAttribute(eq("error"), contains("guidance"));
    assertThat(view).isEqualTo("redirect:/issues/8#plan-review");
}

@Test
void guidedRetryKeepsApprovedVersionAndResetsOnlyConformanceCycle() {
    issue.setStatus(IssueStatus.FAILED);
    issue.setPlanConformanceAttempt(2);
    issue.setApprovedPlanningVersion(approvedVersion);
    controller.retryPlanImplementation(issue.getId(), "Handle the null branch", redirects);
    assertThat(issue.getApprovedPlanningVersion()).isSameAs(approvedVersion);
    assertThat(issue.getPlanConformanceAttempt()).isZero();
    verify(guidance).save(argThat(g -> g.getGuidance().contains("null branch")));
    verify(workflow).processIssueAsync(any(), isNull());
}
```

- [ ] **Step 2: Run controller and dispatch tests and confirm endpoint/signature failures**

Run: `./mvnw -q -Dtest=IssueControllerTest,IssueDispatchServiceTest test`

Expected: FAIL because approval has no version ID, rejection has capped semantics, and there is no dedicated guidance-only retry.

- [ ] **Step 3: Replace reject with revise and add the guarded retry action**

The guided retry endpoint must:

1. Fresh-read the issue.
2. Require `FAILED` or `COOLDOWN`, `planConformanceAttempt == 2`, and a non-legacy approved version.
3. Require nonblank guidance and bound it to 4,000 characters.
4. Use `IssueDispatchService.claimRetry` so pause and repository serialization remain authoritative.
5. Reset `currentIteration`, `currentReviewIteration`, `planConformanceAttempt`, cooldown, phase, and correction-pending state without changing `approvedPlanningVersion`.
6. Insert `IssueGuidance`, log `PLAN_IMPLEMENTATION_RETRY`, post a concise GitHub audit comment, and call `processIssueAsync`.

Return a stale-action error from approval/revision as a flash message and reload the latest issue state; never mutate based on the controller's stale entity.

- [ ] **Step 4: Run endpoint and safe-dispatch tests**

Run: `./mvnw -q -Dtest=IssueControllerTest,IssueDispatchServiceTest test`

Expected: PASS for version-bound actions, pause rejection, repository serialization, exact approved-version preservation, and guidance insertion.

- [ ] **Step 5: Commit action endpoints**

```bash
git add src/main/java/com/dbbaskette/issuebot/controller/IssueController.java \
  src/test/java/com/dbbaskette/issuebot/controller/IssueControllerTest.java \
  src/main/java/com/dbbaskette/issuebot/service/workflow/IssueDispatchService.java \
  src/test/java/com/dbbaskette/issuebot/service/workflow/IssueDispatchServiceTest.java
git commit -m "feat: add version-safe planning actions"
```

### Task 7: Build the focused-tab Plan Review card and version history

**Files:**
- Modify: `src/main/java/com/dbbaskette/issuebot/controller/IssueController.java`
- Modify: `src/main/resources/templates/issue-detail.html`
- Modify: `src/main/resources/static/css/style.css`
- Modify: `src/main/resources/static/js/app.js`
- Test: `src/test/java/com/dbbaskette/issuebot/controller/IssueDetailPlanReviewRenderTest.java`

**Interfaces:**
- GET `/issues/{id}?planVersion=N#plan-review` selects a historical version; absent/invalid `planVersion` selects the latest.
- Model attributes: `planningVersions`, `selectedPlanningVersion`, `currentPlanningVersion`, `selectedPlanIsHistorical`, `selectedDesignSpecHtml`, `selectedImplementationPlanHtml`, and `planReviewAttempts`.

- [ ] **Step 1: Write render tests for tabs, historical banner, immutable actions, and needs-guidance panel**

```java
@Test
void latestPendingVersionShowsSeparatedTabsAndOneApprovalBar() {
    String html = render(issueAwaitingApproval(), List.of(v3Pending, v2Superseded));
    assertThat(html).contains("id=\"plan-review\"")
            .contains("data-plan-tab=\"design\"")
            .contains("data-plan-tab=\"implementation\"")
            .contains("data-plan-tab=\"history\"")
            .contains("Approve Version 3")
            .contains("Revise Spec &amp; Plan")
            .contains("name=\"versionId\" value=\"3");
}

@Test
void historicalVersionIsReadOnlyAndLinksBackToCurrent() {
    String html = renderSelected(v2Superseded, v3Pending);
    assertThat(html).contains("Historical version")
            .contains("Return to current version")
            .doesNotContain("Approve Version 2");
}

@Test
void secondMissPanelExplainsGuidanceDoesNotReplan() {
    String html = renderNeedsGuidance(twoFailedReviews());
    assertThat(html).contains("Needs guidance after review 2")
            .contains("Retry Implementation")
            .contains("The approved Design Spec and Implementation Plan will not change");
}
```

- [ ] **Step 2: Run the render test and verify the old combined card fails**

Run: `./mvnw -q -Dtest=IssueDetailPlanReviewRenderTest test`

Expected: FAIL because the existing page renders one combined `planHtml` block with approve/reject modals.

- [ ] **Step 3: Populate selected/current versions and independently rendered Markdown**

In `IssueController.detail`, load versions newest-first once, select the requested number only if it belongs to the issue, fall back to the newest version, and render spec/plan independently through `MarkdownRenderer`. Load the two most recent review-bearing iterations when `planConformanceAttempt == 2`.

- [ ] **Step 4: Replace the combined card with accessible focused tabs and a persistent action bar**

Use buttons with `role="tab"`, `aria-controls`, and `aria-selected`; panels use `role="tabpanel"`. The History tab contains version links, state badge, timestamp, provider/model, revision feedback, and approval time. Only `currentPlanningVersion.state == PENDING`, the issue is `AWAITING_PLAN_APPROVAL`, and the selected version is current may render the action bar.

The revision form posts `versionId` and required feedback to `/plan/revise`; the approval form posts the same version ID to `/plan/approve`. The needs-guidance form posts only guidance to `/plan/retry-implementation`.

- [ ] **Step 5: Add resilient tab behavior and responsive styling**

```javascript
document.addEventListener('click', function (event) {
  var tab = event.target.closest('[data-plan-tab]');
  if (!tab) return;
  var root = tab.closest('[data-plan-review]');
  root.querySelectorAll('[data-plan-tab]').forEach(function (item) {
    var selected = item === tab;
    item.setAttribute('aria-selected', String(selected));
    root.querySelector('#' + item.getAttribute('aria-controls')).hidden = !selected;
  });
});
```

Add `.plan-review-tabs`, `.plan-review-tab`, `.plan-review-panel`, `.plan-version-history`, `.plan-history-banner`, and `.plan-review-actions`. At widths below `700px`, wrap the action buttons and make the guidance textarea full width; keep document content single-column.

- [ ] **Step 6: Run render tests and existing issue-detail UI tests**

Run: `./mvnw -q -Dtest=IssueDetailPlanReviewRenderTest,IssueDetailLayoutRenderTest,IssueDetailGoalCardRenderTest,IssueDetailLivePollRenderTest test`

Expected: PASS with one current action bar, read-only history, safe Markdown, and no regression to recovery/timeline/live-poll fragments.

- [ ] **Step 7: Commit the Plan Review interface**

```bash
git add src/main/java/com/dbbaskette/issuebot/controller/IssueController.java \
  src/main/resources/templates/issue-detail.html \
  src/main/resources/static/css/style.css \
  src/main/resources/static/js/app.js \
  src/test/java/com/dbbaskette/issuebot/controller/IssueDetailPlanReviewRenderTest.java
git commit -m "feat: add focused Plan Review interface"
```

### Task 8: Simplify Inbox approvals and make Plan First the visible repository default

**Files:**
- Create: `src/main/resources/db/migration/V28__remove_autonomous_superpowers.sql`
- Modify: `src/main/java/com/dbbaskette/issuebot/model/WatchedRepo.java`
- Modify: `src/main/java/com/dbbaskette/issuebot/controller/InboxController.java`
- Modify: `src/main/resources/templates/inbox.html`
- Modify: `src/main/java/com/dbbaskette/issuebot/controller/RepositoryController.java`
- Modify: `src/main/resources/templates/repositories.html`
- Modify: `src/main/resources/static/js/app.js`
- Modify: `src/test/java/com/dbbaskette/issuebot/controller/InboxPageRenderTest.java`
- Modify: `src/test/java/com/dbbaskette/issuebot/controller/RepositoryControllerTest.java`
- Modify: `src/test/java/com/dbbaskette/issuebot/controller/RepositoriesPageRenderTest.java`

**Interfaces:**
- Inbox approval rows link to `/issues/{id}#plan-review`; approval/revision occurs on the issue page.
- Repository POST keeps `planFirst` default true and no longer accepts `superpowersMethodology`.

- [ ] **Step 1: Write UI/controller tests for concise Inbox rows and default-on repository creation**

```java
@Test
void planApprovalInboxLinksToCurrentVersionInsteadOfEmbeddingPlan() {
    String html = renderInbox(v3AwaitingApproval);
    assertThat(html).contains("Plan v3 awaiting approval")
            .contains("href=\"/issues/8#plan-review\"")
            .doesNotContain("full plan")
            .doesNotContain("reject-plan-modal-8");
}

@Test
void newRepositoryDefaultsToPlanFirstAndHasNoAutonomousSuperpowersToggle() {
    WatchedRepo repo = submitNewRepoWithoutPlanFirstParameter();
    assertThat(repo.isPlanFirst()).isTrue();
    assertThat(renderRepositories()).contains("Plan First (recommended)")
            .doesNotContain("Superpowers methodology — auto design");
}
```

- [ ] **Step 2: Run Inbox/repository tests and confirm old inline/toggle behavior fails**

Run: `./mvnw -q -Dtest=InboxPageRenderTest,RepositoryControllerTest,RepositoriesPageRenderTest test`

Expected: FAIL because Inbox embeds plans and repository/controller defaults still post false plus a separate Superpowers toggle.

- [ ] **Step 3: Remove inline plan/modals and expose concise version metadata**

Have `InboxController` load the current planning version for each approval issue in one repository query keyed by issue ID. Render repository, issue, `Plan vN awaiting approval`, age, model, and `Review spec & plan`. Remove approve/reject modals from Inbox.

- [ ] **Step 4: Update repository defaults, presets, and copy**

Change the controller parameter to `@RequestParam(defaultValue = "true") boolean planFirst`, remove the autonomous Superpowers parameter and setter, check Plan First by default in the HTML, and set `planFirst: true` for Observe, Assist, and Autonomous presets. Explain that explicit opt-out skips the approval contract; do not imply that Autonomous bypasses Plan First by default.

Remove `superpowersMethodology` from `WatchedRepo` only after every Java/template consumer is gone, and add the final schema cleanup:

```sql
ALTER TABLE watched_repos DROP COLUMN superpowers_methodology;
```

- [ ] **Step 5: Run focused UI tests and dashboard/nav regression tests**

Run: `./mvnw -q -Dtest=InboxPageRenderTest,RepositoryControllerTest,RepositoriesPageRenderTest,InboxControllerTest,DashboardTileRenderTest,NavNeedsYouBadgeRenderTest test`

Expected: PASS; Needs You counts and dashboard links still route correctly.

- [ ] **Step 6: Commit supporting-surface changes**

```bash
git add src/main/java/com/dbbaskette/issuebot/controller/InboxController.java \
  src/main/java/com/dbbaskette/issuebot/model/WatchedRepo.java \
  src/main/resources/templates/inbox.html \
  src/main/java/com/dbbaskette/issuebot/controller/RepositoryController.java \
  src/main/resources/templates/repositories.html \
  src/main/resources/static/js/app.js \
  src/main/resources/db/migration/V28__remove_autonomous_superpowers.sql \
  src/test/java/com/dbbaskette/issuebot/controller/InboxPageRenderTest.java \
  src/test/java/com/dbbaskette/issuebot/controller/RepositoryControllerTest.java \
  src/test/java/com/dbbaskette/issuebot/controller/RepositoriesPageRenderTest.java
git commit -m "feat: make Plan First the operator default"
```

### Task 9: Prove restart recovery, legacy behavior, and the full lifecycle

**Files:**
- Modify: `src/main/java/com/dbbaskette/issuebot/service/polling/OrphanedRunRecovery.java`
- Modify: `src/test/java/com/dbbaskette/issuebot/service/polling/OrphanedRunRecoveryTest.java`
- Modify: `src/test/java/com/dbbaskette/issuebot/service/workflow/IntegrationWorkflowTest.java`
- Modify: `README.md`

**Interfaces:**
- Recovery preserves `AWAITING_PLAN_APPROVAL`, approved-version references, pending correction, and second-miss needs-guidance states.
- Recovery may requeue an interrupted `PLANNING` phase once, but must not create a duplicate version.

- [ ] **Step 1: Add restart and lifecycle integration tests**

```java
@Test
void restartDoesNotDuplicatePendingVersionOrDispatchAwaitingApproval() {
    issue.setStatus(IssueStatus.AWAITING_PLAN_APPROVAL);
    versionRepository.save(pendingVersion(issue, 3));
    recovery.recover();
    assertThat(versionRepository.findByIssueIdOrderByVersionNumberDesc(issue.getId())).hasSize(1);
    verify(workflow, never()).processIssueAsync(any(), any());
}

@Test
void fullLifecycleRevisesApprovesCorrectsStopsAndGuidedRetriesAgainstSameVersion() {
    PlanningVersion v1 = generate("first");
    requestRevision(v1, "include rollback");
    PlanningVersion v2 = generate("second");
    approve(v2);
    runImplementationAndReview(failedReview("missing rollback"));
    runCorrectiveImplementationAndReview(failedReview("rollback test missing"));
    assertNeedsGuidance();
    guidedRetry("test rollback on network failure");
    assertThat(issue.getApprovedPlanningVersion().getId()).isEqualTo(v2.getId());
    runImplementationAndReview(passingReview());
    assertThat(issue.getStatus()).isEqualTo(IssueStatus.COMPLETED);
}
```

- [ ] **Step 2: Run recovery/integration tests and confirm uncovered state failures**

Run: `./mvnw -q -Dtest=OrphanedRunRecoveryTest,IntegrationWorkflowTest test`

Expected: FAIL until recovery recognizes planning/conformance state and the test fixtures use versioned artifacts.

- [ ] **Step 3: Make recovery idempotent and update lifecycle documentation**

Recovery rules:

- Leave `AWAITING_PLAN_APPROVAL`, `FAILED`, and `COOLDOWN` untouched.
- For interrupted `IN_PROGRESS` with phase `PLANNING` and no pending/current version, return to `PENDING`; the normal safe dispatch regenerates once.
- For interrupted corrective implementation with `planCorrectionPending=true`, return to `PENDING` without resetting conformance attempt or approved version.
- Never mutate an approved version or create a version inside recovery.

Update README's Plan First section with default-on behavior, spec/plan tabs, revision history, two-attempt review policy, guidance-only retry, and explicit repository/issue opt-out.

- [ ] **Step 4: Run the full automated suite**

Run: `./mvnw -q test`

Expected: PASS with no failures or errors.

- [ ] **Step 5: Run packaging and static diff checks**

Run: `./mvnw -q package -DskipTests`

Expected: exit 0 and `target/issuebot-0.1.0-SNAPSHOT.jar` produced.

Run: `git diff --check main...HEAD`

Expected: no output.

- [ ] **Step 6: Commit lifecycle recovery and documentation**

```bash
git add src/main/java/com/dbbaskette/issuebot/service/polling/OrphanedRunRecovery.java \
  src/test/java/com/dbbaskette/issuebot/service/polling/OrphanedRunRecoveryTest.java \
  src/test/java/com/dbbaskette/issuebot/service/workflow/IntegrationWorkflowTest.java \
  README.md
git commit -m "test: verify versioned Plan First lifecycle"
```

### Task 10: Live verification of the complete Plan First experience

**Files:**
- No source changes expected; if verification finds a defect, add the smallest failing automated test beside the owning component before fixing it.

**Interfaces:**
- Uses: native `./run.sh` deployment on port 8090 and the signed-in local browser.

- [ ] **Step 1: Start the freshly built native application**

Run: `./run.sh`

Expected startup evidence:

```text
Started IssueBotApplication
=== IssueBot Startup Validation ===
```

- [ ] **Step 2: Verify repository defaults and opt-out**

Open `http://localhost:8090/repositories`, start adding a repository, and verify Plan First is checked and labeled recommended. Edit an existing repository and verify migration enabled it. Save an explicit opt-out, reload, and verify it remains disabled.

- [ ] **Step 3: Verify planning, revision history, and stale-action protection**

Start a Plan First issue and verify no implementation begins before approval. Confirm separate Design Spec and Implementation Plan tabs, request a revision with feedback, confirm Version 1 remains available in History, and approve Version 2. Submit a copied stale Version 1 approval request and verify the UI reports it as stale without changing Version 2.

- [ ] **Step 4: Verify conformance correction and guidance retry**

Using a controlled test issue whose first two review results fail, verify one automatic corrective implementation iteration, then the Needs Guidance panel with both findings. Add guidance and retry; confirm the approved version number does not change and processing respects global pause if paused before retry.

- [ ] **Step 5: Verify responsive and accessible behavior**

At desktop and narrow mobile widths, verify tab labels remain usable, the action bar wraps without horizontal scrolling, historical banners are clear, focus is visible, and tab buttons expose correct `aria-selected` and `aria-controls` values.

- [ ] **Step 6: Capture final evidence and inspect repository state**

Run: `./mvnw -q test && git status --short && git log --oneline main..HEAD`

Expected: tests pass; status contains no unintended files; log shows the planned coherent commits.

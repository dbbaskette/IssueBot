# Durable Decomposition Group Ownership Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Keep a decomposed parent in control of its repository until every ordered child completes and GitHub confirms the parent is closed.

**Architecture:** Persist a `DecompositionGroup` and ordered `DecompositionChild` records before external issue creation, then make the repository-first transactional dispatch gate consult the owning group. A reconciliation coordinator resumes partial creation, advances one child at a time, reconstructs legacy groups, confirms parent closure, and completes cancellation-safe abandonment.

**Tech Stack:** Java 21, Spring Boot 3.4, Spring Data JPA, Flyway, H2, Thymeleaf, JUnit 5, AssertJ, Mockito

## Global Constraints

- Repository lock order remains global processing control when required, watched repository, decomposition group/children, then tracked issues ordered by GitHub issue number.
- Only the lowest-sequence incomplete child may plan, start, retry, or continue.
- Later children remain `QUEUED` without planning versions.
- `CREATING`, `ACTIVE`, `NEEDS_ATTENTION`, `COMPLETING`, and `ABANDONING` groups reserve the repository.
- Parent closure requires every child to be IssueBot `COMPLETED`; manual GitHub closure is insufficient.
- The repository is not released until GitHub confirms the parent is closed.
- Abandonment retains ownership until any active worker reaches a cancellation checkpoint.
- Parallel same-repository execution is out of scope and remains tracked by GitHub issue #159.
- All implementation follows TDD and each task ends with focused tests plus a commit.

---

## File Structure

### New domain and persistence files

- `src/main/java/com/dbbaskette/issuebot/model/DecompositionGroupState.java` — group state enum and ownership predicates.
- `src/main/java/com/dbbaskette/issuebot/model/DecompositionChildState.java` — child creation state enum.
- `src/main/java/com/dbbaskette/issuebot/model/DecompositionGroup.java` — durable parent reservation and reconciliation state.
- `src/main/java/com/dbbaskette/issuebot/model/DecompositionChild.java` — ordered child intent and GitHub/tracked-issue identity.
- `src/main/java/com/dbbaskette/issuebot/repository/DecompositionGroupRepository.java` — owner, waiting, parent, and lock queries.
- `src/main/java/com/dbbaskette/issuebot/repository/DecompositionChildRepository.java` — ordered membership and identity queries.
- `src/main/resources/db/migration/V33__durable_decomposition_groups.sql` — schema, indexes, and foreign keys.

### New workflow files

- `src/main/java/com/dbbaskette/issuebot/service/workflow/DecompositionReservationService.java` — one source of truth for repository owner/current-child eligibility and messages.
- `src/main/java/com/dbbaskette/issuebot/service/workflow/DecompositionGroupTransactionManager.java` — repository-first atomic group creation, linking, transitions, cancellation, and release.
- `src/main/java/com/dbbaskette/issuebot/service/workflow/DecompositionGroupService.java` — GitHub-facing idempotent creation, reconciliation, parent completion, legacy recovery, and abandonment orchestration.

### New UI file

- `src/main/java/com/dbbaskette/issuebot/service/ui/DecompositionGroupViewAssembler.java` — immutable parent/child progress views for detail, queue, and Needs You.

### Existing files changed

- `IssueStatus`, `TrackedIssueRepository`, `GitHubApiClient`
- `IssueDecompositionService`, `IssueDispatchTransactionManager`, `IssuePollingService`
- `IssueController`, `InboxController`, `IssueNextActionResolver`
- `issues.html`, `issue-detail.html`, `inbox.html`, `style.css`

---

### Task 1: Persist decomposition groups and ordered children

**Files:**
- Create: `src/main/resources/db/migration/V33__durable_decomposition_groups.sql`
- Create: `src/main/java/com/dbbaskette/issuebot/model/DecompositionGroupState.java`
- Create: `src/main/java/com/dbbaskette/issuebot/model/DecompositionChildState.java`
- Create: `src/main/java/com/dbbaskette/issuebot/model/DecompositionGroup.java`
- Create: `src/main/java/com/dbbaskette/issuebot/model/DecompositionChild.java`
- Create: `src/main/java/com/dbbaskette/issuebot/repository/DecompositionGroupRepository.java`
- Create: `src/main/java/com/dbbaskette/issuebot/repository/DecompositionChildRepository.java`
- Modify: `src/main/java/com/dbbaskette/issuebot/model/IssueStatus.java`
- Test: `src/test/java/com/dbbaskette/issuebot/repository/DecompositionGroupMigrationTest.java`
- Test: `src/test/java/com/dbbaskette/issuebot/repository/DecompositionGroupRepositoryTest.java`

**Interfaces:**
- Produces: `DecompositionGroupState.ownsRepository()`
- Produces: `DecompositionGroup.currentChild(List<DecompositionChild>)`
- Produces: `DecompositionGroupRepository.findOwningByRepo(...)`
- Produces: `DecompositionChildRepository.findByGroupOrderBySequencePositionAsc(...)`

- [ ] **Step 1: Write migration and repository tests that fail before V33 exists**

The migration test must start Flyway at version 32, insert a watched repository and parent tracked issue, migrate to latest, and assert:

```java
assertThat(f.tableExists("DECOMPOSITION_GROUPS")).isTrue();
assertThat(f.tableExists("DECOMPOSITION_CHILDREN")).isTrue();
assertThat(f.columnExists("DECOMPOSITION_GROUPS", "ATTENTION_REASON")).isTrue();
assertThat(f.columnExists("DECOMPOSITION_GROUPS", "LAST_ERROR")).isTrue();
assertThat(f.columnExists("DECOMPOSITION_GROUPS", "VERSION")).isTrue();
```

The repository test must persist one `ACTIVE` group and two children in reverse insertion order, then assert sequence order and unique parent/group-position constraints.

- [ ] **Step 2: Run the focused tests and verify the red state**

Run:

```bash
./mvnw -Dtest=DecompositionGroupMigrationTest,DecompositionGroupRepositoryTest test
```

Expected: compilation or Flyway failure because the entities, repositories, and V33 do not exist.

- [ ] **Step 3: Add the schema**

Use this schema, matching existing plural table names:

```sql
CREATE TABLE decomposition_groups (
    id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    repo_id BIGINT NOT NULL,
    parent_issue_id BIGINT NOT NULL,
    state VARCHAR(32) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMP,
    released_at TIMESTAMP,
    release_reason VARCHAR(2000),
    released_by VARCHAR(255),
    attention_reason VARCHAR(2000),
    last_error VARCHAR(4000),
    last_reconciled_at TIMESTAMP,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_decomposition_group_repo
        FOREIGN KEY (repo_id) REFERENCES watched_repos(id) ON DELETE CASCADE,
    CONSTRAINT fk_decomposition_group_parent
        FOREIGN KEY (parent_issue_id) REFERENCES tracked_issues(id) ON DELETE CASCADE,
    CONSTRAINT uk_decomposition_group_parent UNIQUE (parent_issue_id)
);

CREATE TABLE decomposition_children (
    id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    group_id BIGINT NOT NULL,
    sequence_position INTEGER NOT NULL,
    proposed_title VARCHAR(500) NOT NULL,
    proposed_body CLOB NOT NULL,
    external_key VARCHAR(255) NOT NULL,
    github_issue_number INTEGER,
    tracked_issue_id BIGINT,
    creation_state VARCHAR(32) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_decomposition_child_group
        FOREIGN KEY (group_id) REFERENCES decomposition_groups(id) ON DELETE CASCADE,
    CONSTRAINT fk_decomposition_child_tracked
        FOREIGN KEY (tracked_issue_id) REFERENCES tracked_issues(id),
    CONSTRAINT uk_decomposition_child_position UNIQUE (group_id, sequence_position),
    CONSTRAINT uk_decomposition_child_external UNIQUE (external_key),
    CONSTRAINT uk_decomposition_child_tracked UNIQUE (tracked_issue_id),
    CONSTRAINT uk_decomposition_child_github UNIQUE (group_id, github_issue_number)
);

CREATE INDEX idx_decomposition_group_repo_state
    ON decomposition_groups(repo_id, state);
CREATE INDEX idx_decomposition_child_group_position
    ON decomposition_children(group_id, sequence_position);
```

- [ ] **Step 4: Add enums and entities**

`DecompositionGroupState`:

```java
public enum DecompositionGroupState {
    CREATING, WAITING, ACTIVE, NEEDS_ATTENTION,
    COMPLETING, ABANDONING, COMPLETED, ABANDONED;

    public boolean ownsRepository() {
        return this == CREATING || this == ACTIVE || this == NEEDS_ATTENTION
                || this == COMPLETING || this == ABANDONING;
    }

    public boolean unfinished() {
        return this != COMPLETED && this != ABANDONED;
    }
}
```

`DecompositionChildState` is `PENDING_CREATION`, `CREATED`, `CANCELLED`. Add `CANCELLED` to `IssueStatus`.

Map `DecompositionGroup` to `decomposition_groups` with eager `repo` and `parentIssue`, `@Version long version`, `@PreUpdate updatedAt`, and transition methods that stamp completion/release times. Map `DecompositionChild` to `decomposition_children`; make `trackedIssue` optional and eager.

- [ ] **Step 5: Add deterministic repository queries**

```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("""
    select g from DecompositionGroup g
    where g.repo.id = :repoId and g.state in :states
    order by g.parentIssue.issueNumber asc
    """)
List<DecompositionGroup> findByRepoIdAndStateInForUpdate(
        @Param("repoId") Long repoId,
        @Param("states") Collection<DecompositionGroupState> states);

Optional<DecompositionGroup> findByParentIssue(TrackedIssue parentIssue);

List<DecompositionChild> findByGroupOrderBySequencePositionAsc(DecompositionGroup group);

Optional<DecompositionChild> findByTrackedIssue(TrackedIssue trackedIssue);
```

Expose `findOwningByRepo` as a repository default method selecting the first group whose state owns the repository.

- [ ] **Step 6: Run focused tests**

Run:

```bash
./mvnw -Dtest=DecompositionGroupMigrationTest,DecompositionGroupRepositoryTest test
```

Expected: all tests pass.

- [ ] **Step 7: Commit**

```bash
git add src/main/resources/db/migration/V33__durable_decomposition_groups.sql \
  src/main/java/com/dbbaskette/issuebot/model \
  src/main/java/com/dbbaskette/issuebot/repository \
  src/test/java/com/dbbaskette/issuebot/repository
git commit -m "feat: persist durable decomposition groups"
```

---

### Task 2: Enforce group ownership in every dispatch claim

**Files:**
- Create: `src/main/java/com/dbbaskette/issuebot/service/workflow/DecompositionReservationService.java`
- Modify: `src/main/java/com/dbbaskette/issuebot/service/workflow/IssueDispatchTransactionManager.java`
- Modify: `src/main/java/com/dbbaskette/issuebot/service/workflow/RepositoryDispatchGate.java`
- Modify: `src/main/java/com/dbbaskette/issuebot/service/polling/IssuePollingService.java`
- Test: `src/test/java/com/dbbaskette/issuebot/service/workflow/DecompositionReservationServiceTest.java`
- Test: `src/test/java/com/dbbaskette/issuebot/service/workflow/IssueDispatchTransactionManagerTest.java`
- Test: `src/test/java/com/dbbaskette/issuebot/service/polling/IssuePollingServiceTest.java`

**Interfaces:**
- Consumes: Task 1 repositories and entity ownership predicates.
- Produces:

```java
public ReservationDecision evaluate(TrackedIssue candidate);
public Optional<Reservation> reservationFor(WatchedRepo repo);
public record Reservation(DecompositionGroup group, DecompositionChild currentChild) {}
public record ReservationDecision(boolean allowed, String reason, Reservation reservation) {}
```

- [ ] **Step 1: Write failing pure-policy tests**

Cover:

```java
assertThat(service.evaluate(current).allowed()).isTrue();
assertThat(service.evaluate(later).reason())
    .isEqualTo("Child #157 is waiting for #155 in decomposition #153.");
assertThat(service.evaluate(unrelated).reason())
    .isEqualTo("Decomposition #153 owns this repository. Complete or release child #155 before starting issue #154.");
```

Also prove `COMPLETING` blocks every issue, `WAITING` does not own, and the first group by parent number wins.

- [ ] **Step 2: Add transactional red tests**

Seed an `ACTIVE` group with current #155, later #156, and unrelated #154. Assert `claimStart(154)` and `claimStart(156)` reject without changing status while `claimStart(155)` reaches `IN_PROGRESS`.

Add `AWAITING_DECOMPOSITION` to the existing active reservation test and assert it blocks unrelated start/retry.

- [ ] **Step 3: Run the focused tests**

```bash
./mvnw -Dtest=DecompositionReservationServiceTest,IssueDispatchTransactionManagerTest test
```

Expected: failures because claims ignore groups and `AWAITING_DECOMPOSITION`.

- [ ] **Step 4: Implement `DecompositionReservationService`**

Load the owning group and ordered children. Select the first child whose tracked issue is not `COMPLETED` and not `CANCELLED`. Return:

```java
if (owner == null) return ReservationDecision.allowed();
if (current == null) {
    return ReservationDecision.rejected(
            "Decomposition #" + parent + " is completing and still owns this repository.",
            new Reservation(owner, null));
}
if (Objects.equals(current.getTrackedIssue().getId(), candidate.getId())) {
    return ReservationDecision.allowed(new Reservation(owner, current));
}
String reason = children.stream().anyMatch(c -> sameTrackedIssue(c, candidate))
        ? "Child #" + candidate.getIssueNumber() + " is waiting for #"
            + current.getGithubIssueNumber() + " in decomposition #" + parent + "."
        : "Decomposition #" + parent + " owns this repository. Complete or release child #"
            + current.getGithubIssueNumber() + " before starting issue #"
            + candidate.getIssueNumber() + ".";
return ReservationDecision.rejected(reason, new Reservation(owner, current));
```

- [ ] **Step 5: Wire the authoritative transactional gate**

Inject the reservation service into `IssueDispatchTransactionManager`. After `lockIssueAndRepo` and before the legacy active-status gate, call `evaluate(issue)` and return its exact rejection.

Add `AWAITING_DECOMPOSITION` to `ACTIVE_STATUSES`. Preserve the existing ready-reservation behavior when no group owns the repository.

Update `IssuePollingService.repositoryBlocker` and queue selection so group rejection is logged as a waiting reason rather than starting the next numerical issue.

- [ ] **Step 6: Run focused tests**

```bash
./mvnw -Dtest=DecompositionReservationServiceTest,IssueDispatchTransactionManagerTest,IssuePollingServiceTest test
```

Expected: all tests pass, including #154 blocked while #155 owns the group.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/dbbaskette/issuebot/service/workflow \
  src/main/java/com/dbbaskette/issuebot/service/polling \
  src/test/java/com/dbbaskette/issuebot/service/workflow \
  src/test/java/com/dbbaskette/issuebot/service/polling
git commit -m "feat: reserve repositories for decomposition groups"
```

---

### Task 3: Make decomposition creation durable and idempotent

**Files:**
- Create: `src/main/java/com/dbbaskette/issuebot/service/workflow/DecompositionGroupTransactionManager.java`
- Create: `src/main/java/com/dbbaskette/issuebot/service/workflow/DecompositionGroupService.java`
- Modify: `src/main/java/com/dbbaskette/issuebot/service/workflow/IssueDecompositionService.java`
- Modify: `src/main/java/com/dbbaskette/issuebot/service/github/GitHubApiClient.java`
- Test: `src/test/java/com/dbbaskette/issuebot/service/workflow/DecompositionGroupTransactionManagerTest.java`
- Test: `src/test/java/com/dbbaskette/issuebot/service/workflow/DecompositionGroupServiceTest.java`
- Test: `src/test/java/com/dbbaskette/issuebot/service/workflow/IssueDecompositionServiceTest.java`
- Test: `src/test/java/com/dbbaskette/issuebot/service/github/GitHubApiClientTest.java`

**Interfaces:**
- Produces:

```java
public DecompositionGroup beginGroup(
        Long parentIssueId, List<ChildIntent> intents);
public DecompositionChild linkCreatedChild(
        Long groupId, Long childId, int githubNumber);
public DecompositionGroup activate(Long groupId);
public void createOrResume(Long groupId);
public record ChildIntent(int position, String title, String body) {}
```

- [ ] **Step 1: Write failing creation-state tests**

Test that `beginGroup`:

- locks repository before parent,
- persists `CREATING` before any GitHub call,
- creates deterministic external keys `{groupId}:{position}`,
- is idempotent for a parent.

Test the coordinator with a stateful fake GitHub client:

```java
service.createOrResume(groupId);
service.createOrResume(groupId);

assertThat(fake.createdIssues()).hasSize(2);
assertThat(children.findByGroupOrderBySequencePositionAsc(group))
    .allSatisfy(child -> {
        assertThat(child.getCreationState()).isEqualTo(CREATED);
        assertThat(child.getTrackedIssue()).isNotNull();
    });
```

Simulate a timeout after remote creation and assert the second attempt finds the marker rather than creating a duplicate.

- [ ] **Step 2: Add missing GitHub read APIs**

Add:

```java
public List<JsonNode> listIssueComments(String owner, String repo, int issueNumber) {
    return webClient.get()
            .uri("/repos/{owner}/{repo}/issues/{number}/comments?per_page=100",
                    owner, repo, issueNumber)
            .retrieve()
            .bodyToFlux(JsonNode.class)
            .retryWhen(retryOnServerError())
            .collectList()
            .block(Duration.ofSeconds(30));
}
```

Continue using `listIssues(owner, repo, "issuebot-decomposed", "all")` for marker reconciliation.

- [ ] **Step 3: Run the red tests**

```bash
./mvnw -Dtest=DecompositionGroupTransactionManagerTest,DecompositionGroupServiceTest,IssueDecompositionServiceTest,GitHubApiClientTest test
```

Expected: failures because group creation still creates GitHub issues before durable intents.

- [ ] **Step 4: Implement the repository-first transaction manager**

`beginGroup` must:

1. read the repository ID from the parent,
2. lock the repository,
3. lock the parent,
4. return the existing group when present,
5. save the group,
6. save ordered child intents.

`linkCreatedChild` must lock repository/group/child, create or reuse `TrackedIssue(repo, githubNumber, title)`, force clean `QUEUED` state, and link both identities in one transaction.

`activate` must require every child to be `CREATED` and linked before setting the parent `DECOMPOSED`, clearing its proposal, and setting the group `ACTIVE` or `WAITING`. The waiting decision considers only unrelated preexisting active issues; it excludes the parent being transferred and every member of the new group, otherwise the parent would make its own group wait forever.

- [ ] **Step 5: Implement marker-based creation**

Each body ends with:

```java
"\n\n<!-- issuebot-decomposition:" + group.getId()
        + ":" + child.getSequencePosition() + " -->"
```

Before `createIssue`, scan all labeled issues for the exact marker. After every created/found issue, call `linkCreatedChild` immediately.

Use completion comment marker:

```html
<!-- issuebot-decomposition-created:{group-id} -->
```

Check existing parent comments before posting the child list.

- [ ] **Step 6: Refactor `IssueDecompositionService`**

Both AUTO decomposition and proposal approval now:

```java
DecompositionGroup group = groupTransactions.beginGroup(
        issue.getId(),
        IntStream.range(0, subIssues.size())
            .mapToObj(index -> new ChildIntent(
                    index + 1,
                    subIssues.get(index).title(),
                    buildSubIssueBody(subIssues.get(index), issue.getIssueNumber())))
            .toList());
groupService.createOrResume(group.getId());
```

Remove the direct `createSubIssues` terminal path. A partial failure keeps the group `CREATING`, records `lastError`, logs the event, and returns control without falling through to implementation.

- [ ] **Step 7: Run focused tests**

```bash
./mvnw -Dtest=DecompositionGroupTransactionManagerTest,DecompositionGroupServiceTest,IssueDecompositionServiceTest,GitHubApiClientTest test
```

Expected: all tests pass.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/dbbaskette/issuebot/service/workflow \
  src/main/java/com/dbbaskette/issuebot/service/github \
  src/test/java/com/dbbaskette/issuebot/service/workflow \
  src/test/java/com/dbbaskette/issuebot/service/github
git commit -m "feat: make decomposition creation resumable"
```

---

### Task 4: Reconcile groups, advance children, and confirm parent completion

**Files:**
- Modify: `src/main/java/com/dbbaskette/issuebot/service/workflow/DecompositionGroupService.java`
- Modify: `src/main/java/com/dbbaskette/issuebot/service/workflow/DecompositionGroupTransactionManager.java`
- Modify: `src/main/java/com/dbbaskette/issuebot/service/polling/IssuePollingService.java`
- Test: `src/test/java/com/dbbaskette/issuebot/service/workflow/DecompositionGroupServiceTest.java`
- Test: `src/test/java/com/dbbaskette/issuebot/service/workflow/DecompositionGroupTransactionManagerTest.java`
- Test: `src/test/java/com/dbbaskette/issuebot/service/polling/IssuePollingServiceTest.java`
- Test: `src/test/java/com/dbbaskette/issuebot/service/workflow/DecompositionLegacyRecoveryTest.java`

**Interfaces:**
- Produces:

```java
public void reconcileRepo(WatchedRepo repo);
public void reconcileGroup(Long groupId);
public List<DecompositionGroup> recoverLegacyGroups(WatchedRepo repo);
```

- [ ] **Step 1: Write failing lifecycle tests**

Cover:

- child #155 `COMPLETED` makes #156 current,
- failed/approval/manual-start current child makes group `NEEDS_ATTENTION`,
- all children completed makes group `COMPLETING`,
- parent close timeout retains `COMPLETING`,
- already-closed parent completes locally,
- manual child closure without tracked completion makes `NEEDS_ATTENTION`,
- premature parent closure invokes `reopenIssue`,
- oldest waiting group promotes before unrelated work.

- [ ] **Step 2: Write the legacy #153 fixture**

The fake GitHub state contains:

```text
#153 issuebot-parent open
#155 1/4 issuebot-decomposed open, body "decomposed from #153"
#156 2/4 issuebot-decomposed open, body "decomposed from #153"
#157 3/4 issuebot-decomposed open, body "decomposed from #153"
#158 4/4 issuebot-decomposed open, body "decomposed from #153"
```

Seed #153 as `DECOMPOSED`, #155–#158 as `QUEUED`, and unrelated #154 as `IN_PROGRESS`. Assert recovery links the four children in order, puts the group in `WAITING`, and promotes it after #154 completes.

- [ ] **Step 3: Run the red tests**

```bash
./mvnw -Dtest=DecompositionGroupServiceTest,DecompositionGroupTransactionManagerTest,DecompositionLegacyRecoveryTest,IssuePollingServiceTest test
```

Expected: failures because polling still uses body-only parent closure and does not reconcile groups.

- [ ] **Step 4: Implement group reconciliation**

For each group:

```java
if (state == CREATING) createOrResume(groupId);
List<DecompositionChild> ordered = children.findByGroupOrderBySequencePositionAsc(group);
Optional<DecompositionChild> incomplete = ordered.stream()
    .filter(child -> child.getTrackedIssue() == null
        || (child.getTrackedIssue().getStatus() != COMPLETED
            && child.getTrackedIssue().getStatus() != CANCELLED))
    .findFirst();
```

If the current remote child is closed but not locally `COMPLETED`, set `NEEDS_ATTENTION`. If its local status is an operator checkpoint, set `NEEDS_ATTENTION`; otherwise keep/restore `ACTIVE`.

When no incomplete child exists, transition to `COMPLETING`.

- [ ] **Step 5: Implement idempotent parent completion**

Use marker:

```html
<!-- issuebot-decomposition-complete:{group-id} -->
```

Post the completion comment only when absent, close the parent, then confirm `getIssue(...).state == "closed"`. Only then transactionally mark parent/group `COMPLETED` and promote the oldest waiting group.

- [ ] **Step 6: Replace body-only parent closure**

`IssuePollingService.recheckRepo` becomes:

```java
recheckBlockedIssues(repo);
decompositionGroups.reconcileRepo(repo);
```

Delete `closeCompletedParents`, `PARENT_REF`, and tests that treat “no open children” as sufficient. Replace them with group-backed completion tests.

- [ ] **Step 7: Implement legacy reconstruction**

Scan `issuebot-parent` and `issuebot-decomposed` using `state=all`. Attribute legacy children with `decomposed from #(\\d+)`. Order valid `N/M:` titles by N, otherwise GitHub number. Reuse tracked rows or create clean `QUEUED` rows. The oldest group is `ACTIVE` only if no preexisting active issue blocks; otherwise it is `WAITING`.

- [ ] **Step 8: Run focused tests**

```bash
./mvnw -Dtest=DecompositionGroupServiceTest,DecompositionGroupTransactionManagerTest,DecompositionLegacyRecoveryTest,IssuePollingServiceTest test
```

Expected: all tests pass.

- [ ] **Step 9: Commit**

```bash
git add src/main/java/com/dbbaskette/issuebot/service/workflow \
  src/main/java/com/dbbaskette/issuebot/service/polling \
  src/test/java/com/dbbaskette/issuebot/service/workflow \
  src/test/java/com/dbbaskette/issuebot/service/polling
git commit -m "feat: reconcile decomposition group lifecycles"
```

---

### Task 5: Add cancellation-safe abandonment

**Files:**
- Modify: `src/main/java/com/dbbaskette/issuebot/service/workflow/DecompositionGroupService.java`
- Modify: `src/main/java/com/dbbaskette/issuebot/service/workflow/DecompositionGroupTransactionManager.java`
- Modify: `src/main/java/com/dbbaskette/issuebot/controller/IssueController.java`
- Test: `src/test/java/com/dbbaskette/issuebot/service/workflow/DecompositionGroupServiceTest.java`
- Test: `src/test/java/com/dbbaskette/issuebot/service/workflow/DecompositionGroupTransactionManagerTest.java`
- Test: `src/test/java/com/dbbaskette/issuebot/controller/IssueControllerTest.java`

**Interfaces:**
- Produces:

```java
public AbandonResult abandon(
        Long parentIssueId, String reason, String releasedBy);
public record AbandonResult(boolean completed, String message) {}
```

- [ ] **Step 1: Write failing abandonment tests**

Assert:

- blank reason is rejected,
- an active child requests workflow cancellation and leaves group `ABANDONING`,
- the repository remains reserved while the child is still `IN_PROGRESS`,
- once stopped, unfinished children become `CANCELLED`,
- `agent-ready` is removed,
- parent remains open and becomes `FAILED`,
- the abandonment comment is posted once,
- repeated requests release exactly once.

- [ ] **Step 2: Add the controller test**

POST `/issues/{parentId}/decomposition/abandon` with `reason=Superseded` and a principal named `dbbaskette`. Assert the service receives the principal name and redirect returns to the parent decomposition card with success/error flash text.

- [ ] **Step 3: Run red tests**

```bash
./mvnw -Dtest=DecompositionGroupServiceTest,DecompositionGroupTransactionManagerTest,IssueControllerTest test
```

Expected: failures because abandonment does not exist.

- [ ] **Step 4: Implement abandonment**

Transactionally mark `ABANDONING` and record reason/by. If the current child is `IN_PROGRESS`, call `WorkflowCancellationService.requestCancellation(childId)` and stop.

During reconciliation, after no child is `IN_PROGRESS`:

- remove `agent-ready` from unfinished GitHub children,
- mark linked tracked issues `CANCELLED`,
- mark child creation state `CANCELLED`,
- post one marker comment:

```html
<!-- issuebot-decomposition-abandoned:{group-id} -->
```

- set parent `FAILED` with `lastFailureReason = "Decomposition abandoned: " + reason`,
- mark group `ABANDONED` and promote the next waiting group.

- [ ] **Step 5: Run focused tests**

```bash
./mvnw -Dtest=DecompositionGroupServiceTest,DecompositionGroupTransactionManagerTest,IssueControllerTest test
```

Expected: all tests pass.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/dbbaskette/issuebot/service/workflow \
  src/main/java/com/dbbaskette/issuebot/controller \
  src/test/java/com/dbbaskette/issuebot/service/workflow \
  src/test/java/com/dbbaskette/issuebot/controller
git commit -m "feat: add decomposition abandonment recovery"
```

---

### Task 6: Render group progress and one actionable Needs You item

**Files:**
- Create: `src/main/java/com/dbbaskette/issuebot/service/ui/DecompositionGroupViewAssembler.java`
- Modify: `src/main/java/com/dbbaskette/issuebot/controller/IssueController.java`
- Modify: `src/main/java/com/dbbaskette/issuebot/controller/InboxController.java`
- Modify: `src/main/java/com/dbbaskette/issuebot/service/ui/IssueNextActionResolver.java`
- Modify: `src/main/resources/templates/issues.html`
- Modify: `src/main/resources/templates/issue-detail.html`
- Modify: `src/main/resources/templates/inbox.html`
- Modify: `src/main/resources/static/css/style.css`
- Test: `src/test/java/com/dbbaskette/issuebot/controller/DecompositionGroupRenderTest.java`
- Test: `src/test/java/com/dbbaskette/issuebot/controller/IssuesQueueRenderTest.java`
- Test: `src/test/java/com/dbbaskette/issuebot/controller/IssueControllerTest.java`
- Test: `src/test/java/com/dbbaskette/issuebot/repository/TrackedIssueRepositoryInboxQueriesTest.java`

**Interfaces:**
- Produces:

```java
public Optional<GroupView> forParent(TrackedIssue parent);
public List<GroupView> activeAndWaiting();
public Optional<GroupAttentionView> attentionFor(DecompositionGroup group);
public record GroupView(
    DecompositionGroup group,
    TrackedIssue parent,
    int completed,
    int total,
    ChildView current,
    List<ChildView> children,
    String reservationMessage) {}
```

- [ ] **Step 1: Write failing view-assembler and rendering tests**

Assert the view for #153 reports `1 of 4`, current #156, completed #155, waiting #157/#158, and exact reservation copy.

Render tests must assert:

- parent detail has `data-decomposition-group`,
- queue has one group block with children in sequence order,
- #157 start control is disabled with “Waiting for #156 in decomposition #153,”
- unrelated #154 shows “Repository reserved by decomposition #153,”
- Needs You renders one group item and its displayed total equals `needsYouCount`,
- abandon form requires a reason.

- [ ] **Step 2: Run red tests**

```bash
./mvnw -Dtest=DecompositionGroupRenderTest,IssuesQueueRenderTest,IssueControllerTest,TrackedIssueRepositoryInboxQueriesTest test
```

Expected: failures because no group views are exposed.

- [ ] **Step 3: Implement the assembler**

Build immutable views from ordered children. Current child is the first not `COMPLETED`/`CANCELLED`. The reservation message is:

```java
current == null
    ? "Closing parent issue before the repository is released."
    : "Repository reserved until child #" + current.issueNumber() + " completes.";
```

- [ ] **Step 4: Populate parent and queue models**

For issue detail, add `decompositionGroupView` when the issue is a parent or child. For the queue, add `decompositionGroups` and `decompositionContextByIssueId` so normal rows can show reservation reasons even when pagination does not include the parent.

Render active/waiting groups as full-width blocks above ordinary paged rows. Suppress duplicate normal rows for issues included in those blocks.

- [ ] **Step 5: Make Needs You group-aware**

Assemble standard issue actions plus group attention actions in one controller result. Exclude the current child from standard `FAILED`, `COOLDOWN`, `READY_TO_START`, `AWAITING_PLAN_APPROVAL`, and `AWAITING_APPROVAL` sections when represented by a group attention item.

Set both:

```java
model.addAttribute("totalCount", inbox.totalCount());
model.addAttribute("needsYouCount", inbox.totalCount());
```

from that same assembled result.

- [ ] **Step 6: Add parent progress and abandonment UI**

The parent card includes progress, state, current action, ordered child list, reservation explanation, attention/error copy, and an abandonment modal with a required reason textarea.

Use responsive CSS classes rather than inline narrow table columns:

```css
.decomposition-group-card { display:grid; gap:1rem; }
.decomposition-progress { display:grid; grid-template-columns:repeat(4,minmax(0,1fr)); gap:.5rem; }
.decomposition-child.is-current { border-color:var(--accent); background:var(--accent-soft); }
@media (max-width: 720px) {
  .decomposition-progress { grid-template-columns:1fr; }
}
```

- [ ] **Step 7: Run focused tests**

```bash
./mvnw -Dtest=DecompositionGroupRenderTest,IssuesQueueRenderTest,IssueControllerTest,TrackedIssueRepositoryInboxQueriesTest test
```

Expected: all tests pass.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/dbbaskette/issuebot/service/ui \
  src/main/java/com/dbbaskette/issuebot/controller \
  src/main/resources/templates src/main/resources/static/css \
  src/test/java/com/dbbaskette/issuebot/controller \
  src/test/java/com/dbbaskette/issuebot/repository
git commit -m "feat: show decomposition ownership and progress"
```

---

### Task 7: Prove race safety, migration, and end-to-end sequencing

**Files:**
- Create: `src/test/java/com/dbbaskette/issuebot/service/workflow/DecompositionGroupConcurrencyTest.java`
- Create: `src/test/java/com/dbbaskette/issuebot/service/workflow/DecompositionGroupAcceptanceTest.java`
- Modify: `src/test/java/com/dbbaskette/issuebot/service/workflow/IntegrationWorkflowTest.java`
- Modify: `README.md`

**Interfaces:**
- Consumes every prior task.
- Produces no new production interface.

- [ ] **Step 1: Add real concurrent transaction tests**

Using `Executors.newVirtualThreadPerTaskExecutor`, latches, and real H2 transactions, race:

- decomposition approval versus unrelated `claimStart`,
- current-child versus later-child start,
- final-child completion/reconciliation versus unrelated start,
- abandonment versus retry.

For every race, assert exactly one legal transition wins and the repository-first lock order prevents deadlock.

- [ ] **Step 2: Add the end-to-end #153/#154 acceptance test**

Model #153 decomposed into #155–#158 with #154 queued. Drive:

```text
#155 plan/start/complete
#156 plan/start/fail/retry/complete
#157 plan approval/start/complete
#158 complete
parent #153 close confirmation
#154 start
```

At every checkpoint assert #154 remains `QUEUED`, later child planning-version counts are zero, and the parent remains open until #158 is `COMPLETED`.

- [ ] **Step 3: Run the new acceptance tests**

```bash
./mvnw -Dtest=DecompositionGroupConcurrencyTest,DecompositionGroupAcceptanceTest,IntegrationWorkflowTest test
```

Expected: all tests pass with no deadlock or duplicate dispatch.

- [ ] **Step 4: Update README**

Document:

- decomposition groups retain repository ownership,
- children execute in sequence,
- failures/approvals retain the group,
- parent closure confirmation releases the repository,
- abandonment is the explicit escape hatch,
- same-repository parallelism remains future issue #159.

- [ ] **Step 5: Run the full verification suite**

```bash
./mvnw clean test
```

Expected: `BUILD SUCCESS`, zero failures/errors, and the complete test count reported.

- [ ] **Step 6: Run package and diff checks**

```bash
./mvnw -DskipTests package
git diff --check
git status --short
```

Expected: package succeeds, diff check is empty, and only intended plan-tracking changes remain.

- [ ] **Step 7: Commit**

```bash
git add src/test README.md
git commit -m "test: verify decomposition group sequencing"
```

---

## Final Review and Delivery

- [ ] Request an independent code review against the approved spec.
- [ ] Fix every Critical, Important, and Minor finding test-first.
- [ ] Rerun `./mvnw clean test` after review fixes.
- [ ] Push `codex/durable-decomposition-groups`.
- [ ] Create a ready-for-review PR with the spec, plan, tests, migration notes, and live recovery impact.
- [ ] Wait for CI and merge the PR.
- [ ] Fast-forward local `main`, rebuild the JAR, and restart `com.dbbaskette.issuebot`.
- [ ] Verify liveness and readiness are `UP`.
- [ ] Verify V33 applies and live #153 reconstructs with #155 as the next group child unless a preexisting active issue still owns the repository.
- [ ] Verify #154 cannot start while the recovered #153 group owns the repository.

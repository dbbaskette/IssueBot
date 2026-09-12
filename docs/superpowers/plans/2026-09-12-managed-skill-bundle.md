# Managed Superpowers bundle — approved implementation plan

The user approved versioned IssueBot-owned guidance with isolated loading, stage selection,
startup validation, visible provenance, and coordinated release/rollback.

## Contract

- Vendor the exact tested `6.3.0-custom.1` source subset and MIT license; record source commit,
  per-file SHA-256, and a pinned manifest digest. No startup downloads or personal cache paths.
- Project selected text into harness requests for both providers. This is explicit emulation,
  not native plugin auto-discovery. Utility/classification receives no workflow skill text.
- IssueBot's stage/response contract overrides interactive skill mechanics: planning remains
  read-only, no additional approval interview, commits/merges remain IssueBot-owned, review
  does not start a second review, and final testing ownership stays unchanged.
- Validate before the application can dispatch. Missing/corrupt resources fail startup.
- Show verified bundle identity and projection mode in Setup without subprocesses on GET.
- Ship resources inside the jar, so native and container deployments use the same bytes.

## Implementation and verification

1. Package source resources and provenance with deterministic integrity checks.
2. Add immutable loader and role mapping at the shared harness boundary, including retries.
3. Add cached Setup metadata and upgrade/rollback documentation; release as 0.8.0, retaining
   the earlier audit corrections in this worktree.
4. Test corruption/missing resources, role filtering, provider forwarding, utility exclusion,
   and actual packaged jar contents. Run combined Java/JavaScript verification once.

No CLI auth/model settings, active deployments, remote forks, PRs, or merges change here.
Native projection and full per-task orchestration remain future work. Transport compatibility
uses existing tested provider prompt APIs, not a new minimum CLI-version claim.

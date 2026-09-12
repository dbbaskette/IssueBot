# Skill and IssueBot prompt refresh — 2026-09-12

## Outcome and scope

Applied the user's supplied skill-creator principles and [OpenAI's skills/prompt guidance](https://developers.openai.com/blog/rethinking-skills-and-prompts-for-gpt-6-astra): precise discovery, relevant references, proportionate process, clear authorization, and completion backed by evidence. Guidance stays model-neutral; no provider, model, subscription, or credential configuration was changed.

Personal changes are installed locally. IssueBot changes were implemented and verified in branch `codex/skill-prompt-refresh`, worktree `.worktrees/codex-skill-prompt-refresh`, based on merged main `03e2065`. The user subsequently authorized commit, push, PR, and merge for IssueBot; Git history and the release PR record that publication. Deployment/restart is not included in that authorization. The patch version is 0.7.1. The previous operator-flow PR 177 remains a separate completed release.

## Personal skills: what changed

Updated **37 logical skills across 67 physical SKILL.md files** in the Codex, Agents, and Claude skill directories. Codex symlinks were resolved to their real targets so a file was not edited twice. Separate runtime mirrors were retained. Names, optional metadata, assets, scripts, and invocation policies were preserved.

| Area | Substantive change |
| --- | --- |
| API/interface design | Replaced generic tutorials with contract/compatibility guidance; retained atomic idempotency claims, payload conflict handling, bounded concurrent duplicates, durable intent, and unknown-outcome reconciliation. |
| Observability | Removed automatic whole-feature telemetry audits; focus on concrete visibility gaps, bounded labels, redaction, useful correlation, and safe validation of changed signals. |
| Performance | Removed universal frontend budgets and checklists for backend work; require comparable measurement, noise awareness, correctness, and honest claims. |
| Security | Removed broad triggers and repeat permission requests for already-authorized local fixes; retained authorization, SSRF/DNS-rebinding considerations, supply-chain boundaries, LLM trust boundaries, and sensitive-data protection. |
| Shipping | Removed fixed observation windows and duplicate full-suite requirements; preserve exact artifact identity, active-work protection, rollback/data distinctions, and explicit deployment authority. |
| PR review | Removed invented/pinned model assumptions and automatic GitHub posting. Respect a user-selected available model; a review-only request now returns findings without approving, posting, or merging. |
| Gemini image | Removed the false assertion that Codex lacks native image tools, broad provider substitution, and requests to paste keys into chat. Explicitly distinguish Gemini API costs from a Codex subscription. |
| HyperFrames entrypoint | Preserve pinned tooling; remove automatic upgrades and skill refreshes for every ordinary task. Install/update for a concrete need and preserve local customizations. |

The remaining **29 logical skills received discovery-description changes only**, not a rewrite of their specialized workflow bodies:

brag; captions-overlay; changelog-video; cut-the-curve; embedded-captions; faceless-explainer; figma; general-video; hyperframes-animation; hyperframes-audio; hyperframes-cli; hyperframes-core; hyperframes-creative; hyperframes-keyframes; hyperframes-registry; media-use; motion-doctrine; motion-graphics; music-to-video; oversized-cursor; pr-to-video; product-launch-video; remotion-to-hyperframes; seam-craft; slideshow; talking-head-recut; tanzu-brand; cf-context; docker-patterns.

Descriptions now name their actual deliverable or domain rather than exhaustive keyword lists or catch-all mandates. Media skills stay scoped to media work; the Cloud Foundry target-selection boundary remains intact. This was not a line-by-line behavioral audit of every video workflow or its supporting scripts.

Across one description per logical skill, whitespace-delimited description words fell from **2,548 to 476** (about 81%). Across all edited files including mirrors, total words fell from **143,073 to 127,276**. These are text-size measurements, not tokenizer measurements or demonstrated latency/cost savings.

Moved the obsolete `tanzu-brand.backup-2026-07-31…` directory, including all assets and references, out of `.agents/skills` into the backup directory. No references to that backup name were found in the three personal skill roots. The current Tanzu skill remains installed.

## Global and repository instructions

Updated the global Codex AGENTS.md to preserve the existing milestone testing cadence and clarify:

- Load applicable skills and only relevant supporting references, not a whole process stack.
- An approved scope does not require reapproval for every mechanical step.
- Continue safe authorized local work through verification and relevant failure repair.
- Reuse valid evidence for an unchanged source tree and relevant environment; a new message or skill does not invalidate it.
- Keep publication, deployment, paid service, destructive-operation, and active-workflow boundaries intact.

IssueBot's repository AGENTS.md now has the same proportional guidance plus contextual documentation pointers. Its version/build-info, stable jar, required release testing, and safe restart conventions are unchanged.

## Managed plugins and durability

Did **not** overwrite managed `.system` skills, curated plugins, or the installed Superpowers caches. Their packaging and updates are not controlled by this repository. The installed development Superpowers copy already had a user deletion of AGENTS.md; it was preserved. The dirty personal `dbb-skills` project was also left untouched.

Saved an **unapplied** upstream-compatible patch for Superpowers `using-superpowers` and `verification-before-completion`. It replaces the 1%-chance catch-all and per-message full-verification requirement with scoped selection and evidence reuse. `git apply --check` passed against the installed development 6.3.0 source. This is a reusable proposal, not an installed plugin release; other Superpowers process bodies and duplicate plugin registrations remain unchanged. The global working agreement expresses the user's policy in the meantime, subject to higher-priority runtime instructions.

Personal media skills are also refreshable by their installer. The HyperFrames entrypoint no longer requests gratuitous updates, but an explicit installer/init operation can still overwrite local files. Backups and the manifest allow comparison/restoration; no claim is made that these changes are synchronized upstream or across other machines.

## IssueBot changes

`PromptGuidance` loads versioned, original Markdown resources from `src/main/resources/prompts/guidance/`. Each stage receives common guidance, only its role-specific guidance, and a conditional frontend section. The built jar was checked to contain all five resources and the loader.

| Stage | Guidance and ownership |
| --- | --- |
| Planning | Observable acceptance criteria, meaningful alternatives, coherent milestones, relevant files/commands; still read-only with the same two-section response contract. |
| Implementation | Finish the authorized outcome; add coverage and run focused milestone checks; identify verification performed and limitations. IssueBot owns configured final verification. |
| Review | Separate review invocation using the selected model, without claiming it must be a different model. Consume supplied local/CI evidence; avoid reflexive full-suite reruns and report uncertain/stale evidence. Existing JSON/scoring rules remain. |
| Frontend, all three stages | Consult the existing design system; consistent controls/statuses; clear next actions; accessibility/mobile layouts; preserve drafts, focus, selection and disclosure state during refresh. UI changes call for relevant visual checks; backend-only work does not. |

The frontend text is original bundled guidance, not copied third-party skill content or a runtime download. No native Superpowers skill projection, automatic skill installer, or per-task execution ledger was added.

### Workflow after an agent-ready issue

Discovery and dependency/capacity checks → repository dispatch claim → planning and configured approval gates → implementation → configured local verification → commit/push → CI when enabled → PR → independent review → correction or completion/merge according to repository settings.

See `docs/operator-workflow.md` for policy-specific details. This refresh changes instructions, **not** dispatch ordering, approvals, trusted verification, review thresholds, or merge freshness checks.

### Important limits

Prompt guidance reduces requests for redundant testing; it does not technically prevent a model from running a command twice. Configured duplicate command lines still run, CI may legitimately overlap local coverage, implementation does not yet receive the exact configured command list, and review summaries are not a command/tree/environment evidence ledger. Review read-only behavior remains an instruction rather than a new sandbox guarantee. Agent claims never bypass trusted gates. No production trace was collected to quantify existing duplication, and no live model/provider invocation was used to claim end-to-end behavioral proof.

## Validation

- Targeted IssueBot milestone: **122 tests passed** across prompt assembly, planning, review, and workflow service tests.
- Final `./mvnw -q verify`: **1,744 Java tests**, zero failures/errors/skips; exit 0.
- Final `node --test src/test/js/*.cjs src/test/js/*.js`: **64 passed**, zero failed/skipped; exit 0. Initial restricted run could not bind its disposable loopback fixture; permitted rerun passed.
- Skill creator `quick_validate.py`: **67/67 files passed**; the two HyperFrames entrypoints passed again after their final focused edit. System/bundled Python lacked PyYAML; validation used an isolated uv environment. No validator implementation was changed.
- Independent, read-only forward-test: PR-review-only, authorized staging deployment, and local validation-fix scenarios preserved scope, authority, evidence reuse, and active-workflow boundaries. No actual external actions were taken by that test.
- `git diff --check` passed; packaged prompt resources verified. No UI layouts changed, so no browser redesign pass was required.

## Backups and recovery

Backups live at `/Users/dbbaskette/.codex/skill-backups/2026-09-12-issuebot-refresh/`, outside skill discovery. `manifest.json` maps each of the 67 edited files to its original. Its size/kind fields describe the first pass; HyperFrames then received the additional entrypoint changes described above. `global-AGENTS.original.md` preserves the original global instructions; `superpowers-proportional-workflow.patch` is the unapplied plugin patch. The archived Tanzu directory is alongside them.

To undo a personal edit, compare the current file with its mapped original and restore only the intended change, preserving newer user edits. Do not blindly restore the whole directory. The IssueBot branch/worktree is separate from the user's prior checkout and running deployment.

# Second skills and IssueBot audit — 2026-09-12

Follow-up: the user subsequently approved the [managed skill bundle](managed-skills.md).
That implementation addresses the bundle-integration gap below using explicit stage-prompt
projection and groups these previously unshipped audit corrections into IssueBot 0.8.0.
The remainder of this report records the earlier audit's findings and validation state.

## Outcome

Reread [OpenAI's article](https://developers.openai.com/blog/rethinking-skills-and-prompts-for-gpt-6-astra), published September 11, 2026. Its recommendations concern precise discovery, contextual references, proportional process, explicit decision boundaries, and completion of authorized work. They do not prescribe changing models, disabling safeguards, or a numerical skill-length limit.

This pass corrects demonstrated instruction problems and one runtime prompt-context gap. It is not a certification that every installed workflow or third-party reference is behaviorally compliant. Automated catalog screening and focused source review have different evidentiary strength.

## Coverage

- Screened all personal Codex/Agents/Claude skill roots and enabled Codex plugin cache entrypoints: **125 physical entrypoints** after resolving directory aliases.
- Personal skills: **37 logical skills / 67 physical files**; all descriptions are below the screening threshold of 220 characters. That threshold is an audit heuristic, not an OpenAI requirement.
- Custom Superpowers: **14 entrypoints**, only `superpowers@superpowers-custom` installed and enabled. No duplicate upstream installation appeared in the live CLI inventory. The previous fresh-session evaluation remains applicable because these files were unchanged.
- Managed plugin entrypoints: **38**, of which **33** exceed the description heuristic. Six additional `.system` entrypoints were screened. These counts include installed templates not necessarily present in every task's catalog.
- Scanned complete entrypoint text for size and process-prescription signals; manually inspected IssueBot stage guidance, implementation/verification wiring, prior workflow documentation, and the selected changed skill bodies and shared media references. Did not claim a line-by-line semantic review of every reference/script or execute every media workflow.

## Implemented changes

### IssueBot 0.7.2: make verification ownership actionable

The prior implementation prompt said IssueBot would run configured verification but omitted the actual command list. The implementation agent therefore could not reliably distinguish focused checks from the upcoming final gate.

Implementation now receives the effective command list parsed by the same parser used by the trusted local gate. This is included in initial/resumed invocations and a failed-resume cold fallback. Blank/comment lines are omitted; command content and order are retained. If no commands are configured, the prompt explicitly tells the agent not to assume a final local suite will run.

Commands are framed as context, not a request to execute them immediately. Diagnostic reruns remain allowed. The change does not deduplicate shell commands, skip trusted checks based on model claims, change approval behavior, or alter CI/merge gates. Identical configured lines can represent intentional checks and are not silently removed.

Regression coverage verifies command content/order, empty configuration, and both prompts sent during resumed-to-cold fallback. No schema or UI changes were needed. Maven build-info remains the version source and the jar name remains stable.

### Personal skills: six families, 16 files including mirrors/references

| Family | Verified problem | Correction |
| --- | --- | --- |
| `cf-context` | Asked for a foundation even if the current request named it; expired auth could trigger the same target question again | Honor and verify explicit targets; retry auth within the existing bounded procedure; preserve production-target and mutation authority |
| `hyperframes` | Complete new briefs still entered a fixed interview; ordinary edit wording mandated a stack of skills | Reuse complete briefs and approvals; route only material missing choices; load relevant domains rather than every listed skill |
| `changelog-video` | Delete/restart instruction could discard work; fixed CloudFront distribution implied unrelated publication authority; every unknown term blocked production | Preserve/repair existing work, verify actual authorized deployment target, ask only for material pronunciation uncertainty, load references at relevant milestones |
| `captions-overlay` | Repeated the same placement/hierarchy rules several times | Consolidate into one short guide; retain caption hierarchy, behind-subject matte requirement, readable overlays, full-frame layout, and user mode preferences |
| `hyperframes-cli` | Mandatory second render approval even for a render request; automatic feedback submission; catalog diagnostics loaded for unrelated commands | Reuse explicit local-render authority, require consent for external feedback, move catalog detail into a conditional reference |
| `hyperframes-core` shared references | Unconditional pre-render question and stopping on quality errors; every summary correction reopened approval | Respect requested checkpoints, diagnose/repair scoped errors, continue clear authorized corrections, preserve storyboard-only completion and separate cloud/publication authority |

Assets, executable production scripts, credentials, telemetry configuration, model choices, and invocation policies were not changed. Source copies in `.agents` and `.claude` were both updated; Codex symlinks continue resolving to their existing targets. No managed plugin cache was edited.

## IssueBot operation: verified boundaries and remaining gaps

The primary checkout was an older planning branch. Work was isolated in `codex/astra-audit`, based on current merged main `b0deb98`; the older checkout and other worktrees were preserved.

Source flow remains discovery/dependency/capacity checks → repository claim → planning/configured approvals → implementation → local verification → commit/push → CI when enabled → PR → independent review → correction or configured completion/merge. See [operator workflow](operator-workflow.md).

No Java listening process was found by a permitted local listener check. The previously used `http://127.0.0.1:8092/actuator/health` returned connection failure. This does not establish remote-host status. No service was started, stopped, or restarted, and no live issue was dispatched for this audit.

Important remaining limitations:

1. **IssueBot does not load the desktop Superpowers fork as its runtime bundle.** Claude excludes personal hooks/settings and Codex isolates user config/rules. The original bundled guidance is aligned, but native skill projection remains unimplemented. Automatically enabling personal plugins would reintroduce uncontrolled stage behavior.
2. **Review read-only behavior is guidance, not universal enforcement.** A dedicated provider-neutral read-only review capability deserves a separately tested change; narrowing permissions should not be casually claimed from a prompt edit.
3. **Review test evidence is still a summary, not a command/tree/environment ledger.** No bare model claim can waive the trusted gate. The new command context reduces ambiguity; it does not prove a measured reduction in duplicated runs.
4. **Large media entrypoints remain candidates for progressive-disclosure refactoring:** `hyperframes-audio` (461 lines), `slideshow` (524), `talking-head-recut` (1,212), and `docker-patterns` (366) in the pre-change inventory. Size alone does not prove bad decisions. Their operational details and scripts were not mechanically deleted or declared fully audited.
5. **Vendor-managed descriptions still carry context overhead.** Durable changes require source ownership/upstream fixes or a maintained package, not cache patching. No unrelated plugins were disabled just to improve an audit metric.
6. **Media customizations remain installer-overwritable.** Backups preserve this pass; they are not a maintained media plugin release or an upstream contribution.

These are open engineering/maintenance risks, not claims that the full system now conforms in every case.

## Validation and handoff

- Focused prompt/workflow tests passed.
- Final `./mvnw -q verify`: **1,746 tests**, zero failures, errors, or skips.
- Final JavaScript suite: **64 passed**, zero failures/skips.
- Skill metadata validation: **81/81 passed** (67 personal + 14 custom Superpowers).
- `git diff --check` passed. Local tests use fixtures; no paid model API, media generation, or remote production run was performed.
- IssueBot changes are local and uncommitted on the isolated audit branch; no push, PR, merge, or deployment is included in this turn.

Personal backups and the file-change manifest are at `/Users/dbbaskette/.codex/skill-backups/2026-09-12-second-audit/`. Compare a current file with its mapped backup before selectively restoring it; preserve newer user edits. Generated catalog evidence records paths, hashes and screening flags. A flag is not a confirmed defect.

# Decision-first IssueBot UI

Approved scope: recommendations 2, 3 and 9, followed by push, PR and merge. No deployment/restart.

## One issue decision surface

Place the current actionable decision immediately below the issue identity, before review history, goal, logs and terminal detail. Use one elevated `issue-decision` surface with the current state and exact consequence. Remove duplicate primary header/Next action controls when the decision surface owns that action. Preserve all existing form endpoints, CSRF, immutable version identifiers, approval preconditions and guidance. Stage approval buttons name the stage; show selected provider/model, bound plan and next configured approval checkpoint. Legacy plan approval remains plan-only, never relabeled as execution. Stop/administrative controls stay separate, quiet and reachable. History is not a primary decision. Preserve anchor links and live OOB refresh without replacing open dialogs or edited forms.

## One repository workflow editor

Put workflow policy in the main Add/Edit Repository form, with one save action. Present Automatic, Approval checkpoints and Existing settings. Existing repositories retain their policy; new repositories retain the existing default behavior unless the operator chooses otherwise. Under checkpoint mode show the five ordered stages and approval switches; only AI-driven stages describe provider/model selection. Explain that actual model choice is made at approval and show existing repository model defaults where relevant; do not invent per-stage model persistence. Under Existing settings expose the older mode/start/merge/plan controls in one clearly labeled advanced disclosure. Remove the autonomy preset selector and separate row-level policy editor; keep compatible old endpoints. Saving policy and repo settings is one operation with server validation. Policy changes affect unstarted issues, never active decisions. Provide a live plain-language workflow summary; no selection auto-saves.

## Visual direction

Product identity: a calm execution console with a distinctive connected workflow rail, not a pile of equally elevated glass cards. Keep Inter and technical-only JetBrains Mono. Palette: canvas #f4f5f8, surface #ffffff, ink #1e293b, action cobalt #2563eb, completed teal #0f766e, waiting amber #b45309; red is reserved for failures/destructive actions. Dark theme keeps matching semantic contrast. Use solid surfaces, 12px panel corners, modest borders, shadows only for the active decision/dialog, sentence-case labels and stronger headings. Keep workflow steps visually connected with a clear current step; no continuous decorative animation. Preserve keyboard focus, reduced motion, long-title wrapping and usability at 390px and 1440px. No navigation redesign, mobile action dock, new recovery engine, history consolidation or notification redesign in this batch.

## Verification

Render tests cover queued, legacy plan approval, staged approval, legacy PR approval, failed and completed states; primary-action duplication, exact copy and live-refresh safeguards. Repository tests cover retained legacy values, automatic/checkpoint policy validation and editing without unintended changes. JavaScript tests cover policy disclosure/summary. Full Java and JS suites plus read-only visual inspection of rendered fixtures, independent review, PR checks and merge verification.

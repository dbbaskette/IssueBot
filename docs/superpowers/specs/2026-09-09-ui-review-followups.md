# Additional UI review recommendations

Scope: read-only browser inspection of real Thymeleaf/MVC-rendered synthetic pages on 2026-09-09. Issue, repository, inbox and dashboard surfaces were inspected in light/dark themes, with issue/repository checks at 390px. This is not a production-data or live-worker test. These recommendations are not part of the approved 2/3/9 implementation.

1. **One dashboard story.** The attention/processing/queue control room is followed by another set of attention and execution cards. In the staged-approval fixture, the top says a decision is needed while a lower card says nothing needs intervention. Remove duplicate summaries or derive them from exactly the same actionable data.
2. **Name every inbox decision.** The staged card says only repository/issue number and “Workflow stage approval required,” under “PR Approvals.” Include the issue title and exact stage; use a broader “Approvals” heading or group by decision type.
3. **Hide empty categories.** Four empty inbox sections consume most of a viewport when there is only one approval. Show populated groups by default, with a quiet “Show empty categories” option if useful.
4. **Keep repository actions in view.** The desktop table has eleven columns and pushes Actions off-screen. Lead with repository, effective workflow policy, next checkpoint and issue count; put detailed legacy switches in Edit or an expansion.
5. **Use one destination for decisions.** Needs You and Approvals compete in navigation. Make Approvals a filter within Needs You, retaining old links as redirects.
6. **Use human-readable evidence links.** “View bound plan · artifact 1” exposes a storage identifier. Prefer “Approved plan v3,” with immutable IDs in technical detail only.
7. **Compact the mobile processing bar.** At 390px, the global state text is squeezed by two actions. Use a concise state label and arrange stop/pause controls without truncation; keep immediate stop available and distinguish it from draining current work.
8. **Show useful history, not empty containers.** Collapse or omit zero-attempt iteration history and zero-event activity. Explain where future evidence will appear in one short line.
9. **Separate subscription usage from dollar estimates.** The issue shows $0.0000 even with a subscription CLI provider selected. Identify the billing mode and show the appropriate usage signal; do not imply a measured zero charge when no cost estimate exists.

Recommended next group: 1–3 together, because they address contradictory or hard-to-identify decisions using the same canonical attention model. Repository table and navigation simplification can follow independently.

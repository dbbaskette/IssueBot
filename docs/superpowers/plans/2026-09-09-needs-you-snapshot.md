# Canonical Needs You snapshot — #154, #162–165

## Spec

One transactional `NeedsYouService.snapshot()` supplies immutable list collections for PR/stage approvals, plans, ready reservations, split proposals, human intervention, and decomposition attention. All represented group members (including parent) are excluded from standalone sections. Its total is computed from exactly those collections. No independent count query remains.

`InboxController` builds its cards from that snapshot and exposes it in the model. A post-handler MVC interceptor supplies the same snapshot-derived badge count to all layout/content views, reusing the inbox snapshot. Running after mutations avoids pre-handler stale counts. Unrelated JSON, SSE, redirect, and detail-poll requests do not load the snapshot.

A persistent layout refresh coordinator listens to issue-update SSE events and mutation completion, with bounded polling fallback for missed events. It requests one live response: badge only off the inbox, badge plus inbox content on the inbox. A single snapshot supplies both. Refresh requests serialize/coalesce; modal or edited controls defer inbox replacement to preserve operator input. Background response swaps never overwrite a different page after navigation. Zero remains a real addressable hidden badge element so later positive updates work.

## Implementation plan

1. Canonical snapshot service/record; remove count queries; migrate inbox and query/unit tests.
2. Shared post-handler layout model interceptor; remove independent count calls in all controllers; regression coverage for normal/HTMX/post-mutation rendering.
3. Live response endpoint and fragments, persistent client coordinator, event/poll refresh, focused input safety and navigation race checks.
4. Validate populated/empty/grouped/stage-wait states, last-item transitions, count-to-render equality, run the full relevant suite, review, and commit this unit separately.

Follow the user's milestone testing cadence. Existing styles and approval behavior are preserved; no new workflow actions or merge/deployment operations are introduced.

## Completion and verification

All four implementation steps completed. Independent review found no blocking defects. The final full Java suite passed: 1,363 tests, zero failures/errors/skips (`./mvnw -q test`). The live-refresh suite passed all six tests (`node --test src/test/js/needs-you.test.cjs`). Real MVC/H2 tests verify that completing the final item removes both its card and the positive badge in one live response.

The work is retained in a separate commit after #167. PR/merge and deployment remain integration choices; the running server was not restarted.

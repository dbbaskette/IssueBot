# Task 3 report: full-screen fixtures and integration verification

## Delivered

- Expanded `UiVisualFixturesTest` into a hermetic real-MVC export covering every primary screen, populated approval/stage/diff/review data, queued/blocked/running rows, paused empty Needs You, recorded-zero and no-data Costs, mixed Setup prerequisites, INFO/WARN/ERROR notifications, and missing-issue error handling.
- Exported both full pages and HX content for the same application routes, plus all polling/panel fragments required by the visual matrix.
- Added `scripts/ui-fixture-server.cjs`, a dependency-free loopback-only, manifest-allowlisted, read-only server with no-store headers, per-asset cache busting, and rejection of encoded or literal traversal segments.
- Added focused server contract tests and the durable verification record in `docs/superpowers/specs/2026-09-10-ui-consistency-verification.md`.

## Route manifest

The generated `/tmp/issuebot-ui-consistency/fixture-manifest.json` is authoritative. Current routes are:

```text
/
/repositories
/issues
/issues/1
/issues/1/live-status
/inbox
/inbox?fixture=empty-paused
/fixtures/inbox-empty-paused
/inbox/live?includeInbox=true
/approvals
/settings
/setup
/setup/prereqs
/costs
/costs?fixture=no-data
/fixtures/costs-no-data
/dashboard/live
/notifications/panel
/issues/999999
/fixtures
```

Start after any prior process using port 8092 has stopped:

```sh
node scripts/ui-fixture-server.cjs --root /tmp/issuebot-ui-consistency --host 127.0.0.1 --port 8092
```

## Test evidence

- Focused MVC export: passed (`UiVisualFixturesTest`), 29 page/fragment files.
- Fixture server: 6/6 passed (`ui-fixture-server.test.cjs`), including a raw-request regression for literal dot-segment traversal.
- Full Java/JavaScript suites and final CUA desktop/mobile light/dark review: intentionally pending root's final gate.

Final CUA review observed a mobile-width overflow in the Setup prerequisites table. This is recorded as an application UI follow-up; Task 3 did not alter production templates or styles.

No live worker, production database, CLI command, GitHub request, mutation route, deployment, push, or merge was used.

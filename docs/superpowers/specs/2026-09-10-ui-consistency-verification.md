# UI consistency verification

## Hermetic fixture coverage

`UiVisualFixturesTest` renders the real Spring MVC controllers and Thymeleaf templates against an in-memory H2 database. It exports only synthetic records and mocks worker/startup, configuration initialization, processing state, agent CLI, model discovery, and GitHub boundaries. Setup prerequisite results are deliberately mixed (`CLI OK`, authentication failed, GitHub token invalid, work directory OK). Opening the notification panel is asserted not to change unread state.

The generated manifest is `/tmp/issuebot-ui-consistency/fixture-manifest.json`. It includes full-page and `HX-Request: true` variants for `/`, `/repositories`, `/issues`, `/issues/1`, `/inbox`, `/approvals`, `/settings`, `/setup`, `/costs`, and `/issues/999999`. It also includes `/dashboard/live`, `/issues/1/live-status`, `/inbox/live?includeInbox=true`, `/setup/prereqs`, and `/notifications/panel`, plus the review-only states `/inbox?fixture=empty-paused` and `/costs?fixture=no-data`. Browser-bridge-safe aliases `/fixtures/inbox-empty-paused` and `/fixtures/costs-no-data` serve the same review states without query strings. The generated issue id is deterministic in the isolated fixture database but consumers should treat the manifest as authoritative.

Generate and serve the fixtures:

```sh
./mvnw -q -Dtest=UiVisualFixturesTest \
  -Dissuebot.visualOutput=/tmp/issuebot-ui-consistency test
node scripts/ui-fixture-server.cjs \
  --root /tmp/issuebot-ui-consistency --host 127.0.0.1 --port 8092
```

Open `http://127.0.0.1:8092/fixtures`. The fixture server binds loopback only, serves only manifest routes and allowlisted static assets, uses exact query-route matching, rejects non-GET/HEAD methods and traversal-shaped paths, and sets no-store headers. HTML asset URLs receive a current file-derived cache-busting query.

## Evidence already observed

Before this fixture expansion, the browser harness recorded real HTMX 2.0.4 plus Idiomorph 0.3.0 preserving explicit parent/nested open and closed disclosure choices through morph, outerHTML, OOB, and history restoration while accepting new server content. It also recorded independent plan-version state, same-tab restoration, expiring success versus persistent/dismissible error toasts, and navigation state updates between Repositories and Issue Queue. These observations are documented in `.superpowers/sdd/2026-09-10-ui-consistency/visual-matrix.md` and are not re-characterized as results of the new export.

## Focused automated evidence

- `./mvnw -q -Dtest=UiVisualFixturesTest -Dissuebot.visualOutput=/tmp/issuebot-ui-consistency test` — passed; 20 allowlisted routes and 29 page/fragment files exported.
- `node --test src/test/js/ui-fixture-server.test.cjs` — 5 passed; full/HX selection, exact query fixtures, cache busting/no-store assets, GET/HEAD behavior, mutation rejection, traversal rejection, and missing routes.

## Pending final gate

The root task owns the final CUA pass at 1440×1000 and 390×844 in light and dark themes, including overflow/clipping/button heights and the fresh-asset checks for previously corrected mobile layouts. That pass observed horizontal overflow in the Setup prerequisites table at mobile width; it is an application UI follow-up for the final review and was intentionally not changed in this fixture/server task. The root also owns the final complete Java and JavaScript suites. No deployment is claimed by this document.

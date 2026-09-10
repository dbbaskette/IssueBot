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

Before this fixture expansion, the browser harness recorded real HTMX 2.0.4 plus Idiomorph 0.3.0 preserving explicit parent/nested open and closed disclosure choices through morph, outerHTML, OOB, and history restoration while accepting new server content. It also recorded independent plan-version state, same-tab restoration, expiring success versus persistent/dismissible error toasts, and navigation state updates between Repositories and Issue Queue. These are separate behavioral-harness observations, not results of the new export.

The real MVC exports were inspected in the browser at desktop (1440×1000) and phone (390×844) sizes, using both themes across the review:

| Surface | Observed result |
| --- | --- |
| Dashboard | Desktop dark and phone light layouts fit; badges wrap without splitting; Run details stays open through the normal 10-second refresh. |
| Repositories | Desktop controls measure 40px and phone controls 44px; workflow descriptions stack; lessons open in a full-width section; no page overflow. |
| Issues | Phone filters and cards align; issue numbers stay intact; no page overflow. |
| Issue detail | Desktop and phone decision sections are readable and preserve model/action controls; no page overflow. |
| Needs You | Empty paused fixture says no actions need attention, shows zero active/queued, and retains the truthful Work stopped rail. |
| Approvals | Desktop and phone cards fit and use readable stage names. Final preview shows neutral Overall 92% styling alongside the separate Review passed verdict. |
| Settings | Desktop model/reasoning pairs and phone stacks fit; provider remains separate; save labels are explicit. |
| Setup | Desktop diagnostics fit; navigation places the heading below the processing rail and updates aria-current. Final phone preview has no page overflow: all four tables scroll within 318px named regions, status words stay intact, and disclosures have one marker. Keyboard ArrowRight scrolls diagnostics. Re-check and reload retain the open optional section. |
| Costs | Desktop and phone metrics fit; recorded zero is $0.0000 while the no-data fixture shows dashes with an explanation. |
| Notifications | Phone dark panel fits; severity, message, and date/time are readable. No read-state mutation was performed. |
| Error | Phone dark missing-issue screen has a readable explanation and return action. |

## Focused automated evidence

- `./mvnw -q -Dtest=UiVisualFixturesTest -Dissuebot.visualOutput=/tmp/issuebot-ui-consistency test` — passed; 20 allowlisted routes and 29 page/fragment files exported.
- `node --test src/test/js/ui-fixture-server.test.cjs` — 6 passed; full/HX selection, exact query fixtures, cache busting/no-store assets, GET/HEAD behavior, mutation rejection, traversal rejection (including literal dot segments), and missing routes.

## Final review

Whole-branch review and one scoped fix review are complete. The final corrections address mobile Setup containment, duplicate disclosure markers, neutral approval scores, and history-safe toast dismiss buttons. The toast regression simulates repeated serialized-history restoration and asserts exactly one working dismiss control for success, warning, and error severities.

The first complete Java run found one stale integration assertion for the deliberately replaced empty-state message (1 failure among 1419 tests); its two text assertions were aligned with the approved wording while retaining badge/content consistency checks.

Final verification on the corrected branch:

- `./mvnw -q test`: 1419 tests across 139 suites; zero failures, errors, or skips.
- `node --test src/test/js/*.cjs src/test/js/*.js`: 34 passed; zero failures or skips.
- `git diff --check`: clean.

No production settings or issue processing were changed. No deployment is claimed by this document.

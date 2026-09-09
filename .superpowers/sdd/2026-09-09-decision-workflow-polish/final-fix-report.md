# Final fix report

## Changes

- Restored the Add Repository form's legacy Assist default by resetting `mode` to `APPROVAL_GATED`; the other former Assist defaults remain unchanged.
- Added a VM-backed browser regression that loads the real `app.js`, dispatches the actual Add button click path, and inspects the fields that the form submits.
- Scoped decision-workflow CSS assertions to their owning selector and media-query blocks, including the grouped sentence-case selector.
- Replaced `StartupValidator` with a mock in `UiVisualFixturesTest`, preventing its dummy GitHub token from causing an external startup probe.

## Verification

Red regression before the production fix:

```text
node --test src/test/js/app-repository-form.test.cjs
Result: FAIL, 0 passed / 1 failed. Actual submitted mode was AUTONOMOUS; expected APPROVAL_GATED.
```

Final focused verification:

```text
node --test src/test/js/app-repository-form.test.cjs src/test/js/repository-workflow.test.js && ./mvnw -q -Dtest=DecisionWorkflowCssTest,UiVisualFixturesTest test -Dissuebot.visualOutput=/tmp/issuebot-ui-review
Result: PASS (exit 0). JavaScript: 9 passed / 0 failed. Java: 4 passed / 0 failures / 0 errors / 0 skipped.
```

Exported fixture files:

```text
/tmp/issuebot-ui-review/dashboard.html
/tmp/issuebot-ui-review/inbox.html
/tmp/issuebot-ui-review/issue.html
/tmp/issuebot-ui-review/queue.html
/tmp/issuebot-ui-review/repositories.html
/tmp/issuebot-ui-review/css/style.css
/tmp/issuebot-ui-review/js/app.js
/tmp/issuebot-ui-review/js/needs-you.js
/tmp/issuebot-ui-review/js/repository-workflow.js
```

Final whitespace check:

```text
git diff --check
Result: PASS (exit 0, no output).
```

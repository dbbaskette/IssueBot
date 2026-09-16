# Release conventions

## Working scope and verification

- Consult documentation and skills for the boundary being changed, not a mandatory full-repository reading list. Use `docs/operator-workflow.md` for orchestration and `src/main/resources/prompts/guidance/` when changing agent guidance.
- Work in coherent functional increments. Use focused checks at milestones and the full relevant suite before completion; reuse successful evidence for an unchanged source tree and relevant environment instead of rerunning it solely for another skill or report. Strict red-green-refactor is optional unless requested.
- Continue authorized work through implementation and verification. Safe local tests using disposable fixtures may be run and failures caused by the change fixed without per-command approval. This does not authorize production access, paid integrations, destructive actions, or interruption of running workflows.
- Treat plan/spec approval as approval of that scope, not a requirement to ask again before each implementation step. Preserve explicit publication and deployment boundaries below.

## Release requirements

- Before closing a feature task, use the relevant acceptance group in `docs/testing.md` and extend coverage for new behavior. Reuse current-tree evidence; live checks require authorization and missing live evidence must be disclosed, not treated as passing. The runner never closes GitHub issues automatically.

- For each user-facing release, increment the project version in `pom.xml` and add a concise entry to `CHANGELOG.md`. Use a patch increment for fixes and a minor increment for features; group one requested change set into one release.
- The UI reads Maven build-info. Do not introduce a second hardcoded version. Keep the output filename `target/issuebot.jar` stable.
- Run relevant Java and JavaScript tests before release. Push, merge, and deploy only when the user authorizes those actions.
- Before restarting a deployment, pause automatic dispatch and check for active issues. Do not interrupt active workflows without explicit permission. The macOS installer uses immutable release jars so builds cannot overwrite a running JVM's classes.

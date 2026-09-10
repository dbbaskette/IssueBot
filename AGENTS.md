# Release conventions

- For each user-facing release, increment the project version in `pom.xml` and add a concise entry to `CHANGELOG.md`. Use a patch increment for fixes and a minor increment for features; group one requested change set into one release.
- The UI reads Maven build-info. Do not introduce a second hardcoded version. Keep the output filename `target/issuebot.jar` stable.
- Run relevant Java and JavaScript tests before release. Push, merge, and deploy only when the user authorizes those actions.
- Before restarting a deployment, pause automatic dispatch and check for active issues. Do not interrupt active workflows without explicit permission. The macOS installer uses immutable release jars so builds cannot overwrite a running JVM's classes.

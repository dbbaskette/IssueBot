# Managed agent skill bundle

IssueBot 0.8.0 includes the tested stage subset of Superpowers Custom **6.3.0-custom.1**.
It is a release dependency, not an operator-installed global plugin. Native and Docker
deployments consume identical classpath resources inside `target/issuebot.jar`.

## Loading and ownership

The shared harness boundary projects one selected resource into every planning,
implementation or review request, for either Codex or Claude. Utility/classification calls
receive no workflow skill text. Debugging and task-review roles have explicit mappings.
Current issue-level correction iterations use the implementation role and receive its
completion/diagnostic guidance as before.

This is **stage-prompt-v1**, an explicit prompt adaptation, not native plugin discovery.
Native-skill capability flags remain false. No CLI installation flags, personal config,
plugin hooks, external folders, network downloads, auth or model choices are changed.
Both providers already support this prompt transport; no new minimum CLI version is claimed.
Their existing availability and subscription checks still apply. Exact CLI version pinning
is a separate deployment policy, not a guarantee supplied by the skill bundle.

The adapter text makes IssueBot's stage and output contract authoritative over interactive
skill mechanics. Planning returns artifacts rather than writing files/asking questions;
implementation does not create a second workflow or publish commits; review does not
delegate another review. Existing trusted verification/CI, retry bounds and approval gates
are unchanged. An agent's test report never waives those gates.

## Integrity and provenance

Resources and the MIT license are in `src/main/resources/managed-skills/superpowers/`.
`manifest.properties` records the fork URL, exact source commit, projection schema, original
file paths and SHA-256 for every included resource. `ManagedSkillBundle` pins the manifest's
own SHA-256 and verifies all resources while constructing `CodingHarnessService`, before
dispatch can start. Missing or changed bytes prevent startup; there is no silent fallback.
This detects packaging drift, not a malicious party able to replace both code and resources.

Setup shows version, projection mode, source commit and manifest digest from this validated
in-memory identity. GET performs no new CLI/network probe. The application version remains
the Maven build-info version already shown by the layout.

## Upgrade procedure

1. Select and verify a tested commit of the maintained fork. The initial source commit is
   recorded in the manifest; it was committed locally, not published by this integration.
2. Review the relevant skill changes against IssueBot's stage ownership, response formats,
   subscription boundaries and verification cadence. Do not import the full interactive stack.
3. Replace the vendored subset byte-for-byte from those source files, retaining license and
   attribution. Regenerate file checksums/provenance, then deliberately update the pinned
   manifest digest in `ManagedSkillBundle`. No runtime auto-update is involved.
4. Run integrity/corruption tests, role/provider routing tests, the relevant full suites,
   and inspect the built jar. Increment IssueBot's version and record the bundled version.
5. Publish and deploy only when authorized. Pause dispatch and check active work before
   restarting. Roll back the complete immutable IssueBot release jar/image if necessary;
   do not swap skills underneath an active workflow. Database rollback has its own policy.

An end-to-end live provider run was not used for validation in this implementation. Tests
verify actual resource integrity, role selection, provider request delivery and application
rendering; they do not prove every model response will obey the guidance.

## Release verification

- Clean serial `./mvnw -q clean verify`: 1,750 tests, zero failures/errors/skips.
- JavaScript suite: 64 passed, zero failures/skips.
- All six jar resource files (four stage texts, license, manifest) matched source bytes.
- Synthetic real-MVC Setup rendering passed. Desktop and 390px browser checks confirmed
  readable provenance, checksum wrapping, and expanded state retained on reload.
- An earlier overlapping Maven/fixture run failed because recompilation temporarily removed
  classes under the other run. The clean serial result above supersedes that invalid run.
- No real provider task, Docker runtime, production dispatch, or deployment restart was run.

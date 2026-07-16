# Docker Deployment Design

Date: 2026-07-16

## Summary

IssueBot will move from a native process on `dbbaskette@home-services.local` to a Docker Compose deployment. Every deployment updates the host's existing IssueBot checkout with a safe fast-forward-only Git pull, builds a versioned IssueBot image on the host, pulls an independently released execution-runner image by immutable registry digest, restarts the stack, and verifies application health and essential integrations.

The Compose project belongs to the IssueBot repository. The execution runner is a separate project with its own release lifecycle. This design consumes the runner through a documented compatibility contract but does not design its API, disposable job containers, credentials, or Claude/Codex provider abstraction.

## Goals

- Deploy IssueBot repeatably to `home-services.local` through Docker Compose.
- Require a clean, fast-forward-only update of the existing checkout before every deployment.
- Preserve the current H2 database, configuration, cloned repositories, worktrees, and logs.
- Use a dedicated, restricted SSH deployment key.
- Build IssueBot from the checked-out source while consuming the runner as a pinned registry image.
- Make version identification, verification, diagnostics, and rollback routine.
- Establish a deployment path that future completed IssueBot work can use consistently.

## Non-goals

- Designing or implementing the reusable execution runner.
- Designing disposable per-job containers or their resource controls.
- Adding the Claude/Codex provider abstraction.
- Defining runner authentication, credential storage formats, or provider-specific behavior.
- Publishing the IssueBot image to a registry in the first deployment version.
- Introducing Kubernetes, Docker Swarm, or another orchestrator.
- Replacing the embedded H2 database with an external database.

## Current Runtime Constraints

IssueBot is a Spring Boot 3.4.2 application on Java 21 and listens on port `8090`. It currently runs through `run.sh`, which loads a repository-local `.env`, builds the JAR, kills the existing native process, and starts Java in the foreground.

Runtime state defaults to `${user.home}/.issuebot`:

- `issuebot.mv.db` is the file-backed H2 database.
- `config.yml` is imported as external Spring configuration.
- `repos/` contains managed repository clones and agent worktrees.
- `logs/` contains production-profile rolling application logs.

The application currently launches the `claude` executable directly with `ProcessBuilder`. An app-only container therefore cannot process issues until the independently designed execution-runner integration replaces that direct process boundary. The Docker deployment may be implemented in parallel with the runner project, but production cutover requires a compatible runner release and IssueBot integration.

The existing health endpoint includes external GitHub reachability and can report a degraded or down aggregate status when the application itself is alive. Deployment health must distinguish process liveness from operational readiness.

## Considered Approaches

### 1. Remote IssueBot build with pinned runner image

The host fast-forward-updates the IssueBot checkout, builds IssueBot locally, and pulls the runner by immutable registry digest.

This approach is selected. It satisfies the Git-first deployment requirement, avoids an immediate IssueBot image-publishing pipeline, and preserves an independent runner release lifecycle. Its main cost is build time and build tooling on the server.

### 2. Registry images for IssueBot and runner

The host updates the checkout for deployment metadata, then pulls both images. This is faster and more reproducible but requires coordinated IssueBot image publishing and safeguards against source/image version drift. It is a possible future evolution.

### 3. Build IssueBot and runner from separate host checkouts

The host updates and builds both projects. This avoids a registry but couples two repositories to each deployment, increases build time, and weakens independent runner versioning. It is rejected.

## Architecture

The IssueBot repository owns the production Compose file and deployment metadata. The stack contains:

- `issuebot`, built from the fast-forward-updated local checkout and tagged with the Git commit SHA;
- `execution-runner`, pulled from a registry and pinned by immutable digest; and
- a private Compose network used for IssueBot-to-runner traffic.

Only IssueBot port `8090` is published. It binds to the intended LAN address or to loopback when an existing reverse proxy is responsible for exposure. The runner has no host-published port.

The runner image has an independently managed version and release process. Compose records a readable runner release tag for operators but resolves it to an immutable digest. IssueBot and runner expose compatible protocol versions so deployment verification can reject an incompatible pair.

## Persistent Data and Filesystem Layout

The existing host `~/.issuebot` directory remains the authoritative IssueBot state and is bind-mounted at a stable application-home path in the IssueBot container. This preserves the database, external configuration, repositories, worktrees, and logs across container replacement.

The IssueBot container runs with the host operator's UID and GID. Preflight verifies that all required paths are readable and writable by that identity before the native process is stopped. The deployment never copies live H2 files while the native process is running and never invokes `docker compose down -v`.

Runner credentials, provider state, caches, and job data use runner-owned protected volumes or bind mounts. They are not stored under `~/.issuebot` and are never mounted into the IssueBot container.

## Image Construction and Pinning

IssueBot uses a multi-stage Dockerfile:

1. A pinned Maven/Java 21 builder image runs the test suite and packages the application; a test failure aborts deployment before shutdown.
2. A pinned minimal Java 21 runtime image receives only the executable JAR and required runtime files.
3. The runtime process uses a non-root user matching the configured host UID/GID.

The resulting image is tagged with the source Git SHA and includes OCI labels for the repository revision and build timestamp. Base images are pinned to specific versions and preferably digests. Mutable `latest` tags are forbidden for production inputs.

The deployment manifest records the IssueBot Git SHA, resulting image ID, runner tag and digest, deployment timestamp, backup path, and Compose project name. The current and previous known-good IssueBot images and manifests are retained.

## Secrets and Authentication

The deployment uses a dedicated SSH key. Its public key on `home-services.local` invokes an allowlisted deployment wrapper rather than an unrestricted shell. The wrapper accepts only `preflight`, `deploy`, `rollback`, `status`, and `logs`, validates arguments, and serializes state-changing operations with a deployment lock. The private key remains on the initiating machine and is never forwarded or mounted into a container.

Runtime secrets remain outside Git and image layers. A protected host environment file, readable only by the deployment owner, provides the GitHub token, optional dashboard credentials, webhook secret, and other IssueBot runtime secrets. Compose contains variable names and file locations, not values. Registry authentication is installed in the deployment account's protected Docker configuration or through an equivalently scoped credential helper.

Claude and Codex credentials belong exclusively to the independently designed runner. The IssueBot container does not receive them.

## Remote Prerequisites

The deployment preflight verifies:

- a supported Linux host architecture;
- Docker Engine and Docker Compose v2;
- permission for `dbbaskette` to use Docker without interactive elevation;
- Git and access to the existing IssueBot checkout and its upstream remote;
- registry authentication and availability of the pinned runner image for the host architecture;
- sufficient disk for the checkout, build cache, two application images, persistent data, logs, and backups;
- outbound DNS and HTTPS access required by GitHub and the configured providers;
- availability of the intended host binding for port `8090`; and
- identification of the current native IssueBot process and its startup mechanism.

Remote inspection was not possible during design because the available SSH session had no accepted key. These checks are therefore mandatory acceptance criteria rather than assumed host facts.

## Deployment Workflow

The initiating deployment script connects with the dedicated key and invokes the remote wrapper. The wrapper performs these steps:

1. Acquire an exclusive deployment lock.
2. Run remote prerequisite, disk, checkout, secret, and registry preflight checks.
3. Require the expected branch, a non-detached `HEAD`, a clean worktree, and a configured upstream.
4. Record the current deployment manifest and known-good versions.
5. Run `git fetch --prune`, then `git pull --ff-only`; refuse divergence, local commits that cannot fast-forward, or uncommitted changes.
6. Resolve and validate the configured runner digest and Compose configuration.
7. Build the SHA-tagged IssueBot image while the native instance remains available.
8. Pull the runner image by digest.
9. Stop the native IssueBot process gracefully and confirm that it released port `8090` and the H2 files.
10. Create a timestamped, integrity-checked backup of the closed H2 database and deployment metadata.
11. Start or recreate the Compose stack with the new versions.
12. Wait for liveness and readiness, then run post-deploy verification.
13. On success, disable the old native autostart mechanism and mark the new manifest known-good.
14. On failure, collect diagnostics and execute the safe rollback rules.

The first cutover treats the native startup mechanism carefully: it is disabled only after the containerized application passes verification. Subsequent deployments operate only on the Compose stack.

## Health and Post-deploy Verification

IssueBot provides separate health semantics:

- Liveness confirms that the JVM and web application respond. It does not depend on GitHub or runner availability.
- Readiness confirms that the database opened, Flyway completed, persistent paths are writable, and the configured runner endpoint is compatible and reachable.
- External integration details report GitHub and runner status without conflating temporary provider failure with a dead process.

The runner supplies its own container health check and a non-billable capability/authentication probe through its separately defined contract.

Post-deploy verification proves that:

1. Both Compose services reach their expected health states.
2. IssueBot reports the deployed Git SHA and the runner's compatible protocol version.
3. H2 opens without locking or corruption and Flyway reports no migration failure.
4. Configuration, repository, worktree, and log paths are accessible.
5. The dashboard responds through the intended interface with the expected authentication behavior.
6. GitHub token validation succeeds or reports an explicit degraded integration state.
7. IssueBot reaches the runner on the private network.
8. The runner's non-billable capability/authentication probe succeeds.
9. Recent logs contain no startup exception, permission error, database lock, missing executable, incompatible protocol, or exposed secret.
10. The native process is absent and only the containerized IssueBot owns port `8090`.

A deployment is successful only after all required checks pass. Degraded external integrations are reported distinctly and fail deployment when they prevent IssueBot's core agent workflow.

## Rollback and Database Safety

Before cutover, deployment records the current versions and backs up the closed H2 database. A failed deployment captures Compose status, health details, and sanitized logs before changing state again.

Application rollback reuses the previous deployment manifest, previous IssueBot image or source SHA, and previous runner digest. It does not pull an arbitrary branch state or mutable image tag.

Flyway migrations may be forward-only. Automatic rollback first determines whether the previous application version is compatible with the migrated schema. If compatibility is known, it restarts the previous versions without replacing data. If compatibility is unknown or false, the wrapper stops and reports that deliberate database restoration is required. Database restoration is never automatic because it can discard writes accepted after migration.

An operator-approved data rollback restores the pre-deploy H2 backup only while all IssueBot processes are stopped, preserves the failed database for investigation, verifies the restored database, and then starts the previous application version.

## Runtime Security

Both services run as non-root users with `no-new-privileges`. Compose drops all Linux capabilities unless a specific requirement is documented. Root filesystems are read-only where supported, with explicit writable persistent mounts and temporary filesystems. Services receive CPU, memory, and process limits appropriate to their roles.

The Compose network is private, and the runner is reachable only by IssueBot. Containers do not receive the deployment SSH key, host Docker socket, repository checkout, or unrelated host credentials. IssueBot receives only its persistent home and required runtime configuration.

Graceful stop timeouts allow IssueBot to finish application shutdown and release H2 cleanly. Restart policies recover from host reboot but are bounded during deployment verification so a broken release does not loop indefinitely and hide the original failure.

## Logs and Operations

`docker compose logs` is the primary live operational view. Docker's logging driver uses bounded rotation. The existing IssueBot production file logs remain in the persistent `~/.issuebot/logs` mount with their configured rotation for historical diagnosis.

The remote wrapper exposes allowlisted status and recent-log commands. Deployment output includes timestamps, deployment identifiers, Git SHAs, image IDs, health transitions, and backup paths. It never prints environment values, authorization headers, tokens, or credential file contents.

Routine operations include:

- viewing service, image, and health status;
- following or tailing sanitized service logs;
- showing the current deployment manifest;
- checking persistent-data and image disk usage;
- deploying a new fast-forwarded revision; and
- rolling back to the previous known-good manifest.

## Failure Handling

- A dirty, detached, divergent, or unexpected checkout stops before build or shutdown.
- A failed fast-forward-only pull leaves the running deployment unchanged.
- A failed IssueBot build or runner pull leaves the running deployment unchanged.
- Failure to stop the native process or release the H2 database aborts cutover and does not start a competing container.
- Failure to create or verify the database backup aborts cutover.
- Failed health or functional verification captures diagnostics and attempts only schema-safe application rollback.
- A failed rollback leaves services stopped or on the last verifiably safe version and prints explicit recovery instructions.
- The deployment lock prevents concurrent deploy or rollback operations.
- Secrets are sanitized from all failure output.

## Testing

### Image and Compose tests

- Build the multi-stage IssueBot image on the target architecture.
- Confirm the runtime image contains the application but no build tools, source checkout, `.env`, or credentials.
- Validate Compose with all required variables and fail clearly when they are absent.
- Confirm images and base images are pinned according to policy.
- Verify non-root execution, dropped capabilities, read-only filesystems, limits, and private networking.

### Deployment workflow tests

- Deploy from a clean checkout with a valid fast-forward.
- Refuse dirty, detached, divergent, and unexpected-branch states.
- Leave the running version untouched after pull, build, registry, or preflight failures.
- Prevent concurrent deployments with the lock.
- Verify first cutover stops and disables the native service only after successful container verification.
- Recover automatically after a host reboot.

### Persistence and rollback tests

- Migrate a copy of the existing native `~/.issuebot` state into the container without changing ownership or losing data.
- Verify database, configuration, repositories, worktrees, and logs survive recreation.
- Prove H2 is never opened concurrently by native and container processes.
- Roll back application and runner versions with a schema-compatible database.
- Require explicit database restoration for an incompatible forward migration.
- Verify backup integrity, failed-database preservation, and restored-database startup.

### Health and security tests

- Distinguish liveness from GitHub or runner degradation.
- Fail readiness for database, migration, persistent-path, or required-runner failures.
- Verify the authenticated dashboard, GitHub validation, runner connectivity, and protocol compatibility.
- Verify deployment and application logs redact all configured secret forms.
- Confirm neither service can access the deployment key or unrelated host paths.

## Delivery Boundary and Sequencing

This deployment project can add Docker assets, health semantics, the restricted deployment workflow, persistence handling, and verification independently. Production cutover is gated on a separately approved execution-runner project publishing a compatible pinned image and IssueBot replacing direct local `claude` execution with that runner contract.

The runner project remains responsible for disposable execution containers, Claude/Codex selection, credential isolation, provider authentication, and its API. This specification neither chooses nor constrains those internal designs beyond requiring a versioned compatibility and health contract suitable for Compose deployment.

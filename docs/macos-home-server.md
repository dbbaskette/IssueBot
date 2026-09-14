# macOS home-server deployment

The current home server runs Docker Desktop and Cloudflare Tunnel, while
IssueBot runs as a native macOS `launchd` service. This is the same topology as
BlogForge: `cloudflared` reaches the host through `host.docker.internal`.

Native execution is required until the separate `codex-cli-provider` image is
available because IssueBot invokes the authenticated Codex or Claude CLI on the
host. The dashboard is still exposed only through the existing Cloudflare
Tunnel hostname, `issuebot.baskettecase.com`.

The non-secret host topology is committed in
`deploy/macos/home-server.env`. Change that file when the checkout, Java,
home-server, hostname, label, or port changes. The installer renders the
LaunchAgent from the committed plist template.

`ISSUEBOT_CODEX_NETWORK_ALLOWED_REPOSITORIES` in that file is an exact,
comma-separated `owner/repo` allowlist for outbound network access during Codex
implementation turns (for example, Maven dependency downloads). It is empty by
default. Planning, review, and all non-allowlisted repositories remain
network-restricted. Restart IssueBot after changing the allowlist.

The protected runtime file is configured by `ISSUEBOT_RUNTIME_ENV` and must
remain outside the repository with mode `0600`. Start from the committed
`deploy/macos/runtime.env.example`:

```dotenv
GITHUB_TOKEN=replace_me
ISSUEBOT_USERNAME=admin
ISSUEBOT_PASSWORD=replace_me
ISSUEBOT_WEBHOOK_SECRET=replace_me
```

For a manual deploy of the **current checkout** on `home-services.local`, run:

```bash
cd /Users/dbbaskette/Projects/IssueBot
./deploy/macos/deploy-current.sh
```

This does not pull or switch branches. It first refuses active work, then runs
the Java and JavaScript suites, pauses automatic dispatch, and checks again
before restarting the service.
After the installer reports ready, it verifies the UI version and restores a
previously running queue; a queue that was already paused or stopped stays that
way. If a check fails after pausing, the queue stays paused for inspection.
Run this only from code you intend to serve. It does not touch the Cloudflare
Tunnel, which needs recreation only when its own configuration changes.

For low-level service installation or troubleshooting, use:

```bash
./deploy/macos/install-service.sh
# Or, after a separately verified build:
./deploy/macos/install-service.sh --skip-build
cd ../home-server
docker compose up -d --force-recreate cloudflared
```

The installer waits for launchd to confirm the prior job is fully removed before
bootstrapping the replacement. If removal does not complete within 30 seconds,
deployment stops without racing a second registration attempt.

Verify both sides:

```bash
curl --fail http://127.0.0.1:8090/actuator/health/readiness
curl --fail https://issuebot.baskettecase.com/actuator/health/readiness
```

The dashboard hostname should be protected by Cloudflare Access and by
IssueBot's own username/password. If GitHub webhooks are enabled, configure a
path-specific Access bypass only for `/webhooks/github`; the endpoint verifies
the GitHub HMAC signature using `ISSUEBOT_WEBHOOK_SECRET`.

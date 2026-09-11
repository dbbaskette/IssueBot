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

The protected runtime file is configured by `ISSUEBOT_RUNTIME_ENV` and must
remain outside the repository with mode `0600`. Start from the committed
`deploy/macos/runtime.env.example`:

```dotenv
GITHUB_TOKEN=replace_me
ISSUEBOT_USERNAME=admin
ISSUEBOT_PASSWORD=replace_me
ISSUEBOT_WEBHOOK_SECRET=replace_me
```

Install or redeploy the service with:

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

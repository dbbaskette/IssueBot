#!/usr/bin/env bash
set -euo pipefail

cd "$(git rev-parse --show-toplevel)"

command -v docker >/dev/null
test -f compose.yaml

fixture_dir="$(mktemp -d)"
trap 'rm -rf "$fixture_dir"' EXIT
mkdir -p "$fixture_dir/issuebot-home"
: >"$fixture_dir/runtime.env"

export APP_UID=1000
export APP_GID=1000
export ISSUEBOT_GIT_SHA=0123456789abcdef0123456789abcdef01234567
export BUILD_DATE=2026-07-16T12:00:00Z
export ISSUEBOT_HOME="$fixture_dir/issuebot-home"
export ISSUEBOT_BIND_ADDRESS=127.0.0.1
export ISSUEBOT_SECRET_ENV="$fixture_dir/runtime.env"
export CODEX_CLI_PROVIDER_IMAGE='registry.example.test/codex-cli-provider@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa'
export CODEX_CLI_PROVIDER_PROTOCOL_VERSION=1

docker compose -f compose.yaml config --format json >"$fixture_dir/config.json"

python3 - "$fixture_dir/config.json" "$ISSUEBOT_HOME" <<'PY'
import json
import sys

config_path, issuebot_home = sys.argv[1:]
with open(config_path, encoding="utf-8") as stream:
    config = json.load(stream)

services = config.get("services", {})
expected = {"issuebot", "codex-cli-provider"}
assert set(services) == expected, f"expected exactly {sorted(expected)}, got {sorted(services)}"

issuebot = services["issuebot"]
provider = services["codex-cli-provider"]

ports = issuebot.get("ports", [])
assert len(ports) == 1, f"IssueBot must publish one port, got {ports!r}"
port = ports[0]
assert port.get("host_ip") == "127.0.0.1", f"unexpected host IP: {port!r}"
assert port.get("published") == "8090" and port.get("target") == 8090, f"unexpected port mapping: {port!r}"
assert not provider.get("ports"), "provider must not publish ports"

assert "build" not in provider, "provider must be pulled, never built by this project"
assert "@sha256:" in provider.get("image", ""), "provider image must be immutable"

for name, service in services.items():
    assert service.get("read_only") is True, f"{name} root filesystem must be read-only"
    assert service.get("restart") == "unless-stopped", f"{name} restart policy is not bounded"
    assert "no-new-privileges:true" in service.get("security_opt", []), f"{name} lacks no-new-privileges"
    assert service.get("cap_drop") == ["ALL"], f"{name} must drop all capabilities"
    assert int(service.get("pids_limit", 0)) > 0, f"{name} lacks a PID limit"
    assert float(service.get("cpus", 0)) > 0, f"{name} lacks a CPU limit"
    assert int(service.get("mem_limit", 0)) > 0, f"{name} lacks a memory limit"
    assert "/tmp" in service.get("tmpfs", []), f"{name} lacks a writable /tmp tmpfs"
    assert set(service.get("networks", {})) == {"backend"}, f"{name} must use only backend"
    logging = service.get("logging", {})
    assert logging.get("driver") == "json-file", f"{name} must use json-file logging"
    options = logging.get("options", {})
    assert options.get("max-size") == "10m" and options.get("max-file") == "5", f"{name} logs are not rotated"
    for volume in service.get("volumes", []):
        source = str(volume.get("source", "")).lower()
        target = str(volume.get("target", "")).lower()
        assert "docker.sock" not in source + target, f"{name} mounts the Docker socket"
        assert "/.ssh" not in source + target and not source.endswith("/ssh"), f"{name} mounts SSH state"

mounts = issuebot.get("volumes", [])
assert any(
    mount.get("type") == "bind"
    and mount.get("source") == issuebot_home
    and mount.get("target") == "/home/issuebot/.issuebot"
    for mount in mounts
), "ISSUEBOT_HOME must bind-mount to /home/issuebot/.issuebot"

assert "postgres" not in services and "postgresql" not in services, "PostgreSQL is out of scope"
assert "minio" not in services, "MinIO is out of scope"
PY

printf 'compose-test: PASS\n'

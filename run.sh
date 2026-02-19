#!/usr/bin/env bash
# IssueBot — run script
# Kills any existing instance, then starts fresh on port 8090
# Usage: ./run.sh [--cleanup]

set -e

# ── Load .env if present ─────────────────────────────────────────
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
if [ -f "$SCRIPT_DIR/.env" ]; then
    set -a
    source "$SCRIPT_DIR/.env"
    set +a
    echo "> Loaded environment from .env"
fi

PORT=8090
APP_NAME="issuebot"
ISSUEBOT_HOME="${HOME}/.issuebot"

# ── Handle --cleanup flag ──────────────────────────────────────────
if [ "$1" = "--cleanup" ]; then
    echo ""
    echo "  ⚠  This will permanently delete:"
    echo "     • H2 database    (${ISSUEBOT_HOME}/issuebot.mv.db)"
    echo "     • Cloned repos   (${ISSUEBOT_HOME}/repos/)"
    echo ""
    echo "  All tracked repositories, issues, events, and local clones will be lost."
    echo ""
    read -r -p "  Are you sure? [y/N] " confirm
    if [[ "$confirm" =~ ^[Yy]$ ]]; then
        echo "> Removing database..."
        rm -f "${ISSUEBOT_HOME}/issuebot.mv.db" "${ISSUEBOT_HOME}/issuebot.trace.db"
        echo "> Removing cloned repos..."
        rm -rf "${ISSUEBOT_HOME}/repos"
        echo "> Cleanup complete. Starting fresh."
        echo ""
    else
        echo "> Cleanup cancelled."
        exit 0
    fi
fi

# ── Kill existing IssueBot processes ───────────────────────────────
echo "> Checking for existing IssueBot processes..."
PIDS=$(pgrep -f "$APP_NAME" 2>/dev/null || true)
if [ -n "$PIDS" ]; then
    echo "> Stopping existing processes: $PIDS"
    kill $PIDS 2>/dev/null || true
    sleep 2
    # Force kill if still running
    REMAINING=$(pgrep -f "$APP_NAME" 2>/dev/null || true)
    if [ -n "$REMAINING" ]; then
        echo "> Force killing: $REMAINING"
        kill -9 $REMAINING 2>/dev/null || true
    fi
fi

# Kill anything else on port 8090
PORT_PID=$(lsof -ti :$PORT 2>/dev/null || true)
if [ -n "$PORT_PID" ]; then
    echo "> Killing process on port $PORT: $PORT_PID"
    kill $PORT_PID 2>/dev/null || true
    sleep 1
fi

# ── Build & start ─────────────────────────────────────────────────
echo "> Building..."
./mvnw package -DskipTests -q

echo "> Starting IssueBot on port $PORT"
exec java -jar target/issuebot-0.1.0-SNAPSHOT.jar

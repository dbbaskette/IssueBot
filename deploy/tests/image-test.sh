#!/usr/bin/env bash
set -euo pipefail

cd "$(git rev-parse --show-toplevel)"

grep -Eq '^FROM .*@sha256:[0-9a-f]{64} AS build$' Dockerfile
grep -Eq '^FROM .*@sha256:[0-9a-f]{64}$' Dockerfile
grep -Fq 'USER issuebot' Dockerfile
grep -Fq 'groupadd --non-unique' Dockerfile
grep -Fq 'useradd --non-unique' Dockerfile
grep -Fq '/actuator/health/liveness' Dockerfile
grep -Fq '.env' .dockerignore
grep -Fq '.git' .dockerignore
grep -Fq '.issuebot' .dockerignore

printf 'image-test: PASS\n'

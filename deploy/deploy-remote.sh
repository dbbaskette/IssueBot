#!/usr/bin/env bash
set -Eeuo pipefail

DEPLOY_HOST="${DEPLOY_HOST:-dbbaskette@home-services.local}"
DEPLOY_KEY="${DEPLOY_KEY:-${HOME}/.ssh/issuebot_deploy_ed25519}"

usage() {
  printf 'Usage: %s {preflight|deploy|rollback|status|logs {issuebot|codex-cli-provider} {1-500}}\n' "$0" >&2
  exit 2
}

(( $# >= 1 && $# <= 3 )) || usage
case "$1:$#" in
  preflight:1|deploy:1|rollback:1|status:1) ;;
  logs:3)
    case "$2" in
      issuebot|codex-cli-provider) ;;
      *) usage ;;
    esac
    [[ "$3" =~ ^[0-9]{1,3}$ ]] || usage
    (( 10#$3 >= 1 && 10#$3 <= 500 )) || usage
    ;;
  *) usage ;;
esac

[[ "$DEPLOY_HOST" =~ ^[A-Za-z0-9._-]+@[A-Za-z0-9._-]+$ ]] || {
  printf 'ERROR: DEPLOY_HOST must be user@host\n' >&2
  exit 1
}
[[ -f "$DEPLOY_KEY" && ! -L "$DEPLOY_KEY" ]] || {
  printf 'ERROR: deployment private key is missing or unsafe: %s\n' "$DEPLOY_KEY" >&2
  exit 1
}

exec ssh \
  -i "$DEPLOY_KEY" \
  -o IdentitiesOnly=yes \
  -o BatchMode=yes \
  -o ForwardAgent=no \
  -o ConnectTimeout=10 \
  -o StrictHostKeyChecking=yes \
  "$DEPLOY_HOST" "$@"

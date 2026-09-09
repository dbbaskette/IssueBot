#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

DEPLOY_HOST="${DEPLOY_HOST:-dbbaskette@home-services.local}"
if (( $# != 1 )); then
  printf 'Usage: %s ED25519_PUBLIC_KEY_FILE\n' "$0" >&2
  exit 2
fi

PUBLIC_KEY_FILE="$1"
[[ -f "$PUBLIC_KEY_FILE" && ! -L "$PUBLIC_KEY_FILE" ]] || {
  printf 'ERROR: public key file is missing or unsafe: %s\n' "$PUBLIC_KEY_FILE" >&2
  exit 1
}
[[ "$DEPLOY_HOST" =~ ^[A-Za-z0-9._-]+@[A-Za-z0-9._-]+$ ]] || {
  printf 'ERROR: DEPLOY_HOST must be user@host\n' >&2
  exit 1
}

key_data="$(awk '$1 == "ssh-ed25519" { print $2; exit }' "$PUBLIC_KEY_FILE")"
[[ "$key_data" =~ ^[A-Za-z0-9+/]+={0,2}$ ]] || {
  printf 'ERROR: file does not contain a valid Ed25519 public key\n' >&2
  exit 1
}
key_type="$(ssh-keygen -lf "$PUBLIC_KEY_FILE" 2>/dev/null | awk '{ print $4 }')"
[[ "$key_type" == '(ED25519)' ]] || {
  printf 'ERROR: public key is not Ed25519\n' >&2
  exit 1
}

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
wrapper="$script_dir/issuebot-deploy"
[[ -f "$wrapper" && ! -L "$wrapper" ]] || {
  printf 'ERROR: local forced-command wrapper is missing or unsafe\n' >&2
  exit 1
}

ssh_options=(
  -o ForwardAgent=no
  -o ConnectTimeout=10
  -o StrictHostKeyChecking=yes
)

if ! ssh "${ssh_options[@]}" "$DEPLOY_HOST" bash -s -- "$key_data" <<'REMOTE_CHECK'
set -Eeuo pipefail
key_data="$1"
authorized_keys="$HOME/.ssh/authorized_keys"
if [[ -f "$authorized_keys" ]] && awk -v wanted="$key_data" '
  { for (i = 1; i <= NF; i++) if ($i == wanted) { found = 1; exit } }
  END { exit !found }
' "$authorized_keys"; then
  printf 'ERROR: the public-key fingerprint is already authorized\n' >&2
  exit 1
fi
REMOTE_CHECK
then
  exit 1
fi

# Force bash remotely instead of relying on the account's login shell, and use
# HOME rather than a hard-coded path. This also separates directory creation
# from the upload so a failed setup cannot produce a misleading mktemp error.
ssh "${ssh_options[@]}" "$DEPLOY_HOST" bash -s <<'REMOTE_PREPARE_WRAPPER'
set -Eeuo pipefail
umask 077
install_dir="$HOME/.local/libexec"
mkdir -p "$install_dir"
test -d "$install_dir" && test ! -L "$install_dir"
REMOTE_PREPARE_WRAPPER

scp \
  -o ForwardAgent=no \
  -o ConnectTimeout=10 \
  -o StrictHostKeyChecking=yes \
  "$wrapper" "$DEPLOY_HOST:.local/libexec/issuebot-deploy.new"

ssh "${ssh_options[@]}" "$DEPLOY_HOST" bash -s <<'REMOTE_INSTALL_WRAPPER'
set -Eeuo pipefail
umask 077
install_dir="$HOME/.local/libexec"
candidate="$install_dir/issuebot-deploy.new"
target="$install_dir/issuebot-deploy"
test -f "$candidate" && test ! -L "$candidate"
chmod 0755 "$candidate"
mv -f "$candidate" "$target"
REMOTE_INSTALL_WRAPPER

authorized_key_line="$(printf 'restrict,command="%s/.local/libexec/issuebot-deploy" ssh-ed25519 %s issuebot-deploy\n' '$HOME' "$key_data")"
printf '%s\n' "$authorized_key_line" | ssh "${ssh_options[@]}" "$DEPLOY_HOST" '
set -Eeuo pipefail
umask 077
mkdir -p "$HOME/.ssh"
chmod 0700 "$HOME/.ssh"
touch "$HOME/.ssh/authorized_keys"
chmod 0600 "$HOME/.ssh/authorized_keys"
cat >>"$HOME/.ssh/authorized_keys"
'

printf 'Installed restricted deployment wrapper and key on %s\n' "$DEPLOY_HOST"

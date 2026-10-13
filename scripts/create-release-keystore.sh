#!/usr/bin/env bash
# Creates (or reuses) the MediaSync release signing key and stores it as secrets in the protected
# 'release' environment on GitHub. Run it yourself: it asks for the password, never writes it to
# disk and never passes it on a command line (where other processes could see it).
#
# Usage: scripts/create-release-keystore.sh [path/to/mediasync-release.jks]
# Keep one keystore file per app, so a leak in one project can't expose another app's key.
set -euo pipefail

KEYSTORE="${1:-mediasync-release.jks}"
REPO="${MEDIASYNC_REPO:-danyk20/mediasync}"
ALIAS="mediasync"

for tool in keytool gh base64; do
    command -v "$tool" >/dev/null || { echo "Missing tool: $tool" >&2; exit 1; }
done
gh auth status >/dev/null 2>&1 || { echo "Log in to GitHub first: gh auth login" >&2; exit 1; }

if [[ -e "$KEYSTORE" ]]; then
    echo "Using the existing key: $KEYSTORE"
    read -rs -p "Keystore password: " MEDIASYNC_KEYSTORE_PASSWORD; echo
else
    echo "Creating a new signing key in $KEYSTORE"
    read -rs -p "Choose a keystore password (at least 12 characters): " MEDIASYNC_KEYSTORE_PASSWORD; echo
    read -rs -p "Repeat the password: " repeat; echo
    [[ "$MEDIASYNC_KEYSTORE_PASSWORD" == "$repeat" ]] || { echo "Passwords don't match." >&2; exit 1; }
    unset repeat
    (( ${#MEDIASYNC_KEYSTORE_PASSWORD} >= 12 )) || { echo "Password is too short." >&2; exit 1; }
    export MEDIASYNC_KEYSTORE_PASSWORD
    # Google Play requires validity beyond 22 October 2033; 30 years is plenty.
    keytool -genkeypair -keystore "$KEYSTORE" -storetype PKCS12 -alias "$ALIAS" \
        -keyalg RSA -keysize 4096 -validity 10950 \
        -storepass:env MEDIASYNC_KEYSTORE_PASSWORD -dname "CN=MediaSync, O=The MediaSync Authors"
    chmod 600 "$KEYSTORE"
fi

# Fails here, before anything is uploaded, if the password doesn't open the key.
export MEDIASYNC_KEYSTORE_PASSWORD
keytool -list -keystore "$KEYSTORE" -storepass:env MEDIASYNC_KEYSTORE_PASSWORD -alias "$ALIAS" >/dev/null \
    || { echo "The password doesn't open the '$ALIAS' key in $KEYSTORE." >&2; exit 1; }

echo "Uploading secrets to the 'release' environment of ${REPO}..."
base64 < "$KEYSTORE" | gh secret set MEDIASYNC_KEYSTORE_BASE64 --repo "$REPO" --env release
# printf is a shell builtin, so the password never appears in the process list.
printf '%s' "$MEDIASYNC_KEYSTORE_PASSWORD" | gh secret set MEDIASYNC_KEYSTORE_PASSWORD --repo "$REPO" --env release
# PKCS12 keystores use the same password for the key.
printf '%s' "$MEDIASYNC_KEYSTORE_PASSWORD" | gh secret set MEDIASYNC_KEY_PASSWORD --repo "$REPO" --env release
gh secret set MEDIASYNC_KEY_ALIAS --repo "$REPO" --env release --body "$ALIAS"
unset MEDIASYNC_KEYSTORE_PASSWORD

echo
gh secret list --repo "$REPO" --env release
echo
echo "Done. Now:"
echo "  1. Save the password in your password manager (if you haven't yet)."
echo "  2. Back up $KEYSTORE to a second safe place. Losing it means installed copies can't be updated."

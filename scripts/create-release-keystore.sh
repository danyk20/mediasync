#!/usr/bin/env bash
# Creates the MediaSync release signing key on this machine and prints the GitHub commands to store it.
# The keystore file and passwords never leave this machine except as encrypted GitHub secrets.
set -euo pipefail

KEYSTORE="${1:-mediasync-release.jks}"
ALIAS="mediasync"

if [[ -e "$KEYSTORE" ]]; then
  echo "Refusing to overwrite existing $KEYSTORE" >&2
  exit 1
fi

read -r -s -p "Keystore password (min. 12 characters): " PASSWORD
echo
read -r -s -p "Repeat password: " PASSWORD_AGAIN
echo
if [[ "$PASSWORD" != "$PASSWORD_AGAIN" || ${#PASSWORD} -lt 12 ]]; then
  echo "Passwords don't match or are shorter than 12 characters." >&2
  exit 1
fi

# Google Play requires a validity beyond 22 October 2033; 30 years is plenty.
keytool -genkeypair -v \
  -keystore "$KEYSTORE" -storetype PKCS12 \
  -alias "$ALIAS" -keyalg RSA -keysize 4096 -validity 10950 \
  -storepass "$PASSWORD" -keypass "$PASSWORD" \
  -dname "CN=MediaSync, O=The MediaSync Authors"

cat <<EOF

Created $KEYSTORE. Back it up in two safe places now: losing it means existing installs can't be updated.

Store it in the GitHub 'release' environment (run from the repository directory):

  base64 < "$KEYSTORE" | gh secret set MEDIASYNC_KEYSTORE_BASE64 --env release
  gh secret set MEDIASYNC_KEYSTORE_PASSWORD --env release   # paste the password
  gh secret set MEDIASYNC_KEY_PASSWORD --env release        # paste the same password
  gh secret set MEDIASYNC_KEY_ALIAS --env release --body "$ALIAS"
EOF

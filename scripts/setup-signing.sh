#!/usr/bin/env bash
# One-time: create Sloota's release signing key and give it to GitHub Actions.
# Every release must be signed with this same key, or self-updates stop installing. Keep a backup.
set -euo pipefail

REPO="${REPO:-pridgey/Sloota}"
DIR="${DIR:-$HOME/keystores/sloota}"
KEYTOOL="${KEYTOOL:-keytool}"

mkdir -p "$DIR" && chmod 700 "$DIR"
if [[ -f "$DIR/release.jks" ]]; then
  echo "Using the existing key in $DIR"
  PW=$(sed -n 's/^store password: //p' "$DIR/credentials.txt")
else
  PW=$(openssl rand -base64 30 | tr -dc 'A-Za-z0-9' | head -c 32)
  "$KEYTOOL" -genkeypair -keystore "$DIR/release.jks" -storetype PKCS12 -alias sloota \
    -keyalg RSA -keysize 4096 -validity 36500 -storepass "$PW" -keypass "$PW" -dname "CN=Sloota"
  printf 'Sloota release signing key. Losing it means everyone has to reinstall.\nkeystore: %s/release.jks\nalias: sloota\nstore password: %s\nkey password: %s\n' \
    "$DIR" "$PW" "$PW" > "$DIR/credentials.txt"
  chmod 600 "$DIR/release.jks" "$DIR/credentials.txt"
  echo "Created $DIR/release.jks"
fi

base64 -w0 "$DIR/release.jks" | gh secret set KEYSTORE_BASE64 -R "$REPO"
gh secret set KEYSTORE_PASSWORD -R "$REPO" --body "$PW"
gh secret set KEY_PASSWORD -R "$REPO" --body "$PW"
gh secret set KEY_ALIAS -R "$REPO" --body "sloota"
echo "Secrets set on $REPO:"
gh secret list -R "$REPO"

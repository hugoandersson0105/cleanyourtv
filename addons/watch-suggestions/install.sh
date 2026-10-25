#!/usr/bin/env bash
# Build Watch Suggestions and install it on the TV. The keys are optional here: leave them out and the app asks
# for them on its first start (type them with the remote), or hand them over from this computer:
#   TV_HOST=<tv ip> TMDB_API_KEY=<key> [TRAKT_CLIENT_ID=<id>] addons/watch-suggestions/install.sh
set -euo pipefail
: "${TV_HOST:?set TV_HOST to the TV IP address}"
cd "$(dirname "$0")"
TOOLS="$(cd ../../tools && pwd)"
PY=$TOOLS/venv/bin/python
[ -x "$PY" ] || PY=$TOOLS/venv/Scripts/python.exe
export MSYS_NO_PATHCONV=1
PKG=io.github.alessandromata.watchsuggestions
# Script paths stay relative: with MSYS_NO_PATHCONV, Git Bash would hand Windows Python a /c/... path.
tv() { "$PY" -I ../../tools/tv.py "$@"; }

# The keys end up in a shell command on the TV: refuse anything that is not a plain key.
for v in "${TMDB_API_KEY:-}" "${TRAKT_CLIENT_ID:-}"; do
  case "$v" in *[!A-Za-z0-9_-]*) echo "ERROR: a key may only hold letters, digits, - and _" >&2; exit 1 ;; esac
done

[ -f local.properties ] || { [ -n "${ANDROID_HOME:-}" ] && echo "sdk.dir=${ANDROID_HOME//\\//}" > local.properties; } || true
if [ -n "${JAVA_HOME:-}" ]; then export PATH="$JAVA_HOME/bin:$PATH"; fi
./gradlew assembleRelease -q --console=plain

"$PY" -I ../../tools/b64push.py app/build/outputs/apk/release/app-release.apk /data/local/tmp/watch-suggestions.apk
OUT=$(tv 'pm install -r /data/local/tmp/watch-suggestions.apk; rm -f /data/local/tmp/watch-suggestions.apk')
echo "$OUT"
case "$OUT" in *Success*) ;; *) echo "ERROR: pm install failed" >&2; exit 1 ;; esac

if [ -n "${TMDB_API_KEY:-}" ]; then
  tv "am start -n $PKG/.SettingsActivity --es tmdb_key $TMDB_API_KEY --es trakt_id '${TRAKT_CLIENT_ID:-}' >/dev/null; echo keys-saved"
fi
tv "am start -n $PKG/.MainActivity >/dev/null; echo opened"
echo "Installed. Watch Suggestions is open on the TV; press Home and answer Show card for the card."

#!/bin/bash
# Publit for Android shares its translations (815 strings, 15 languages) with the iOS app.
# The string catalog (>1 MB) is fetched from the iOS repo instead of being duplicated here.
# Usage: scripts/fetch-ios-strings.sh [git ref]   (default: the iOS commit this port is based on)
set -e
REF=${1:-$(cat "$(dirname "$0")/../upstream/IOS_COMMIT" 2>/dev/null || echo main)}
DEST="$(dirname "$0")/../Sources/Publit/Resources/Localizable.xcstrings"
curl -fsSL "https://raw.githubusercontent.com/elija-7580/Publit/$REF/Sources/Shared/Localizable.xcstrings" -o "$DEST"
echo "Localizable.xcstrings ($REF) -> $DEST"

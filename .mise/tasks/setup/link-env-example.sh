#!/usr/bin/env bash
#MISE description="Symlink app/src/main/resources/env-example → root .env.example"
set -euo pipefail

cd "$(dirname "$0")/../../.."

TARGET="app/src/main/resources/env-example"
SOURCE="../../../../.env.example"

if [ -L "$TARGET" ]; then
    echo "✓ Symlink already exists: $TARGET → $(readlink "$TARGET")"
    exit 0
fi

if [ -f "$TARGET" ]; then
    echo "⚠ Removing regular file: $TARGET"
    rm "$TARGET"
fi

ln -s "$SOURCE" "$TARGET"
echo "✓ Created symlink: $TARGET → $SOURCE"

#!/usr/bin/env bash
set -euo pipefail

# Discover IDE versions from the ide.*.platformVersion entries in gradle.properties
VERSIONS=$(grep -o '^ide\.[0-9]*\.platformVersion' gradle.properties | sed 's/^ide\.\([0-9]*\)\.platformVersion/\1/' | sort -n)

echo "=== Verifying all IDE versions: $(echo $VERSIONS | tr '\n' ' ')==="
echo ""

FAILED=()

for VERSION in $VERSIONS; do
    echo "--- Building for IDE $VERSION ---"
    if ./gradlew clean buildPlugin -PpluginIdeVersionMajor="$VERSION" --stacktrace; then
        echo "✓ IDE $VERSION: OK"
    else
        echo "✗ IDE $VERSION: FAILED"
        FAILED+=("$VERSION")
    fi
    echo ""
done

# Also verify the default (open-ended) build
echo "--- Building default (no IDE version) ---"
if ./gradlew clean buildPlugin --stacktrace; then
    echo "✓ Default: OK"
else
    echo "✗ Default: FAILED"
    FAILED+=("default")
fi

echo ""
echo "=== Summary ==="
if [ ${#FAILED[@]} -eq 0 ]; then
    echo "All builds passed."
else
    echo "Failed: ${FAILED[*]}"
    exit 1
fi

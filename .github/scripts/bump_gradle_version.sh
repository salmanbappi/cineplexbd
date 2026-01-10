#!/bin/bash
GRADLE_FILE="app/build.gradle"

if [ ! -f "$GRADLE_FILE" ]; then
    echo "Error: $GRADLE_FILE not found!"
    exit 1
fi

# Extract current versionCode
# Format: extVersionCode = 1007
CURRENT_CODE=$(grep "extVersionCode =" "$GRADLE_FILE" | awk '{print $3}')
if [ -z "$CURRENT_CODE" ]; then
    echo "Error: Could not find extVersionCode in $GRADLE_FILE"
    exit 1
fi

# Increment versionCode
NEW_CODE=$((CURRENT_CODE + 1))

# Update build.gradle
sed -i "s/extVersionCode = $CURRENT_CODE/extVersionCode = $NEW_CODE/" "$GRADLE_FILE"

echo "Bumped Gradle version: $CURRENT_CODE -> $NEW_CODE"

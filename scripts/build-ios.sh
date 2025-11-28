#!/usr/bin/env bash
set -euo pipefail

# Simple helper to build the iOS frameworks via Gradle, avoiding IDE CidrBuild path.
# Usage:
#   scripts/build-ios.sh                  # Build Simulator framework (default)
#   scripts/build-ios.sh device           # Build Device framework
#   scripts/build-ios.sh xcframework      # Assemble universal XCFramework
#
# After building, open iosApp/iosApp.xcodeproj in Xcode and ensure it links the produced framework.

cd "$(dirname "$0")/.."

cmd="sim"
if [[ $# -gt 0 ]]; then
  cmd="$1"
fi

case "$cmd" in
  sim|simulator)
    ./gradlew :composeApp:linkDebugFrameworkIosSimulatorArm64
    ;;
  dev|device)
    ./gradlew :composeApp:linkDebugFrameworkIosArm64
    ;;
  xc|xcframework)
    ./gradlew :composeApp:assembleXCFramework
    ;;
  *)
    echo "Unknown argument: $cmd" >&2
    echo "Valid options: [sim|simulator|device|dev|xc|xcframework]" >&2
    exit 1
    ;;
 esac

echo "\nBuild complete."

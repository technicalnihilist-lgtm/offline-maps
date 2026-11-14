#!/usr/bin/env bash
#
# Fail the build when a module that has unit tests produced no test results.
#
# `./gradlew test` already fails on a failing test, but it does not fail when a module's test task
# runs nothing at all: a module whose test sources are misplaced, whose test source set is dropped
# from the build, or whose task is skipped for any reason leaves the step green and the module
# silently untested. That is what this script refuses to allow (OPTIMIZATION.md J-07).
#
# The module list comes from settings.gradle.kts, not from a copy kept here, so a newly added module
# is covered the day it is included. Run it after `./gradlew test`, from anywhere:
#
#     bash tools/verify_test_results.sh
#
set -uo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root" || exit 2

if [ ! -f settings.gradle.kts ]; then
    echo "verify_test_results: no settings.gradle.kts in $root" >&2
    exit 2
fi

# ":core:data" -> "core/data"
modules="$(sed -nE 's/^[[:space:]]*include\(":?([^"]+)"\).*/\1/p' settings.gradle.kts | tr ':' '/')"

if [ -z "$modules" ]; then
    echo "verify_test_results: no modules parsed out of settings.gradle.kts - the scan is blind" >&2
    exit 2
fi

checked=0
missing=0
total=0

for module in $modules; do
    if [ ! -f "$module/build.gradle.kts" ]; then
        echo "FAIL $module: included in settings.gradle.kts but has no build.gradle.kts" >&2
        missing=$((missing + 1))
        continue
    fi

    # Only modules that actually carry unit-test sources are expected to report results.
    if ! find "$module/src/test" -type f \( -name '*.kt' -o -name '*.java' \) -print -quit 2>/dev/null | grep -q .; then
        continue
    fi

    checked=$((checked + 1))

    files="$(find "$module/build/test-results" -type f -name '*.xml' 2>/dev/null)"
    if [ -z "$files" ]; then
        echo "FAIL $module: has test sources but wrote no test-results XML - its tests did not run" >&2
        missing=$((missing + 1))
        continue
    fi

    # Sum tests="N" over every suite of every variant (debug and release).
    count="$(grep -ho 'tests="[0-9]*"' $files 2>/dev/null | tr -cd '0-9\n' | awk '{ s += $1 } END { printf "%d", s + 0 }')"
    count="${count:-0}"
    total=$((total + count))

    if [ "$count" -eq 0 ]; then
        echo "FAIL $module: test-results exist but report 0 executed tests" >&2
        missing=$((missing + 1))
        continue
    fi

    echo "ok   $module: $count test executions"
done

if [ "$checked" -eq 0 ]; then
    echo "verify_test_results: no module with test sources was found - the scan is blind" >&2
    exit 2
fi

if [ "$missing" -ne 0 ]; then
    echo "verify_test_results: $missing module(s) reported no tests; $total executions seen in $checked module(s)" >&2
    exit 1
fi

echo "verify_test_results: $total test executions across $checked module(s)"

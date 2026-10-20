#!/usr/bin/env bash
#MISE description = "Enforce the JaCoCo line-coverage gate against existing reports (no Maven)"
set -euo pipefail

# The gate is bound in app/pom.xml and therefore only runs inside `./mvnw verify`, which is
# an ~11 minute reactor build. This task runs the same script against the reports already on
# disk, so "am I above the gate?" costs about a second instead of a full rebuild.
#
# The floor is read from pom.xml rather than written here: a copy in prose is exactly how the
# floor silently drifted four points below reality in the first place.
#
# `--strict-instrumentation` is passed for CI parity. It fails when a module with passing
# tests reports zero coverage, which is the signature of a JaCoCo agent that never attached -
# the defect class that hid an entire module's missing coverage for months.

if [ ! -f pom.xml ]; then
    echo "Run this from the repository root (no pom.xml in $PWD)." >&2
    exit 1
fi

minimum="$(sed -n 's/.*<jacoco\.minimum\.line\.coverage>\([0-9.]*\)<.*/\1/p' pom.xml | head -1)"
if [ -z "$minimum" ]; then
    echo "Could not read jacoco.minimum.line.coverage from pom.xml." >&2
    exit 1
fi

# Chatter to stderr: stdout carries the gate's own verdict and nothing else.
echo "Gate floor (from pom.xml): $minimum" >&2
exec python3 ./scripts/verify_jacoco_coverage.py --minimum "$minimum" --strict-instrumentation "$@"

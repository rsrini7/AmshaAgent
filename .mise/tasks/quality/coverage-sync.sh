#!/usr/bin/env bash
#MISE description = "Rewrite the Java test and coverage badges in README.md from existing reports"
set -euo pipefail

# The README badges were hand-maintained until this task existed, and drifted out of step with
# `docs/TEST_COVERAGE_ANALYSIS.md` more than once on the Jev branch. This reads the same
# Surefire/Failsafe reports the coverage gate reads and rewrites both badges, so the two cannot
# disagree about the same tree. The UI badges are `analyze_ui_coverage.py`'s to write and are
# left untouched here.
#
# It never builds: it reports whatever is on disk, which is why the report age is printed
# instead of assumed. Run `./mvnw verify` first, or the badge will faithfully report the last
# build rather than the current source.
#
# Fails loudly if a badge cannot be found. A silent no-op here would report success while
# leaving the one number this task exists to correct still wrong.

if [ ! -f pom.xml ]; then
    echo "Run this from the repository root (no pom.xml in $PWD)." >&2
    exit 1
fi

reports="$(find . \( -path '*/target/site/jacoco/jacoco.csv' -o -path '*/target/surefire-reports/TEST-*.xml' \) -type f 2>/dev/null | wc -l | tr -d ' ')"
if [ "$reports" -eq 0 ]; then
    echo "No JaCoCo or test reports found - run './mvnw --batch-mode verify < /dev/null' first." >&2
    echo "This task reads existing reports; it does not build." >&2
    exit 1
fi

newest="$(find . -path '*/target/site/jacoco/jacoco.csv' -type f -exec ls -t {} + 2>/dev/null | head -1 || true)"
{
    echo "Reports found: $reports"
    if [ -n "$newest" ]; then
        echo "Newest report:  $newest ($(date -r "$newest" '+%Y-%m-%d %H:%M' 2>/dev/null || echo 'mtime unavailable'))"
    fi
    echo "These figures describe that build, not necessarily the current source tree." >&2
    echo
} >&2

exec python3 ./scripts/analyze_jacoco_coverage.py --sync-readme "$@"

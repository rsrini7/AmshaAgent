#!/usr/bin/env bash
#MISE description = "Report reactor coverage: line/branch summary plus per-module totals"
set -euo pipefail

# Reads the on-disk JaCoCo reports produced by `./mvnw verify`. It never builds, so the age
# of the reports is printed rather than assumed - a stale `jacoco.csv` reports confident,
# wrong numbers, which is the failure mode the coverage docs call out most often.
#
# The per-module table is the view that shows whether one module is subsidising another
# inside the reactor-wide number. `test:summary` reports only the reactor total, so a
# module at 35% stays invisible there.
#
# Pass any analyzer flags to replace the default report:
#   mise run quality:coverage -- --json
#   mise run quality:coverage -- --module core
#   mise run quality:coverage -- --badge
#
# RULE: this task's own diagnostics go to stderr, always. The analyzer can emit JSON and
# shields.io badges on stdout, so anything this wrapper prints to stdout would silently
# corrupt that output - `coverage.sh --json | jq` must receive JSON and nothing else.

if [ ! -f pom.xml ]; then
    echo "Run this from the repository root (no pom.xml in $PWD)." >&2
    exit 1
fi

reports="$(find . -path '*/target/site/jacoco/jacoco.csv' -type f 2>/dev/null | wc -l | tr -d ' ')"
if [ "$reports" -eq 0 ]; then
    echo "No JaCoCo reports found - run './mvnw --batch-mode verify < /dev/null' first." >&2
    echo "This task reads existing reports; it does not build." >&2
    exit 1
fi

# Newest report by mtime. `ls -t` is used rather than `find -printf` (GNU-only) so this
# works on macOS, Linux and WSL2 alike. Failure here is cosmetic, never fatal.
newest="$(find . -path '*/target/site/jacoco/jacoco.csv' -type f -exec ls -t {} + 2>/dev/null | head -1 || true)"
{
    echo "Modules reporting: $reports"
    if [ -n "$newest" ]; then
        echo "Newest report:     $newest ($(date -r "$newest" '+%Y-%m-%d %H:%M' 2>/dev/null || echo 'mtime unavailable'))"
    fi
    echo
} >&2

# Explicit flags win outright: handing the whole invocation to the analyzer keeps --json
# and --badge valid, which they would not be if two outputs were concatenated.
if [ "$#" -gt 0 ]; then
    exec python3 ./scripts/analyze_jacoco_coverage.py "$@"
fi

python3 ./scripts/analyze_jacoco_coverage.py --summary
echo
python3 ./scripts/analyze_jacoco_coverage.py --modules

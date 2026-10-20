#!/usr/bin/env bash
#MISE description = "Rank the largest coverage gaps, with the missed line numbers to target"
set -euo pipefail

# `--lines` attaches the actual missed JaCoCo line numbers by reading each module's
# jacoco.xml, which turns the output from a scoreboard into a worklist - you see which
# lines to write tests for, not just how many are missing.
#
# Reads existing reports; it does not build. Default depth is 20.
#   mise run quality:coverage-gaps                 # top 20, with line numbers
#   mise run quality:coverage-gaps -- --top 50
#   mise run quality:coverage-gaps -- --module core
#   mise run quality:coverage-gaps -- --json

if [ ! -f pom.xml ]; then
    echo "Run this from the repository root (no pom.xml in $PWD)." >&2
    exit 1
fi

if [ "$#" -gt 0 ]; then
    exec python3 ./scripts/analyze_jacoco_coverage.py "$@"
fi

exec python3 ./scripts/analyze_jacoco_coverage.py --lines --top 20

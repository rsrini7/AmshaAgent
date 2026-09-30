#!/usr/bin/env bash
#MISE description = "Run the Python script & harness contract test suites (CI parity plus two)"
set -euo pipefail

# Mirrors the "Run Script & Harness Unit Tests" step in .github/workflows/ci.yml, and adds
# the two suites that step does NOT run:
#
#   scripts/quality/test_verify_docs.py
#   scripts/quality/test_jacoco_lines.py
#
# Those two are green but unguarded in CI, so nothing stops them rotting. Running them here
# means a broken tool surfaces locally even though CI stays silent.
#
# Every suite is dependency-free by design - they exercise repo scripts, so they have to run
# on a clean checkout. A failure here is a broken tool, not a flaky test, which is why the
# failing suite's own output is printed rather than swallowed.

if [ ! -f pom.xml ]; then
    echo "Run this from the repository root (no pom.xml in $PWD)." >&2
    exit 1
fi

suites=(
    "repo-root:scripts/test_repo_root.py"
    "dist-wrappers:scripts/test_dist_wrappers.py"
    "llm-preflight:scripts/distribution/test_llm_preflight.py"
    "autotest-runners:scripts/test_autotest_runners.py"
    "freeze-sarvam:scripts/test_freeze_sarvam_static.py"
    "prove-sarvam:scripts/test_prove_sarvam_offline.py"
    "fix-fqns:scripts/quality/test_fix_fqns.py"
    "verify-jacoco-gate:scripts/quality/test_verify_jacoco_coverage.py"
    "analyze-jacoco:scripts/quality/test_analyze_jacoco_coverage.py"
    "common-http:rest-http/scripts/test_common_http.py"
    "verify-docs:scripts/quality/test_verify_docs.py"
    "jacoco-lines:scripts/quality/test_jacoco_lines.py"
)

ran=0
failed=0
missing=0

for entry in "${suites[@]}"; do
    name="${entry%%:*}"
    path="${entry#*:}"

    if [ ! -f "$path" ]; then
        echo "MISS  $name - $path not found" >&2
        missing=$((missing + 1))
        continue
    fi

    if output="$(python3 "$path" 2>&1)"; then
        echo "ok    $name"
    else
        echo "FAIL  $name" >&2
        printf '%s\n' "$output" | sed 's/^/          /' >&2
        failed=$((failed + 1))
    fi
    ran=$((ran + 1))
done

echo
if [ "$failed" -ne 0 ] || [ "$missing" -ne 0 ]; then
    echo "$ran suites run, $failed failed, $missing missing." >&2
    exit 1
fi
echo "$ran suites run, all passed."

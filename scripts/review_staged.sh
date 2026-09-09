#!/usr/bin/env bash
# Read-only staged-change review runner.
#
# This script deliberately never runs git add/reset/restore/commit. Build tools
# are expected to write only ignored artifacts; tracked-file mutations are
# reported as a failure at the end.
set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEFAULT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

usage() {
    cat <<'EOF'
Usage: scripts/review_staged.sh [options]

Review the current Git index without changing it.

Options:
  --fast                 Diff, secret, and documentation checks only.
  --full                 Run the complete affected-module test suites.
  --native               Run mise run build:native-local when native-sensitive files changed.
  --repo-root PATH       Review another checkout (mainly for self-tests).
  --evidence-dir PATH    Store a redacted status report (default: .scratch/review-staged).
  --help                 Show this help.

Environment:
  AMSHA_REVIEW_EVIDENCE_DIR  Default evidence directory.
EOF
}

FAST=0
FULL=0
NATIVE=0
REPO_ROOT="$DEFAULT_ROOT"
EVIDENCE_DIR="${AMSHA_REVIEW_EVIDENCE_DIR:-}"

while (($# > 0)); do
    case "$1" in
        --fast) FAST=1 ;;
        --full) FULL=1 ;;
        --native) NATIVE=1 ;;
        --repo-root)
            (($# >= 2)) || { echo "--repo-root requires a path" >&2; exit 2; }
            REPO_ROOT="$2"
            shift
            ;;
        --evidence-dir)
            (($# >= 2)) || { echo "--evidence-dir requires a path" >&2; exit 2; }
            EVIDENCE_DIR="$2"
            shift
            ;;
        --help|-h) usage; exit 0 ;;
        *) echo "Unknown option: $1" >&2; usage >&2; exit 2 ;;
    esac
    shift
done

if ((FAST && FULL)); then
    echo "--fast and --full cannot be used together" >&2
    exit 2
fi

REPO_ROOT="$(cd "$REPO_ROOT" && pwd)"
cd "$REPO_ROOT"

git rev-parse --show-toplevel >/dev/null 2>&1 || {
    echo "Not inside a Git worktree: $REPO_ROOT" >&2
    exit 2
}

if [[ -z "$EVIDENCE_DIR" ]]; then
    EVIDENCE_DIR="$REPO_ROOT/.scratch/review-staged"
elif [[ "$EVIDENCE_DIR" != /* ]]; then
    EVIDENCE_DIR="$REPO_ROOT/$EVIDENCE_DIR"
fi

TMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/amsha-review-staged.XXXXXX")"
cleanup() { rm -rf "$TMP_DIR"; }
trap cleanup EXIT

STAGED_FILES="$TMP_DIR/staged-files"
git diff --cached --name-only --diff-filter=ACMR >"$STAGED_FILES"
if [[ ! -s "$STAGED_FILES" ]]; then
    echo "No staged changes found. Stage the intended files, then rerun this read-only review." >&2
    exit 2
fi

sha256_file() {
    if command -v shasum >/dev/null 2>&1; then
        shasum -a 256 | awk '{print $1}'
    else
        sha256sum | awk '{print $1}'
    fi
}

index_hash_before="$(git diff --cached --binary | sha256_file)"
worktree_hash_before="$(git diff --binary | sha256_file)"
git diff --name-only | sort >"$TMP_DIR/worktree-files-before"
commit_sha="$(git rev-parse HEAD)"
short_sha="${commit_sha:0:12}"
timestamp="$(date -u +%Y%m%dT%H%M%SZ)"
mkdir -p "$EVIDENCE_DIR"
evidence_file="$EVIDENCE_DIR/${timestamp}-${short_sha}.md"

declare -a CHECK_NAMES=()
declare -a CHECK_RESULTS=()
FAILURES=0

record_check() {
    local name="$1"
    local result="$2"
    CHECK_NAMES+=("$name")
    CHECK_RESULTS+=("$result")
    if [[ "$result" == "FAIL" ]]; then
        FAILURES=$((FAILURES + 1))
    fi
    printf '[%s] %s\n' "$result" "$name"
}

run_check() {
    local name="$1"
    shift
    if "$@" >"$TMP_DIR/${#CHECK_NAMES[@]}.log" 2>&1; then
        record_check "$name" PASS
    else
        record_check "$name" FAIL
    fi
}

printf 'Reviewing %s staged files at %s\n' "$(wc -l <"$STAGED_FILES" | tr -d ' ')" "$short_sha"
printf 'Index diff hash: %s\n' "$index_hash_before"

run_check "staged whitespace/error check" git diff --cached --check

DIFF_FILE="$TMP_DIR/staged.diff"
git diff --cached --unified=0 --no-ext-diff >"$DIFF_FILE"
ADDED_FILE="$TMP_DIR/added-lines"
grep '^+' "$DIFF_FILE" | grep -v '^+++' >"$ADDED_FILE" || :

# Scan additions only. The output intentionally reports no match text because
# a review command must not echo a credential-shaped value into the terminal.
if ! command -v rg >/dev/null 2>&1; then
    record_check "secret-shaped additions scan (rg unavailable)" FAIL
elif rg -n -i \
        -e 'AKIA[0-9A-Z]{16}' \
        -e 'AIza[0-9A-Za-z_-]{20,}' \
        -e 'gh[pousr]_[A-Za-z0-9]{20,}' \
        -e 'sk-[A-Za-z0-9][A-Za-z0-9_-]{19,}' \
        -e 'Bearer[[:space:]]+[A-Za-z0-9._~+/=-]{24,}' \
        -e '(api[_-]?key|access[_-]?token|refresh[_-]?token|client[_-]?secret|password|secret)[[:space:]]*[:=][[:space:]]*[A-Za-z0-9._~+/=-]{16,}' \
        "$ADDED_FILE" >"$TMP_DIR/secret-matches" 2>/dev/null; then
    record_check "secret-shaped additions scan" FAIL
else
    record_check "secret-shaped additions scan" PASS
fi

JAVA_CHANGED=0
DOCS_CHANGED=0
UI_CHANGED=0
NATIVE_SENSITIVE=0
MAVEN_MODULES=""
TEST_CLASSES=""

while IFS= read -r path; do
    case "$path" in
        *.java)
            JAVA_CHANGED=1
            ;;
        *.md)
            DOCS_CHANGED=1
            ;;
        ui/*)
            UI_CHANGED=1
            ;;
    esac
    case "$path" in
        */src/main/*|*/src/test/*|*/pom.xml|pom.xml|app/src/main/resources/*|*/src/main/resources/*)
            NATIVE_SENSITIVE=1
            ;;
    esac
    module="${path%%/*}"
    case "$module" in
        app|core|common|channel-api|channel-telegram|channel-web|classifier-api|llm-provider-api|llm-providers|memory|mcp|onboarding|persistence-api|persistence-common|persistence-fs|persistence-postgres|persistence-sqlite|rag|rest-http|sandbox|security|security-api|tool-api|tools-builtin|local-ml-onnx)
            case ",$MAVEN_MODULES," in
                *",$module,"*) ;;
                *) MAVEN_MODULES="${MAVEN_MODULES:+$MAVEN_MODULES,}$module" ;;
            esac
            ;;
    esac
    if [[ "$path" == */src/test/java/*Test.java ]]; then
        class_name="${path##*/}"
        TEST_CLASSES="${TEST_CLASSES:+$TEST_CLASSES,}${class_name%.java}"
    fi
done <"$STAGED_FILES"

if ((DOCS_CHANGED)); then
    run_check "Markdown link/documentation verification" python3 ./scripts/verify_docs.py
fi

if ((JAVA_CHANGED)) && ((FAST == 0)); then
    if [[ -x ./mvnw ]]; then
        if [[ -n "$MAVEN_MODULES" ]]; then
            run_check "Spotless check ($MAVEN_MODULES)" ./mvnw -q -pl "$MAVEN_MODULES" -am spotless:check
            run_check "PMD gate ($MAVEN_MODULES)" ./mvnw -q -pl "$MAVEN_MODULES" -am pmd:check -DskipTests
            if ((FULL)); then
                run_check "affected Maven test suites ($MAVEN_MODULES)" ./mvnw -q -pl "$MAVEN_MODULES" -am test -Dsurefire.failIfNoSpecifiedTests=false
            elif [[ -n "$TEST_CLASSES" ]]; then
                run_check "staged focused tests ($TEST_CLASSES)" ./mvnw -q -pl "$MAVEN_MODULES" -am -Dtest="$TEST_CLASSES" -Dsurefire.failIfNoSpecifiedTests=false test
            else
                run_check "affected Maven compile ($MAVEN_MODULES)" ./mvnw -q -pl "$MAVEN_MODULES" -am compile -DskipTests
            fi
        else
            record_check "Java module detection" FAIL
        fi
    else
        record_check "Maven wrapper available" FAIL
    fi
fi

if ((UI_CHANGED)) && ((FAST == 0)); then
    if [[ -f ui/package.json ]]; then
        run_check "UI production build" bash -c 'cd ui && npm run build'
        run_check "UI Vitest suite" bash -c 'cd ui && npm test'
    else
        record_check "UI package manifest available" FAIL
    fi
fi

if ((NATIVE)) && ((NATIVE_SENSITIVE)); then
    run_check "native local build" mise run build:native-local
elif ((NATIVE)); then
    record_check "native local build (no native-sensitive files)" SKIP
fi

index_hash_after="$(git diff --cached --binary | sha256_file)"
worktree_hash_after="$(git diff --binary | sha256_file)"
git diff --name-only | sort >"$TMP_DIR/worktree-files-after"
if [[ "$index_hash_before" != "$index_hash_after" ]]; then
    record_check "Git index unchanged" FAIL
else
    record_check "Git index unchanged" PASS
fi
if [[ "$worktree_hash_before" != "$worktree_hash_after" ]]; then
    record_check "tracked worktree unchanged" FAIL
    echo "Tracked paths changed during review:" >&2
    comm -3 "$TMP_DIR/worktree-files-before" "$TMP_DIR/worktree-files-after" >&2 || :
else
    record_check "tracked worktree unchanged" PASS
fi

{
    echo "# Staged review evidence"
    echo
    echo "- Commit: \`$commit_sha\`"
    echo "- Timestamp (UTC): \`$timestamp\`"
    echo "- Staged diff SHA-256: \`$index_hash_before\`"
    echo "- Mode: \`$([[ $FAST == 1 ]] && echo fast || ([[ $FULL == 1 ]] && echo full || echo affected))\`"
    echo
    echo "## Staged files"
    echo
    sed 's/^/- /' "$STAGED_FILES"
    echo
    echo "## Checks"
    echo
    for i in "${!CHECK_NAMES[@]}"; do
        printf -- '- **%s**: %s\n' "${CHECK_NAMES[$i]}" "${CHECK_RESULTS[$i]}"
    done
    echo
    echo "This report records statuses only; command output and payloads are intentionally discarded."
} >"$evidence_file"

printf 'Evidence: %s\n' "$evidence_file"
if ((FAILURES > 0)); then
    printf 'Review failed with %d check(s). Command output was discarded to avoid leaking payloads; rerun the named command for diagnostics.\n' "$FAILURES" >&2
    exit 1
fi
echo 'Review passed.'

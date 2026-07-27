#!/usr/bin/env bash
set -euo pipefail

# ArcClaw -> Amsha Agent repository migration.
#
# Naming rules:
#   ArcClawInternal -> (preserved)        (repo root dir, kept as-is per decision)
#   ArcClawFoo      -> AmshaFoo              (Java/type identifier)
#   ArcClaw         -> Amsha Agent            (standalone display name)
#   X-ArcClaw-*     -> X-Amsha-*              (HTTP header namespace)
#   ARCCLAW_*       -> AMSHA_*
#   com.arcclaw     -> com.amsha              (Java/Maven namespace)
#   arcclaw.*       -> amsha.*                (configuration namespace)
#   arcclaw-core    -> core/ or amsha-core     (folder vs Maven artifact)
#   arcclaw         -> amsha                   (CLI/file/runtime slug)
#
# Usage:
#   ./rebrand.sh             Apply the migration
#   ./rebrand.sh --dry-run   Show the paths and content counts that would change
#   ./rebrand.sh --verify    Report remaining old-brand paths/content

DRY_RUN=false
VERIFY_ONLY=false

for arg in "$@"; do
    case "$arg" in
        --dry-run) DRY_RUN=true ;;
        --verify) VERIFY_ONLY=true ;;
        -h|--help)
            sed -n '3,17p' "$0" | sed 's/^# \?//'
            exit 0
            ;;
        *)
            echo "Unknown argument: $arg" >&2
            echo "Usage: $0 [--dry-run|--verify]" >&2
            exit 2
            ;;
    esac
done

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

log() { printf "${GREEN}  OK${NC} %s\n" "$1"; }
warn() { printf "${YELLOW}  WARN${NC} %s\n" "$1"; }
err() { printf "${RED}  ERROR${NC} %s\n" "$1" >&2; }
phase() { printf "\n${BLUE}== %s ==${NC}\n" "$1"; }

is_excluded_path() {
    case "$1" in
        rebrand.sh|*/target/*|target/*|*/node_modules/*|node_modules/*|*/dist/*|dist/*|\
        */data/*|data/*|*/.git/*|.git/*|*/.scannerwork/*|.scannerwork/*|\
        */.playwright-cli/*|.playwright-cli/*|*/.playwright-mcp/*|.playwright-mcp/*|\
        */.devbox/*|.devbox/*|*.class|*.jar|*.so|*.dylib|*.dll|*.log|*.log.*)
            return 0
            ;;
        *) return 1 ;;
    esac
}

# Top-level Maven module folders are intentionally plain. Other branded path
# tokens retain the concise Amsha stem.
transform_path() {
    # ArcClawInternal (repo root dir) is preserved per project decision.
    printf '%s' "$1" | perl -pe '
        $modules = qr/(?:app|channel-api|channel-telegram|channel-web|common|core|deployment|mcp|onboarding|persistence-api|persistence-common|persistence-fs|persistence-postgres|persistence-sqlite|rag|tool-playwright|ui)/;
        s{\Aarcclaw-($modules)(?=/)}{$1}g;
        s{com/arcclaw}{com/amsha}g;
        s/com\.arcclaw/com.amsha/g;
        s/ArcClaw(?!Internal)/Amsha/g;
        s/ARCCLAW/AMSHA/g;
        s/Arcclaw/Amsha/g;
        s/arc[Cc]law/amsha/g;
        s/ARC_CLAW/AMSHA/g;
        s/ARC-CLAW/AMSHA/g;
        s/arc[-_]claw/amsha/g;
    '
}

has_old_brand() {
    # Ignore the intentionally retained parent-directory token while detecting
    # every other case variant handled by replace_content.
    LC_ALL=C perl -ne '
        s/ArcClawInternal//g;
        if (/arcclaw|arc[-_]claw/i) { $found = 1; last; }
        END { exit($found ? 0 : 1); }
    ' "$1" 2>/dev/null
}

print_old_brand_matches() {
    LC_ALL=C perl -ne '
        $candidate = $_;
        $candidate =~ s/ArcClawInternal//g;
        print "$.:$_" if $candidate =~ /arcclaw|arc[-_]claw/i;
    ' "$1" 2>/dev/null
}

replace_content() {
    # Filesystem module paths become plain, while Maven artifact IDs retain an
    # amsha- prefix. Java packages and configuration use the concise Amsha stem.
    perl -pi -e '
        $modules = qr/(?:app|channel-api|channel-telegram|channel-web|common|core|deployment|mcp|onboarding|persistence-api|persistence-common|persistence-fs|persistence-postgres|persistence-sqlite|rag|tool-playwright|ui)/;
        # ArcClawInternal is intentionally preserved: it is the repo root
        # directory name and appears in absolute paths (.openclaude, .kimchi).
        # Renaming it would break those paths while the folder stays unchanged.
        s{<module>arcclaw-($modules)</module>}{<module>$1</module>}g;
        s{arcclaw-($modules)(?=[/\\])}{$1}g;
        s{(?<=[/\\])arcclaw-($modules)\b}{$1}g;
        s{\b(cd|pushd)\s+arcclaw-($modules)\b}{$1 $2}g;
        s{\x27arcclaw-($modules)\x27(?=\s+in\s+current_path)}{\x27$1\x27}g;
        s/X-ArcClaw-/X-Amsha-/g;
        s/X-ARCCLAW-/X-AMSHA-/g;
        s/com\.arcclaw/com.amsha/g;
        s/arcclaw\.(?!txt\b|exe\b|log\b)/amsha./g;
        s/ArcClaw(?!Internal)(?=[A-Za-z0-9_])/Amsha/g;
        s/ArcClaw(?!Internal)(?:\s+Agent)?/Amsha Agent/g;
        s/ARCCLAW/AMSHA/g;
        s/Arcclaw/Amsha/g;
        s/arc[Cc]law/amsha/g;
        s/ARC_CLAW/AMSHA/g;
        s/ARC-CLAW/AMSHA/g;
        s/arc[-_]claw/amsha/g;
    ' "$1"
}

collect_tracked_paths() {
    git ls-files -z
}

verify_migration() {
    local path old_path_count=0 content_count=0 shown=0

    phase "Verification"
    while IFS= read -r -d '' path; do
        is_excluded_path "$path" && continue
        if [[ "$(transform_path "$path")" != "$path" ]]; then
            printf '  old-brand path: %s\n' "$path"
            old_path_count=$((old_path_count + 1))
        fi
        if [[ -f "$path" ]] && has_old_brand "$path"; then
            if [[ $shown -lt 100 ]]; then
                print_old_brand_matches "$path" | head -5 || true
            fi
            shown=$((shown + 1))
            content_count=$((content_count + 1))
        fi
    done < <(collect_tracked_paths)

    # .env is intentionally untracked but is part of the runtime migration.
    if [[ -f .env ]] && has_old_brand .env; then
        print_old_brand_matches .env | head -20 || true
        content_count=$((content_count + 1))
    fi

    if [[ $old_path_count -eq 0 && $content_count -eq 0 ]]; then
        log "No old-brand references remain in tracked paths/content or .env."
        return 0
    fi

    err "$old_path_count old-brand paths and $content_count files with old-brand content remain."
    return 1
}

if $VERIFY_ONLY; then
    verify_migration
    exit $?
fi

phase "Preflight"
if [[ ! -f pom.xml ]] || [[ ! -d arcclaw-app ]]; then
    err "Run this script once from the ArcClaw repository root."
    exit 1
fi
command -v git >/dev/null || { err "git is required."; exit 1; }
command -v perl >/dev/null || { err "perl is required."; exit 1; }

if [[ -n "$(git status --porcelain --untracked-files=all | grep -vE '^.. rebrand\.sh$' || true)" ]]; then
    warn "The working tree contains changes other than rebrand.sh."
    warn "Commit or stash them first so the migration is reviewable and recoverable."
    if ! $DRY_RUN; then
        read -r -p "  Continue anyway? [y/N] " confirm
        [[ "$confirm" == [yY] ]] || exit 1
    fi
fi

path_count=0
content_count=0
while IFS= read -r -d '' path; do
    [[ "$(transform_path "$path")" != "$path" ]] && path_count=$((path_count + 1))
    if ! is_excluded_path "$path" && [[ -f "$path" ]] && has_old_brand "$path"; then
        content_count=$((content_count + 1))
    fi
done < <(collect_tracked_paths)
[[ -f .env ]] && has_old_brand .env && content_count=$((content_count + 1))

log "$path_count tracked files need path migration."
log "$content_count tracked/runtime files need content migration."

if $DRY_RUN; then
    phase "Path preview"
    while IFS= read -r -d '' path; do
        new_path=$(transform_path "$path")
        [[ "$new_path" != "$path" ]] && printf '  %s -> %s\n' "$path" "$new_path"
    done < <(collect_tracked_paths)
    log "Dry run complete; no files changed."
    exit 0
fi

phase "Rename tracked paths"
# Move files rather than a hardcoded directory list. This covers modules, Java
# class filenames, package trees, native-image metadata, docs, assets and rulesets.
while IFS= read -r -d '' path; do
    new_path=$(transform_path "$path")
    [[ "$new_path" == "$path" ]] && continue
    if [[ -e "$new_path" ]]; then
        err "Cannot rename '$path': destination '$new_path' already exists."
        exit 1
    fi
    mkdir -p "$(dirname "$new_path")"
    git mv "$path" "$new_path"
done < <(collect_tracked_paths)
log "Renamed $path_count tracked paths."

phase "Replace content"
while IFS= read -r -d '' path; do
    is_excluded_path "$path" && continue
    [[ -f "$path" ]] || continue
    has_old_brand "$path" || continue
    replace_content "$path"
done < <(collect_tracked_paths)

if [[ -f .env ]] && has_old_brand .env; then
    replace_content .env
    log "Migrated untracked runtime configuration in .env."
fi
log "Updated $content_count files, including package-lock.json when applicable."

verify_migration

phase "Required validation"
cat <<'EOF'
  1. python3 ./scripts/fix_fqns.py && ./mvnw spotless:apply -q
  2. ./mvnw compile -DskipTests -q
  3. (cd ui && npm run build)
  4. ./mvnw verify
  5. mise run sonar:issues

External migration still required:
  - The ArcClawInternal repository directory is intentionally retained; update
    absolute paths only if it is renamed in a separate future migration.
  - Rename the remote repository separately if its public name should change.
  - Update CI/CD secrets and ARCCLAW_* variables to AMSHA_*.
  - Update container registries, deployment paths, DNS and service integrations.
  - Decide whether compatibility aliases are needed for existing users' env vars,
    database paths, CLI history, API keys and published Maven/container artifacts.
EOF

log "Repository migration complete. Review with: git status && git diff --stat"

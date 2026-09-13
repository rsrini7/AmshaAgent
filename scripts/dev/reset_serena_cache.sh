#!/usr/bin/env bash
# ==============================================================================
# reset_serena_cache.sh
#
# Diagnoses and repairs corrupted Serena LSP / Eclipse JDTLS workspace caches.
# Fixes common failures such as:
#   - "Incompatible file format. Workspace was saved with an incompatible version: 0"
#   - "LanguageServerTerminatedException: Language server stdout read process terminated unexpectedly"
#   - "Failed to start 1 language server(s): java"
# ==============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
if [ -f "$SCRIPT_DIR/../lib/repo_root.sh" ]; then
  # shellcheck source=../lib/repo_root.sh
  source "$SCRIPT_DIR/../lib/repo_root.sh"
  PROJECT_ROOT="$(get_repo_root "$SCRIPT_DIR")"
else
  PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
fi

SERENA_DIR="${HOME}/.serena"
JDTLS_WORKSPACES_DIR="${SERENA_DIR}/language_servers/static/EclipseJDTLS/workspaces"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
BOLD='\033[1m'
NC='\033[0m' # No Color

TARGET_PROJECT="$PROJECT_ROOT"
CLEAN_ALL=false
CHECK_ONLY=false
CLEAN_SYMBOL_CACHE=false
KILL_PROCESSES=false
FORCE=false

print_usage() {
    echo -e "${BOLD}Usage:${NC} $(basename "$0") [options]

${BOLD}Options:${NC}
  -p, --project <dir>    Target project root (default: repo root ${PROJECT_ROOT})
  -a, --all              Clear all Eclipse JDTLS workspace caches across all projects
  -c, --check            Diagnose only: scan for corrupted files and errors without deleting
  -s, --symbol-cache     Also clear Serena's local symbol cache (.serena/cache/)
  -k, --kill             Terminate running Serena / JDTLS processes before cleaning
  -f, --force            Proceed without interactive confirmation
  -h, --help             Show this help message

${BOLD}Examples:${NC}
  $(basename "$0") --check               # Check if Serena / JDTLS cache has corruption
  $(basename "$0")                       # Clean corrupted JDTLS cache for current project
  $(basename "$0") --kill --symbol-cache # Kill running JDTLS and wipe all project caches
  $(basename "$0") --all --force         # Reset all Eclipse JDTLS workspaces without prompting"
}

# Parse command line arguments
while [[ $# -gt 0 ]]; do
    case "$1" in
        -p|--project)
            TARGET_PROJECT="$(cd "$2" && pwd)"
            shift 2
            ;;
        -a|--all)
            CLEAN_ALL=true
            shift
            ;;
        -c|--check)
            CHECK_ONLY=true
            shift
            ;;
        -s|--symbol-cache)
            CLEAN_SYMBOL_CACHE=true
            shift
            ;;
        -k|--kill)
            KILL_PROCESSES=true
            shift
            ;;
        -f|--force)
            FORCE=true
            shift
            ;;
        -h|--help)
            print_usage
            exit 0
            ;;
        *)
            echo -e "${RED}Unknown option: $1${NC}" >&2
            print_usage
            exit 1
            ;;
    esac
done

echo -e "${BOLD}======================================================${NC}"
echo -e "${BOLD}   Serena LSP / Eclipse JDTLS Cache Doctor${NC}"
echo -e "${BOLD}======================================================${NC}"
echo -e "Project root: ${BLUE}${TARGET_PROJECT}${NC}"
echo ""

# Compute SolidLSP workspace hash for target project
find_workspace_dirs() {
    local target_dirs=()

    if [ "$CLEAN_ALL" = true ]; then
        if [ -d "$JDTLS_WORKSPACES_DIR" ]; then
            while IFS= read -r -d '' ws; do
                target_dirs+=("$ws")
            done < <(find "$JDTLS_WORKSPACES_DIR" -mindepth 1 -maxdepth 1 -type d -print0)
        fi
    else
        # SolidLSP computes hash using: hashlib.md5(f"{project}|{launcher_jar}|{}")
        # We find launcher jar if available
        local launcher_jar
        launcher_jar="$(find "${SERENA_DIR}/language_servers/static/EclipseJDTLS" -name "org.eclipse.equinox.launcher_*.jar" 2>/dev/null | head -n 1 || true)"

        local computed_hash=""
        if [ -n "$launcher_jar" ] && command -v python3 >/dev/null 2>&1; then
            computed_hash="$(python3 -c "
import hashlib
s = '${TARGET_PROJECT}|${launcher_jar}|{}'
print(hashlib.md5(s.encode()).hexdigest())
" 2>/dev/null || true)"
        fi

        if [ -n "$computed_hash" ] && [ -d "${JDTLS_WORKSPACES_DIR}/${computed_hash}" ]; then
            target_dirs+=("${JDTLS_WORKSPACES_DIR}/${computed_hash}")
        else
            # Search workspaces containing references to this project
            if [ -d "$JDTLS_WORKSPACES_DIR" ]; then
                for ws in "$JDTLS_WORKSPACES_DIR"/*; do
                    if [ -d "$ws" ]; then
                        if grep -q -s "$TARGET_PROJECT" "$ws/data_dir/.metadata/.log" 2>/dev/null; then
                            target_dirs+=("$ws")
                        fi
                    fi
                done
            fi
        fi
    fi

    echo "${target_dirs[@]:-}"
}

# Check for corrupted .tree files (0-bytes or zero header)
CORRUPTED_TREES=()
check_tree_files() {
    if [ -d "$JDTLS_WORKSPACES_DIR" ]; then
        while IFS= read -r -d '' tree_file; do
            local file_size
            file_size="$(wc -c < "$tree_file" | tr -d ' ')"
            if [ "$file_size" -eq 0 ]; then
                CORRUPTED_TREES+=("$tree_file (empty file, size 0 bytes)")
            elif [ "$file_size" -ge 4 ]; then
                local header
                header="$(head -c 4 "$tree_file" | xxd -p 2>/dev/null || od -An -tx1 -N4 "$tree_file" | tr -d ' \n' || true)"
                if [ "$header" = "00000000" ]; then
                    CORRUPTED_TREES+=("$tree_file (corrupted format version 0, size ${file_size} bytes)")
                fi
            fi
        done < <(find "$JDTLS_WORKSPACES_DIR" -name "*.tree" -print0 2>/dev/null)
    fi
}

# Scan today's Serena logs for known errors
LOG_ERRORS=()
check_serena_logs() {
    local today
    today="$(date +%Y-%m-%d)"
    local today_logs_dir="${SERENA_DIR}/logs/${today}"
    if [ -d "$today_logs_dir" ]; then
        local match_count
        match_count="$(grep -l -E "(Incompatible file format|LanguageServerTerminatedException|Failed to start 1 language server)" "$today_logs_dir"/*.txt 2>/dev/null | wc -l | tr -d ' ' || echo "0")"
        if [ "$match_count" -gt 0 ]; then
            LOG_ERRORS+=("Found ${match_count} Serena log(s) today reporting JDTLS crashes / corrupted workspace format.")
        fi
    fi
}

# Check running processes
RUNNING_PIDS=()
check_running_processes() {
    local pids
    pids="$(pgrep -f "org.eclipse.jdt.ls.core" 2>/dev/null || true)"
    if [ -n "$pids" ]; then
        while read -r pid; do
            [ -n "$pid" ] && RUNNING_PIDS+=("$pid")
        done <<< "$pids"
    fi
}

echo -e "🔍 ${BOLD}Diagnosing Serena & Eclipse JDTLS state...${NC}"
check_tree_files
check_serena_logs
check_running_processes

if [ "${#LOG_ERRORS[@]}" -gt 0 ]; then
    echo -e "  ${RED}❌ Recent log failures detected:${NC}"
    for err in "${LOG_ERRORS[@]}"; do
        echo -e "     • $err"
    done
else
    echo -e "  ${GREEN}✓ No recent crash signatures in today's Serena logs.${NC}"
fi

if [ "${#CORRUPTED_TREES[@]}" -gt 0 ]; then
    echo -e "  ${RED}❌ Corrupted workspace tree files detected:${NC}"
    for tree in "${CORRUPTED_TREES[@]}"; do
        echo -e "     • $tree"
    done
else
    echo -e "  ${GREEN}✓ No corrupted 0-version .tree files detected.${NC}"
fi

if [ "${#RUNNING_PIDS[@]}" -gt 0 ]; then
    echo -e "  ${YELLOW}⚠️  Active Eclipse JDTLS process(es) running (PID: ${RUNNING_PIDS[*]})${NC}"
fi

echo ""

if [ "$CHECK_ONLY" = true ]; then
    if [ "${#CORRUPTED_TREES[@]}" -gt 0 ]; then
        echo -e "${RED}${BOLD}❌ Diagnosis: Active cache corruption detected (.tree file corrupted). Run without '--check' to repair.${NC}"
        exit 1
    elif [ "${#LOG_ERRORS[@]}" -gt 0 ]; then
        echo -e "${YELLOW}⚠️  Diagnosis: Past crashes found in today's logs, but current .tree files are clean.${NC}"
        exit 0
    else
        echo -e "${GREEN}${BOLD}✅ Diagnosis: Serena JDTLS workspace cache is completely healthy.${NC}"
        exit 0
    fi
fi

# Locate workspace directories to remove
WS_DIRS=($(find_workspace_dirs))

if [ "${#WS_DIRS[@]}" -eq 0 ] && [ "${#CORRUPTED_TREES[@]}" -eq 0 ] && [ "$CLEAN_SYMBOL_CACHE" = false ]; then
    echo -e "${GREEN}No target Eclipse JDTLS workspace cache directories found to clean.${NC}"
    exit 0
fi

echo -e "🧹 ${BOLD}Target caches to remove:${NC}"
for ws in "${WS_DIRS[@]}"; do
    echo -e "  • ${YELLOW}$ws${NC}"
done

if [ "$CLEAN_SYMBOL_CACHE" = true ]; then
    local_symbol_cache="${TARGET_PROJECT}/.serena/cache"
    if [ -d "$local_symbol_cache" ]; then
        echo -e "  • ${YELLOW}$local_symbol_cache (symbol cache)${NC}"
    fi
fi

echo ""

# Confirm before proceeding unless --force
if [ "$FORCE" = false ]; then
    read -p "Proceed with cache cleanup? [y/N] " -n 1 -r
    echo ""
    if [[ ! $REPLY =~ ^[Yy]$ ]]; then
        echo "Aborted."
        exit 0
    fi
fi

# Stop running processes if requested or confirmed
if [ "${#RUNNING_PIDS[@]}" -gt 0 ]; then
    if [ "$KILL_PROCESSES" = true ] || [ "$FORCE" = true ]; then
        echo -e "🛑 Stopping running JDTLS processes (PIDs: ${RUNNING_PIDS[*]})..."
        kill "${RUNNING_PIDS[@]}" 2>/dev/null || true
        sleep 1
    else
        echo -e "${YELLOW}Notice: JDTLS process is still running. It is recommended to stop it before cleaning.${NC}"
    fi
fi

# Perform deletion
for ws in "${WS_DIRS[@]}"; do
    if [ -d "$ws" ]; then
        echo -e "🗑️  Removing: ${BLUE}$ws${NC}"
        rm -rf "$ws"
    fi
done

if [ "$CLEAN_SYMBOL_CACHE" = true ]; then
    local_symbol_cache="${TARGET_PROJECT}/.serena/cache"
    if [ -d "$local_symbol_cache" ]; then
        echo -e "🗑️  Removing: ${BLUE}$local_symbol_cache${NC}"
        rm -rf "$local_symbol_cache"
    fi
fi

echo ""
echo -e "${GREEN}${BOLD}✅ Serena cache cleaned successfully!${NC}"
echo -e "Eclipse JDTLS will re-import the project and rebuild a fresh, clean index on its next run."

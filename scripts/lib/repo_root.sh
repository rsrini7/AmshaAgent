#!/usr/bin/env bash
# scripts/lib/repo_root.sh — Robust repository-root discovery for POSIX shell scripts.
#
# Usage:
#   source scripts/lib/repo_root.sh
#   REPO_ROOT="$(get_repo_root)"
# Or run directly:
#   bash scripts/lib/repo_root.sh

get_repo_root() {
    local anchor="${1:-}"
    local search_dir=""

    if [[ -n "$anchor" ]]; then
        if [[ -d "$anchor" ]]; then
            search_dir="$(cd "$anchor" && pwd)"
        else
            search_dir="$(cd "$(dirname "$anchor")" && pwd)"
        fi
    else
        search_dir="$(pwd)"
    fi

    # 1. Try git boundary detection from the search directory
    if command -v git >/dev/null 2>&1; then
        local git_root
        git_root="$(git -C "$search_dir" rev-parse --show-toplevel 2>/dev/null || true)"
        if [[ -n "$git_root" && -d "$git_root" && ( -f "$git_root/pom.xml" || -f "$git_root/mise.toml" ) ]]; then
            echo "$git_root"
            return 0
        fi
    fi

    # 2. Upward directory traversal searching for marker files
    local curr="$search_dir"
    while [[ -n "$curr" && "$curr" != "/" ]]; do
        if [[ -f "$curr/mise.toml" || -f "$curr/pom.xml" ]]; then
            echo "$curr"
            return 0
        fi
        curr="$(dirname "$curr")"
    done

    # 3. Fallback relative to this script directory (scripts/lib/.. -> repo root)
    local lib_dir
    lib_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
    (cd "$lib_dir/../.." && pwd)
}

if [[ "${BASH_SOURCE[0]}" == "${0}" ]]; then
    get_repo_root "$@"
fi

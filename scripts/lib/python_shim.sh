#!/usr/bin/env bash
# scripts/lib/python_shim.sh — Cross-platform Python 3 discovery and /tmp path translation
#
# Usage:
#   source scripts/lib/python_shim.sh
#   ensure_python3_shim
#
# Solves two cross-platform issues:
# 1. On Windows environments (like Git Bash), Python may only be available as python.exe or py.exe.
# 2. In MSYS/Git Bash, temporary files created in /tmp (e.g. by mktemp or /tmp/resp) reside in
#    the Windows temp directory (cygpath -m /tmp). Native Windows Python cannot open /tmp/...
#    paths directly. The shim dynamically translates /tmp/ arguments to the Windows temp path.

ensure_python3_shim() {
  if [[ "${AMSHA_PYTHON3_SHIM_ACTIVE:-false}" == "true" ]]; then
    return 0
  fi

  local python_bin=""
  # Prefer python or py on Windows if python3 is not available
  if command -v python >/dev/null 2>&1; then
    python_bin="$(command -v python)"
  elif command -v py >/dev/null 2>&1; then
    python_bin="$(command -v py)"
  elif command -v python3 >/dev/null 2>&1; then
    python_bin="$(command -v python3)"
  fi

  if [[ -z "$python_bin" ]]; then
    return 1
  fi

  local is_msys=false
  local real_tmp=""
  if command -v cygpath >/dev/null 2>&1; then
    real_tmp="$(cygpath -m /tmp 2>/dev/null || true)"
    if [[ -n "$real_tmp" && "$real_tmp" != "/tmp" ]]; then
      is_msys=true
    fi
  fi

  # If python3 is already available and we are NOT on MSYS/Cygwin (e.g. Linux / macOS), nothing needed
  if command -v python3 >/dev/null 2>&1 && [[ "$is_msys" != true ]]; then
    export AMSHA_PYTHON3_SHIM_ACTIVE="true"
    return 0
  fi

  # Create shim directory
  local shim_dir
  shim_dir="$(mktemp -d "${TMPDIR:-/tmp}/amsha_py3.XXXXXX" 2>/dev/null || mktemp -d 2>/dev/null || true)"
  if [[ -z "$shim_dir" || ! -d "$shim_dir" ]]; then
    return 1
  fi

  cat << SHIM_EOF > "$shim_dir/python3"
#!/usr/bin/env bash
REAL_TMP="$real_tmp"
REAL_PY="$python_bin"
NEW_ARGS=()
for arg in "\$@"; do
  if [[ -n "\$REAL_TMP" && "\$REAL_TMP" != "/tmp" ]]; then
    if [[ "\$arg" == *"/tmp/"* ]]; then
      NEW_ARGS+=("\${arg//\/tmp\//\$REAL_TMP\/}")
    elif [[ "\$arg" == "/tmp" ]]; then
      NEW_ARGS+=("\$REAL_TMP")
    else
      NEW_ARGS+=("\$arg")
    fi
  else
    NEW_ARGS+=("\$arg")
  fi
done
exec "\$REAL_PY" "\${NEW_ARGS[@]}"
SHIM_EOF

  chmod +x "$shim_dir/python3"
  export PATH="$shim_dir:$PATH"
  export AMSHA_PYTHON3_SHIM_ACTIVE="true"
  export AMSHA_PYTHON3_SHIM_DIR="$shim_dir"
  return 0
}

if [[ "${BASH_SOURCE[0]}" == "${0}" ]]; then
  ensure_python3_shim
  echo "Python 3 shim active at: $(command -v python3)"
  python3 --version
fi

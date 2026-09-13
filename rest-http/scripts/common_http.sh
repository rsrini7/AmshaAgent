#!/usr/bin/env bash
# rest-http/scripts/common_http.sh — Shared HTTP helpers with request isolation and safe retry policies.
#
# Inclusion hierarchy:
#   This file sources common_env.sh once.
#   Validators source common_http.sh ONCE before setting their own local KEY, B, timeout, or parser overrides.

COMMON_HTTP_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$COMMON_HTTP_DIR/common_env.sh"

# ── Composable Cleanup Traps ──
if [[ "${_AMSHA_COMMON_HTTP_INITIALIZED:-false}" != "true" ]]; then
  _AMSHA_CLEANUP_HOOKS=()
  _AMSHA_COMMON_HTTP_INITIALIZED=true
  _AMSHA_PREVIOUS_EXIT_TRAP="$(trap -p EXIT || true)"
  _AMSHA_PREVIOUS_INT_TRAP="$(trap -p INT || true)"
  _AMSHA_PREVIOUS_TERM_TRAP="$(trap -p TERM || true)"
fi

register_cleanup() {
  local hook="$1"
  _AMSHA_CLEANUP_HOOKS+=("$hook")
}

_amsha_cleanup_all() {
  local signal_name="${1:-EXIT}"
  local exit_code="${2:-$?}"
  if [[ "${_AMSHA_CLEANUP_COMPLETED:-false}" == "true" ]]; then
    return 0
  fi
  _AMSHA_CLEANUP_COMPLETED=true
  # Run registered hooks in reverse order
  local i
  for ((i=${#_AMSHA_CLEANUP_HOOKS[@]}-1; i>=0; i--)); do
    eval "${_AMSHA_CLEANUP_HOOKS[i]}" 2>/dev/null || true
  done
  # Clean up the run-scoped temp directory
  if [[ -n "${_AMSHA_RUN_DIR:-}" && -d "${_AMSHA_RUN_DIR}" ]]; then
    rm -rf "${_AMSHA_RUN_DIR}" 2>/dev/null || true
  fi
  local previous_trap previous_action
  case "$signal_name" in
    EXIT) previous_trap="${_AMSHA_PREVIOUS_EXIT_TRAP:-}" ;;
    INT) previous_trap="${_AMSHA_PREVIOUS_INT_TRAP:-}" ;;
    TERM) previous_trap="${_AMSHA_PREVIOUS_TERM_TRAP:-}" ;;
  esac
  if [[ -n "$previous_trap" ]]; then
    previous_action="${previous_trap#trap -- \'}"
    previous_action="${previous_action%\' EXIT}"
    previous_action="${previous_action%\' INT}"
    previous_action="${previous_action%\' TERM}"
    previous_action="${previous_action%\' SIGINT}"
    previous_action="${previous_action%\' SIGTERM}"
    eval "$previous_action" 2>/dev/null || true
  fi
  exit "$exit_code"
}

_amsha_cleanup_exit() { local code=$?; _amsha_cleanup_all EXIT "$code"; }
_amsha_cleanup_int() { local code=$?; _amsha_cleanup_all INT "$code"; }
_amsha_cleanup_term() { local code=$?; _amsha_cleanup_all TERM "$code"; }
if [[ "${_AMSHA_COMMON_HTTP_TRAPS_INSTALLED:-false}" != "true" ]]; then
trap _amsha_cleanup_exit EXIT
trap _amsha_cleanup_int INT
trap _amsha_cleanup_term TERM
  _AMSHA_COMMON_HTTP_TRAPS_INSTALLED=true
fi

# Run-scoped directory for this process/validator run
if [[ -z "${_AMSHA_RUN_DIR:-}" || ! -d "${_AMSHA_RUN_DIR}" ]]; then
  _AMSHA_RUN_DIR="$(mktemp -d "${TMPDIR:-/tmp}/amsha_http_run.XXXXXX")"
fi

# Retry caps and defaults
AMSHA_RETRY_AFTER_CAP="${AMSHA_RETRY_AFTER_CAP:-120}"
AMSHA_DEFAULT_RETRY_WAIT="${AMSHA_DEFAULT_RETRY_WAIT:-1}"

# Shared formatting helpers
info()  { printf "%-4s %-60s " "" "$1"; }
ok()    { printf "✅ HTTP %s\n" "$1"; PASS=$((PASS+1)); }
fail()  { printf "❌ HTTP %s\n" "$(format_http_code "$1")"; FAIL=$((FAIL+1)); }

# Low-level request with request-scoped isolation
# Usage:
#   http_call [options] <curl_args...>
# Direct invocation sets:
#   HTTP_RESP: path to the isolated response file
#   HTTP_HDRS: path to the response headers file
#   HTTP_CODE: HTTP status code
http_call() {
  local idempotent=false
  local timeout="${CURL_TIMEOUT:-60}"
  local custom_output=""
  local curl_args=()

  while (($# > 0)); do
    case "$1" in
      --idempotent)
        idempotent=true
        shift
        ;;
      --timeout)
        timeout="$2"
        shift 2
        ;;
      -o|--output)
        custom_output="$2"
        shift 2
        ;;
      *)
        curl_args+=("$1")
        shift
        ;;
    esac
  done

  local resp_file hdr_file
  if [[ -n "$custom_output" ]]; then
    resp_file="$custom_output"
  else
    resp_file="$(mktemp "${_AMSHA_RUN_DIR}/resp.XXXXXX")"
  fi
  hdr_file="$(mktemp "${_AMSHA_RUN_DIR}/hdr.XXXXXX")"
  HTTP_RESP="$resp_file"
  HTTP_HDRS="$hdr_file"

  # Detect HTTP method. Curl changes a request containing --data/--form to
  # POST even when no explicit method was supplied, so do not classify it as
  # a retry-safe GET. Explicit -G/--get and -I/--head retain their meaning.
  local method="GET"
  local method_explicit=false
  local request_has_body=false
  local i
  for ((i=0; i<${#curl_args[@]}; i++)); do
    case "${curl_args[i]}" in
      -X|--request)
        if ((i+1 < ${#curl_args[@]})); then
          method="${curl_args[i+1]}"
          method_explicit=true
          ((i++))
        fi
        ;;
      -X?*) method="${curl_args[i]:2}"; method_explicit=true ;;
      --request=*) method="${curl_args[i]#*=}"; method_explicit=true ;;
      -G|--get) method="GET"; method_explicit=true ;;
      -I|--head) method="HEAD"; method_explicit=true ;;
      -d|--data|--data-raw|--data-ascii|--data-binary|--data-urlencode|--json|-F|--form|--form-string)
        request_has_body=true
        ;;
      --data=*|--data-raw=*|--data-ascii=*|--data-binary=*|--data-urlencode=*|--json=*|--form=*|--form-string=*)
        request_has_body=true
        ;;
    esac
  done
  if [[ "$method_explicit" != true && "$request_has_body" == true ]]; then
    method="POST"
  fi
  local upper_method
  upper_method="$(echo "$method" | tr '[:lower:]' '[:upper:]')"

  # Method safety policy
  local is_safe=false
  if [[ "$upper_method" == "GET" || "$upper_method" == "HEAD" ]]; then
    is_safe=true
  elif [[ "$upper_method" == "PUT" || "$upper_method" == "DELETE" ]]; then
    if [[ "$idempotent" == true || "${AMSHA_RETRY_MUTATIONS:-false}" == "true" ]]; then
      is_safe=true
    fi
  elif [[ "$upper_method" == "POST" ]]; then
    if [[ "$idempotent" == true ]]; then
      is_safe=true
    fi
  fi

  local code
  code="$(curl -sS -m "$timeout" -D "$hdr_file" -o "$resp_file" -w "%{http_code}" "${curl_args[@]}" 2>/dev/null || echo "000")"

  # Bounded 429 retry
  if [[ "$code" == "429" && "$is_safe" == true ]]; then
    local retry_after=""
    if [[ -f "$hdr_file" ]]; then
      retry_after="$(grep -i "^Retry-After:" "$hdr_file" | tr -d '\r' | awk '{print $2}' | head -1)"
    fi

    local wait_sec="$AMSHA_DEFAULT_RETRY_WAIT"
    local can_retry=true
    if [[ "$retry_after" =~ ^[0-9]+$ ]]; then
      if (( retry_after > AMSHA_RETRY_AFTER_CAP )); then
        can_retry=false
      else
        wait_sec="$retry_after"
      fi
    fi

    if [[ "$can_retry" == true ]]; then
      sleep "$wait_sec"
      code="$(curl -sS -m "$timeout" -D "$hdr_file" -o "$resp_file" -w "%{http_code}" "${curl_args[@]}" 2>/dev/null || echo "000")"
    fi
  fi

  HTTP_CODE="$code"
  echo "$code"
}

# Standard 4-arg signature validator helper:
#   run_req <method> <key> <url> [data] [--idempotent]
run_req() {
  local method="$1"
  local key="$2"
  local url="$3"
  local data="${4:-}"
  local idempotent_flag=""
  if [[ "${5:-}" == "--idempotent" || "$data" == "--idempotent" ]]; then
    idempotent_flag="--idempotent"
    if [[ "$data" == "--idempotent" ]]; then
      data=""
    fi
  fi

  local req_args=(-X "$method" -H "X-Amsha-API-Key: $key")
  if [[ -n "$data" ]]; then
    req_args+=(-H "Content-Type: application/json" -d "$data")
  fi
  req_args+=("$url")

  if [[ -n "$idempotent_flag" ]]; then
    http_call "$idempotent_flag" "${req_args[@]}"
  else
    http_call "${req_args[@]}"
  fi
}

# Mute helper replacement with isolation
mute() {
  http_call "$@"
}

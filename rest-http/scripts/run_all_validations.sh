#!/usr/bin/env bash
# run_all_validations.sh — Master validator for rest-http test coverage
# Usage:
#   bash run_all_validations.sh                  → run all validators
#   bash run_all_validations.sh --reflex         → reflex commands only (fast, ~30s)
#   bash run_all_validations.sh --skills         → skill CRUD + errors (~90s)
#   bash run_all_validations.sh --memory         → archival + core + Arabic (~3min)
#   bash run_all_validations.sh --web            → web search + fetch + SSRF (~2min)
#   bash run_all_validations.sh --roles          → secondary agents + aliases (~4min)
#   bash run_all_validations.sh --mcp            → MCP protocol tests (~2min)
#   bash run_all_validations.sh --admin          → Admin endpoints validation (fast)
#   bash run_all_validations.sh --auth-session   → API Key Auth + Session lifecycle (fast)
#   bash run_all_validations.sh --identity       → P109 Identity/Access/bootstrap + OAuth negative (fast)
#   bash run_all_validations.sh --pii-audit      → PII scanner + Prompt injection + Audit verify (fast)
#   bash run_all_validations.sh --fast           → all fast/zero-token validators (no LLM wait)
#   bash run_all_validations.sh --llm            → memory + web + roles (needs LLM)
#   bash run_all_validations.sh --list           → list available validators

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

REFLEX_SCRIPT="$SCRIPT_DIR/validate_reflex.sh"
SKILL_SCRIPT="$SCRIPT_DIR/validate_skill.sh"
MCP_SCRIPT="$SCRIPT_DIR/validate_mcp.sh"
MEMORY_SCRIPT="$SCRIPT_DIR/validate_memory.sh"
WEB_SCRIPT="$SCRIPT_DIR/validate_web.sh"
ROLES_SCRIPT="$SCRIPT_DIR/validate_roles.sh"
ADMIN_SCRIPT="$SCRIPT_DIR/validate_admin.sh"
AUTH_SESSION_SCRIPT="$SCRIPT_DIR/validate_auth_session.sh"
PII_INJECTION_AUDIT_SCRIPT="$SCRIPT_DIR/validate_pii_injection_audit.sh"
CRON_SCRIPT="$SCRIPT_DIR/validate_cron.sh"
SELF_EVOLVE_SCRIPT="$SCRIPT_DIR/validate_self_evolve.sh"
IDENTITY_SCRIPT="$SCRIPT_DIR/validate_identity_access.sh"
HITL_SCRIPT="$SCRIPT_DIR/validate_hitl.sh"
RAG_SCRIPT="$SCRIPT_DIR/validate_rag.sh"
BUDGET_SCRIPT="$SCRIPT_DIR/validate_budget.sh"
SSE_SETTINGS_SCRIPT="$SCRIPT_DIR/validate_sse_settings.sh"

check_app() {
  local check_base="${TEST_BASE:-http://localhost:8080}"
  curl -s -m 3 -o /dev/null -w "%{http_code}" "$check_base/api/v1/mcp/status" \
    -H "X-Amsha-API-Key: JV/qXAikcUoSCCUsqYxLvNjxW/N3KROQtawg3VRFrJs=" 2>/dev/null
}

run_validator() {
  local name="$1" script="$2"
  if [ ! -f "$script" ]; then
    echo "  ⚠️  $name: script not found ($script)"
    return
  fi
  echo ""; echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
  echo " RUNNING: $name"
  echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
  bash "$script" 2>&1
  local rc=$?
  TOTAL=$((TOTAL + 1))
  if [ $rc -eq 0 ]; then
    PASSED=$((PASSED + 1))
  else
    FAILED=$((FAILED + 1))
  fi
}

# ── Parse args ──
RUN_ALL=true; FAST=false; LLM=false
for arg in "$@"; do
  case $arg in
    --reflex) RUN_ALL=false; RUN_REFLEX=true ;;
    --skills) RUN_ALL=false; RUN_SKILLS=true ;;
    --mcp)    RUN_ALL=false; RUN_MCP=true ;;
    --memory) RUN_ALL=false; RUN_MEMORY=true ;;
    --web)    RUN_ALL=false; RUN_WEB=true ;;
    --roles)  RUN_ALL=false; RUN_ROLES=true ;;
    --admin)  RUN_ALL=false; RUN_ADMIN=true ;;
    --auth-session) RUN_ALL=false; RUN_AUTH_SESSION=true ;;
    --pii-audit)    RUN_ALL=false; RUN_PII_AUDIT=true ;;
    --cron)         RUN_ALL=false; RUN_CRON=true ;;
    --identity)     RUN_ALL=false; RUN_IDENTITY=true ;;
    --self-evolve)  RUN_ALL=false; RUN_SELF_EVOLVE=true ;;
    --hitl)         RUN_ALL=false; RUN_HITL=true ;;
    --rag)          RUN_ALL=false; RUN_RAG=true ;;
    --budget)       RUN_ALL=false; RUN_BUDGET=true ;;
    --sse-settings) RUN_ALL=false; RUN_SSE_SETTINGS=true ;;
    --fast)   FAST=true; RUN_ALL=false; RUN_REFLEX=true; RUN_SKILLS=true; RUN_ADMIN=true; RUN_AUTH_SESSION=true; RUN_IDENTITY=true; RUN_PII_AUDIT=true; RUN_SELF_EVOLVE=true; RUN_SSE_SETTINGS=true ;;
    --llm)    LLM=true; RUN_ALL=false; RUN_MEMORY=true; RUN_WEB=true; RUN_ROLES=true; RUN_CRON=true; RUN_RAG=true; RUN_BUDGET=true; RUN_HITL=true ;;
    --list)
      echo "Available validators:"
      echo "  --reflex       validate_reflex.sh             (zero-token, ~30s)"
      echo "  --skills       validate_skill.sh              (CRUD + errors, ~90s)"
      echo "  --mcp          validate_mcp.sh                (MCP protocol, ~2min)"
      echo "  --memory       validate_memory.sh             (archival + core, ~3min)"
      echo "  --web          validate_web.sh                (search + fetch, ~2min)"
      echo "  --roles        validate_roles.sh              (agents + aliases, ~4min)"
      echo "  --admin        validate_admin.sh              (admin endpoints, fast)"
      echo "  --auth-session validate_auth_session.sh       (auth & session lifecycle, fast)"
      echo "  --identity     validate_identity_access.sh     (P109 identity/access/bootstrap, fast)"
      echo "  --pii-audit    validate_pii_injection_audit.sh (pii + injection + audit, fast)"
      echo "  --self-evolve  validate_self_evolve.sh         (behavioral directives REST, fast)"
      echo "  --hitl         validate_hitl.sh                (approve/deny + taskId governance, needs LLM)"
      echo "  --rag          validate_rag.sh                 (ingest → approve → recall, needs LLM)"
      echo "  --budget       validate_budget.sh              (budget + autonomy SUPERVISED/FULL/READONLY, needs LLM)"
      echo "  --sse-settings validate_sse_settings.sh        (UI SSE streams + ws-token + settings CRUD, fast)"
      echo "  --cron         validate_cron.sh               (programmatic cron resource REST APIs, needs LLM)"
      echo "  --fast         reflex + skills + admin + auth-session + identity + pii-audit + self-evolve + sse-settings"
      echo "  --llm          memory + web + roles + cron + rag + budget + hitl"
      echo "  (no args)      run ALL validators"
      exit 0
      ;;
    --help)
      echo "Usage: bash run_all_validations.sh [options]"
      echo "Options: --reflex --skills --mcp --memory --web --roles --admin --auth-session --identity --pii-audit --self-evolve --hitl --rag --budget --sse-settings --cron --fast --llm --list --help"
      exit 0
      ;;
  esac
done

# ── Pre-flight check ──
echo "═══════════════════════════════════════════"
echo " AMSHA REST-HTTP VALIDATION SUITE"
echo "═══════════════════════════════════════════"
echo ""
echo -n "Checking app status: "
APP_CODE=$(check_app)
if [ "$APP_CODE" = "200" ]; then
  echo "✅ Running (HTTP $APP_CODE)"
else
  echo "❌ App not reachable (HTTP $APP_CODE)"
  echo "   Start the app with: cd dist && ./amsha-sqlite serve"
  exit 1
fi

if [ "$FAST" = true ]; then
  echo "Mode: --fast (reflex + skills + admin + auth-session + pii-audit, no LLM)"
elif [ "$LLM" = true ]; then
  echo "Mode: --llm (memory + web + roles, needs LLM)"
elif [ "$RUN_ALL" = true ]; then
  echo "Mode: ALL (reflex + skills + MCP + memory + web + roles + admin + auth-session + identity + pii-audit)"
fi

TOTAL=0; PASSED=0; FAILED=0

# ── Run selected validators ──
if [ "$RUN_REFLEX" = true ] || [ "$RUN_ALL" = true ]; then
  run_validator "REFLEX COMMANDS" "$REFLEX_SCRIPT"
fi

if [ "$RUN_SKILLS" = true ] || [ "$RUN_ALL" = true ]; then
  run_validator "SKILLS CRUD" "$SKILL_SCRIPT"
fi

if [ "$RUN_MCP" = true ] || [ "$RUN_ALL" = true ]; then
  run_validator "MCP PROTOCOL" "$MCP_SCRIPT"
fi

if [ "$RUN_MEMORY" = true ] || [ "$RUN_ALL" = true ]; then
  run_validator "MEMORY (archival + core)" "$MEMORY_SCRIPT"
fi

if [ "$RUN_WEB" = true ] || [ "$RUN_ALL" = true ]; then
  run_validator "WEB TOOLS" "$WEB_SCRIPT"
fi

if [ "$RUN_ROLES" = true ] || [ "$RUN_ALL" = true ]; then
  run_validator "AGENT ROLES" "$ROLES_SCRIPT"
fi

if [ "$RUN_ADMIN" = true ] || [ "$RUN_ALL" = true ]; then
  run_validator "ADMIN ENDPOINTS" "$ADMIN_SCRIPT"
fi

if [ "$RUN_AUTH_SESSION" = true ] || [ "$RUN_ALL" = true ]; then
  run_validator "AUTH & SESSION LIFECYCLE" "$AUTH_SESSION_SCRIPT"
fi

if [ "$RUN_PII_AUDIT" = true ] || [ "$RUN_ALL" = true ]; then
  run_validator "PII, INJECTION, & AUDIT" "$PII_INJECTION_AUDIT_SCRIPT"
fi

if [ "$RUN_IDENTITY" = true ] || [ "$RUN_ALL" = true ]; then
  run_validator "P109 IDENTITY / ACCESS" "$IDENTITY_SCRIPT"
fi

if [ "$RUN_CRON" = true ] || [ "$RUN_ALL" = true ]; then
  run_validator "CRON REST RESOURCE" "$CRON_SCRIPT"
fi

if [ "$RUN_SELF_EVOLVE" = true ] || [ "$RUN_ALL" = true ]; then
  run_validator "SELF-EVOLVE DIRECTIVES" "$SELF_EVOLVE_SCRIPT"
fi

if [ "$RUN_HITL" = true ] || [ "$RUN_ALL" = true ]; then
  run_validator "HITL GOVERNANCE" "$HITL_SCRIPT"
fi

if [ "$RUN_RAG" = true ] || [ "$RUN_ALL" = true ]; then
  run_validator "RAG INGEST→RECALL" "$RAG_SCRIPT"
fi

if [ "$RUN_BUDGET" = true ] || [ "$RUN_ALL" = true ]; then
  run_validator "BUDGET & AUTONOMY" "$BUDGET_SCRIPT"
fi

if [ "$RUN_SSE_SETTINGS" = true ] || [ "$RUN_ALL" = true ]; then
  run_validator "UI SSE & SETTINGS" "$SSE_SETTINGS_SCRIPT"
fi

# ── Summary ──
echo ""
echo "═══════════════════════════════════════════"
echo " VALIDATION SUITE COMPLETE"
echo "   Suites: $TOTAL run"
echo "   Passed: $PASSED"
echo "   Failed: $FAILED"
echo "═══════════════════════════════════════════"

exit "$FAILED"

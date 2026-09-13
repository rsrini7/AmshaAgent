#!/usr/bin/env bash
#MISE description = "Run end-to-end autotest harness (Telegram, SkillsJars, Loka)"
set -euo pipefail
exec bash scripts/autotest.sh "$@"

#!/usr/bin/env bash
#MISE description = "Verify native SkillsJar resource index parity against app/pom.xml"
set -euo pipefail
exec python3 scripts/verify_skillsjar_index.py "$@"

#!/usr/bin/env bash
#MISE description = "Format code using fix_fqns.py and spotless:apply"
set -euo pipefail
python3 scripts/fix_fqns.py
./mvnw spotless:apply -q "$@"

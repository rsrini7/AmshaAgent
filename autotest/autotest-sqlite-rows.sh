#!/usr/bin/env bash
# Forwarding shim to parameterized autotest runner
exec "$(dirname "$0")/run.sh" --backend sqlite --rows 1-20 --suite telegram "$@"

#!/usr/bin/env bash
# Forwarding shim to parameterized autotest runner
exec "$(dirname "$0")/run.sh" --backend sqlite --suite telegram "$@"

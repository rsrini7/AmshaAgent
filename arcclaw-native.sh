#!/bin/bash
export LD_PRELOAD=/lib/x86_64-linux-gnu/libm.so.6
exec "$(dirname "$0")/arcclaw-app/target/arcclaw-app-1.0.0-SNAPSHOT-runner" "$@"

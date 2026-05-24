#!/usr/bin/env bash
cd "$(dirname "$0")/.."
python3 autotest/autotest.py -f docs/telegram-test-arcclaw.txt -j autotest/logs/results-sqlite.json -l autotest/logs/run-sqlite.log -v --port 8999 --backend sqlite

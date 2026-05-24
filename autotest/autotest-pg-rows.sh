#!/usr/bin/env bash
cd "$(dirname "$0")/.."
python3 autotest/autotest.py -r 1-20 -f docs/telegram-test-arcclaw.txt -j autotest/logs/results-pg.json -l autotest/logs/run-pg.log -v --port 8999 --backend pg

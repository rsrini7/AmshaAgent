#!/usr/bin/env bash
cd "$(dirname "$0")/.."
python3 autotest/autotest.py -f docs-test/telegram-test-amsha.txt -j autotest/logs/results-pg.json -l autotest/logs/run-pg.log -v --port 8999 --backend pg

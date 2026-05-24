#!/usr/bin/env bash
cd "$(dirname "$0")/.."
python3 autotest/autotest.py -f docs/telegram-test-arcclaw.txt -j autotest/logs/results-fs.json -l autotest/logs/run-fs.log -v --port 8999 --backend fs

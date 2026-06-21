#!/usr/bin/env bash
cd "$(dirname "$0")/.."
python3 autotest/autotest.py -r 1-20 -f docs-test/telegram-test-amsha.txt -j autotest/logs/results-fs.json -l autotest/logs/run-fs.log -v --port 8999 --backend fs

#!/usr/bin/env bash
cd "$(dirname "$0")/.."
python3 autotest/autotest.py -f autotest/files/loka-connectivity-test.txt -j autotest/logs/results-loka.json -l autotest/logs/run-loka.log -v --port 8999 --backend sqlite

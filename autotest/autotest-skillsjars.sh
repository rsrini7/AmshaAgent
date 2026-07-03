#!/usr/bin/env bash
cd "$(dirname "$0")/.."
python3 autotest/autotest.py -f autotest/files/skillsjars-test.txt -j autotest/logs/results-skillsjars.json -l autotest/logs/run-skillsjars.log -v --port 8999 --backend sqlite

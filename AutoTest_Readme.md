```bash
# Full run with JSON report
python3 autotest.py -f telegram-test-arcclaw.txt -j results.json -l run.log -v

# Quick sanity: first 20 cases, fail-fast
python3 autotest.py -r 1-20 --fail-fast

# Skip heavy browser/code-exec tests
python3 autotest.py --skip-categories browse,code_exec -j quick.json
```

Old Ref:

python3 ./autotest.py --log-file autotest-full.log --delay 4 --approval-delay 2 

python3 ./autotest.py --log-file autotest-with-rows.log -r 148-150 --delay 4 --approval-delay 2 --port 8999